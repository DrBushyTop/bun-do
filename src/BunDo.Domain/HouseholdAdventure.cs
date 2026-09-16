namespace BunDo.Domain;

public sealed record AdventurePhase(string RootId, string Name, int Stars, int Minutes);
public sealed record AdventureDraft(string Title, string Flavor, AdventurePhase[] Phases);
public sealed record AdventureProposal(Guid Id, AdventureDraft Draft, string Artwork = "dojo-garden");
public sealed record AdventureBatch(Guid Id, string Status, DateTimeOffset CreatedAt,
    DateTimeOffset? ExpiresAt = null, DateTimeOffset? LeaseUntil = null,
    AdventureProposal[]? Proposals = null, string? Error = null);
public sealed record AcceptedAdventure(Guid Id, Guid BatchId, ulong Version, AdventureDraft Draft,
    DateTimeOffset AcceptedAt, string Artwork = "dojo-garden");
// Only one bounded batch and one accepted adventure. No task content or adventure history accumulates here.
public sealed record AdventureBoard(AdventureBatch? Batch = null, AcceptedAdventure? Active = null, AdventureCreation? Creation = null);
public sealed record AdventureRoot(string RootId, bool Available, TaskSnapshot? Task, TaskSnapshot[] Checklist);
public sealed record AdventureProgress(int Completed, int Total, bool IsComplete, AdventureRoot[] Roots);

public static class HouseholdAdventure
{
    public const int MinimumSuggestedPhases = 3;
    public const int MaximumPhases = 8;
    // Creation/editing may deliberately remove work. Only generated suggestions have this minimum.
    public static bool ValidSuggestions(AdventureDraft[]? drafts) => drafts is { Length: >= 1 and <= 2 } &&
        drafts.All(d => Valid(d) && d.Phases.Length >= MinimumSuggestedPhases) &&
        (drafts.Length == 1 || !drafts[0].Phases.Select(p => p.RootId).ToHashSet(StringComparer.Ordinal)
            .SetEquals(drafts[1].Phases.Select(p => p.RootId)));
    public static bool Valid(AdventureDraft? draft, bool allowEmpty = false) => draft is not null &&
        Text(draft.Title, 160) && Text(draft.Flavor, 600, empty: true) && draft.Phases is { } phases &&
        phases.Length <= MaximumPhases && (allowEmpty || phases.Length > 0) &&
        phases.All(p => p is not null && Text(p.RootId, 200) && Text(p.Name, 160) && p.Stars is >= 1 and <= 3 && p.Minutes is >= 1 and <= 1440) &&
        phases.Select(p => p.RootId).Distinct(StringComparer.Ordinal).Count() == phases.Length;

    private static bool Text(string? value, int maximum, bool empty = false) => value is not null &&
        value == value.Trim() && (empty || value.Length > 0) && value.EnumerateRunes().Count() <= maximum && !value.Any(char.IsControl);

    public static bool Available(TaskSnapshot? task) => task is { ParentId: null, Deletion: null, Lifecycle: "OPEN" or "COMPLETED" };
    public static bool Candidate(TaskSnapshot? task) => Available(task) && task!.Lifecycle == "OPEN";

    public static (string Code, AdventureBoard Board) Accept(AdventureBoard board, Guid batchId, Guid proposalId,
        IReadOnlyDictionary<string, TaskSnapshot> tasks, ulong revision, DateTimeOffset now)
    {
        if (board.Active is { } active)
            return (active.Id == proposalId && active.BatchId == batchId ? "ACCEPTED" : "ADVENTURE_ACTIVE", board);
        if (board.Creation is not null) return ("CREATION_PENDING", board);
        if (board.Batch is not { Status: "READY" } batch || batch.Id != batchId || batch.ExpiresAt <= now)
            return ("SUGGESTIONS_UNAVAILABLE", board);
        var proposal = batch.Proposals?.SingleOrDefault(p => p.Id == proposalId);
        if (proposal is null || !ValidSuggestions(batch.Proposals?.Select(p => p.Draft).ToArray()))
            return ("SUGGESTIONS_UNAVAILABLE", board);
        if (proposal.Draft.Phases.Any(p => !tasks.TryGetValue(p.RootId, out var task) || !Candidate(task)))
            return ("SOURCE_UNAVAILABLE", board);
        return ("ACCEPTED", board with {
            Batch = batch with { Status = "CONSUMED", Proposals = null },
            Active = new(proposalId, batchId, revision, proposal.Draft, now, proposal.Artwork),
        });
    }

    public static (string Code, AdventureBoard Board) Edit(AdventureBoard board, Guid id, ulong expectedVersion,
        AdventureDraft draft, IReadOnlyDictionary<string, TaskSnapshot> tasks, ulong revision)
    {
        if (board.Active is not { } active || active.Id != id) return ("ADVENTURE_CHANGED", board);
        if (active.Version != expectedVersion) return ("ADVENTURE_CHANGED", board);
        if (!Valid(draft, allowEmpty: true)) return ("INVALID_ADVENTURE", board);
        var existing = active.Draft.Phases.Select(p => p.RootId).ToHashSet(StringComparer.Ordinal);
        if (draft.Phases.Any(p => !existing.Contains(p.RootId) &&
            (!tasks.TryGetValue(p.RootId, out var task) || !Available(task)))) return ("SOURCE_UNAVAILABLE", board);
        return ("ACCEPTED", board with { Active = active with { Draft = draft, Version = revision } });
    }

    public static (string Code, AdventureBoard Board) Close(AdventureBoard board, Guid id, ulong expectedVersion,
        bool leave, bool confirmed, IReadOnlyDictionary<string, TaskSnapshot> tasks)
    {
        // A lost successful close reply is safe to retry, but must not close a later adventure.
        if (board.Active is null) return ("ACCEPTED", board);
        if (board.Active.Id != id || board.Active.Version != expectedVersion) return ("ADVENTURE_CHANGED", board);
        if (leave && !confirmed) return ("CONFIRMATION_REQUIRED", board);
        if (!leave && !Progress(board.Active.Draft, tasks).IsComplete) return ("ADVENTURE_NOT_COMPLETE", board);
        return ("ACCEPTED", board with { Active = null });
    }

    public static AdventureBoard? AssignArtwork(AdventureBoard board, Guid batch, Guid choice, string key, DateTimeOffset now)
    {
        if (board.Active is { } active) {
            if (active.BatchId != batch || active.Id != choice) return null;
            // Artwork is independent of the editable draft version. Once assigned it never rotates.
            return active.Artwork == "dojo-garden" ? board with { Active = active with { Artwork = key } } : board;
        }
        if (board.Batch is not { Status: "READY" } proposals || proposals.Id != batch || proposals.ExpiresAt <= now ||
            proposals.Proposals?.Any(p => p.Id == choice) != true) return null;
        return board with { Batch = proposals with { Proposals = proposals.Proposals.Select(p =>
            p.Id == choice && p.Artwork == "dojo-garden" ? p with { Artwork = key } : p).ToArray() } };
    }

    public static AdventureProgress Progress(AdventureDraft draft, IReadOnlyDictionary<string, TaskSnapshot> tasks)
    {
        var roots = draft.Phases.Select(p => p.RootId).Distinct(StringComparer.Ordinal).Select(id => {
            tasks.TryGetValue(id, out var task);
            var available = Available(task);
            return new AdventureRoot(id, available, available ? task : null, available && task!.IsChecklist
                ? (task.ChildOrder ?? []).Where(tasks.ContainsKey).Select(child => tasks[child])
                    .Where(child => child.ParentId == id && child.Deletion is null).ToArray() : []);
        }).ToArray();
        var completed = roots.Count(r => r.Available && r.Task!.Lifecycle == "COMPLETED");
        return new(completed, roots.Length, roots.Length > 0 && completed == roots.Length, roots);
    }
}
