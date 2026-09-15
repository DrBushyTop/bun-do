using System.Collections.Immutable;

namespace BunDo.Domain;

public sealed record RepeatRule(string Frequency, int? Weekday, string ZoneId);
public sealed record RepeatInfo(string Id, ulong Version, bool Active, RepeatRule Rule, string Title, string? Description);
public sealed record RepeatSchedule(string Id, ulong Version, RepeatRule Rule, string Title, string? Description,
    bool Active, string CurrentTaskId, string? OpenTaskId, ulong Ordinal, DateOnly? PendingDate, Guid CreatorId)
{
    [System.Text.Json.Serialization.JsonIgnore]
    public RepeatInfo Info => new(Id, Version, Active, Rule, Title, Description);
}
public abstract record RepeatCommand(string TaskId, ulong ExpectedScheduleVersion, TaskStateVersions Expected) : TaskCommand;
public sealed record ConfigureRepeat(string TaskId, ulong ExpectedScheduleVersion, TaskStateVersions Expected,
    RepeatRule Rule, string Title, string? Description) : RepeatCommand(TaskId, ExpectedScheduleVersion, Expected);
public sealed record StopRepeat(string TaskId, ulong ExpectedScheduleVersion, TaskStateVersions Expected)
    : RepeatCommand(TaskId, ExpectedScheduleVersion, Expected);

/// <summary>Schedule advancement is part of the task transaction. Generation consumes its durable slot.</summary>
public static class TaskRepeats
{
    public static bool Valid(RepeatRule rule)
    {
        if (rule.Frequency == "DAILY" ? rule.Weekday is not null : rule.Frequency != "WEEKLY" || rule.Weekday is not (>= 1 and <= 7)) return false;
        if (rule.ZoneId is null || rule.ZoneId.Length is 0 or > 100) return false;
        try { return TimeZoneInfo.FindSystemTimeZoneById(rule.ZoneId).HasIanaId; }
        catch (Exception error) when (error is TimeZoneNotFoundException or InvalidTimeZoneException or ArgumentException) { return false; }
    }

    public static DateOnly NextDate(RepeatRule rule, DateTimeOffset acceptedAt)
    {
        var next = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(acceptedAt, TimeZoneInfo.FindSystemTimeZoneById(rule.ZoneId)).DateTime).AddDays(1);
        if (rule.Frequency == "WEEKLY")
            while (((int)next.DayOfWeek + 6) % 7 + 1 != rule.Weekday) next = next.AddDays(1);
        return next;
    }

    public static (string Code, TaskSnapshot? Task, RepeatSchedule? Schedule) Configure(WorkspaceState state,
        FrozenOperation operation, Guid member, ulong revision, DateTimeOffset acceptedAt)
    {
        var command = (RepeatCommand)operation.Command;
        if (!state.Tasks.TryGetValue(command.TaskId, out var task)) return ("ENTITY_MISSING", null, null);
        (string, TaskSnapshot?, RepeatSchedule?) Fail(string code) => (code, task, null);
        if (command.Expected.Deletion != task.DeletionVersion) return Fail("DELETION_CONFLICT");
        if (task.Deletion is not null) return Fail("TASK_DELETED");
        if (command.Expected.Lifecycle != task.LifecycleVersion) return Fail("LIFECYCLE_CONFLICT");
        if (command.Expected.Hierarchy != task.HierarchyVersion || task.ParentId is not null) return Fail("HIERARCHY_CONFLICT");
        var schedule = task.Repeat is { } link ? state.Repeats?.GetValueOrDefault(link.Id) : null;
        if (task.Repeat is not null && schedule is null) return Fail("REPEAT_UNAVAILABLE");
        if ((schedule?.Version ?? 0) != command.ExpectedScheduleVersion) return Fail("REPEAT_CONFLICT");
        if (schedule is not null && schedule.CurrentTaskId != task.Id) return Fail("REPEAT_MOVED");
        if (command is ConfigureRepeat configure)
        {
            if (task.Lifecycle != "OPEN" && schedule?.PendingDate is null) return Fail("TASK_NOT_OPEN");
            if (schedule?.OpenTaskId is { } occupied && occupied != task.Id) return Fail("REPEAT_OPEN_CONFLICT");
            if (!Valid(configure.Rule) || configure.Rule.ZoneId != (schedule?.Rule.ZoneId ?? state.TimeZoneId)) return Fail("INVALID_REPEAT");
            var blueprint = task with { Title = configure.Title, Description = configure.Description };
            if (WorkspaceServer.ValidateText(blueprint) != "ACCEPTED") return Fail("INVALID_TEXT");
            schedule = new(schedule?.Id ?? TaskIdentity.ForCreate(operation.DeviceId, operation.Sequence), revision,
                configure.Rule, configure.Title, configure.Description, true, task.Id,
                task.Lifecycle == "OPEN" ? task.Id : null,
                schedule?.Ordinal ?? 1, task.Lifecycle == "OPEN" ? null : NextDate(configure.Rule, acceptedAt),
                schedule?.CreatorId ?? member);
        }
        else
        {
            if (schedule is null) return Fail("REPEAT_UNAVAILABLE");
            schedule = schedule with { Version = revision, Active = false, PendingDate = null };
        }
        return ("ACCEPTED", task with { Repeat = schedule.Info }, schedule);
    }

    public static (string Code, ImmutableDictionary<string, TaskSnapshot> Effects, ImmutableDictionary<string, RepeatSchedule> Schedules)
        Reconcile(WorkspaceState state, ImmutableDictionary<string, TaskSnapshot> effects,
            ImmutableDictionary<string, RepeatSchedule> schedules, ulong revision, DateTimeOffset acceptedAt)
    {
        foreach (var task in effects.Values.Where(x => x.ParentId is null && x.Repeat is not null).ToArray())
        {
            if (!schedules.TryGetValue(task.Repeat!.Id, out var repeat)) return ("REPEAT_UNAVAILABLE", effects, schedules);
            var open = task.Lifecycle == "OPEN" && task.Deletion is null;
            var before = state.Tasks.GetValueOrDefault(task.Id);
            var wasOpen = before is { Lifecycle: "OPEN", Deletion: null };
            if (open && !wasOpen)
            {
                if (repeat.OpenTaskId is { } occupied && occupied != task.Id || repeat.Active && repeat.PendingDate is not null)
                    return ("REPEAT_OPEN_CONFLICT", effects, schedules);
                repeat = repeat with { OpenTaskId = task.Id, Version = revision };
            }
            if (!open && wasOpen && repeat.OpenTaskId == task.Id)
            {
                repeat = repeat with { OpenTaskId = null, Version = revision };
                if (task.Id == repeat.CurrentTaskId && repeat.Active)
                {
                    if (task.Deletion is not null) repeat = repeat with { Active = false, PendingDate = null };
                    else
                    {
                        var ordinal = checked(repeat.Ordinal + 1);
                        repeat = repeat with { Ordinal = ordinal, PendingDate = NextDate(repeat.Rule, acceptedAt) };
                    }
                }
            }
            // Deleting a completed current occurrence also stops a repeat before generation resumes.
            if (task.Deletion is not null && before?.Deletion is null && task.Id == repeat.CurrentTaskId)
                repeat = repeat with { Active = false, PendingDate = null, Version = revision };
            schedules = schedules.SetItem(repeat.Id, repeat);
            effects = effects.SetItem(task.Id, task with { Repeat = repeat.Info });
        }
        return ("ACCEPTED", effects, schedules);
    }

    public static WorkspaceState? Generate(WorkspaceState state, string repeatId, DateTimeOffset now)
    {
        var repeat = state.Repeats?.GetValueOrDefault(repeatId);
        if (repeat is not { Active: true, PendingDate: { } date, OpenTaskId: null } || state.TaskCount >= 1024 ||
            state.Revision == ulong.MaxValue) return null;
        var id = OccurrenceId(repeat);
        if (state.Tasks.ContainsKey(id)) return null;
        var revision = state.Revision + 1;
        // Delayed generation still creates just one future chore, not an overdue catch-up slot.
        var today = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(now, TimeZoneInfo.FindSystemTimeZoneById(repeat.Rule.ZoneId)).DateTime);
        if (date < today) date = NextDate(repeat.Rule, now);
        repeat = repeat with { Version = revision, PendingDate = null, OpenTaskId = id, CurrentTaskId = id };
        var nominal = new TaskDue("DATE_ONLY", date.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture), null, repeat.Rule.ZoneId);
        if (!TaskDates.TryNormalize(nominal, out var due)) return null;
        var task = new TaskSnapshot(repeat.CurrentTaskId, repeat.Title, repeat.Description, new(revision, revision), new(revision, revision),
            DeletionVersion: revision, LifecycleVersion: revision, ClaimVersion: revision, HierarchyVersion: revision,
            OrderIntentVersion: revision, Due: due, DueVersion: new(revision, revision), UrgencyVersion: revision,
            Creation: new(null, now, now), InitialPlacement: "APPENDED", Repeat: repeat.Info);
        var order = RootOrdering.Current(state).Add(task.Id);
        return state with { Revision = revision, TaskCount = state.TaskCount + 1, RootOrder = order,
            Tasks = state.Tasks.Add(task.Id, task), Repeats = state.Repeats!.SetItem(repeat.Id, repeat),
            RecentActivity = HouseholdProgress.Record(state.RecentActivity, revision, task.Id, null, "GenerateRepeat", now),
            Changes = state.Changes.Add(new(revision, [task], now, RootOrder: order, Repeats: [repeat])) };
    }

    public static string OccurrenceId(RepeatSchedule repeat) => TaskIdentity.ForCreate(Guid.Parse(repeat.Id), repeat.Ordinal);
}
