using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class TaskDeletionTests
{
    private readonly Guid workspace = Guid.NewGuid(), epoch = Guid.NewGuid(), alice = Guid.NewGuid(), bob = Guid.NewGuid(),
        deviceA = Guid.NewGuid(), deviceB = Guid.NewGuid();

    [Theory]
    [InlineData("OPEN")]
    [InlineData("COMPLETED")]
    [InlineData("CANCELLED")]
    public void Delete_and_restore_preserve_lifecycle_snooze_and_credit_but_not_claims(string lifecycle)
    {
        var original = new TaskSnapshot("task", "Text", "Details", new(1, 1), new(1, 1), 1,
            Lifecycle: lifecycle, LifecycleVersion: 1, ClaimantId: alice, ClaimVersion: 1, HierarchyVersion: 1,
            FirstCompletion: new("task", bob, DateTimeOffset.UtcNow.AddDays(-1)), SnoozedUntil: DateTimeOffset.UtcNow.AddDays(1));
        var members = HouseholdMembership.Create(alice);
        var now = DateTimeOffset.UtcNow;
        Assert.Equal("ACCEPTED", TaskLifecycle.Apply(original, new DeleteTask("task", Versions(original)), members,
            alice, 2, now, out var deleted, "device:7"));
        Assert.Equal("device:7", deleted!.Deletion!.GroupId);
        Assert.Null(deleted.ClaimantId);
        Assert.Equal("ACCEPTED", TaskLifecycle.Apply(deleted, new RestoreTask("task", Versions(deleted)), members,
            alice, 3, now, out var restored));
        Assert.Null(restored!.Deletion);
        Assert.Null(restored.ClaimantId);
        Assert.Equal(original.Lifecycle, restored.Lifecycle);
        Assert.Equal(original.SnoozedUntil, restored.SnoozedUntil);
        Assert.Equal(original.FirstCompletion, restored.FirstCompletion);
        Assert.Equal(original.LifecycleVersion, restored.LifecycleVersion);
    }

    [Fact]
    public void Delete_edit_races_never_resurrect_and_stale_restore_cannot_restore_a_new_group()
    {
        var store = Store(); var server = new WorkspaceServer(store);
        var created = Send(server, deviceA, 1, new CreateTask(TaskIdentity.ForCreate(deviceA, 1), "Original")).Receipt!.Task!;
        var deleted = Send(server, deviceA, 2, new DeleteTask(created.Id, Versions(created))).Receipt!.Task!;
        Assert.Empty(store.Read().RootOrder!.Value);
        Assert.Equal("DELETION_CONFLICT", Send(server, deviceB, 1, new EditTask(created.Id, new("Draft", 1), ExpectedDeletionVersion: 1)).Code);
        Assert.Equal("TASK_DELETED", Send(server, deviceB, 2, new EditTask(created.Id, new("Draft", 1))).Code);
        Assert.Equal("TASK_DELETED", Send(server, deviceB, 3, new CompleteTask(created.Id, Versions(deleted))).Code);
        var restore = new FrozenOperation(workspace, epoch, deviceA, 3, new RestoreTask(created.Id, Versions(deleted)));
        var restored = server.Handle(alice, restore).Receipt!.Task!;
        Assert.Equal(new[] { created.Id }, store.Read().RootOrder!.Value);
        Assert.Equal("ACCEPTED", server.Handle(alice, restore).Code);
        var again = Send(server, deviceA, 4, new DeleteTask(created.Id, Versions(restored))).Receipt!.Task!;
        Assert.NotEqual(deleted.Deletion!.GroupId, again.Deletion!.GroupId);
        Assert.Equal("DELETION_CONFLICT", Send(server, deviceB, 4, new RestoreTask(created.Id, Versions(deleted))).Code);
        Assert.NotNull(store.Read().Tasks[created.Id].Deletion);
        Assert.Equal("Original", store.Read().Tasks[created.Id].Title);
    }

    [Fact]
    public void Accepted_edit_is_retained_by_delete_but_changed_lifecycle_rejects_delete()
    {
        var store = Store(); var server = new WorkspaceServer(store);
        var created = Send(server, deviceA, 1, new CreateTask(TaskIdentity.ForCreate(deviceA, 1), "Original")).Receipt!.Task!;
        Send(server, deviceB, 1, new EditTask(created.Id, new("Changed", 1), ExpectedDeletionVersion: 1));
        var deleted = Send(server, deviceA, 2, new DeleteTask(created.Id, Versions(created))).Receipt!.Task!;
        Assert.Equal("Changed", deleted.Title);
        var restored = Send(server, deviceA, 3, new RestoreTask(created.Id, Versions(deleted))).Receipt!.Task!;
        Send(server, deviceB, 2, new CompleteTask(created.Id, Versions(restored)));
        Assert.Equal("LIFECYCLE_CONFLICT", Send(server, deviceA, 4, new DeleteTask(created.Id, Versions(restored))).Code);
    }

    [Fact]
    public void Purging_or_missing_records_cannot_be_restored()
    {
        var members = HouseholdMembership.Create(alice);
        var purging = new TaskSnapshot("task", "Text", null, new(1, 1), new(1, 1),
            Deletion: new("device:1", DateTimeOffset.UtcNow.AddDays(-121), true));
        Assert.Equal("TASK_PURGING", TaskLifecycle.Apply(purging, new RestoreTask("task", Versions(purging)), members,
            alice, 2, DateTimeOffset.UtcNow, out _));
        Assert.Equal("ENTITY_MISSING", TaskLifecycle.Apply(null, new RestoreTask("task", Versions(purging)), members,
            alice, 2, DateTimeOffset.UtcNow, out _));
    }

    private InMemoryWorkspaceStore Store()
    {
        var members = HouseholdMembership.Create(alice);
        members = members with { Members = members.Members.Add(bob, new(bob, 0)) };
        return new(workspace, epoch, members, [new(deviceA, alice), new(deviceB, bob)]);
    }
    private SubmissionResult Send(WorkspaceServer server, Guid device, ulong seq, TaskCommand command) =>
        server.Handle(device == deviceA ? alice : bob, new(workspace, epoch, device, seq, command));
    private static TaskStateVersions Versions(TaskSnapshot task) => new(task.LifecycleVersion, task.ClaimVersion, task.HierarchyVersion, task.DeletionVersion);
}
