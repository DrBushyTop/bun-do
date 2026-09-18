using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class ReusableListsTests
{
    private static SavedList Definition() => new(Guid.NewGuid(), "Cottage", "Weekend", [new("Milk, 1 litre", "Oat"), new("Keys")]);
    [Fact]
    public void Definitions_are_versioned_independent_content_with_retry_and_deletion_guards()
    {
        var value = Definition();
        var command = new SaveList(Guid.NewGuid(), 0, value.Id, value);
        var first = ReusableLists.Apply(new(Lists: []), command);
        Assert.Equal("ACCEPTED", first.Code);
        Assert.Equal(first.Library, ReusableLists.Apply(first.Library, command).Library);
        Assert.Equal("OPERATION_ID_REUSED", ReusableLists.Apply(first.Library, command with { Value = value with { Title = "Other" } }).Code);
        Assert.Equal("LIST_CHANGED", ReusableLists.Apply(first.Library, command with { OperationId = Guid.NewGuid() }).Code);
        var deleted = ReusableLists.Apply(first.Library, new(Guid.NewGuid(), 1, value.Id, null));
        Assert.Empty(deleted.Library.Lists);
        Assert.Equal("LIST_CHANGED", ReusableLists.Apply(deleted.Library, command).Code);
        Assert.Equal("Oat", value.Items[0].Notes);
        Assert.Equal("INVALID_LIST", ReusableLists.Apply(new(Lists: []), command with { Value = value with { Items = [] } }).Code);
    }
    [Theory]
    [InlineData("STANDING", "OPEN", false)]
    [InlineData("FINITE", "COMPLETED", true)]
    [InlineData(null, "COMPLETED", true)]
    public void Standing_and_finite_lists_share_item_state_but_have_distinct_completion(string? kind, string lifecycle, bool credit)
    {
        var member = Guid.NewGuid(); var device = Guid.NewGuid(); var workspace = Guid.NewGuid(); var epoch = Guid.NewGuid();
        var store = new InMemoryWorkspaceStore(workspace, epoch, HouseholdMembership.Create(member), [new(device, member)]);
        var server = new WorkspaceServer(store); ulong sequence = 0;
        SubmissionResult Send(TaskCommand command) => server.Handle(member, new(workspace, epoch, device, ++sequence, command));
        var root = Send(new CreateTask(TaskIdentity.ForCreate(device, 1), "Groceries", ListKind: kind)).Receipt!.Task!;
        root = Send(new SplitTask(root.Id, ChecklistTasks.Versions(root), ["Milk", "Dough"], 1, 1) { Notes = ["Oat, 1 litre", null] }).Receipt!.Task!;
        Assert.Equal("Oat, 1 litre", store.Read().Tasks[root.ChildOrder!.Value[0]].Description);
        foreach (var id in root.ChildOrder.Value)
        {
            var child = store.Read().Tasks[id];
            Assert.Equal("ACCEPTED", Send(new CompleteTask(id, ChecklistTasks.Versions(child))).Code);
        }
        root = store.Read().Tasks[root.Id];
        Assert.Equal(lifecycle, root.Lifecycle);
        Assert.Equal(credit, root.FirstCompletion is not null);
        var pin = new SetListPinned(root.Id, ChecklistTasks.Versions(root), true);
        Assert.True(Send(pin).Receipt!.Task!.ListPinned);
        Assert.Equal("HIERARCHY_CONFLICT", Send(pin with { Pinned = false }).Code);
        var milk = store.Read().Tasks[root.ChildOrder!.Value[0]];
        Assert.Equal("ACCEPTED", Send(new ReopenTask(milk.Id, ChecklistTasks.Versions(milk))).Code);
        Assert.Equal("Oat, 1 litre", store.Read().Tasks[milk.Id].Description);
        Assert.Equal("OPEN", store.Read().Tasks[root.Id].Lifecycle);
    }
    [Fact]
    public void Explicit_adventure_finish_preserves_unchecked_and_missing_work_without_credit()
    {
        var root = new TaskSnapshot(Guid.NewGuid().ToString(), "Find dough", null, new(1, 1), new(1, 1));
        var active = new AcceptedAdventure(Guid.NewGuid(), Guid.NewGuid(), 1, new("Pizza", "", [new(root.Id, "Shop", 1, 10)]), DateTimeOffset.UtcNow, "dojo-garden");
        var board = new AdventureBoard(Active: active);
        var tasks = new Dictionary<string, TaskSnapshot> { [root.Id] = root };
        Assert.Equal("ADVENTURE_NOT_COMPLETE", HouseholdAdventure.Close(board, active.Id, 1, false, false, tasks).Code);
        var result = HouseholdAdventure.Close(board, active.Id, 1, false, true, tasks);
        Assert.Equal("ACCEPTED", result.Code); Assert.Null(result.Board.Active);
        Assert.Equal("OPEN", tasks[root.Id].Lifecycle); Assert.Null(tasks[root.Id].FirstCompletion);
        Assert.Equal("ADVENTURE_CHANGED", HouseholdAdventure.Close(board, active.Id, 2, false, true, tasks).Code);
    }
}
