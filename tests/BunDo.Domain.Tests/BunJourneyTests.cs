using System.Collections.Immutable;
using System.Text.Json;
using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class BunJourneyTests
{
    private static readonly JourneyStart Start = new(100, DateTimeOffset.Parse("2026-09-16T12:00:00Z"), 500);

    [Theory]
    [InlineData(0, 0, 0, false)]
    [InlineData(4, 0, 4, false)]
    [InlineData(5, 1, 0, false)]
    [InlineData(24, 4, 4, false)]
    [InlineData(25, 4, 5, true)]
    [InlineData(1000, 4, 5, true)]
    public void New_credits_advance_five_locations_then_rest_without_losing_total(int credits, int index, int atLocation, bool resting)
    {
        var result = BunJourney.Calculate(Start, 100 + credits);
        Assert.Equal(credits, result.Credits);
        Assert.Equal(index, result.LocationIndex);
        Assert.Equal(atLocation, result.LocationCompletions);
        Assert.Equal(5, result.LocationCount);
        Assert.Equal(5, result.CompletionsPerLocation);
        Assert.Equal(resting, result.Resting);
        Assert.Equal(resting ? ["dojo-garden"] : Array.Empty<string>(), result.CompletedRouteIds);
        Assert.Equal(Start.EnabledAt, result.EnabledAt);
    }

    [Fact]
    public void Next_authored_route_starts_at_zero_and_completed_history_remains()
    {
        ImmutableArray<JourneyRoute> routes = [.. BunJourney.Routes,
            new("riverside", ["r1", "r2", "r3", "r4", "r5"])];
        var second = BunJourney.Calculate(Start, 125, routes);
        Assert.Equal("riverside", second.RouteId);
        Assert.Equal("r1", second.LocationId);
        Assert.Equal(0, second.LocationCompletions);
        Assert.False(second.Resting);
        Assert.Equal(new[] { "dojo-garden" }, second.CompletedRouteIds);
        var end = BunJourney.Calculate(Start, 150, routes);
        Assert.True(end.Resting);
        Assert.Equal(new[] { "dojo-garden", "riverside" }, end.CompletedRouteIds);
    }

    [Fact]
    public void Serialized_enablement_and_quiet_calendar_boundaries_do_not_reset_progress()
    {
        var restored = JsonSerializer.Deserialize<JourneyStart>(JsonSerializer.Serialize(Start))!;
        var before = BunJourney.Calculate(Start, 112);
        var after = BunJourney.Calculate(restored, 112);
        Assert.Equal(JsonSerializer.Serialize(before), JsonSerializer.Serialize(after));
        Assert.Equal(12, after.Credits);
    }

    [Fact]
    public void Invalid_credit_or_route_inputs_cannot_silently_reset_history()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => BunJourney.Calculate(Start, 99));
        Assert.Throws<ArgumentException>(() => BunJourney.Calculate(Start, 100, []));
        Assert.Throws<ArgumentException>(() => BunJourney.Calculate(Start, 100, [new("route", ["a", "a"])]));
        Assert.Throws<ArgumentException>(() => BunJourney.Calculate(Start, 100, [new("route", ["a"]), new("route", ["b"])]));
    }
}
