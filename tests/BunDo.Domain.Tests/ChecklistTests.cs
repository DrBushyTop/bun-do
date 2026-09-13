using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class ChecklistTests
{
    [Fact]
    public void Split_creates_direct_stable_ids_and_only_the_root_earns_completion_credit()
    {
        var f = new Fixture(); var root = f.Create();
        var split = f.Send(new SplitTask(root.Id, ChecklistTasks.Versions(root), ["First", "Second"], 1, 1));
        Assert.Equal("ACCEPTED", split.Code);
        root = f.Task(root.Id);
        Assert.True(root.IsChecklist);
        Assert.Equal(new[] { TaskIdentity.ForCreate(f.Device, 2, 1), TaskIdentity.ForCreate(f.Device, 2, 2) }, root.ChildOrder!.Value);
        Assert.Equal(3, f.Store.Read().Changes[^1].Tasks.Length);
        Assert.Equal(3, f.Store.Read().TaskCount);
        Assert.Equal(new[] { root.Id }, f.Store.Read().RootOrder!.Value);
        Assert.All(f.Children(root), child => Assert.Equal(root.Id, child.ParentId));
        Assert.Equal("CHECKLIST_ROOT", f.Send(new ClaimTask(root.Id, ChecklistTasks.Versions(root))).Code);
        Assert.Equal("CHECKLIST_DEPTH", f.Send(new SplitTask(root.ChildOrder.Value[0], ChecklistTasks.Versions(f.Children(root)[0]), ["Nested"], 2, 2)).Code);
        f.Complete(root.ChildOrder.Value[0]);
        Assert.Equal("OPEN", f.Task(root.Id).Lifecycle);
        f.Complete(root.ChildOrder.Value[1], bob: true);
        var credit = f.Task(root.Id).FirstCompletion;
        Assert.NotNull(credit);
        Assert.Equal(f.Bob, credit.MemberId);
        Assert.Equal(root.Id, credit.RootId);
        Assert.Equal("COMPLETED", f.Task(root.Id).Lifecycle);
        Assert.All(f.Children(root), child => Assert.Null(child.FirstCompletion));
        var first = f.Task(root.ChildOrder.Value[0]);
        f.Send(new ReopenTask(first.Id, ChecklistTasks.Versions(first)));
        f.Complete(first.Id);
        Assert.Equal(credit, f.Task(root.Id).FirstCompletion);
    }

    [Fact]
    public void Split_guards_text_and_lifecycle_and_rejects_the_whole_oversized_batch()
    {
        var f = new Fixture(); var root = f.Create();
        var preview = new SplitTask(root.Id, ChecklistTasks.Versions(root), ["Step"], 1, 1);
        f.Send(new EditTask(root.Id, new("Changed", 1), ExpectedDeletionVersion: 1));
        Assert.Equal("FIELD_CONFLICT", f.Send(preview).Code);
        Assert.Single(f.Store.Read().Tasks);
        root = f.Task(root.Id);
        var tooMany = new SplitTask(root.Id, ChecklistTasks.Versions(root), Enumerable.Repeat("Step", ChecklistTasks.MaximumChildren + 1).ToArray(), root.TitleVersion.Human, root.DescriptionVersion.Human);
        Assert.Equal("CHECKLIST_LIMIT", f.Send(tooMany).Code);
        Assert.Single(f.Store.Read().Tasks);
        preview = preview with { ExpectedTitleHumanVersion = root.TitleVersion.Human };
        f.Complete(root.Id);
        Assert.Equal("LIFECYCLE_CONFLICT", f.Send(preview).Code);
        Assert.Single(f.Store.Read().Tasks);
    }

    [Fact]
    public void Child_edits_fence_root_delete_and_group_restore_keeps_independent_deletions()
    {
        var f = new Fixture(); var root = f.Split(); var children = f.Children(root);
        f.Send(new DeleteTask(children[0].Id, ChecklistTasks.Versions(children[0])));
        var independent = f.Task(children[0].Id).Deletion;
        root = f.Task(root.Id);
        var deletion = new DeleteTask(root.Id, ChecklistTasks.Versions(root));
        f.Send(new EditTask(children[1].Id, new("Human edit", children[1].TitleVersion.Human), ExpectedDeletionVersion: children[1].DeletionVersion), true);
        Assert.Equal("SUBTREE_CONFLICT", f.Send(deletion).Code);
        root = f.Task(root.Id);
        Assert.Equal("ACCEPTED", f.Send(new DeleteTask(root.Id, ChecklistTasks.Versions(root))).Code);
        var deleted = f.Task(root.Id);
        Assert.Equal(independent, f.Task(children[0].Id).Deletion);
        Assert.Equal(deleted.Deletion!.GroupId, f.Task(children[1].Id).Deletion!.GroupId);
        Assert.Equal("PARENT_UNAVAILABLE", f.Send(new RestoreTask(children[0].Id, ChecklistTasks.Versions(f.Task(children[0].Id)))).Code);
        Assert.Equal("ACCEPTED", f.Send(new RestoreTask(root.Id, ChecklistTasks.Versions(deleted))).Code);
        Assert.NotNull(f.Task(children[0].Id).Deletion);
        Assert.Null(f.Task(children[1].Id).Deletion);
        Assert.Equal("Human edit", f.Task(children[1].Id).Title);
        Assert.Equal("OPEN", f.Task(root.Id).Lifecycle);
    }

    [Fact]
    public void Explicit_cancellation_reopens_only_its_items_and_empty_roots_can_add_items()
    {
        var f = new Fixture(); var root = f.Split(); var children = f.Children(root);
        f.Send(new CancelTask(children[0].Id, ChecklistTasks.Versions(children[0])));
        root = f.Task(root.Id);
        f.Send(new CancelTask(root.Id, ChecklistTasks.Versions(root)));
        var cancelled = f.Task(root.Id);
        var group = cancelled.CancellationGroupId;
        f.Send(new CancelTask(root.Id, ChecklistTasks.Versions(cancelled)));
        Assert.Equal(group, f.Task(root.Id).CancellationGroupId);
        f.Send(new ReopenTask(root.Id, ChecklistTasks.Versions(f.Task(root.Id))));
        Assert.Equal("CANCELLED", f.Task(children[0].Id).Lifecycle);
        Assert.Equal("OPEN", f.Task(children[1].Id).Lifecycle);
        var remaining = f.Task(children[1].Id);
        f.Send(new DeleteTask(remaining.Id, ChecklistTasks.Versions(remaining)));
        root = f.Task(root.Id);
        Assert.True(root.EmptyChecklist);
        Assert.Equal("CANCELLED", root.Lifecycle);
        Assert.Equal("CHECKLIST_DERIVED", f.Send(new ReopenTask(root.Id, ChecklistTasks.Versions(root))).Code);
        Assert.Equal("ACCEPTED", f.Send(new AddChildren(root.Id, ChecklistTasks.Versions(root), ["A new step"], root.TitleVersion.Human, root.DescriptionVersion.Human)).Code);
        Assert.Equal("OPEN", f.Task(root.Id).Lifecycle);
        Assert.False(f.Task(root.Id).EmptyChecklist);
    }

    [Fact]
    public void Root_snooze_clears_claims_and_blocks_already_open_child_actions()
    {
        var f = new Fixture(); var root = f.Split(); var child = f.Children(root)[0];
        f.Send(new ClaimTask(child.Id, ChecklistTasks.Versions(child)), true);
        root = f.Task(root.Id);
        var until = DateTimeOffset.UtcNow.AddHours(1);
        Assert.Equal("ACCEPTED", f.Send(new SetSnooze(root.Id, ChecklistTasks.Versions(root), until)).Code);
        Assert.Null(f.Task(child.Id).ClaimantId);
        child = f.Task(child.Id);
        Assert.Equal("TASK_SNOOZED", f.Send(new ClaimTask(child.Id, ChecklistTasks.Versions(child))).Code);
        Assert.Equal("TASK_SNOOZED", f.Send(new CompleteTask(child.Id, ChecklistTasks.Versions(child))).Code);
        root = f.Task(root.Id);
        f.Send(new DeleteTask(root.Id, ChecklistTasks.Versions(root)));
        root = f.Task(root.Id);
        f.Send(new RestoreTask(root.Id, ChecklistTasks.Versions(root)));
        Assert.Equal(until, f.Task(root.Id).SnoozedUntil);
        root = f.Task(root.Id);
        f.Send(new ClearSnooze(root.Id, ChecklistTasks.Versions(root)));
        Assert.Equal("ACCEPTED", f.Complete(child.Id).Code);
    }

    [Fact]
    public void Item_order_uses_only_sibling_anchors_and_advances_the_root_subtree()
    {
        var f = new Fixture(); var root = f.Split(); var children = f.Children(root);
        var other = f.Create(); var before = root.SubtreeVersion;
        var child = children[1];
        Assert.Equal("ACCEPTED", f.Send(new MoveTask(child.Id, child.OrderIntentVersion, child.DeletionVersion,
            root.Id, other.Id, children[0].Id)).Code);
        Assert.Equal(new[] { child.Id, children[0].Id }, f.Task(root.Id).ChildOrder!.Value);
        Assert.True(f.Task(root.Id).SubtreeVersion > before);
        Assert.Equal(new[] { root.Id, other.Id }, f.Store.Read().RootOrder!.Value);
    }

    private sealed class Fixture
    {
        public Guid Member { get; } = Guid.NewGuid();
        public Guid Bob { get; } = Guid.NewGuid();
        public Guid Device { get; } = Guid.NewGuid();
        private readonly Guid bobDevice = Guid.NewGuid(), workspace = Guid.NewGuid(), epoch = Guid.NewGuid();
        public InMemoryWorkspaceStore Store { get; }
        private readonly WorkspaceServer server;
        public Fixture()
        {
            var membership = HouseholdMembership.Create(Member);
            membership = membership with { Members = membership.Members.Add(Bob, new(Bob, 0)) };
            Store = new(workspace, epoch, membership, [new(Device, Member), new(bobDevice, Bob)]);
            server = new(Store);
        }
        public SubmissionResult Send(TaskCommand command, bool bob = false)
        {
            var device = bob ? bobDevice : Device;
            return server.Handle(bob ? Bob : Member, new(workspace, epoch, device, Store.Read().Devices[device].LastTerminalSequence + 1, command));
        }
        public TaskSnapshot Create()
        {
            var seq = Store.Read().Devices[Device].LastTerminalSequence + 1;
            return Send(new CreateTask(TaskIdentity.ForCreate(Device, seq), "Root")).Receipt!.Task!;
        }
        public TaskSnapshot Split()
        {
            var root = Create();
            Assert.Equal("ACCEPTED", Send(new SplitTask(root.Id, ChecklistTasks.Versions(root), ["First", "Second"], root.TitleVersion.Human, root.DescriptionVersion.Human)).Code);
            return Task(root.Id);
        }
        public TaskSnapshot Task(string id) => Store.Read().Tasks[id];
        public TaskSnapshot[] Children(TaskSnapshot root) => root.ChildOrder!.Value.Select(Task).ToArray();
        public SubmissionResult Complete(string id, bool bob = false) => Send(new CompleteTask(id, ChecklistTasks.Versions(Task(id))), bob);
    }
}
