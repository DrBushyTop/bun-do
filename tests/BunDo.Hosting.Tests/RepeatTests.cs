#if DEBUG
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Repeats;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class RepeatTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-repeat-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), device = Guid.NewGuid();
    private Guid epoch;
    private ulong sequence;
    private readonly LocalHouseholdDocuments Documents;
    public RepeatTests() { Documents = new(path); }
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private async Task<TaskSnapshot> Send(TaskCommand command)
    {
        var result = await new SyncService(Documents).SubmitAsync(member, new(workspace, epoch, device, ++sequence, command), default);
        Assert.Equal("ACCEPTED", result.Code); return result.Receipt!.Task!;
    }
    private async Task<TaskSnapshot> Start()
    {
        epoch = (await new HouseholdService(Documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
        var task = await Send(new CreateTask(TaskIdentity.ForCreate(device, 1), "Original"));
        task = await Send(new ConfigureRepeat(task.Id, 0, ChecklistTasks.Versions(task), new("DAILY", null, "Europe/Helsinki"), "Next", null));
        return await Send(new CompleteTask(task.Id, ChecklistTasks.Versions(task)));
    }
    [Fact] public async Task Restart_and_duplicate_workers_commit_one_task_and_one_change_group()
    {
        var task = await Start();
        await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => new RepeatWorker(Documents).RunAsync(member, workspace, epoch, default)));
        var state = (await Documents.ReadAsync<WorkspaceState>(workspace.ToString(), "state", default))!.Value;
        Assert.Equal(4UL, state.Revision); Assert.Equal(2, state.TaskCount);
        var schedule = (await Documents.ReadAsync<RepeatSchedule>(workspace.ToString(), "repeat:" + task.Repeat!.Id, default))!.Value;
        Assert.Null(schedule.PendingDate); Assert.NotEqual(task.Id, schedule.CurrentTaskId);
        var next = (await Documents.ReadAsync<TaskSnapshot>(workspace.ToString(), WorkspaceCommit.TaskId(schedule.CurrentTaskId), default))!.Value;
        Assert.Equal("Next", next.Title); Assert.Equal("OPEN", next.Lifecycle);
        var page = await new SyncService(Documents).PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.Contains(page.Groups, g => g.Parts[0].Payload.Contains(schedule.CurrentTaskId));
        Assert.Empty(state.Tasks); Assert.Null(state.Repeats);
    }
    [Fact] public async Task Worker_respects_stop_and_authority_and_never_revives_deleted_current()
    {
        var task = await Start(); var id = task.Repeat!.Id;
        Assert.False(await new RepeatWorker(Documents).ProcessAsync(Guid.NewGuid(), workspace, epoch, id, default));
        Assert.False(await new RepeatWorker(Documents).ProcessAsync(member, workspace, Guid.NewGuid(), id, default));
        await Send(new DeleteTask(task.Id, ChecklistTasks.Versions(task)));
        Assert.False(await new RepeatWorker(Documents).ProcessAsync(member, workspace, epoch, id, default));
    }
    [Fact] public async Task Competing_stop_and_generation_have_a_serial_outcome()
    {
        var task = await Start();
        var stop = new FrozenOperation(workspace, epoch, device, ++sequence,
            new StopRepeat(task.Id, task.Repeat!.Version, ChecklistTasks.Versions(task)));
        var stopping = new SyncService(Documents).SubmitAsync(member, stop, default);
        var generating = new RepeatWorker(Documents).ProcessAsync(member, workspace, epoch, task.Repeat.Id, default);
        await Task.WhenAll(stopping, generating);
        var schedule = (await Documents.ReadAsync<RepeatSchedule>(workspace.ToString(), "repeat:" + task.Repeat.Id, default))!.Value;
        if ((await stopping).Code == "ACCEPTED") { Assert.False(schedule.Active); Assert.False(await generating); }
        else { Assert.Contains((await stopping).Code, new[] { "REPEAT_CONFLICT", "REPEAT_MOVED" }); Assert.True(await generating); }
    }
}
#endif
