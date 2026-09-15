#if DEBUG
using System.Collections.Immutable;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Progress;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class ProgressTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-progress-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly LocalHouseholdDocuments documents;
    private Guid epoch;
    private ulong sequence;
    public ProgressTests() { documents = new(path); }
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private async Task<TaskSnapshot> Send(TaskCommand command)
    { var result = await new SyncService(documents).SubmitAsync(member, new(workspace, epoch, device, ++sequence, command), default); Assert.Equal("ACCEPTED", result.Code); return result.Receipt!.Task!; }
    private Task<ProgressSnapshot> Read(IHouseholdDocuments? storage = null) => new ProgressService(storage ?? documents).ReadAsync(member, workspace, epoch, default);
    private async Task<TaskSnapshot> Start()
    {
        epoch = (await new HouseholdService(documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
        return await Send(new CreateTask(TaskIdentity.ForCreate(device, 1), "Private canary title", "Private canary description"));
    }
    [Fact] public async Task First_credit_and_recent_activity_survive_retries_reopen_recomplete_and_delete_restore()
    {
        var task = await Start();
        var complete = new FrozenOperation(workspace, epoch, device, ++sequence, new CompleteTask(task.Id, ChecklistTasks.Versions(task)));
        var sync = new SyncService(documents);
        task = (await sync.SubmitAsync(member, complete, default)).Receipt!.Task!;
        var first = await Read();
        await sync.SubmitAsync(member, complete, default);
        Assert.Equal(2, (await Read()).Activity.Count);
        task = await Send(new ReopenTask(task.Id, ChecklistTasks.Versions(task)));
        task = await Send(new CompleteTask(task.Id, ChecklistTasks.Versions(task)));
        task = await Send(new DeleteTask(task.Id, ChecklistTasks.Versions(task)));
        Assert.Equal(1, (await Read()).Statistics.LifetimeCount);
        await Send(new RestoreTask(task.Id, ChecklistTasks.Versions(task)));
        var stats = await Read();
        Assert.Equal(first.Statistics, stats.Statistics with { WeekDays = first.Statistics.WeekDays, MonthWeeks = first.Statistics.MonthWeeks });
        Assert.Equal("RestoreTask", stats.Activity[0].Action);
        Assert.All(stats.Activity, a => { Assert.Equal(member, a.ActorId); Assert.Equal(task.Id, a.TaskId); });
        Assert.DoesNotContain("Private canary", JsonSerializer.Serialize(stats));
    }
    [Fact] public async Task Checklist_children_never_inflate_completion_counts_and_activity_is_bounded()
    {
        var root = await Start();
        root = await Send(new SplitTask(root.Id, ChecklistTasks.Versions(root), ["First", "Second"], 1, 1));
        foreach (var id in root.ChildOrder!.Value)
        {
            var child = (await documents.ReadAsync<TaskSnapshot>(workspace.ToString(), WorkspaceCommit.TaskId(id), default))!.Value;
            await Send(new CompleteTask(id, ChecklistTasks.Versions(child)));
        }
        Assert.Equal(1, (await Read()).Statistics.LifetimeCount);
        for (var i = 0; i < 65; i++)
        {
            root = (await documents.ReadAsync<TaskSnapshot>(workspace.ToString(), WorkspaceCommit.TaskId(root.Id), default))!.Value;
            await Send(new EditTask(root.Id, new("Changed " + i, root.TitleVersion.Human)));
        }
        var events = (await Read()).Activity;
        Assert.Equal(60, events.Count);
        Assert.Equal(events.Count, events.Select(e => e.Revision).Distinct().Count());
        Assert.True(events[0].Revision > events[^1].Revision);
    }
    [Fact] public async Task Paginated_retained_and_legacy_credits_count_once_and_authority_is_checked()
    {
        await Start();
        for (var i = 0; i < 70; i++)
            Assert.True(await documents.WriteAsync(workspace.ToString(), "completion:" + i, null,
                new FirstCompletion(i.ToString(), member, DateTimeOffset.UtcNow), default));
        var stored = (await documents.ReadAsync<WorkspaceState>(workspace.ToString(), "state", default))!;
        var legacy = new TaskSnapshot("0", "Legacy", null, new(1,1), new(1,1), FirstCompletion: new("0", member, DateTimeOffset.UtcNow));
        Assert.True(await documents.WriteAsync(workspace.ToString(), "state", stored.Version,
            stored.Value with { Tasks = ImmutableDictionary<string,TaskSnapshot>.Empty.Add("0", legacy) }, default));
        Assert.Equal(70, (await Read()).Statistics.LifetimeCount);
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(() => new ProgressService(documents).ReadAsync(Guid.NewGuid(), workspace, epoch, default))).Code);
        Assert.Equal("EPOCH_CHANGED", (await Assert.ThrowsAsync<SyncException>(() => new ProgressService(documents).ReadAsync(member, workspace, Guid.NewGuid(), default))).Code);
    }
    [Fact] public async Task Concurrent_completion_restarts_the_scan_instead_of_returning_mixed_counts()
    {
        var task = await Start();
        var racing = new Interleaved(documents, async () => { await Send(new CompleteTask(task.Id, ChecklistTasks.Versions(task))); });
        Assert.Equal(1, (await Read(racing)).Statistics.LifetimeCount);
        Assert.True(racing.Pages >= 4);
    }
    private sealed class Interleaved(IHouseholdDocuments inner, Func<Task> mutate) : IHouseholdDocuments
    {
        public int Pages;
        public Task<StoredDocument<T>?> ReadAsync<T>(string p, string id, CancellationToken ct) => inner.ReadAsync<T>(p,id,ct);
        public Task<bool> WriteAsync<T>(string p,string id,string? version,T value,CancellationToken ct) => inner.WriteAsync(p,id,version,value,ct);
        public Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected,WorkspaceState next,CancellationToken ct,IReadOnlyList<string>? deletes=null) => inner.CommitWorkspaceAsync(expected,next,ct,deletes);
        public async Task<DocumentPage<T>> ReadPageAsync<T>(string p,string prefix,string? continuation,int limit,CancellationToken ct)
        { var page=await inner.ReadPageAsync<T>(p,prefix,continuation,limit,ct); if (++Pages==1) await mutate(); return page; }
    }
}
#endif
