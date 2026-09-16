using System.Collections.Immutable;

namespace BunDo.Domain;

/// <summary>The canonical credit count at an atomic, explicitly requested enablement.</summary>
public sealed record JourneyStart(int BaselineCompletions, DateTimeOffset EnabledAt, ulong EnabledRevision);
public sealed record JourneyRoute(string Id, ImmutableArray<string> Locations);
public sealed record JourneySnapshot(DateTimeOffset EnabledAt, int Credits, string RouteId, string LocationId,
    int LocationIndex, int LocationCount, int LocationCompletions, int CompletionsPerLocation,
    ImmutableArray<string> CompletedRouteIds, bool Resting);

public static class BunJourney
{
    public const int CompletionsPerLocation = 5;

    // Published routes and location identities are append-only. Changing one would rewrite family history.
    public static readonly ImmutableArray<JourneyRoute> Routes =
    [
        new("dojo-garden", ["dojo-gate", "bamboo-path", "moss-bridge", "cedar-ridge", "lantern-garden"]),
    ];

    public static JourneySnapshot Calculate(JourneyStart start, int canonicalCompletions) =>
        Calculate(start, canonicalCompletions, Routes);

    public static JourneySnapshot Calculate(JourneyStart start, int canonicalCompletions,
        ImmutableArray<JourneyRoute> routes)
    {
        if (start.BaselineCompletions < 0 || canonicalCompletions < start.BaselineCompletions)
            throw new ArgumentOutOfRangeException(nameof(canonicalCompletions), "Canonical credits cannot decrease.");
        if (routes.IsDefaultOrEmpty || routes.Any(r => string.IsNullOrWhiteSpace(r.Id) ||
                r.Locations.IsDefaultOrEmpty || r.Locations.Any(string.IsNullOrWhiteSpace) ||
                r.Locations.Distinct().Count() != r.Locations.Length) ||
            routes.Select(r => r.Id).Distinct().Count() != routes.Length)
            throw new ArgumentException("Authored routes require distinct stable identities and locations.", nameof(routes));
        var credits = canonicalCompletions - start.BaselineCompletions;
        var remaining = credits;
        var completed = ImmutableArray.CreateBuilder<string>();
        foreach (var route in routes)
        {
            var length = checked(route.Locations.Length * CompletionsPerLocation);
            if (remaining < length)
            {
                var index = remaining / CompletionsPerLocation;
                return new(start.EnabledAt, credits, route.Id, route.Locations[index], index, route.Locations.Length,
                    remaining % CompletionsPerLocation, CompletionsPerLocation, completed.ToImmutable(), false);
            }
            completed.Add(route.Id);
            remaining -= length;
        }
        var destination = routes[^1];
        return new(start.EnabledAt, credits, destination.Id, destination.Locations[^1], destination.Locations.Length - 1,
            destination.Locations.Length, CompletionsPerLocation, CompletionsPerLocation, completed.ToImmutable(), true);
    }
}
