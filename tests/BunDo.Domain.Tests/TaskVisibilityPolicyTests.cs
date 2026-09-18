using System.Collections.Immutable;
using System.Text.Json;
using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class TaskVisibilityPolicyTests
{
    private readonly Guid owner = Guid.NewGuid();
    private WorkspaceState State()
    {
        var root = new TaskSnapshot(Guid.NewGuid().ToString(), "Live content", "Current notes", new(1, 1), new(1, 1),
            Creation: new(owner, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow),
            Capture: new("private original", "private original notes", JsonSerializer.SerializeToElement(new { secret = true }), DateTimeOffset.UtcNow),
            LastChange: new(owner, DateTimeOffset.UtcNow, "HUMAN"));
        return new(Guid.NewGuid(), Guid.NewGuid(), 1, ImmutableDictionary<Guid, DeviceRegistration>.Empty,
            ImmutableDictionary<string, TaskSnapshot>.Empty.Add(root.Id, root), ImmutableDictionary<string, OperationReceipt>.Empty,
            [], HouseholdMembership.Create(owner) with { PersonalOwnerId = owner });
    }

    [Fact]
    public void Export_and_import_never_publish_deleted_items_private_capture_or_change_history()
    {
        var source = State(); var root = source.Tasks.Values.Single();
        var deleted = root with { Id = Guid.NewGuid().ToString(), ParentId = root.Id, Title = "Deleted private secret", Deletion = new("old", DateTimeOffset.UtcNow) };
        var child = root with { Id = Guid.NewGuid().ToString(), ParentId = root.Id, Title = "Live item", Capture = null };
        root = root with { IsChecklist = true, ChildOrder = [child.Id, deleted.Id] };
        source = source with { Tasks = source.Tasks.SetItem(root.Id, root).Add(child.Id, child).Add(deleted.Id, deleted) };
        Assert.Equal("ACCEPTED", TaskVisibility.Validate(source, owner, root.Id));
        var content = TaskVisibility.Export(source, root.Id);
        var imported = TaskVisibility.Import(State() with { Membership = HouseholdMembership.Create(owner) }, owner, Guid.NewGuid(), content, DateTimeOffset.UtcNow);
        var payload = JsonSerializer.Serialize(imported.Changes.Single());
        Assert.DoesNotContain("private original", payload);
        Assert.DoesNotContain("Deleted private secret", payload);
        Assert.Equal(2, imported.Changes.Single().Tasks.Length);
        Assert.All(imported.Changes.Single().Tasks, t => { Assert.Null(t.Capture); Assert.Null(t.LastChange); Assert.Equal(owner, t.Creation!.ActorId); });
        Assert.Single(imported.Changes.Single().Tasks.Single(t => t.ParentId is null).ChildOrder!.Value);
    }

    [Fact]
    public void Moving_a_cancelled_checklist_preserves_group_reopening_without_publishing_private_operation_ids()
    {
        var source = State(); var root = source.Tasks.Values.Single();
        var child = root with { Id = Guid.NewGuid().ToString(), ParentId = root.Id, Lifecycle = "CANCELLED", CancellationGroupId = "private-operation" };
        var independent = child with { Id = Guid.NewGuid().ToString(), CancellationGroupId = null };
        root = root with { IsChecklist = true, ChildOrder = [child.Id, independent.Id], Lifecycle = "CANCELLED", CancellationGroupId = "private-operation" };
        source = source with { Tasks = source.Tasks.SetItem(root.Id, root).Add(child.Id, child).Add(independent.Id, independent) };
        var target = TaskVisibility.Import(State(), owner, Guid.NewGuid(), TaskVisibility.Export(source, root.Id), DateTimeOffset.UtcNow);
        var imported = target.Changes.Single().Tasks;
        var parent = imported.Single(t => t.ParentId is null);
        Assert.NotNull(parent.CancellationGroupId); Assert.NotEqual("private-operation", parent.CancellationGroupId);
        Assert.Equal(parent.CancellationGroupId, imported[1].CancellationGroupId);
        Assert.Null(imported[2].CancellationGroupId);
    }

    [Fact]
    public void Personal_owner_rule_denies_an_extra_member_even_if_membership_data_is_inconsistent()
    {
        var state = State(); var stranger = Guid.NewGuid();
        var members = state.Membership with { Members = state.Membership.Members.Add(stranger, new(stranger, 1)) };
        Assert.False(members.CanRead(stranger)); Assert.True(members.CanRead(owner));
        Assert.Equal("PERSONAL_WORKSPACE", MembershipPolicy.Apply(members, stranger,
            new TransferHouseholdOwnership(stranger, 0), DateTimeOffset.UtcNow, 2).Code);
    }

    [Fact]
    public void Unknown_creator_and_child_level_visibility_are_rejected()
    {
        var state = State(); var root = state.Tasks.Values.Single();
        Assert.Equal("NOT_CREATOR", TaskVisibility.Validate(state with { Tasks = state.Tasks.SetItem(root.Id, root with { Creation = null }) }, owner, root.Id));
        Assert.Equal("CHECKLIST_ROOT", TaskVisibility.Validate(state with { Tasks = state.Tasks.SetItem(root.Id, root with { ParentId = "parent" }) }, owner, root.Id));
    }
}
