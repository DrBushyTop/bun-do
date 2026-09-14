using System.Globalization;

namespace BunDo.Domain;

public sealed record TaskDue(string Kind, string LocalDate, string? LocalTime, string ZoneId,
    DateTimeOffset? Instant = null, string? Adjustment = null);
public sealed record DueEdit(TaskDue? Value, ulong ExpectedHumanVersion);
public sealed record UrgencyEdit(bool Value, ulong ExpectedVersion);
public sealed record InitialPlacement(string? AfterTaskId, string? BeforeTaskId);
public sealed record ImportedCapture(DateTimeOffset? CapturedAt, System.Text.Json.JsonElement? Context);
public sealed record TaskCreation(Guid? ActorId, DateTimeOffset? CapturedAt, DateTimeOffset AcceptedAt);
public sealed record TaskChange(Guid? ActorId, DateTimeOffset At, string Source);

public static class TaskDates
{
    public static bool TryNormalize(TaskDue? input, out TaskDue? due)
    {
        due = null;
        if (input is null) return true;
        if (!DateOnly.TryParseExact(input.LocalDate, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date) ||
            date.Year is < 1900 or > 9998 || (input.ZoneId is null || input.ZoneId.Length is 0 or > 100)) return false;
        try
        {
            var zone = TimeZoneInfo.FindSystemTimeZoneById(input.ZoneId);
            if (!zone.HasIanaId) return false;
            if (input.Kind == "DATE_ONLY" && input.LocalTime is null)
            { due = input with { Instant = null, Adjustment = null }; return true; }
            if (input.Kind != "DATE_TIME" || !TimeOnly.TryParseExact(input.LocalTime, "HH:mm", CultureInfo.InvariantCulture,
                DateTimeStyles.None, out var time)) return false;
            var local = date.ToDateTime(time, DateTimeKind.Unspecified);
            string? adjustment = null;
            if (zone.IsInvalidTime(local))
            {
                var before = local; var after = local;
                // Handles ordinary DST as well as zones with a whole skipped date.
                for (var minute = 0; minute < 48 * 60 && zone.IsInvalidTime(before); minute++) before = before.AddMinutes(-1);
                for (var minute = 0; minute < 48 * 60 && zone.IsInvalidTime(after); minute++) after = after.AddMinutes(1);
                if (zone.IsInvalidTime(before) || zone.IsInvalidTime(after)) return false;
                local += zone.GetUtcOffset(after) - zone.GetUtcOffset(before);
                adjustment = "GAP_FORWARD";
            }
            var offset = zone.IsAmbiguousTime(local) ? zone.GetAmbiguousTimeOffsets(local).Max() : zone.GetUtcOffset(local);
            if (zone.IsAmbiguousTime(local)) adjustment = "OVERLAP_EARLIER";
            due = input with { Instant = new DateTimeOffset(local, offset).ToUniversalTime(), Adjustment = adjustment };
            return true;
        }
        catch (Exception error) when (error is TimeZoneNotFoundException or InvalidTimeZoneException or ArgumentException)
        { return false; }
    }

    public static bool Expedited(bool urgent, TaskDue? due, DateTimeOffset capturedAt, string householdZone)
    {
        if (urgent) return true;
        if (due is null) return false;
        var zone = TimeZoneInfo.FindSystemTimeZoneById(householdZone);
        var today = DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(capturedAt, zone).DateTime);
        var date = due.Kind == "DATE_ONLY" ? DateOnly.ParseExact(due.LocalDate, "yyyy-MM-dd", CultureInfo.InvariantCulture)
            : DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(due.Instant!.Value, zone).DateTime);
        return date <= today.AddDays(1);
    }

    public static DateTimeOffset CapturedAt(TaskSnapshot task, DateTimeOffset fallback) =>
        task.Capture is { } capture && capture.Context.ValueKind == System.Text.Json.JsonValueKind.Object && capture.Context.TryGetProperty("capturedInstant", out var instant) &&
        instant.TryGetDateTimeOffset(out var at) ? at : fallback;
}
