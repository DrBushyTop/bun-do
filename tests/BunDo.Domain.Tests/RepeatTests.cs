using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class RepeatTests
{
    private readonly Guid workspace = Guid.NewGuid(), epoch = Guid.NewGuid(), member = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly Clock clock = new();
    private ulong sequence;
    private sealed class Clock : TimeProvider { public DateTimeOffset Now = DateTimeOffset.Parse("2026-09-14T21:30:00Z"); public override DateTimeOffset GetUtcNow() => Now; }
    private InMemoryWorkspaceStore Store() => new(workspace, epoch, HouseholdMembership.Create(member), [new(device, member)]);
    private SubmissionResult Send(InMemoryWorkspaceStore store, TaskCommand command) => new WorkspaceServer(store, clock).Handle(member, new(workspace, epoch, device, ++sequence, command));
    private TaskSnapshot Accept(InMemoryWorkspaceStore store, TaskCommand command) { var result = Send(store, command); Assert.Equal("ACCEPTED", result.Code); return result.Receipt!.Task!; }
    private TaskSnapshot Start(InMemoryWorkspaceStore store)
    {
        var task = Accept(store, new CreateTask(TaskIdentity.ForCreate(device, sequence + 1), "Milk", "Oat"));
        return Accept(store, new ConfigureRepeat(task.Id, 0, ChecklistTasks.Versions(task), new("DAILY", null, "Europe/Helsinki"), "Future milk", "Two"));
    }
    private static TaskSnapshot Generate(InMemoryWorkspaceStore store, string id, DateTimeOffset now)
    {
        var state = store.Read(); var next = TaskRepeats.Generate(state, id, now)!;
        Assert.NotNull(next); Assert.True(store.TryCommit(state.Revision, next));
        return next.Tasks[next.Repeats![id].CurrentTaskId];
    }

    [Fact] public void Seed_offline_completion_retry_and_generation_keep_one_open_occurrence_and_independent_credit()
    {
        var store = Store(); var first = Start(store);
        Assert.Single(store.Read().Tasks); Assert.Equal("Milk", first.Title); Assert.Null(first.Due);
        clock.Now = clock.Now.AddDays(10); // An offline completion is accepted only on reconnect.
        var operation = new FrozenOperation(workspace, epoch, device, ++sequence, new CompleteTask(first.Id, ChecklistTasks.Versions(first)));
        var server = new WorkspaceServer(store, clock); var completed = server.Handle(member, operation);
        Assert.Equal(completed, server.Handle(member, operation));
        var repeat = store.Read().Repeats![first.Repeat!.Id];
        Assert.Equal(new DateOnly(2026, 9, 26), repeat.PendingDate);
        var next = Generate(store, repeat.Id, clock.Now);
        Assert.Null(TaskRepeats.Generate(store.Read(), repeat.Id, clock.Now));
        Assert.NotEqual(first.Id, next.Id); Assert.Equal("Future milk", next.Title); Assert.Equal("2026-09-26", next.Due!.LocalDate);
        Assert.Null(next.FirstCompletion); Assert.Null(next.ClaimantId); Assert.False(next.IsChecklist);
        Assert.Equal("REPEAT_OPEN_CONFLICT", Send(store, new ReopenTask(first.Id, ChecklistTasks.Versions(completed.Receipt!.Task!))).Code);
        var second = Accept(store, new CompleteTask(next.Id, ChecklistTasks.Versions(next)));
        Assert.Equal(2, store.Read().Tasks.Values.Count(t => t.FirstCompletion != null));
        Assert.NotEqual(first.Id, second.FirstCompletion!.RootId);
    }

    [Fact] public void Edit_stop_delete_undo_and_pending_generation_are_serialized()
    {
        var store = Store(); var task = Start(store);
        var changed = Accept(store, new ConfigureRepeat(task.Id, task.Repeat!.Version, ChecklistTasks.Versions(task), new("WEEKLY", 1, "Europe/Helsinki"), "Monday", null));
        Assert.Equal("Milk", changed.Title);
        Assert.Equal("REPEAT_CONFLICT", Send(store, new StopRepeat(task.Id, task.Repeat.Version, ChecklistTasks.Versions(task))).Code);
        var completed = Accept(store, new CompleteTask(task.Id, ChecklistTasks.Versions(changed)));
        Assert.Equal(new DateOnly(2026, 9, 21), store.Read().Repeats![task.Repeat.Id].PendingDate);
        var stopped = Accept(store, new StopRepeat(task.Id, completed.Repeat!.Version, ChecklistTasks.Versions(completed)));
        Assert.Null(TaskRepeats.Generate(store.Read(), task.Repeat.Id, clock.Now));
        var reopened = Accept(store, new ReopenTask(task.Id, ChecklistTasks.Versions(stopped)));
        var restarted = Accept(store, new ConfigureRepeat(task.Id, reopened.Repeat!.Version, ChecklistTasks.Versions(reopened), new("DAILY", null, "Europe/Helsinki"), "Next", null));
        var deleted = Accept(store, new DeleteTask(task.Id, ChecklistTasks.Versions(restarted)));
        Assert.False(deleted.Repeat!.Active);
        var restored = Accept(store, new RestoreTask(task.Id, ChecklistTasks.Versions(deleted)));
        Assert.False(restored.Repeat!.Active); Assert.Equal("Milk", restored.Title);
        Accept(store, new CompleteTask(task.Id, ChecklistTasks.Versions(restored)));
        Assert.Null(TaskRepeats.Generate(store.Read(), task.Repeat.Id, clock.Now));
        Assert.Single(store.Read().Tasks.Values, t => t.FirstCompletion != null);
    }

    [Fact] public void Delete_completed_current_stops_pending_generation_and_late_worker_skips_missed_slots()
    {
        var store = Store(); var task = Start(store);
        var completed = Accept(store, new CompleteTask(task.Id, ChecklistTasks.Versions(task)));
        var late = TaskRepeats.Generate(store.Read(), task.Repeat!.Id, clock.Now.AddDays(30))!;
        Assert.Equal("2026-10-16", late.Tasks[late.Repeats![task.Repeat.Id].CurrentTaskId].Due!.LocalDate);
        Accept(store, new DeleteTask(task.Id, ChecklistTasks.Versions(completed)));
        Assert.Null(TaskRepeats.Generate(store.Read(), task.Repeat.Id, clock.Now));
    }

    [Fact] public void Checklist_completion_advances_once_and_older_child_reopen_cannot_create_another_open_root()
    {
        var store = Store(); var root = Start(store);
        root = Accept(store, new SplitTask(root.Id, ChecklistTasks.Versions(root), ["Step"], root.TitleVersion.Human, root.DescriptionVersion.Human));
        var child = store.Read().Tasks[root.ChildOrder!.Value[0]];
        var completed = Accept(store, new CompleteTask(child.Id, ChecklistTasks.Versions(child)));
        Assert.NotNull(store.Read().Tasks[root.Id].FirstCompletion);
        Assert.Null(completed.FirstCompletion);
        var next = Generate(store, root.Repeat!.Id, clock.Now);
        Assert.False(next.IsChecklist);
        Assert.Equal("REPEAT_OPEN_CONFLICT", Send(store, new ReopenTask(child.Id, ChecklistTasks.Versions(completed))).Code);
        Assert.Equal("COMPLETED", store.Read().Tasks[child.Id].Lifecycle);
    }

    [Theory]
    [InlineData("2026-03-28T22:30:00Z", "DAILY", null, "2026-03-30")]
    [InlineData("2026-10-24T21:30:00Z", "DAILY", null, "2026-10-26")]
    [InlineData("2026-12-31T22:30:00Z", "DAILY", null, "2027-01-02")]
    [InlineData("2026-09-13T21:00:00Z", "WEEKLY", 1, "2026-09-21")]
    public void Calendar_dates_use_saved_zone_and_are_strictly_future(string instant, string frequency, int? weekday, string expected)
    { Assert.Equal(DateOnly.Parse(expected), TaskRepeats.NextDate(new(frequency, weekday, "Europe/Helsinki"), DateTimeOffset.Parse(instant))); }

    [Fact] public void Invalid_rules_are_rejected()
    {
        Assert.False(TaskRepeats.Valid(new("WEEKLY", 0, "Europe/Helsinki")));
        Assert.False(TaskRepeats.Valid(new("DAILY", 1, "Europe/Helsinki")));
        Assert.False(TaskRepeats.Valid(new("MONTHLY", null, "Europe/Helsinki")));
        Assert.False(TaskRepeats.Valid(new("DAILY", null, "Missing/Zone")));
    }

    [Fact] public void Worker_crossing_midnight_keeps_todays_persisted_slot()
    {
        var store = Store(); var task = Start(store);
        task = Accept(store, new ConfigureRepeat(task.Id, task.Repeat!.Version, ChecklistTasks.Versions(task),
            new("WEEKLY", 1, "Europe/Helsinki"), "Monday", null));
        clock.Now = DateTimeOffset.Parse("2026-09-13T20:59:59Z");
        Accept(store, new CompleteTask(task.Id, ChecklistTasks.Versions(task)));
        var next = Generate(store, task.Repeat!.Id, clock.Now.AddSeconds(2));
        Assert.Equal("2026-09-14", next.Due!.LocalDate);
    }
}
