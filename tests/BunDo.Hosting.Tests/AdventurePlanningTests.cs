#if DEBUG
using BunDo.Domain;
using BunDo.Functions.Adventures;
using BunDo.Functions.AI;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class AdventurePlanningTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-plan-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), registration = Guid.NewGuid();
    private readonly LocalHouseholdDocuments documents;
    private readonly Planner planner = new();
    private Guid epoch;
    public AdventurePlanningTests() { documents = new(path); }
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private AdventureService Service(Func<CancellationToken, Task<bool>>? valid = null) => new(documents, registrationActive: valid, planner: planner);
    private async Task Start() => epoch = (await new HouseholdService(documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
    private Task<AdventureSnapshot> Read() => Service().ReadAsync(member, workspace, epoch, default);
    private static GuidedDraft Draft() => new("A quiet corner", "", [new(null, "Sort papers", "Make room", 1, 15), new(null, "Wipe shelf", "A fresh start", 1, 10), new(null, "Put things back", "Finish", 1, 5)]);
    private sealed class Planner : IAdventurePlanner {
        public GuidedDraft Value = Draft();
        public Task<GuidedDraft> PlanAsync(string outcome, int? minutes, IReadOnlyList<AdventureInput> tasks, CancellationToken ct) => Task.FromResult(Value);
    }
    [Fact] public async Task Empty_queue_draft_changes_nothing_then_partial_acceptance_resumes_without_duplicate_tasks()
    {
        await Start(); var original = await Read();
        var generated = await Service().PlanAsync(member, workspace, epoch, "Clear a corner", 30, default);
        Assert.Equal(3, generated.Phases.Length);
        var draft = generated with { Phases = generated.Phases.Take(2).ToArray() }; // The user may remove suggested work.
        Assert.Equal(original.Revision, (await Read()).Revision);
        var id = Guid.NewGuid();
        await Service().BeginCreationAsync(member, workspace, epoch, registration, id, original.Revision, draft, default);
        await Service().BeginCreationAsync(member, workspace, epoch, registration, id, original.Revision, draft, default);
        var roots = new[] { TaskIdentity.ForCreate(registration, 1), TaskIdentity.ForCreate(registration, 2) };
        var first = new FrozenOperation(workspace, epoch, registration, 1, new CreateTask(roots[0], draft.Phases[0].TaskTitle!));
        var sync = new SyncService(documents);
        Assert.Equal("ACCEPTED", (await sync.SubmitAsync(member, first, default)).Code);
        Assert.Equal("TASKS_PENDING", (await Assert.ThrowsAsync<SyncException>(() => Service().FinishCreationAsync(member, workspace, epoch, registration, id, roots, default))).Code);
        Assert.Null((await Read()).Board.Active);
        Assert.Equal("ACCEPTED", (await sync.SubmitAsync(member, first, default)).Code);
        Assert.Equal("ACCEPTED", (await sync.SubmitAsync(member, new(workspace, epoch, registration, 2, new CreateTask(roots[1], draft.Phases[1].TaskTitle!)), default)).Code);
        await new AdventureService(new LocalHouseholdDocuments(path)).FinishCreationAsync(member, workspace, epoch, registration, id, roots, default);
        await Service().FinishCreationAsync(member, workspace, epoch, registration, id, roots, default);
        var active = await Read(); Assert.Equal(id, active.Board.Active!.Id); Assert.Equal(2, active.Progress!.Total);
        Assert.Equal(2, (await documents.ReadAsync<WorkspaceState>(workspace.ToString(), "state", default))!.Value.TaskCount);
    }
    [Fact] public async Task Provider_and_reservation_validate_sources_membership_epoch_and_registration()
    {
        await Start(); planner.Value = new("Bad", "", [new("unknown", null, "Existing", 1, 5)]);
        Assert.Equal("INVALID_OUTPUT", (await Assert.ThrowsAsync<SyncException>(() => Service().PlanAsync(member, workspace, epoch, "Clear", null, default))).Code);
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(() => Service().PlanAsync(Guid.NewGuid(), workspace, epoch, "Clear", null, default))).Code);
        Assert.Equal("EPOCH_CHANGED", (await Assert.ThrowsAsync<SyncException>(() => Service().PlanAsync(member, workspace, Guid.NewGuid(), "Clear", null, default))).Code);
        Assert.Equal("REGISTRATION_RETIRED", (await Assert.ThrowsAsync<SyncException>(() => Service(_ => Task.FromResult(false)).PlanAsync(member, workspace, epoch, "Clear", null, default))).Code);
        planner.Value = Draft(); var revision = (await Read()).Revision; var id = Guid.NewGuid();
        await Service().BeginCreationAsync(member, workspace, epoch, registration, id, revision, Draft(), default);
        Assert.Equal("CREATION_PENDING", (await Assert.ThrowsAsync<SyncException>(() => Service().BeginCreationAsync(member, workspace, epoch, Guid.NewGuid(), Guid.NewGuid(), revision, Draft(), default))).Code);
        await Service().RefreshAsync(member, workspace, epoch, null, false, default);
        Assert.Null((await Read()).Board.Batch);
        await Service().CancelCreationAsync(member, workspace, epoch, id, true, default);
        await Service().BeginCreationAsync(member, workspace, epoch, registration, id, revision, Draft(), default);
        Assert.Null((await Read()).Board.Creation);
    }
}
#endif
