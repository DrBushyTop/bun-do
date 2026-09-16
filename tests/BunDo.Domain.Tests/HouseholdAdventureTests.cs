using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class HouseholdAdventureTests
{
    private static TaskSnapshot Root(string id, string lifecycle = "OPEN") => new(id, "Current " + id, null, new(1, 1), new(1, 1), Lifecycle: lifecycle);
    private static AdventureDraft Draft(params string[] ids) => new("A useful session", "", ids.Select(id => new AdventurePhase(id, "A gentle phase", 2, 10)).ToArray());
    private static AdventureBoard Board(AdventureDraft draft) => new(new(Guid.NewGuid(), "READY", DateTimeOffset.UtcNow,
        DateTimeOffset.UtcNow.AddHours(24), Proposals: [new(Guid.NewGuid(), draft)]));

    [Fact]
    public void Generated_suggestions_require_three_roots_and_different_sets_but_existing_edits_remain_valid()
    {
        Assert.False(HouseholdAdventure.ValidSuggestions([Draft("one")]));
        Assert.False(HouseholdAdventure.ValidSuggestions([Draft("one", "two")]));
        Assert.True(HouseholdAdventure.ValidSuggestions([Draft("one", "two", "three")]));
        Assert.False(HouseholdAdventure.ValidSuggestions([Draft("one", "two", "three"), Draft("three", "one", "two")]));
        Assert.True(HouseholdAdventure.ValidSuggestions([Draft("one", "two", "three"), Draft("one", "two", "four")]));
        var legacy = Board(Draft("one"));
        Assert.Equal("SUGGESTIONS_UNAVAILABLE", HouseholdAdventure.Accept(legacy, legacy.Batch!.Id,
            legacy.Batch.Proposals![0].Id, new Dictionary<string, TaskSnapshot> { ["one"] = Root("one") }, 1, DateTimeOffset.UtcNow).Code);
        Assert.True(HouseholdAdventure.Valid(Draft("one")));
    }

    [Fact]
    public void Estimates_text_and_distinct_root_references_are_bounded()
    {
        Assert.True(HouseholdAdventure.Valid(Draft("one")));
        Assert.False(HouseholdAdventure.Valid(Draft("one", "one")));
        Assert.False(HouseholdAdventure.Valid(Draft()));
        Assert.True(HouseholdAdventure.Valid(Draft(), allowEmpty: true));
        Assert.False(HouseholdAdventure.Valid(Draft("one") with { Title = " " }));
        Assert.False(HouseholdAdventure.Valid(Draft("one") with { Flavor = new string('x', 601) }));
        Assert.False(HouseholdAdventure.Valid(Draft("one") with { Phases = [new("one", "x", 4, 10)] }));
        Assert.False(HouseholdAdventure.Valid(Draft("one") with { Phases = [new("one", "x", 1, 0)] }));
        Assert.False(HouseholdAdventure.Valid(Draft("one") with { Phases = [null!] }));
        Assert.False(HouseholdAdventure.Valid(Draft(Enumerable.Range(0, 9).Select(i => i.ToString()).ToArray())));
    }

    [Fact]
    public void Progress_tracks_current_roots_not_phases_or_checklist_size_and_can_fall()
    {
        var tasks = new Dictionary<string, TaskSnapshot> {
            ["one"] = Root("one", "COMPLETED") with { IsChecklist = true, ChildOrder = ["child"] },
            ["child"] = Root("child", "COMPLETED") with { ParentId = "one" }, ["two"] = Root("two"),
        };
        var progress = HouseholdAdventure.Progress(Draft("one", "two", "one"), tasks);
        Assert.Equal(1, progress.Completed); Assert.Equal(2, progress.Total); Assert.False(progress.IsComplete);
        Assert.Single(progress.Roots[0].Checklist);
        tasks["one"] = tasks["one"] with { Lifecycle = "OPEN", Title = "Edited title", ChildOrder = ["child", "new"] };
        tasks["new"] = Root("new") with { ParentId = "one" };
        progress = HouseholdAdventure.Progress(Draft("one", "two"), tasks);
        Assert.Equal(0, progress.Completed); Assert.Equal(2, progress.Total);
        Assert.Equal("Edited title", progress.Roots[0].Task!.Title); Assert.Equal(2, progress.Roots[0].Checklist.Length);
    }

    [Theory]
    [InlineData("deleted")]
    [InlineData("cancelled")]
    [InlineData("child")]
    [InlineData("missing")]
    public void Unavailable_roots_never_count_as_complete_or_expose_deleted_content(string state)
    {
        var task = Root("one", "COMPLETED");
        task = state switch { "deleted" => task with { Deletion = new("x", DateTimeOffset.UtcNow) },
            "cancelled" => task with { Lifecycle = "CANCELLED" }, "child" => task with { ParentId = "parent" }, _ => task };
        var tasks = new Dictionary<string, TaskSnapshot>();
        if (state != "missing") tasks.Add(task.Id, task);
        var result = HouseholdAdventure.Progress(Draft("one"), tasks);
        Assert.Equal(0, result.Completed); Assert.Equal(1, result.Total); Assert.False(result.IsComplete);
        Assert.False(result.Roots[0].Available); Assert.Null(result.Roots[0].Task);
        Assert.False(HouseholdAdventure.Progress(Draft(), tasks).IsComplete);
    }

    [Fact]
    public void Accept_retry_preserves_choice_and_does_not_resurrect_it_after_close()
    {
        var board = Board(Draft("one", "two", "three"));
        var proposal = board.Batch!.Proposals![0]; var batch = board.Batch.Id;
        var tasks = new[] { "one", "two", "three" }.ToDictionary(id => id, id => Root(id));
        var accepted = HouseholdAdventure.Accept(board, batch, proposal.Id, tasks, 10, DateTimeOffset.UtcNow);
        Assert.Equal("ACCEPTED", accepted.Code); Assert.Equal("CONSUMED", accepted.Board.Batch!.Status);
        Assert.Null(accepted.Board.Batch.Proposals);
        Assert.Equal(accepted.Board, HouseholdAdventure.Accept(accepted.Board, batch, proposal.Id, tasks, 20, DateTimeOffset.UtcNow.AddDays(2)).Board);
        Assert.Equal("ADVENTURE_ACTIVE", HouseholdAdventure.Accept(accepted.Board, batch, Guid.NewGuid(), tasks, 20, DateTimeOffset.UtcNow).Code);
        var closed = HouseholdAdventure.Close(accepted.Board, proposal.Id, 10, true, true, tasks);
        Assert.Null(closed.Board.Active);
        Assert.Equal("SUGGESTIONS_UNAVAILABLE", HouseholdAdventure.Accept(closed.Board, batch, proposal.Id, tasks, 21, DateTimeOffset.UtcNow).Code);
    }

    [Fact]
    public void Expired_and_changed_sources_cannot_be_accepted()
    {
        var board = Board(Draft("one", "two", "three")); var batch = board.Batch!; var id = batch.Proposals![0].Id;
        Assert.Equal("SUGGESTIONS_UNAVAILABLE", HouseholdAdventure.Accept(board, batch.Id, id,
            new Dictionary<string, TaskSnapshot>(), 1, batch.ExpiresAt!.Value).Code);
        Assert.Equal("SOURCE_UNAVAILABLE", HouseholdAdventure.Accept(board, batch.Id, id,
            new Dictionary<string, TaskSnapshot> { ["one"] = Root("one", "COMPLETED") }, 1, batch.CreatedAt).Code);
    }

    [Fact]
    public void Edit_can_retain_unavailable_references_but_cannot_add_unavailable_work_and_empty_never_completes()
    {
        var board = new AdventureBoard(Active: new(Guid.NewGuid(), Guid.NewGuid(), 1, Draft("gone"), DateTimeOffset.UtcNow));
        var tasks = new Dictionary<string, TaskSnapshot> { ["next"] = Root("next") };
        Assert.Equal("SOURCE_UNAVAILABLE", HouseholdAdventure.Edit(board, board.Active!.Id, 1, Draft("missing"), tasks, 2).Code);
        Assert.Equal("ACCEPTED", HouseholdAdventure.Edit(board, board.Active.Id, 1, Draft("gone"), tasks, 2).Code);
        var edited = HouseholdAdventure.Edit(board, board.Active.Id, 1, Draft("next"), tasks, 2);
        Assert.Equal("ADVENTURE_CHANGED", HouseholdAdventure.Edit(edited.Board, board.Active.Id, 1, Draft("next"), tasks, 3).Code);
        var empty = HouseholdAdventure.Edit(edited.Board, board.Active.Id, 2, Draft(), tasks, 3);
        Assert.Equal("ADVENTURE_NOT_COMPLETE", HouseholdAdventure.Close(empty.Board, board.Active.Id, 3, false, false, tasks).Code);
        Assert.Equal("CONFIRMATION_REQUIRED", HouseholdAdventure.Close(empty.Board, board.Active.Id, 3, true, false, tasks).Code);
    }
}
