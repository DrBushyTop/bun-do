using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class GuidedAdventureTests
{
    private readonly Guid id = Guid.NewGuid(), member = Guid.NewGuid(), registration = Guid.NewGuid();
    private readonly DateTimeOffset now = DateTimeOffset.UtcNow;
    private static GuidedDraft Draft(params GuidedPhase[] phases) => new("A quiet corner", "", phases);
    private static GuidedPhase New(string title = "Sort papers") => new(null, title, "Make room", 1, 15);
    private static Dictionary<string, TaskSnapshot> Tasks(params TaskSnapshot[] tasks) => tasks.ToDictionary(t => t.Id);
    private TaskSnapshot Created(string key, string title, Guid? actor = null) => new(key, title, null, new(4, 4), new(4, 4),
        Capture: new(title, null, System.Text.Json.JsonSerializer.SerializeToElement(new { }), now), Creation: new(actor ?? member, now, now));
    private AdventureBoard Begin(GuidedDraft draft, Dictionary<string, TaskSnapshot>? tasks = null) =>
        GuidedAdventure.Begin(new(), id, member, registration, 2, 2, draft, tasks ?? [], now).Board;

    [Fact] public void Draft_validation_distinguishes_existing_references_and_new_work()
    {
        Assert.True(GuidedAdventure.Valid(Draft(New(), new("existing", null, "Read", 2, 20))));
        Assert.False(GuidedAdventure.Valid(Draft(New() with { RootId = "existing" })));
        Assert.False(GuidedAdventure.Valid(Draft(New() with { TaskTitle = null })));
        Assert.False(GuidedAdventure.Valid(Draft(New() with { Stars = 4 })));
        Assert.False(GuidedAdventure.Valid(Draft()));
        Assert.False(GuidedAdventure.Valid(Draft(new GuidedPhase("existing", null, "Read", 1, 5), new("existing", null, "Read again", 1, 5))));
    }
    [Fact] public void Reservation_is_idempotent_and_blocks_competing_adventure_acceptance()
    {
        var draft = Draft(New()); var board = Begin(draft);
        Assert.Null(board.Active);
        Assert.Equal(id, board.Creation!.Id);
        Assert.Equal(board, GuidedAdventure.Begin(board, id, member, registration, 2, 3, draft, Tasks(), now).Board);
        Assert.Equal("CREATION_PENDING", GuidedAdventure.Begin(board, Guid.NewGuid(), member, registration, 3, 3, draft, Tasks(), now).Code);
        Assert.Equal("CREATION_PENDING", HouseholdAdventure.Accept(board, Guid.NewGuid(), Guid.NewGuid(), Tasks(), 4, now).Code);
        Assert.Equal("ADVENTURE_CHANGED", GuidedAdventure.Begin(new(), id, member, registration, 1, 3, draft, Tasks(), now).Code);
    }
    [Fact] public void Finish_requires_accepted_approved_roots_and_original_registration()
    {
        var board = Begin(Draft(New()));
        Assert.Equal("TASKS_PENDING", GuidedAdventure.Finish(board, id, member, registration, ["new"], Tasks(), 4, now).Code);
        var tasks = Tasks(Created("new", "Sort papers"));
        Assert.Equal("CREATION_OTHER_DEVICE", GuidedAdventure.Finish(board, id, member, Guid.NewGuid(), ["new"], tasks, 4, now).Code);
        Assert.Equal("SOURCE_UNAVAILABLE", GuidedAdventure.Finish(board, id, member, registration, ["new"], Tasks(Created("new", "Different work")), 4, now).Code);
        var result = GuidedAdventure.Finish(board, id, member, registration, ["new"], tasks, 4, now);
        Assert.Equal("ACCEPTED", result.Code); Assert.Null(result.Board.Creation); Assert.Equal("new", result.Board.Active!.Draft.Phases[0].RootId);
        Assert.Equal(result.Board, GuidedAdventure.Finish(result.Board, id, member, registration, ["new"], tasks, 5, now).Board);
    }
    [Fact] public void Existing_roots_are_validated_and_cancelled_start_cannot_resurrect()
    {
        var draft = Draft(new GuidedPhase("existing", null, "Clear", 1, 10));
        Assert.Equal("SOURCE_UNAVAILABLE", GuidedAdventure.Begin(new(), id, member, registration, 2, 2, draft, Tasks(), now).Code);
        var board = Begin(draft, Tasks(Created("existing", "Anything")));
        Assert.Equal("CONFIRMATION_REQUIRED", GuidedAdventure.Cancel(board, id, false, now).Code);
        var cancelled = GuidedAdventure.Cancel(board, id, true, now).Board;
        Assert.Null(GuidedAdventure.Begin(cancelled, id, member, registration, 2, 4, draft, Tasks(), now).Board.Creation);
        Assert.Null(GuidedAdventure.Finish(cancelled, id, member, registration, ["existing"], Tasks(), 4, now).Board.Active);
        Assert.Equal("ADVENTURE_CHANGED", GuidedAdventure.Begin(cancelled with { Batch = new(Guid.NewGuid(), "EMPTY", now) }, id, member, registration, 2, 5, draft, Tasks(), now).Code);
    }
}
