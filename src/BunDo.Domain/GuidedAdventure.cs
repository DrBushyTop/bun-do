namespace BunDo.Domain;

public sealed record GuidedPhase(string? RootId, string? TaskTitle, string Name, int Stars, int Minutes);
public sealed record GuidedDraft(string Title, string Flavor, GuidedPhase[] Phases);
public sealed record AdventureCreation(Guid Id, Guid MemberId, Guid RegistrationId, ulong Revision,
    GuidedDraft Draft, DateTimeOffset StartedAt);

/// <summary>Reserves one shared choice while ordinary task commands create the explicitly approved work.</summary>
public static class GuidedAdventure
{
    public static bool Valid(GuidedDraft? draft) => draft is not null && draft.Phases is { Length: > 0 and <= 8 } phases &&
        HouseholdAdventure.Valid(new(draft.Title, draft.Flavor, phases.Select((p, i) =>
            new AdventurePhase(p?.RootId ?? $"new-{i}", p?.Name!, p?.Stars ?? 0, p?.Minutes ?? 0)).ToArray())) &&
        phases.All(p => p is not null && (p.RootId is not null ? p.TaskTitle is null :
            p.TaskTitle is { Length: > 0 } title && title == title.Trim() && title.EnumerateRunes().Count() <= 160 && !title.Any(char.IsControl)));

    public static (string Code, AdventureBoard Board) Begin(AdventureBoard board, Guid id, Guid member, Guid registration,
        ulong expectedRevision, ulong revision, GuidedDraft draft, IReadOnlyDictionary<string, TaskSnapshot> tasks, DateTimeOffset now)
    {
        if (board.Active?.Id == id || board.Batch?.Id == id && board.Batch.Status == "CONSUMED") return ("ACCEPTED", board);
        if (board.Active is not null) return ("ADVENTURE_ACTIVE", board);
        if (board.Creation is { } pending)
            return (pending.Id == id && pending.MemberId == member && pending.RegistrationId == registration ? "ACCEPTED" : "CREATION_PENDING", board);
        if (expectedRevision != revision) return ("ADVENTURE_CHANGED", board);
        if (id == Guid.Empty || !Valid(draft)) return ("INVALID_ADVENTURE", board);
        if (draft.Phases.Any(p => p.RootId is { } root && (!tasks.TryGetValue(root, out var task) || !HouseholdAdventure.Candidate(task))))
            return ("SOURCE_UNAVAILABLE", board);
        return ("ACCEPTED", board with { Creation = new(id, member, registration, revision + 1, draft, now) });
    }

    public static (string Code, AdventureBoard Board) Finish(AdventureBoard board, Guid id, Guid member, Guid registration,
        string[] roots, IReadOnlyDictionary<string, TaskSnapshot> tasks, ulong revision, DateTimeOffset now)
    {
        if (board.Active?.Id == id || board.Batch?.Id == id && board.Batch.Status == "CONSUMED") return ("ACCEPTED", board);
        if (board.Active is not null) return ("ADVENTURE_ACTIVE", board);
        if (board.Creation is not { } pending || pending.Id != id) return ("ADVENTURE_CHANGED", board);
        if (pending.MemberId != member || pending.RegistrationId != registration) return ("CREATION_OTHER_DEVICE", board);
        if (roots.Length != pending.Draft.Phases.Length || roots.Distinct(StringComparer.Ordinal).Count() != roots.Length)
            return ("INVALID_ADVENTURE", board);
        for (var i = 0; i < roots.Length; i++)
        {
            var phase = pending.Draft.Phases[i];
            if (!tasks.TryGetValue(roots[i], out var task) || !HouseholdAdventure.Available(task)) return ("TASKS_PENDING", board);
            if (phase.RootId is { } existing ? roots[i] != existing :
                task.Creation?.ActorId != member || task.Creation.AcceptedAt < pending.StartedAt || (task.Capture?.Title ?? task.Title) != phase.TaskTitle)
                return ("SOURCE_UNAVAILABLE", board);
        }
        var draft = new AdventureDraft(pending.Draft.Title, pending.Draft.Flavor, pending.Draft.Phases.Select((p, i) =>
            new AdventurePhase(roots[i], p.Name, p.Stars, p.Minutes)).ToArray());
        return ("ACCEPTED", board with { Creation = null, Batch = new(id, "CONSUMED", now), Active = new(id, id, revision, draft, now) });
    }

    public static (string Code, AdventureBoard Board) Cancel(AdventureBoard board, Guid id, bool confirmed, DateTimeOffset now)
    {
        if (!confirmed) return ("CONFIRMATION_REQUIRED", board);
        if (board.Creation?.Id != id) return ("ADVENTURE_CHANGED", board);
        // Keep the consumed identity until another choice supersedes it, fencing lost begin/finish replies.
        return ("ACCEPTED", board with { Creation = null, Batch = new(id, "CONSUMED", now) });
    }
}
