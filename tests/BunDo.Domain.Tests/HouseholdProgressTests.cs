using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class HouseholdProgressTests
{
    private static FirstCompletion Credit(string id, string at) => new(id, Guid.NewGuid(), DateTimeOffset.Parse(at));
    private static CompletionStatistics Count(IEnumerable<FirstCompletion> credits, string now) =>
        HouseholdProgress.Calculate(credits, "Europe/Helsinki", DateTimeOffset.Parse(now));

    [Fact] public void Monday_and_month_boundaries_use_acceptance_in_fixed_household_zone()
    {
        var credits = new[] { Credit("last-month", "2026-08-31T20:59:59Z"), Credit("this-month", "2026-08-31T21:00:00Z"),
            Credit("last-week", "2026-09-06T20:59:59Z"), Credit("late-offline", "2026-09-06T21:00:00Z") };
        var stats = Count(credits, "2026-09-07T08:00:00Z");
        Assert.Equal(4, stats.LifetimeCount); Assert.Equal(3, stats.MonthCount); Assert.Equal(1, stats.WeekCount);
        Assert.Equal(1, stats.WeekDays[0].Count); Assert.Equal(3, stats.MonthWeeks.Sum(b => b.Count));
        Assert.Equal(new DateOnly(2026, 9, 1), stats.MonthWeeks[0].Start);
        Assert.Equal(new DateOnly(2026, 9, 30), stats.MonthWeeks[^1].End);
    }
    [Fact] public void Empty_current_week_preserves_last_weeks_run_but_a_finished_empty_week_breaks_it()
    {
        var credits = new[] { Credit("a", "2026-09-01T12:00:00Z"), Credit("b", "2026-09-07T12:00:00Z"), Credit("c", "2026-09-08T12:00:00Z") };
        Assert.Equal(2, Count(credits, "2026-09-14T12:00:00Z").Streak);
        Assert.Equal(2, Count(credits, "2026-09-20T20:59:59Z").Streak);
        Assert.Equal(0, Count(credits, "2026-09-20T21:00:00Z").Streak);
        var extended = credits.Append(Credit("d", "2026-09-20T20:00:00Z"));
        Assert.Equal(3, Count(extended, "2026-09-20T21:00:00Z").Streak);
        Assert.Equal(1, Count(credits.Append(Credit("e", "2026-09-21T12:00:00Z")), "2026-09-21T13:00:00Z").Streak);
    }
    [Fact] public void Duplicate_retained_credit_never_repeats_a_milestone_and_zero_is_normal()
    {
        var credits = Enumerable.Range(0, 25).Select(i => Credit(i.ToString(), "2026-01-01T10:00:00Z")).ToArray();
        var stats = Count(credits.Concat(credits), "2026-01-01T12:00:00Z");
        Assert.Equal(25, stats.LifetimeCount); Assert.Equal(25, stats.ReachedMilestone); Assert.Equal(50, stats.NextMilestone);
        var empty = Count([], "2026-01-01T12:00:00Z");
        Assert.Equal(0, empty.Streak); Assert.Equal(0, empty.ReachedMilestone); Assert.Equal(10, empty.NextMilestone);
    }
    [Theory]
    [InlineData("2026-03-29T21:00:00Z", "2026-03-30")]
    [InlineData("2026-10-25T22:00:00Z", "2026-10-26")]
    [InlineData("2026-12-31T22:00:00Z", "2027-01-01")]
    public void Dst_and_year_changes_remain_date_buckets(string instant, string day)
    { var stats = Count([Credit("root", instant)], instant); Assert.Equal(DateOnly.Parse(day), stats.Today); Assert.Equal(1, stats.MonthCount); Assert.Equal(1, stats.WeekCount); }
}
