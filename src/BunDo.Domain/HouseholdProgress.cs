using System.Collections.Immutable;

namespace BunDo.Domain;

public sealed record HouseholdActivity(ulong Revision, string TaskId, Guid? ActorId, string Action, DateTimeOffset AcceptedAt);
public sealed record CompletionBucket(DateOnly Start, DateOnly End, int Count);
public sealed record CompletionStatistics(string ZoneId, DateOnly Today, int WeekCount, int MonthCount, int LifetimeCount,
    int Streak, int ReachedMilestone, int? NextMilestone, ImmutableArray<CompletionBucket> WeekDays,
    ImmutableArray<CompletionBucket> MonthWeeks);

/// <summary>Counts immutable root credits, never current task state or client completion times.</summary>
public static class HouseholdProgress
{
    private static readonly int[] Milestones = [10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000];
    public static CompletionStatistics Calculate(IEnumerable<FirstCompletion> credits, string zoneId, DateTimeOffset now)
    {
        var zone = TimeZoneInfo.FindSystemTimeZoneById(zoneId);
        DateOnly Local(DateTimeOffset at) => DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(at, zone).DateTime);
        var today = Local(now);
        var days = credits.GroupBy(c => c.RootId).Select(g => g.Min(c => c.AcceptedAt)).Select(Local).ToArray();
        var week = Monday(today);
        var month = new DateOnly(today.Year, today.Month, 1);
        var nextMonth = month.AddMonths(1);
        var weeks = days.Select(Monday).ToHashSet();
        var streak = 0;
        var end = weeks.Contains(week) ? week : week.AddDays(-7);
        while (weeks.Contains(end)) { streak++; end = end.AddDays(-7); }
        CompletionBucket Bucket(DateOnly start, DateOnly endDate) => new(start, endDate, days.Count(d => d >= start && d <= endDate));
        var monthWeeks = ImmutableArray.CreateBuilder<CompletionBucket>();
        for (var start = month; start < nextMonth;)
        {
            var endDate = Monday(start).AddDays(6);
            if (endDate >= nextMonth) endDate = nextMonth.AddDays(-1);
            monthWeeks.Add(Bucket(start, endDate)); start = endDate.AddDays(1);
        }
        return new(zoneId, today, days.Count(d => d >= week && d < week.AddDays(7)),
            days.Count(d => d >= month && d < nextMonth), days.Length, streak,
            Milestones.LastOrDefault(m => m <= days.Length), Milestones.Cast<int?>().FirstOrDefault(m => m > days.Length),
            Enumerable.Range(0, 7).Select(i => Bucket(week.AddDays(i), week.AddDays(i))).ToImmutableArray(), monthWeeks.ToImmutable());
    }

    public static DateOnly Monday(DateOnly date) => date.AddDays(-((int)date.DayOfWeek + 6) % 7);

    public static ImmutableArray<HouseholdActivity> Record(ImmutableArray<HouseholdActivity>? recent,
        ulong revision, string taskId, Guid? actor, string action, DateTimeOffset acceptedAt) =>
        (recent ?? []).Add(new(revision, taskId, actor, action, acceptedAt)).TakeLast(60).ToImmutableArray();
}
