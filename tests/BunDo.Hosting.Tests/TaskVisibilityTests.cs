#if DEBUG
using System.Collections.Immutable;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Adventures;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Progress;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class TaskVisibilityTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-visibility-" + Guid.NewGuid());
    private readonly Guid actor = Guid.NewGuid(), other = Guid.NewGuid(), household = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly LocalHouseholdDocuments documents;
    public TaskVisibilityTests() => documents = new(path);
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private async Task<WorkspaceState> State(Guid id) => (await documents.ReadAsync<WorkspaceState>(id.ToString("D"), "state", default))!.Value;
    private async Task<(Guid Home, Guid Personal)> Setup()
    {
        var service = new HouseholdService(documents);
        await service.CreateAsync(actor, household, default);
        var personal = (await service.PersonalAsync(actor, default)).Household!;
        return ((await State(household)).StateEpoch, personal.StateEpoch);
    }
    private Guid Personal => HouseholdService.PersonalId(actor);
    private async Task<TaskSnapshot> Create(Guid workspace, ulong sequence = 1, Guid? author = null)
    {
        var state = await State(workspace);
        var result = await new SyncService(documents).SubmitAsync(author ?? actor,
            new(workspace, state.StateEpoch, author == other ? other : device, sequence,
                new CreateTask(TaskIdentity.ForCreate(author == other ? other : device, sequence), "Current title", "Current notes")), default);
        Assert.Equal("ACCEPTED", result.Code);
        return result.Receipt!.Task!;
    }
    private async Task<VisibilityRequest> Request(Guid source, Guid target, string task) =>
        new(Guid.NewGuid(), source, (await State(source)).StateEpoch, task, (await State(source)).Revision, target, (await State(target)).StateEpoch);
    private async Task<TaskSnapshot> Task(Guid workspace, string id) =>
        (await documents.ReadAsync<TaskSnapshot>(workspace.ToString("D"), WorkspaceCommit.TaskId(id), default))!.Value;
    private async Task Mutate(Guid workspace, Func<WorkspaceState, WorkspaceState> change)
    {
        var stored = (await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", default))!;
        var revision = stored.Value.Revision + 1;
        Assert.True(await documents.CommitWorkspaceAsync(stored, change(stored.Value) with { Revision = revision, Changes = [new(revision, [])] }, default));
    }

    [Fact]
    public async Task Personal_workspace_is_discoverable_only_by_owner_and_cannot_be_shared_or_deleted_as_a_household()
    {
        await Setup();
        var homes = new HouseholdService(documents);
        Assert.Single(await homes.ListAsync(actor, default));
        Assert.Null(await homes.GetAsync(other, Personal, default));
        var personal = (await homes.PersonalAsync(actor, default)).Household!;
        Assert.True(personal.Personal);
        Assert.Equal(Personal, personal.Id);
        foreach (MembershipCommand command in new MembershipCommand[] {
            new IssueHouseholdInvitation(Guid.NewGuid(), new string('A', 64)), new DeleteHousehold(0),
            new TransferHouseholdOwnership(other, 0), new LeaveHousehold(0),
            new RedeemHouseholdInvitation(Guid.NewGuid(), new string('A', 64), "123456") })
            Assert.Equal("PERSONAL_WORKSPACE", (await homes.ChangeAsync(actor, Personal, personal.StateEpoch, command, default)).Code);
        Assert.Equal("INVALID_REQUEST", (await homes.CreateAsync(other, Personal, default)).Code);
        await Create(Personal);
        await Assert.ThrowsAsync<SyncException>(() => new SyncService(documents).PullAsync(other, Personal, personal.StateEpoch, null, [], "ACCEPTED", default));
        await Assert.ThrowsAsync<SyncException>(() => new SyncService(documents).OutcomesAsync(other, Personal, personal.StateEpoch, device, 1, 1, default));
        await Assert.ThrowsAsync<SyncException>(() => new TaskVisibilityService(documents).PendingAsync(other, default));
        await Assert.ThrowsAsync<SyncException>(() => new JourneyService(documents).EnableAsync(actor, Personal, personal.StateEpoch, default));
        await Assert.ThrowsAsync<SyncException>(() => new ListLibraryService(documents, _ => System.Threading.Tasks.Task.FromResult(true)).SendAsync(actor, Personal, personal.StateEpoch, null, default));
    }

    [Fact]
    public async Task Moving_private_then_sharing_again_preserves_content_but_never_capture_or_private_history()
    {
        await Setup();
        var root = await Create(household);
        var service = new TaskVisibilityService(documents);
        var request = await Request(household, Personal, root.Id);
        var moved = await service.SendAsync(actor, request, default);
        Assert.Equal("ACCEPTED", moved.Code);
        Assert.Equal(moved, await service.SendAsync(actor, request, default));
        var retired = await Task(household, root.Id);
        Assert.True(retired.Deletion!.Purging);
        Assert.NotNull(retired.VisibilityTransferId);
        var personal = await Task(Personal, moved.TaskId!);
        Assert.Equal(root.Title, personal.Title);
        Assert.Null(personal.Capture);
        var edit = await new SyncService(documents).SubmitAsync(actor, new(Personal, (await State(Personal)).StateEpoch,
            device, 1, new EditTask(personal.Id, new("Private revised title", personal.TitleVersion.Human))), default);
        Assert.Equal("ACCEPTED", edit.Code);
        var shared = await service.SendAsync(actor, await Request(Personal, household, personal.Id), default);
        Assert.Equal("ACCEPTED", shared.Code);
        var published = await Task(household, shared.TaskId!);
        Assert.Equal("Private revised title", published.Title);
        Assert.Null(published.Capture);
        Assert.Null(published.LastChange);
        var old = await Task(household, root.Id);
        Assert.Equal("Current title", old.Title);
        var restore = await new SyncService(documents).SubmitAsync(actor, new(household, (await State(household)).StateEpoch,
            device, 2, new RestoreTask(old.Id, ChecklistTasks.Versions(old))), default);
        Assert.Equal("TASK_PURGING", restore.Code);
        Assert.Empty(await service.PendingAsync(actor, default));
    }

    [Fact]
    public async Task Only_creator_can_move_a_root_and_all_checklist_children()
    {
        await Setup();
        await Mutate(household, s => s with { Membership = s.Membership with { Members = s.Membership.Members.Add(other, new(other, 1)) } });
        var root = await Create(household, author: other);
        Assert.Equal("NOT_CREATOR", (await new TaskVisibilityService(documents).SendAsync(actor, await Request(household, Personal, root.Id), default)).Code);
        root = await Create(household);
        var split = await new SyncService(documents).SubmitAsync(other, new(household, (await State(household)).StateEpoch, other, 2,
            new SplitTask(root.Id, ChecklistTasks.Versions(root), ["Someone else's step"], root.TitleVersion.Human, root.DescriptionVersion.Human)), default);
        Assert.Equal("ACCEPTED", split.Code);
        Assert.Equal("NOT_CREATOR", (await new TaskVisibilityService(documents).SendAsync(actor, await Request(household, Personal, root.Id), default)).Code);
    }

    [Fact]
    public async Task Adventure_link_and_stale_preview_prevent_retirement()
    {
        await Setup(); var root = await Create(household);
        var request = await Request(household, Personal, root.Id);
        await Mutate(household, s => s);
        Assert.Equal("SOURCE_CHANGED", (await new TaskVisibilityService(documents).SendAsync(actor, request, default)).Code);
        await Mutate(household, s => s with { Adventures = new(Active: new(Guid.NewGuid(), Guid.NewGuid(), 1,
            new("Groceries", "", [new(root.Id, "Shop", 1, 30)]), DateTimeOffset.UtcNow)) });
        Assert.Equal("ADVENTURE_ACTIVE", (await new TaskVisibilityService(documents).SendAsync(actor, await Request(household, Personal, root.Id), default)).Code);
        Assert.Null((await Task(household, root.Id)).Deletion);
    }

    [Fact]
    public async Task Private_completions_do_not_earn_progress_and_old_household_credit_is_not_duplicated_on_return()
    {
        await Setup(); var root = await Create(household);
        var done = await new SyncService(documents).SubmitAsync(actor, new(household, (await State(household)).StateEpoch,
            device, 2, new CompleteTask(root.Id, ChecklistTasks.Versions(root))), default);
        Assert.Equal("ACCEPTED", done.Code);
        var service = new TaskVisibilityService(documents);
        var moved = await service.SendAsync(actor, await Request(household, Personal, root.Id), default);
        Assert.Equal("ACCEPTED", moved.Code);
        var shared = await service.SendAsync(actor, await Request(Personal, household, moved.TaskId!), default);
        Assert.Equal("ACCEPTED", shared.Code);
        Assert.Equal(1, (await new ProgressService(documents).ReadAsync(actor, household, (await State(household)).StateEpoch, default)).Statistics.LifetimeCount);
        var privateTask = await Create(Personal);
        var complete = await new SyncService(documents).SubmitAsync(actor, new(Personal, (await State(Personal)).StateEpoch,
            device, 2, new CompleteTask(privateTask.Id, ChecklistTasks.Versions(privateTask))), default);
        Assert.Null(complete.Receipt!.Task!.FirstCompletion);
        var progress = await new ProgressService(documents).ReadAsync(actor, Personal, (await State(Personal)).StateEpoch, default);
        Assert.Equal(0, progress.Statistics.LifetimeCount); Assert.Empty(progress.Activity); Assert.Null(progress.Journey);
    }

    [Fact]
    public async Task Crash_after_retirement_keeps_private_journal_and_recovers_after_household_deletion()
    {
        await Setup(); var root = await Create(household);
        var request = await Request(household, Personal, root.Id);
        var broken = new FailImport(documents);
        await Assert.ThrowsAsync<IOException>(() => new TaskVisibilityService(broken).SendAsync(actor, request, default));
        var service = new TaskVisibilityService(documents);
        Assert.Single(await service.PendingAsync(actor, default));
        await Mutate(household, s => s with { Membership = s.Membership with { DeletedAt = DateTimeOffset.UtcNow } });
        var resumed = await service.SendAsync(actor, request, default);
        Assert.Equal("ACCEPTED", resumed.Code);
        Assert.Equal(root.Title, (await Task(Personal, resumed.TaskId!)).Title);
        Assert.Empty(await service.PendingAsync(actor, default));
    }

    [Fact]
    public async Task Repeat_moves_with_current_occurrence_and_stops_source_generation()
    {
        await Setup(); var root = await Create(household);
        var configured = await new SyncService(documents).SubmitAsync(actor, new(household, (await State(household)).StateEpoch,
            device, 2, new ConfigureRepeat(root.Id, 0, ChecklistTasks.Versions(root), new("DAILY", null, "Europe/Helsinki"), "Repeat title", "Repeat notes")), default);
        Assert.Equal("ACCEPTED", configured.Code);
        var moved = await new TaskVisibilityService(documents).SendAsync(actor, await Request(household, Personal, root.Id), default);
        Assert.Equal("ACCEPTED", moved.Code);
        var sourceSchedule = (await documents.ReadAsync<RepeatSchedule>(household.ToString("D"), $"repeat:{configured.Receipt!.Task!.Repeat!.Id}", default))!.Value;
        Assert.False(sourceSchedule.Active); Assert.Null(sourceSchedule.PendingDate);
        var target = await Task(Personal, moved.TaskId!);
        var schedule = (await documents.ReadAsync<RepeatSchedule>(Personal.ToString("D"), $"repeat:{target.Repeat!.Id}", default))!.Value;
        Assert.True(schedule.Active); Assert.Equal(actor, schedule.CreatorId); Assert.Equal(target.Id, schedule.CurrentTaskId);
    }

    [Fact]
    public async Task Keep_private_cancels_a_failed_share_after_target_access_is_lost()
    {
        await Setup(); var root = await Create(Personal);
        var request = await Request(Personal, household, root.Id);
        await Assert.ThrowsAsync<IOException>(() => new TaskVisibilityService(new FailImport(documents)).SendAsync(actor, request, default));
        await Mutate(household, s => s with { Membership = s.Membership with { DeletedAt = DateTimeOffset.UtcNow } });
        var service = new TaskVisibilityService(documents);
        Assert.Equal("CANCELLED", (await service.CancelAsync(actor, request.Id, default)).Code);
        Assert.Equal("CANCELLED", (await service.SendAsync(actor, request, default)).Code);
        Assert.Equal("CANCELLED", (await service.CancelAsync(actor, request.Id, default)).Code);
        var restored = await Task(Personal, root.Id);
        Assert.Null(restored.Deletion); Assert.Equal(root.Title, restored.Title);
        Assert.Empty(await service.PendingAsync(actor, default));
    }

    [Fact]
    public async Task Cancellation_never_claims_to_undo_an_already_imported_share()
    {
        await Setup(); var root = await Create(Personal);
        var request = await Request(Personal, household, root.Id);
        var service = new TaskVisibilityService(documents);
        Assert.Equal("ACCEPTED", (await service.SendAsync(actor, request, default)).Code);
        Assert.Equal("ACCEPTED", (await service.CancelAsync(actor, request.Id, default)).Code);
        Assert.Null((await Task(household, TaskVisibility.ImportedId(request.Id, 1))).Deletion);
    }

    [Fact]
    public async Task Personal_snapshots_are_owner_only_and_household_snapshots_exclude_private_content()
    {
        await Setup(); var root = await Create(Personal);
        var artifacts = new BunDo.Functions.Identity.Development.LocalSnapshotArtifacts(Path.Combine(path, "artifacts"));
        var snapshots = new BunDo.Functions.Recovery.SnapshotService(documents, artifacts);
        var id = Guid.NewGuid(); var epoch = (await State(Personal)).StateEpoch;
        var manifest = await snapshots.CreateAsync(actor, Personal, epoch, device, id, default);
        Assert.Equal(1, manifest.DocumentCount);
        await Assert.ThrowsAsync<BunDo.Functions.Recovery.SnapshotException>(() => snapshots.ManifestAsync(other, Personal, epoch, device, id, default));
        var shared = await snapshots.CreateAsync(actor, household, (await State(household)).StateEpoch, device, Guid.NewGuid(), default);
        Assert.Equal(0, shared.DocumentCount);
    }

    [Fact]
    public async Task Editing_a_shared_completed_private_checklist_does_not_turn_private_work_into_household_credit()
    {
        await Setup(); var root = await Create(Personal); var epoch = (await State(Personal)).StateEpoch;
        var sync = new SyncService(documents);
        var split = await sync.SubmitAsync(actor, new(Personal, epoch, device, 2,
            new SplitTask(root.Id, ChecklistTasks.Versions(root), ["Private step"], root.TitleVersion.Human, root.DescriptionVersion.Human)), default);
        var child = split.Receipt!.RelatedTasks!.Value.Single(t => t.ParentId == root.Id);
        Assert.Equal("ACCEPTED", (await sync.SubmitAsync(actor, new(Personal, epoch, device, 3,
            new CompleteTask(child.Id, ChecklistTasks.Versions(child))), default)).Code);
        var shared = await new TaskVisibilityService(documents).SendAsync(actor, await Request(Personal, household, root.Id), default);
        var published = await Task(household, shared.TaskId!);
        Assert.Equal("COMPLETED", published.Lifecycle); Assert.Null(published.FirstCompletion);
        var edit = await sync.SubmitAsync(actor, new(household, (await State(household)).StateEpoch, device, 1,
            new EditTask(published.Id, new("Shared title", published.TitleVersion.Human))), default);
        Assert.Equal("ACCEPTED", edit.Code); Assert.Null(edit.Receipt!.Task!.FirstCompletion);
        Assert.Equal(0, (await new ProgressService(documents).ReadAsync(actor, household, (await State(household)).StateEpoch, default)).Statistics.LifetimeCount);
    }

    [Fact]
    public async Task Returned_credit_is_retained_once_when_both_task_copies_are_purged()
    {
        await Setup(); var source = await State(household);
        var credit = new FirstCompletion("original", actor, DateTimeOffset.UtcNow);
        var next = source with { Revision = source.Revision + 1,
            Changes = [new(source.Revision + 1, [], RetainedCompletions: [credit, credit])] };
        var plan = WorkspaceCommit.Plan(source, next);
        var retained = Assert.Single(plan.Writes, w => w.Id.StartsWith("completion:", StringComparison.Ordinal));
        Assert.Equal(credit, retained.Value); Assert.False(retained.CreateOnly);
    }

    [Fact]
    public async Task Transfer_using_another_members_device_id_never_replaces_their_task()
    {
        await Setup();
        await Mutate(household, s => s with { Membership = s.Membership with { Members = s.Membership.Members.Add(other, new(other, 1)) } });
        var theirs = await Create(household, author: other);
        var mine = await Create(Personal);
        var request = (await Request(Personal, household, mine.Id)) with { Id = other };
        var reply = await new TaskVisibilityService(documents).SendAsync(actor, request, default);
        Assert.Equal("ACCEPTED", reply.Code);
        Assert.NotEqual(theirs.Id, reply.TaskId);
        Assert.Equal(theirs, await Task(household, theirs.Id));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Existing_destination_identity_is_rejected_before_source_retirement(bool repeatCollision)
    {
        await Setup(); var root = await Create(Personal);
        if (repeatCollision)
        {
            await new SyncService(documents).SubmitAsync(actor, new(Personal, (await State(Personal)).StateEpoch,
                device, 2, new ConfigureRepeat(root.Id, 0, ChecklistTasks.Versions(root), new("DAILY", null, "Europe/Helsinki"), "Repeat", null)), default);
        }
        var request = await Request(Personal, household, root.Id);
        var partition = household.ToString("D");
        if (repeatCollision)
        {
            var configured = await Task(Personal, root.Id);
            var repeat = (await documents.ReadAsync<RepeatSchedule>(Personal.ToString("D"), $"repeat:{configured.Repeat!.Id}", default))!.Value;
            Assert.True(await documents.WriteAsync(partition, $"repeat:{TaskVisibility.ImportedId(request.Id, 100)}", null, repeat, default));
        }
        else Assert.True(await documents.WriteAsync(partition, WorkspaceCommit.TaskId(TaskVisibility.ImportedId(request.Id, 1)), null, root, default));
        Assert.Equal("TARGET_CONFLICT", (await new TaskVisibilityService(documents).SendAsync(actor, request, default)).Code);
        Assert.Null((await Task(Personal, root.Id)).Deletion);
    }

    [Fact]
    public async Task Keep_private_restores_original_capture_attribution_deleted_children_and_repeat()
    {
        await Setup(); var root = await Create(Personal); var epoch = (await State(Personal)).StateEpoch;
        var sync = new SyncService(documents);
        var edited = await sync.SubmitAsync(actor, new(Personal, epoch, device, 2,
            new EditTask(root.Id, new("Revised private title", root.TitleVersion.Human))), default);
        root = edited.Receipt!.Task!;
        var split = await sync.SubmitAsync(actor, new(Personal, epoch, device, 3,
            new SplitTask(root.Id, ChecklistTasks.Versions(root), ["Live", "Deleted"], root.TitleVersion.Human, root.DescriptionVersion.Human)), default);
        var child = split.Receipt!.RelatedTasks!.Value.Last(t => t.ParentId == root.Id);
        await sync.SubmitAsync(actor, new(Personal, epoch, device, 4, new DeleteTask(child.Id, ChecklistTasks.Versions(child))), default);
        root = await Task(Personal, root.Id);
        await sync.SubmitAsync(actor, new(Personal, epoch, device, 5,
            new ConfigureRepeat(root.Id, 0, ChecklistTasks.Versions(root), new("DAILY", null, "Europe/Helsinki"), "Repeat", null)), default);
        root = await Task(Personal, root.Id); child = await Task(Personal, child.Id);
        var originalTime = DateTimeOffset.Parse("2025-01-02T03:04:05Z");
        root = root with { Creation = new(actor, originalTime, originalTime),
            Capture = new("Original private text", "Original private notes", JsonSerializer.SerializeToElement(new { source = "private-canary" }), originalTime) };
        var storedRoot = (await documents.ReadAsync<TaskSnapshot>(Personal.ToString("D"), WorkspaceCommit.TaskId(root.Id), default))!;
        Assert.True(await documents.WriteAsync(Personal.ToString("D"), WorkspaceCommit.TaskId(root.Id), storedRoot.Version, root, default));
        var request = await Request(Personal, household, root.Id);
        await Assert.ThrowsAsync<IOException>(() => new TaskVisibilityService(new FailImport(documents)).SendAsync(actor, request, default));
        Assert.Equal("CANCELLED", (await new TaskVisibilityService(documents).CancelAsync(actor, request.Id, default)).Code);
        var restored = await Task(Personal, root.Id); var restoredChild = await Task(Personal, child.Id);
        Assert.Equal(root.Title, restored.Title); Assert.Equal(JsonSerializer.Serialize(root.Capture), JsonSerializer.Serialize(restored.Capture));
        Assert.Equal(root.Creation, restored.Creation); Assert.Equal(root.LastChange, restored.LastChange);
        Assert.Equal(root.ChildOrder, restored.ChildOrder); Assert.Equal(child.Deletion, restoredChild.Deletion);
        Assert.Equal(child.Capture, restoredChild.Capture); Assert.Null(restoredChild.VisibilityTransferId);
        Assert.Equal(root.Repeat!.Id, restored.Repeat!.Id); Assert.True(restored.Repeat.Active);
    }

    [Fact]
    public void Imports_use_create_only_entities_in_the_partition_transaction()
    {
        var state = new WorkspaceState(household, Guid.NewGuid(), 0, ImmutableDictionary<Guid, DeviceRegistration>.Empty,
            ImmutableDictionary<string, TaskSnapshot>.Empty, ImmutableDictionary<string, OperationReceipt>.Empty, [], HouseholdMembership.Create(actor));
        var task = new TaskSnapshot("source", "Title", null, new(0, 0), new(0, 0));
        var next = TaskVisibility.Import(state, actor, Guid.NewGuid(), new([task], null), DateTimeOffset.UtcNow);
        Assert.All(WorkspaceCommit.Plan(state, next).Writes, write => Assert.True(write.CreateOnly));
    }

    private sealed class FailImport(IHouseholdDocuments inner) : IHouseholdDocuments
    {
        public Task<StoredDocument<T>?> ReadAsync<T>(string p, string id, CancellationToken ct) => inner.ReadAsync<T>(p, id, ct);
        public Task<bool> WriteAsync<T>(string p, string id, string? version, T value, CancellationToken ct) => inner.WriteAsync(p, id, version, value, ct);
        public Task<DocumentPage<T>> ReadPageAsync<T>(string p, string prefix, string? after, int limit, CancellationToken ct) => inner.ReadPageAsync<T>(p, prefix, after, limit, ct);
        public Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected, WorkspaceState next, CancellationToken ct, IReadOnlyList<string>? deletes = null) =>
            next.VisibilityReceipts?.Any(r => r.Stage == "IMPORTED") == true ? throw new IOException("Simulated crash") : inner.CommitWorkspaceAsync(expected, next, ct, deletes);
    }
}
#endif
