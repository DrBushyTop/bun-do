#if DEBUG
using System.Collections.Immutable;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Progress;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class JourneyTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-journey-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly LocalHouseholdDocuments documents;
    private Guid epoch;
    private ulong sequence;
    public JourneyTests() { documents = new(path); }
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private async Task Start() =>
        epoch = (await new HouseholdService(documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
    private async Task<TaskSnapshot> Send(TaskCommand command)
    {
        var result = await new SyncService(documents).SubmitAsync(member, new(workspace, epoch, device, ++sequence, command), default);
        Assert.Equal("ACCEPTED", result.Code);
        return result.Receipt!.Task!;
    }
    private Task<TaskSnapshot> Capture() => Send(new CreateTask(TaskIdentity.ForCreate(device, sequence + 1), "Private task canary"));
    private Task<ProgressSnapshot> Read(IHouseholdDocuments? storage = null) =>
        new ProgressService(storage ?? documents).ReadAsync(member, workspace, epoch, default);
    private Task Enable(IHouseholdDocuments? storage = null) =>
        new JourneyService(storage ?? documents).EnableAsync(member, workspace, epoch, default);
    private Task<StoredDocument<WorkspaceState>?> State() => documents.ReadAsync<WorkspaceState>(workspace.ToString(), "state", default);

    [Fact]
    public async Task Explicit_enable_ignores_history_and_retries_after_restart_preserve_baseline_and_task_history()
    {
        await Start();
        var historical = await Capture();
        await Send(new CompleteTask(historical.Id, ChecklistTasks.Versions(historical)));
        Assert.Null((await Read()).Journey);
        var before = (await State())!;
        await Enable();
        var enabled = (await State())!;
        Assert.Equal(before.Value.Revision + 1, enabled.Value.Revision);
        Assert.Equal(1, enabled.Value.Journey!.BaselineCompletions);
        Assert.Equal(0, (await Read()).Journey!.Credits);
        Assert.Equal(2, (await Read()).Activity.Count);
        var task = await Capture();
        await Send(new CompleteTask(task.Id, ChecklistTasks.Versions(task)));
        var after = (await State())!;
        await Enable(new LocalHouseholdDocuments(path));
        Assert.Equal(after.Version, (await State())!.Version);
        var result = await Read(new LocalHouseholdDocuments(path));
        Assert.Equal(1, result.Journey!.Credits);
        Assert.Equal(2, result.Statistics.LifetimeCount);
        Assert.Equal(enabled.Value.Journey, (await State())!.Value.Journey);
        var pull = await new SyncService(documents).PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.All(pull.Groups.Single(g => g.Revision == enabled.Value.Revision).Parts, part => Assert.Empty(part.EntityIds));
        Assert.DoesNotContain("Private task canary", JsonSerializer.Serialize(result));
    }

    [Fact]
    public async Task Reopen_recomplete_delete_restore_and_checklist_children_never_change_permanent_credit()
    {
        await Start(); await Enable();
        var root = await Capture();
        root = await Send(new SplitTask(root.Id, ChecklistTasks.Versions(root), ["One", "Two"],
            root.TitleVersion.Human, root.DescriptionVersion.Human));
        foreach (var id in root.ChildOrder!.Value)
        {
            var child = (await documents.ReadAsync<TaskSnapshot>(workspace.ToString(), WorkspaceCommit.TaskId(id), default))!.Value;
            await Send(new CompleteTask(id, ChecklistTasks.Versions(child)));
        }
        Assert.Equal(1, (await Read()).Journey!.Credits);
        var reopened = (await documents.ReadAsync<TaskSnapshot>(workspace.ToString(), WorkspaceCommit.TaskId(root.ChildOrder.Value[0]), default))!.Value;
        reopened = await Send(new ReopenTask(reopened.Id, ChecklistTasks.Versions(reopened)));
        Assert.Equal(1, (await Read()).Journey!.Credits);
        await Send(new CompleteTask(reopened.Id, ChecklistTasks.Versions(reopened)));
        root = (await documents.ReadAsync<TaskSnapshot>(workspace.ToString(), WorkspaceCommit.TaskId(root.Id), default))!.Value;
        root = await Send(new DeleteTask(root.Id, ChecklistTasks.Versions(root)));
        Assert.Equal(1, (await Read()).Journey!.Credits);
        await Send(new RestoreTask(root.Id, ChecklistTasks.Versions(root)));
        Assert.Equal(1, (await Read()).Journey!.Credits);
    }

    [Fact]
    public async Task Paginated_retained_credits_and_legacy_duplicates_share_one_baseline()
    {
        await Start(); await Capture();
        var now = DateTimeOffset.UtcNow;
        for (var i = 0; i < 70; i++)
            Assert.True(await documents.WriteAsync(workspace.ToString(), "completion:" + i, null,
                new FirstCompletion(i.ToString(), member, now), default));
        var stored = (await State())!;
        var duplicate = new TaskSnapshot("0", "Old private text", null, new(1, 1), new(1, 1), FirstCompletion: new("0", member, now));
        Assert.True(await documents.WriteAsync(workspace.ToString(), "state", stored.Version,
            stored.Value with { Tasks = ImmutableDictionary<string, TaskSnapshot>.Empty.Add("0", duplicate) }, default));
        await Enable();
        Assert.Equal(70, (await State())!.Value.Journey!.BaselineCompletions);
        Assert.Equal(0, (await Read()).Journey!.Credits);
    }

    [Fact]
    public async Task Purging_task_content_retains_journey_credit()
    {
        await Start(); await Enable();
        var task = await Capture();
        task = await Send(new CompleteTask(task.Id, ChecklistTasks.Versions(task)));
        task = await Send(new DeleteTask(task.Id, ChecklistTasks.Versions(task)));
        var stored = (await State())!;
        var revision = stored.Value.Revision + 1;
        // The production purge's atomic task-to-content-free-credit transfer.
        var next = stored.Value with { Revision = revision,
            Changes = stored.Value.Changes.Add(new(revision, [], PurgedTaskIds: [task.Id], RetainedCompletions: [task.FirstCompletion!])) };
        Assert.True(await documents.CommitWorkspaceAsync(stored, next, default, [WorkspaceCommit.TaskId(task.Id)]));
        Assert.Equal(1, (await Read(new LocalHouseholdDocuments(path))).Journey!.Credits);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Completion_during_scan_or_before_commit_belongs_to_baseline(bool beforeCommit)
    {
        await Start();
        var task = await Capture();
        var racing = new Interleaved(documents) {
            Mutate = async () => await Send(new CompleteTask(task.Id, ChecklistTasks.Versions(task))),
            BeforeCommit = beforeCommit,
        };
        await Enable(racing);
        Assert.Equal(1, (await State())!.Value.Journey!.BaselineCompletions);
        Assert.Equal(0, (await Read()).Journey!.Credits);
        Assert.True(racing.Calls >= 2);
    }

    [Fact]
    public async Task Competing_enablement_and_lost_commit_reply_cannot_reset_original_start()
    {
        await Start();
        var racing = new Interleaved(documents) { BeforeCommit = true, Mutate = () => Enable() };
        await Enable(racing);
        Assert.Equal(1UL, (await State())!.Value.Journey!.EnabledRevision);
        var start = (await State())!.Value.Journey;
        var task = await Capture();
        await Send(new CompleteTask(task.Id, ChecklistTasks.Versions(task)));
        await Enable();
        Assert.Equal(start, (await State())!.Value.Journey);
        Assert.Equal(1, (await Read()).Journey!.Credits);
    }

    [Fact]
    public async Task Authorization_epoch_and_member_removal_are_rechecked_before_commit()
    {
        await Start();
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(() =>
            new JourneyService(documents).EnableAsync(Guid.NewGuid(), workspace, Guid.NewGuid(), default))).Code);
        Assert.Equal("EPOCH_CHANGED", (await Assert.ThrowsAsync<SyncException>(() =>
            new JourneyService(documents).EnableAsync(member, workspace, Guid.NewGuid(), default))).Code);
        var racing = new Interleaved(documents) { BeforeCommit = true, Mutate = async () => {
            var state = (await State())!.Value;
            await new HouseholdService(documents).ChangeAsync(member, workspace, epoch,
                new DeleteHousehold(state.Membership.Version), default);
        }};
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(() => Enable(racing))).Code);
        Assert.Null((await State())!.Value.Journey);
    }

    [Fact]
    public async Task Continuous_contention_is_bounded_and_does_not_enable()
    {
        await Start();
        var racing = new Interleaved(documents) { BeforeCommit = true, AlwaysConflict = true };
        Assert.Equal("BUSY", (await Assert.ThrowsAsync<SyncException>(() => Enable(racing))).Code);
        Assert.Equal(8, racing.Calls);
        Assert.Null((await State())!.Value.Journey);
    }

    private sealed class Interleaved(IHouseholdDocuments inner) : IHouseholdDocuments
    {
        public Func<Task>? Mutate;
        public bool BeforeCommit, AlwaysConflict;
        public int Calls;
        private async Task Mutation()
        {
            Calls++;
            if (Mutate is { } mutate) { Mutate = null; await mutate(); }
        }
        public Task<StoredDocument<T>?> ReadAsync<T>(string p, string id, CancellationToken ct) => inner.ReadAsync<T>(p, id, ct);
        public Task<bool> WriteAsync<T>(string p, string id, string? version, T value, CancellationToken ct) => inner.WriteAsync(p, id, version, value, ct);
        public async Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected, WorkspaceState next,
            CancellationToken ct, IReadOnlyList<string>? deletes = null)
        {
            if (BeforeCommit) await Mutation();
            return !AlwaysConflict && await inner.CommitWorkspaceAsync(expected, next, ct, deletes);
        }
        public async Task<DocumentPage<T>> ReadPageAsync<T>(string p, string prefix, string? continuation, int limit, CancellationToken ct)
        {
            var page = await inner.ReadPageAsync<T>(p, prefix, continuation, limit, ct);
            if (!BeforeCommit) await Mutation();
            return page;
        }
    }
}
#endif
