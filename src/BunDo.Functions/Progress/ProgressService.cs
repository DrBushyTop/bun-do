using BunDo.Domain;
using BunDo.Functions.Households;

namespace BunDo.Functions.Progress;

public sealed record ProgressSnapshot(ulong Revision, DateTimeOffset AsOf, CompletionStatistics Statistics,
    IReadOnlyList<HouseholdActivity> Activity, JourneySnapshot? Journey = null);

/// <summary>Statistics and the journey use the same accepted canonical credits.</summary>
public sealed class ProgressService(IHouseholdDocuments documents, TimeProvider? time = null)
{
    private readonly TimeProvider clock = time ?? TimeProvider.System;
    public async Task<ProgressSnapshot> ReadAsync(Guid member, Guid workspace, Guid epoch, CancellationToken ct)
    {
        var snapshot = await new CanonicalCompletions(documents).ReadAsync(member, workspace, epoch, ct);
        var state = snapshot.State.Value;
        var now = clock.GetUtcNow();
        return new(state.Revision, now, HouseholdProgress.Calculate(snapshot.Credits, state.TimeZoneId, now),
            (state.RecentActivity ?? []).Reverse().ToArray(),
            state.Journey is { } start ? BunJourney.Calculate(start, snapshot.Credits.Count) : null);
    }
}
