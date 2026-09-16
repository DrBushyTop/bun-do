#if DEBUG
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Adventures;
using BunDo.Functions.AI;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class AdventureTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-adventure-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly LocalHouseholdDocuments documents;
    private readonly Clock clock = new();
    private readonly Provider provider = new();
    private Guid epoch;
    private ulong sequence;
    public AdventureTests() { documents = new(path); }
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private AdventureService Service(IHouseholdDocuments? storage = null) => new(storage ?? documents, provider, clock);
    private Task<AdventureSnapshot> Read() => Service().ReadAsync(member, workspace, epoch, default);
    private Task Refresh(Guid? batch = null, bool retry = false, IHouseholdDocuments? storage = null) =>
        Service(storage).RefreshAsync(member, workspace, epoch, batch, retry, default);
    private async Task Start() => epoch = (await new HouseholdService(documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
    private async Task<TaskSnapshot> Send(TaskCommand command)
    {
        var result = await new SyncService(documents).SubmitAsync(member, new(workspace, epoch, device, ++sequence, command), default);
        Assert.Equal("ACCEPTED", result.Code); return result.Receipt!.Task!;
    }
    private async Task<TaskSnapshot> Capture()
    {
        var root = await Send(new CreateTask(TaskIdentity.ForCreate(device, sequence + 1), "Private shopping canary"));
        // Existing tests need a useful proposal set; keep the primary source first in the fake provider.
        if (sequence == 1) for (var i = 0; i < 3; i++)
            await Send(new CreateTask(TaskIdentity.ForCreate(device, sequence + 1), $"Supporting task {i}"));
        return root;
    }
    private async Task<AcceptedAdventure> Accept()
    {
        var batch = (await Read()).Board.Batch!;
        await Service().AcceptAsync(member, workspace, epoch, batch.Id, batch.Proposals![0].Id, default);
        return (await Read()).Board.Active!;
    }
    private Task<TaskSnapshot> TaskById(string id) => ReadTask(id);
    private async Task<TaskSnapshot> ReadTask(string id) => (await documents.ReadAsync<TaskSnapshot>(workspace.ToString(), WorkspaceCommit.TaskId(id), default))!.Value;

    [Fact]
    public async Task Fewer_than_three_roots_skip_inference_and_three_roots_offer_one_adventure()
    {
        await Start(); Guid? batch = null;
        for (var count = 0; count <= 3; count++) {
            if (count > 0) await Send(new CreateTask(TaskIdentity.ForCreate(device, sequence + 1), $"Task {count}"));
            await Refresh(batch); var result = (await Read()).Board.Batch!; batch = result.Id;
            Assert.Equal(count < 3 ? "EMPTY" : "READY", result.Status);
            Assert.Equal(count < 3 ? 0 : 1, provider.Calls);
            if (count == 3) Assert.Equal(3, Assert.Single(result.Proposals!).Draft.Phases.Length);
        }
    }
    [Fact]
    public async Task Duplicate_sets_and_undersized_provider_proposals_never_publish()
    {
        await Start(); await Capture(); provider.SameSet = true; await Refresh();
        var batch = (await Read()).Board.Batch!; Assert.Equal("INVALID_OUTPUT", batch.Error);
        provider.SameSet = false; provider.TooShort = true; await Refresh(batch.Id, true);
        Assert.Equal("INVALID_OUTPUT", (await Read()).Board.Batch!.Error);
    }

    [Fact]
    public async Task Shared_batch_persists_and_accepted_adventure_never_expires_or_changes_tasks()
    {
        await Start(); var root = await Capture(); var taskRevision = (await Read()).Revision; await Refresh();
        var batch = (await Read()).Board.Batch!;
        Assert.Equal("READY", batch.Status); Assert.Equal(2, batch.Proposals!.Length);
        Assert.Equal(clock.Now.AddHours(24), batch.ExpiresAt);
        await Refresh(batch.Id); Assert.Equal(1, provider.Calls);
        var active = await Accept();
        clock.Now = clock.Now.AddDays(3);
        await Refresh(batch.Id);
        await Service().AcceptAsync(member, workspace, epoch, batch.Id, active.Id, default);
        var read = await new AdventureService(new LocalHouseholdDocuments(path), provider, clock).ReadAsync(member, workspace, epoch, default);
        Assert.Equal(active.Id, read.Board.Active!.Id); Assert.Equal(active.AcceptedAt, read.Board.Active.AcceptedAt);
        Assert.Equal(JsonSerializer.Serialize(root), JsonSerializer.Serialize(await TaskById(root.Id)));
        Assert.Equal(1, provider.Calls);
        Assert.Null(read.Board.Batch!.Proposals);
        var pull = await new SyncService(documents).PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.All(pull.Groups.Where(g => g.Revision > taskRevision).SelectMany(g => g.Parts), part => Assert.Empty(part.EntityIds));
    }

    [Fact]
    public async Task Empty_queue_does_not_invent_work_and_next_visit_sees_new_roots()
    {
        await Start(); await Refresh();
        var batch = (await Read()).Board.Batch!;
        Assert.Equal("EMPTY", batch.Status); Assert.Equal(0, provider.Calls);
        await Capture(); await Refresh(batch.Id);
        Assert.Equal("READY", (await Read()).Board.Batch!.Status); Assert.Equal(1, provider.Calls);
    }

    [Fact]
    public async Task Expiration_requires_an_online_refresh_and_stale_refresh_cannot_repeat_inference()
    {
        await Start(); await Capture(); await Refresh();
        var batch = (await Read()).Board.Batch!;
        clock.Now = batch.ExpiresAt!.Value;
        Assert.Equal("EXPIRED", (await Read()).Board.Batch!.Status); Assert.Equal(1, provider.Calls);
        Assert.Equal("SUGGESTIONS_UNAVAILABLE", (await Assert.ThrowsAsync<SyncException>(() =>
            Service().AcceptAsync(member, workspace, epoch, batch.Id, batch.Proposals![0].Id, default))).Code);
        await Refresh(batch.Id); await Refresh(batch.Id);
        Assert.Equal(2, provider.Calls); Assert.NotEqual(batch.Id, (await Read()).Board.Batch!.Id);
    }

    [Fact]
    public async Task Competing_visits_share_one_lease_and_one_set()
    {
        await Start(); await Capture();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        provider.Before = async () => { entered.SetResult(); await release.Task; };
        var first = Refresh(); await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        await Refresh(); var running = (await Read()).Board.Batch!;
        await Refresh(running.Id, retry: true);
        Assert.Equal("RUNNING", running.Status); Assert.Equal(1, provider.Calls);
        release.SetResult(); await first;
        Assert.Equal(running.Id, (await Read()).Board.Batch!.Id);
    }

    [Fact]
    public async Task Failure_and_interrupted_lease_need_explicit_retry_and_cancellation_never_reports_success()
    {
        await Start(); await Capture();
        provider.Before = () => throw new CleanupProviderException("Private arbitrary canary");
        await Refresh(); var failed = (await Read()).Board.Batch!;
        Assert.Equal("PROVIDER_UNAVAILABLE", failed.Error);
        await Refresh(failed.Id); Assert.Equal(1, provider.Calls);
        using var cancellation = new CancellationTokenSource();
        provider.Before = () => { cancellation.Cancel(); return Task.CompletedTask; };
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Service().RefreshAsync(member, workspace, epoch, failed.Id, true, cancellation.Token));
        var running = (await Read()).Board.Batch!;
        Assert.Equal("RUNNING", running.Status);
        clock.Now = clock.Now.AddMinutes(3);
        Assert.Equal("INTERRUPTED", (await Read()).Board.Batch!.Error);
        await Refresh(running.Id); Assert.Equal(2, provider.Calls);
        provider.Before = null;
        await Refresh(running.Id, true); Assert.Equal("READY", (await Read()).Board.Batch!.Status);
    }

    [Theory]
    [InlineData("delete")]
    [InlineData("complete")]
    [InlineData("split")]
    public async Task Inference_cannot_publish_after_source_changes(string change)
    {
        await Start(); var task = await Capture();
        provider.Before = async () => { await Send(change switch {
            "delete" => new DeleteTask(task.Id, ChecklistTasks.Versions(task)),
            "complete" => new CompleteTask(task.Id, ChecklistTasks.Versions(task)),
            _ => new SplitTask(task.Id, ChecklistTasks.Versions(task), ["one", "two"], task.TitleVersion.Human, task.DescriptionVersion.Human),
        }); };
        await Refresh();
        Assert.Equal("SOURCE_CHANGED", (await Read()).Board.Batch!.Error);
        Assert.Null((await Read()).Board.Batch!.Proposals);
    }

    [Fact]
    public async Task Late_provider_response_cannot_replace_newer_retry()
    {
        await Start(); await Capture();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        provider.Before = async () => { entered.SetResult(); await release.Task; };
        var old = Refresh(); await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var running = (await Read()).Board.Batch!; clock.Now = clock.Now.AddMinutes(3);
        provider.Before = null; await Refresh(running.Id, true);
        var newer = (await Read()).Board.Batch!;
        release.SetResult(); await old;
        Assert.Equal(newer.Id, (await Read()).Board.Batch!.Id);
    }

    [Fact]
    public async Task Competing_accepts_and_lost_reply_preserve_first_choice_and_close_never_resurrects()
    {
        await Start(); await Capture(); await Refresh(); var batch = (await Read()).Board.Batch!;
        var racing = new Interleaved(documents) { BeforeCommit = async () => await Accept() };
        Assert.Equal("ADVENTURE_ACTIVE", (await Assert.ThrowsAsync<SyncException>(() =>
            Service(racing).AcceptAsync(member, workspace, epoch, batch.Id, batch.Proposals![1].Id, default))).Code);
        var active = (await Read()).Board.Active!;
        await Service().CloseAsync(member, workspace, epoch, active.Id, active.Version, true, true, default);
        await Service().CloseAsync(member, workspace, epoch, active.Id, active.Version, true, true, default);
        Assert.Equal("SUGGESTIONS_UNAVAILABLE", (await Assert.ThrowsAsync<SyncException>(() =>
            Service().AcceptAsync(member, workspace, epoch, batch.Id, active.Id, default))).Code);
        await Refresh(batch.Id); batch = (await Read()).Board.Batch!;
        var lost = new Interleaved(documents) { LoseReply = true };
        await Assert.ThrowsAsync<IOException>(() => Service(lost).AcceptAsync(member, workspace, epoch, batch.Id, batch.Proposals![0].Id, default));
        await Service().AcceptAsync(member, workspace, epoch, batch.Id, batch.Proposals![0].Id, default);
        Assert.Equal(batch.Proposals![0].Id, (await Read()).Board.Active!.Id);
    }

    [Fact]
    public async Task Live_checklist_work_reopens_progress_and_dismiss_rechecks_completion_at_commit()
    {
        await Start(); var root = await Capture();
        root = await Send(new SplitTask(root.Id, ChecklistTasks.Versions(root), ["Milk", "Eggs"], root.TitleVersion.Human, root.DescriptionVersion.Human));
        await Refresh(); var active = await Accept();
        foreach (var phase in active.Draft.Phases.Where(p => p.RootId != root.Id)) {
            var other = await TaskById(phase.RootId); await Send(new CompleteTask(other.Id, ChecklistTasks.Versions(other)));
        }
        foreach (var id in root.ChildOrder!.Value) { var child = await TaskById(id); await Send(new CompleteTask(id, ChecklistTasks.Versions(child))); }
        Assert.True((await Read()).Progress!.IsComplete);
        var racing = new Interleaved(documents) { BeforeCommit = async () => {
            var child = await TaskById(root.ChildOrder.Value[0]); await Send(new ReopenTask(child.Id, ChecklistTasks.Versions(child)));
        }};
        Assert.Equal("ADVENTURE_NOT_COMPLETE", (await Assert.ThrowsAsync<SyncException>(() =>
            Service(racing).CloseAsync(member, workspace, epoch, active.Id, active.Version, false, false, default))).Code);
        Assert.Equal(2, (await Read()).Progress!.Completed); Assert.Equal(3, (await Read()).Progress!.Total);
        Assert.Equal(2, (await Read()).Progress!.Roots[0].Checklist.Length);
        var reopened = await TaskById(root.ChildOrder.Value[0]); await Send(new CompleteTask(reopened.Id, ChecklistTasks.Versions(reopened)));
        await Service().CloseAsync(member, workspace, epoch, active.Id, active.Version, false, false, default);
        Assert.Null((await Read()).Board.Active);
    }

    [Fact]
    public async Task Deleted_sources_are_unavailable_and_explicit_replacement_or_removal_never_changes_tasks()
    {
        await Start(); var root = await Capture(); await Refresh(); var active = await Accept();
        root = await Send(new DeleteTask(root.Id, ChecklistTasks.Versions(root)));
        Assert.False((await Read()).Progress!.Roots[0].Available);
        Assert.Null((await Read()).Progress!.Roots[0].Task);
        var other = await Capture();
        var draft = active.Draft with { Phases = [new(other.Id, "Next phase", 1, 5)] };
        await Service().EditAsync(member, workspace, epoch, active.Id, active.Version, draft, default);
        Assert.Equal(other.Id, (await Read()).Progress!.Roots[0].RootId);
        Assert.Equal(JsonSerializer.Serialize(root), JsonSerializer.Serialize(await TaskById(root.Id)));
        Assert.Equal("ADVENTURE_CHANGED", (await Assert.ThrowsAsync<SyncException>(() =>
            Service().EditAsync(member, workspace, epoch, active.Id, active.Version, draft, default))).Code);
        active = (await Read()).Board.Active!;
        await Service().EditAsync(member, workspace, epoch, active.Id, active.Version, draft with { Phases = [] }, default);
        Assert.Equal(0, (await Read()).Progress!.Total); Assert.False((await Read()).Progress!.IsComplete);
    }

    [Fact]
    public async Task Membership_epoch_and_registration_are_rechecked_after_inference()
    {
        await Start(); await Capture();
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(() => Service().ReadAsync(Guid.NewGuid(), workspace, epoch, default))).Code);
        Assert.Equal("EPOCH_CHANGED", (await Assert.ThrowsAsync<SyncException>(() => Service().ReadAsync(member, workspace, Guid.NewGuid(), default))).Code);
        var active = true;
        provider.Before = () => { active = false; return Task.CompletedTask; };
        var gated = new AdventureService(documents, provider, clock, _ => Task.FromResult(active));
        Assert.Equal("REGISTRATION_RETIRED", (await Assert.ThrowsAsync<SyncException>(() => gated.RefreshAsync(member, workspace, epoch, null, false, default))).Code);
        var running = (await Read()).Board.Batch!; clock.Now = clock.Now.AddMinutes(3);
        provider.Before = async () => {
            var state = (await documents.ReadAsync<WorkspaceState>(workspace.ToString(), "state", default))!.Value;
            await new HouseholdService(documents).ChangeAsync(member, workspace, epoch, new DeleteHousehold(state.Membership.Version), default);
        };
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(() => Refresh(running.Id, true))).Code);
    }

    [Fact]
    public async Task Untrusted_provider_cannot_invent_roots_or_return_invalid_estimates()
    {
        await Start(); await Capture();
        provider.InventRoot = true; await Refresh();
        Assert.Equal("INVALID_OUTPUT", (await Read()).Board.Batch!.Error);
    }

    [Fact]
    public async Task Technical_input_limits_do_not_create_or_modify_omitted_tasks()
    {
        await Start();
        for (var i = 0; i < 40; i++) await Capture();
        await Refresh();
        Assert.Equal(AdventureService.MaximumInputRoots, provider.LastInput!.Count);
        Assert.True(JsonSerializer.SerializeToUtf8Bytes(provider.LastInput).Length < AdventureService.MaximumInputBytes);
    }

    [Fact]
    public async Task Continuous_commit_conflicts_are_bounded_without_paying_for_inference()
    {
        await Start(); await Capture();
        var racing = new Interleaved(documents) { AlwaysConflict = true };
        Assert.Equal("BUSY", (await Assert.ThrowsAsync<SyncException>(() => Refresh(storage: racing))).Code);
        Assert.Equal(8, racing.Commits); Assert.Equal(0, provider.Calls);
    }

    [Fact]
    public async Task Second_member_sees_the_same_batch_and_can_accept_and_end_it_for_both()
    {
        await Start(); await Capture();
        var bob = Guid.NewGuid();
        var state = (await documents.ReadAsync<WorkspaceState>(workspace.ToString(), "state", default))!;
        var revision = state.Value.Revision + 1;
        Assert.True(await documents.CommitWorkspaceAsync(state, state.Value with { Revision = revision,
            Membership = state.Value.Membership with { Members = state.Value.Membership.Members.Add(bob, new(bob, revision)) },
            Changes = state.Value.Changes.Add(new(revision, [])) }, default));
        await Refresh(); var aliceView = await Read();
        var bobView = await Service().ReadAsync(bob, workspace, epoch, default);
        Assert.Equal(JsonSerializer.Serialize(aliceView), JsonSerializer.Serialize(bobView));
        var batch = bobView.Board.Batch!;
        await Service().AcceptAsync(bob, workspace, epoch, batch.Id, batch.Proposals![1].Id, default);
        var active = (await Read()).Board.Active!;
        Assert.Equal(batch.Proposals[1].Id, active.Id);
        await Service().CloseAsync(bob, workspace, epoch, active.Id, active.Version, true, true, default);
        Assert.Null((await Read()).Board.Active);
    }

    [Fact]
    public async Task Source_change_immediately_before_accept_is_rechecked_after_CAS_conflict()
    {
        await Start(); var root = await Capture(); await Refresh(); var batch = (await Read()).Board.Batch!;
        var racing = new Interleaved(documents) { BeforeCommit = async () =>
            await Send(new DeleteTask(root.Id, ChecklistTasks.Versions(root))) };
        Assert.Equal("SOURCE_UNAVAILABLE", (await Assert.ThrowsAsync<SyncException>(() =>
            Service(racing).AcceptAsync(member, workspace, epoch, batch.Id, batch.Proposals![0].Id, default))).Code);
        Assert.Null((await Read()).Board.Active);
    }

    [Fact]
    public async Task Large_descriptions_are_bounded_before_inference_without_truncating_meaning()
    {
        await Start();
        for (var i = 0; i < 20; i++) await Send(new CreateTask(TaskIdentity.ForCreate(device, sequence + 1), "Task", new string('x', 4000)));
        await Refresh();
        Assert.InRange(provider.LastInput!.Count, 1, 19);
        Assert.All(provider.LastInput, item => Assert.Equal(4000, item.Description!.Length));
        Assert.True(JsonSerializer.SerializeToUtf8Bytes(provider.LastInput).Length < AdventureService.MaximumInputBytes);
    }

    [Fact]
    public async Task Adventure_growth_cannot_consume_the_membership_revocation_storage_reserve()
    {
        await Start(); await Capture();
        var stored = (await documents.ReadAsync<WorkspaceState>(workspace.ToString(), "state", default))!;
        var large = stored.Value with { Name = new string('x', HouseholdDocumentLimits.GrowthBytes) };
        Assert.True(await documents.WriteAsync(workspace.ToString(), "state", stored.Version, large, default));
        await Assert.ThrowsAsync<HouseholdStorageFullException>(() => Refresh());
        Assert.Equal(0, provider.Calls);
        Assert.Null((await documents.ReadAsync<WorkspaceState>(workspace.ToString(), "state", default))!.Value.Adventures);
    }

    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now = new(2026, 9, 16, 12, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }
    private sealed class Provider : IAdventureProvider
    {
        public int Calls;
        public Func<Task>? Before;
        public bool InventRoot, SameSet, TooShort;
        public IReadOnlyList<AdventureInput>? LastInput;
        public async Task<AdventureDraft[]> GenerateAsync(IReadOnlyList<AdventureInput> tasks, CancellationToken ct)
        {
            Calls++; LastInput = tasks;
            if (Before is { } before) await before();
            var ordered = tasks.OrderBy(t => t.Title, StringComparer.Ordinal).ToArray();
            AdventureDraft Draft(string title, IEnumerable<AdventureInput> roots) => new(title, "", (TooShort ? roots.Take(1) : roots).Select(t =>
                new AdventurePhase(InventRoot ? "invented" : t.RootId, "A useful phase", 1, 10)).ToArray());
            return ordered.Length == 3 ? [Draft("A gentle session", ordered)] :
                [Draft("A gentle session", ordered.Take(3)), Draft("Another way", SameSet ? ordered.Take(3).Reverse() : ordered.Take(2).Append(ordered[3]))];
        }
    }
    private sealed class Interleaved(IHouseholdDocuments inner) : IHouseholdDocuments
    {
        public Func<Task>? BeforeCommit;
        public bool AlwaysConflict, LoseReply;
        public int Commits;
        public Task<StoredDocument<T>?> ReadAsync<T>(string p, string id, CancellationToken ct) => inner.ReadAsync<T>(p, id, ct);
        public Task<bool> WriteAsync<T>(string p, string id, string? version, T value, CancellationToken ct) => inner.WriteAsync(p, id, version, value, ct);
        public Task<DocumentPage<T>> ReadPageAsync<T>(string p, string prefix, string? continuation, int limit, CancellationToken ct) => inner.ReadPageAsync<T>(p, prefix, continuation, limit, ct);
        public async Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected, WorkspaceState next, CancellationToken ct, IReadOnlyList<string>? deletes = null)
        {
            Commits++;
            if (BeforeCommit is { } before) { BeforeCommit = null; await before(); }
            if (AlwaysConflict) return false;
            var result = await inner.CommitWorkspaceAsync(expected, next, ct, deletes);
            if (result && LoseReply) { LoseReply = false; throw new IOException("Lost reply"); }
            return result;
        }
    }
}
#endif
