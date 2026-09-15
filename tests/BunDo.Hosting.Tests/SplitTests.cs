#if DEBUG
using BunDo.Domain;
using BunDo.Functions.AI;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class SplitTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-split-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly LocalHouseholdDocuments documents;
    private Guid epoch;
    private ulong sequence;
    public SplitTests() { documents = new(path); }
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private async Task<TaskSnapshot> Start()
    {
        epoch = (await new HouseholdService(documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
        var task = (await Send(new CreateTask(TaskIdentity.ForCreate(device, 1), "Clean kitchen", "No strong chemicals"))).Receipt!.Task!;
        return (await Send(new RequestSplit(task.Id, task.TitleVersion.Server, task.DescriptionVersion.Server,
            task.LifecycleVersion, task.HierarchyVersion, task.DeletionVersion, null, "Start with dishes"))).Receipt!.Task!;
    }
    private Task<SubmissionResult> Send(TaskCommand command) => new SyncService(documents)
        .SubmitAsync(member, new(workspace, epoch, device, ++sequence, command), default);
    private async Task<TaskSnapshot> Read(string id) =>
        (await documents.ReadAsync<TaskSnapshot>(workspace.ToString("D"), WorkspaceCommit.TaskId(id), default))!.Value;
    private sealed class Provider(Func<CancellationToken, Task<CleanupProposal>> generate) : ICleanupProvider
    {
        public int Calls;
        public string? Instructions;
        public Task<CleanupProposal> GenerateAsync(string title, string? description, CancellationToken ct, System.Text.Json.JsonElement? context = null) => throw new InvalidOperationException();
        public Task<CleanupProposal> GenerateSplitAsync(string title, string? description, string? instructions, CancellationToken ct)
        { Calls++; Instructions = instructions; return generate(ct); }
    }
    private static CleanupProposal Good => new("", null, "en", true, Items: ["Wash dishes", "Wipe counters"]);
    private static SplitTask Accept(TaskSnapshot task, string[]? items = null)
    {
        var source = task.Cleanup!.SplitSource!;
        return new(task.Id, source.State, items ?? task.Cleanup.Proposal!.Items!, source.Title.Human, source.Description.Human) {
            ExpectedTitleFieldVersion = source.Title.Server, ExpectedDescriptionFieldVersion = source.Description.Server,
        };
    }

    [Fact]
    public async Task Preview_is_durable_and_accepts_only_the_human_edited_items()
    {
        var task = await Start(); var provider = new Provider(_ => Task.FromResult(Good));
        await new CleanupWorker(documents, provider).RunAsync(member, workspace, epoch, default);
        var ready = await Read(task.Id);
        Assert.Equal("READY", ready.Cleanup!.Status); Assert.False(ready.IsChecklist);
        Assert.Equal(task.Title, ready.Title); Assert.Equal(task.Description, ready.Description);
        Assert.Null(ready.LastChange); Assert.Equal(task.Creation, ready.Creation);
        Assert.Equal("Start with dishes", provider.Instructions);
        Assert.Null(ready.Cleanup.Instructions); Assert.Null(ready.Cleanup.InputTitle);
        Assert.Equal("No strong chemicals", ready.Cleanup.SplitSource!.SourceDescription);
        await new CleanupWorker(documents, provider).RunAsync(member, workspace, epoch, default);
        Assert.Equal(1, provider.Calls);
        var accepted = await Send(Accept(ready, ["Wash dishes without strong chemicals"]));
        Assert.Equal("ACCEPTED", accepted.Code);
        Assert.True(accepted.Receipt!.Task!.IsChecklist);
        Assert.Equal("APPLIED", accepted.Receipt.Task.Cleanup!.Status); Assert.Null(accepted.Receipt.Task.Cleanup.Proposal);
        var child = accepted.Receipt.RelatedTasks!.Value.Single(t => t.ParentId == task.Id);
        Assert.Equal("Wash dishes without strong chemicals", child.Title);
    }

    [Theory]
    [InlineData(false)] [InlineData(true)]
    public async Task Split_racing_parent_edit_or_completion_retains_preview_and_makes_no_partial_change(bool complete)
    {
        var task = await Start();
        var provider = new Provider(async _ => {
            var current = await Read(task.Id);
            var changed = await Send(complete ? new CompleteTask(task.Id, ChecklistTasks.Versions(current)) :
                new EditTask(task.Id, Title: new("Clean only sink", current.TitleVersion.Human)));
            Assert.Equal("ACCEPTED", changed.Code);
            return Good;
        });
        await new CleanupWorker(documents, provider).RunAsync(member, workspace, epoch, default);
        var ready = await Read(task.Id);
        Assert.Equal("READY", ready.Cleanup!.Status);
        var rejected = await Send(Accept(ready));
        Assert.Equal(complete ? "LIFECYCLE_CONFLICT" : "FIELD_CONFLICT", rejected.Code);
        var retained = await Read(task.Id);
        Assert.False(retained.IsChecklist); Assert.Equal(Good.Items, retained.Cleanup!.Proposal!.Items);
        Assert.Equal("Clean kitchen", retained.Cleanup.SplitSource!.SourceTitle);
    }

    [Fact]
    public async Task Cancellation_keeps_parent_and_late_worker_cannot_restore_preview()
    {
        var task = await Start();
        var provider = new Provider(async _ => {
            var current = await Read(task.Id);
            Assert.Equal("ACCEPTED", (await Send(new CancelCleanup(task.Id, current.TitleVersion.Server, current.DescriptionVersion.Server,
                current.LifecycleVersion, current.HierarchyVersion, current.DeletionVersion, current.Cleanup!.Id))).Code);
            return Good;
        });
        await new CleanupWorker(documents, provider).RunAsync(member, workspace, epoch, default);
        var cancelled = await Read(task.Id);
        Assert.Equal("SUPERSEDED", cancelled.Cleanup!.Status); Assert.Null(cancelled.Cleanup.Proposal);
        Assert.Equal(task.Title, cancelled.Title); Assert.False(cancelled.IsChecklist);
    }
}
#endif
