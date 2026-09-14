using System.Text.Json;

namespace BunDo.Domain.Tests;

public sealed class TaskDetailsTests
{
    [Theory]
    [InlineData("2026-03-29", "03:30", "2026-03-29T01:30:00Z", "GAP_FORWARD")]
    [InlineData("2026-10-25", "03:30", "2026-10-25T00:30:00Z", "OVERLAP_EARLIER")]
    [InlineData("2026-09-14", "09:00", "2026-09-14T06:00:00Z", null)]
    public void Helsinki_resolves_nominal_time_without_losing_it(string date, string time, string instant, string? adjustment)
    {
        Assert.True(TaskDates.TryNormalize(new("DATE_TIME", date, time, "Europe/Helsinki"), out var due));
        Assert.Equal(date, due!.LocalDate); Assert.Equal(time, due.LocalTime);
        Assert.Equal(DateTimeOffset.Parse(instant), due.Instant); Assert.Equal(adjustment, due.Adjustment);
        Assert.True(TaskDates.TryNormalize(due, out var replay)); Assert.Equal(due, replay);
    }

    [Theory]
    [InlineData("DATE_ONLY", "2026-02-30", null, "Europe/Helsinki")]
    [InlineData("DATE_ONLY", "2026-09-14", "09:00", "Europe/Helsinki")]
    [InlineData("DATE_TIME", "2026-09-14", "24:00", "Europe/Helsinki")]
    [InlineData("DATE_ONLY", "2026-09-14", null, "invalid")]
    [InlineData("DATE_ONLY", "2026-09-14", null, null)]
    public void Invalid_deadlines_are_rejected(string kind, string date, string? time, string? zone) =>
        Assert.False(TaskDates.TryNormalize(new(kind, date, time, zone!), out _));

    [Fact]
    public void Soon_due_includes_tomorrow_in_household_zone_not_UTC()
    {
        var now = DateTimeOffset.Parse("2026-09-14T22:30:00Z"); // September 15 in Helsinki.
        Assert.True(TaskDates.TryNormalize(new("DATE_ONLY", "2026-09-16", null, "Europe/Helsinki"), out var due));
        Assert.Null(due!.Instant);
        Assert.True(TaskDates.Expedited(false, due, now, "Europe/Helsinki"));
        Assert.False(TaskDates.Expedited(false, due, now, "America/New_York"));
        Assert.Equal("Europe/Helsinki", due.ZoneId);
        Assert.True(TaskDates.Expedited(true, null, now, "Europe/Helsinki"));
    }

    [Fact]
    public void Human_deadline_conflicts_preserve_creation_and_do_not_reshuffle()
    {
        var f = new Fixture();
        var a = f.Create("Ordinary"); var b = f.Create("Other");
        var fast = f.Create("Urgent", urgent: true, placement: new(null, a.Id));
        Assert.Equal(new[] { fast.Id, a.Id, b.Id }, RootOrdering.Current(f.Store.Read()));
        var originalCreation = a.Creation;
        var changed = f.Send(new EditTask(a.Id, Description: new("Details", a.DescriptionVersion.Human),
            Due: new(new("DATE_ONLY", "2026-09-15", null, "Europe/Helsinki"), a.DueVersion!.Human),
            Urgent: new(true, a.UrgencyVersion))).Receipt!.Task!;
        Assert.Equal(originalCreation, changed.Creation);
        Assert.Equal(new TaskChange(f.Member, f.Now, "HUMAN"), changed.LastChange);
        Assert.Equal("FIELD_CONFLICT", f.Send(new EditTask(a.Id, Due: new(null, a.DueVersion.Human))).Code);
        Assert.Equal(changed.Due, f.Store.Read().Tasks[a.Id].Due);
        Assert.Equal(new[] { fast.Id, a.Id, b.Id }, RootOrdering.Current(f.Store.Read()));
    }

    [Fact]
    public void Imported_capture_has_no_invented_creator()
    {
        var f = new Fixture(); var task = f.Create("Imported", anonymous: true);
        Assert.Null(task.Creation!.ActorId);
        Assert.Equal(f.Now, task.Creation.AcceptedAt);
        var changed = f.Send(new EditTask(task.Id, Title: new("Edited", task.TitleVersion.Human))).Receipt!.Task!;
        Assert.Equal(task.Creation, changed.Creation); Assert.Equal(f.Member, changed.LastChange!.ActorId);
    }

    [Theory]
    [InlineData(false, false, "APPLIED")]
    [InlineData(true, false, "READY")]
    [InlineData(false, true, "READY")]
    public void AI_due_is_fenced_and_never_reorders(bool competingHuman, bool ambiguous, string status)
    {
        var f = new Fixture(); var first = f.Create("First"); var task = f.Create("Tomorrow");
        var lease = Guid.NewGuid();
        var running = task with { Capture = new(task.Title, null, JsonSerializer.SerializeToElement(new { capturedInstant = "2026-09-14T08:00:00Z" }), f.Now), Cleanup = new("request", f.Member, f.Store.Read().StateEpoch, "RUNNING", task.Title, null,
            task.TitleVersion.Server, task.DescriptionVersion.Server, task.LifecycleVersion, task.HierarchyVersion,
            task.DeletionVersion, lease, f.Now.AddMinutes(1), ExpectedDueVersion: task.DueVersion!.Server) };
        if (competingHuman) running = running with { DueVersion = new(10, 10), Due = new("DATE_ONLY", "2026-09-20", null, "Europe/Helsinki") };
        var result = TaskCleanup.Finish(f.Store.Read(), running, lease,
            new("Tomorrow", null, "en", ambiguous, new("DATE_ONLY", "2026-09-15", null, "Europe/Helsinki")), null, 11, f.Now);
        Assert.Equal(status, result.Cleanup!.Status); Assert.Equal(task.Creation, result.Creation);
        if (status == "APPLIED") { Assert.Equal(task.DueVersion.Human, result.DueVersion!.Human); Assert.Equal("AI", result.LastChange!.Source); }
        else Assert.Equal(running.Due, result.Due);
        Assert.Equal(new[] { first.Id, task.Id }, RootOrdering.Current(f.Store.Read()));
    }

    [Fact]
    public void Unknown_import_time_stays_unknown_and_AI_dates_require_review()
    {
        var f = new Fixture(); var task = f.Create("Finish tomorrow", anonymous: true);
        Assert.Null(task.Creation!.CapturedAt); Assert.Equal(JsonValueKind.Null, task.Capture!.Context.ValueKind);
        var lease = Guid.NewGuid();
        var running = task with { Cleanup = new("request", f.Member, f.Store.Read().StateEpoch, "RUNNING", task.Title, null,
            task.TitleVersion.Server, task.DescriptionVersion.Server, task.LifecycleVersion, task.HierarchyVersion,
            task.DeletionVersion, lease, f.Now.AddMinutes(1), ExpectedDueVersion: task.DueVersion!.Server) };
        var result = TaskCleanup.Finish(f.Store.Read(), running, lease,
            new("Finish tomorrow", null, "en", false, new("DATE_ONLY", "1970-01-02", null, "Europe/Helsinki")), null, 3, f.Now);
        Assert.Equal("READY", result.Cleanup!.Status); Assert.True(result.Cleanup.Proposal!.NeedsReview); Assert.Null(result.Due);
    }

    [Fact]
    public void Imported_wire_preserves_original_context_instead_of_import_context()
    {
        var workspace = Guid.NewGuid(); var epoch = Guid.NewGuid(); var device = Guid.NewGuid(); var member = Guid.NewGuid();
        var store = new InMemoryWorkspaceStore(workspace, epoch, HouseholdMembership.Create(member), [new(device, member)]);
        var original = new { capturedInstant = "2026-03-27T22:30:00Z", capturedLocal = "2026-03-28T00:30:00.000",
            captureZoneId = "Europe/Helsinki", captureOffsetSeconds = 7200, zoneSource = "DEVICE", locale = "fi", clockConfidence = "UNKNOWN" };
        var imported = new { capturedInstant = "2026-09-14T08:00:00Z", capturedLocal = "2026-09-14T04:00:00.000",
            captureZoneId = "America/New_York", captureOffsetSeconds = -14400, zoneSource = "DEVICE", locale = "en", clockConfidence = "UNKNOWN" };
        var wire = JsonSerializer.SerializeToUtf8Bytes(new { protocolVersion = 1, commandVersion = 1, workspaceId = workspace,
            stateEpoch = epoch, deviceId = device, sequence = "1", command = "CreateTask", dependencies = Array.Empty<string>(),
            observedVersions = new { }, occurredAtContext = imported,
            payload = new { taskId = TaskIdentity.ForCreate(device, 1), title = "Finish tomorrow", description = (string?)null,
                anonymousCapture = true, originalCapture = new { capturedAt = (string?)null, context = original } } });
        var task = new WorkspaceServer(store).Handle(member, OperationEnvelope.Parse(wire)).Receipt!.Task!;
        Assert.Null(task.Creation!.ActorId);
        Assert.Equal(DateTimeOffset.Parse(original.capturedInstant), task.Creation.CapturedAt);
        Assert.Equal("Europe/Helsinki", task.Capture!.Context.GetProperty("captureZoneId").GetString());
        Assert.Equal(original.capturedLocal, task.Capture.Context.GetProperty("capturedLocal").GetString());
    }

    private sealed class Fixture
    {
        public Guid Member { get; } = Guid.NewGuid();
        private Guid Device { get; } = Guid.NewGuid();
        public DateTimeOffset Now { get; } = DateTimeOffset.Parse("2026-09-14T08:00:00Z");
        public InMemoryWorkspaceStore Store { get; }
        private WorkspaceServer Server { get; }
        private ulong sequence;
        public Fixture()
        {
            Store = new(Guid.NewGuid(), Guid.NewGuid(), HouseholdMembership.Create(Member), [new(Device, Member)]);
            Server = new(Store, new Clock(Now));
        }
        public SubmissionResult Send(TaskCommand command) => Server.Handle(Member,
            new(Store.Read().WorkspaceId, Store.Read().StateEpoch, Device, ++sequence, command));
        public TaskSnapshot Create(string title, bool urgent = false, InitialPlacement? placement = null, bool anonymous = false) =>
            Send(new CreateTask(TaskIdentity.ForCreate(Device, sequence + 1), title, Urgent: urgent, Placement: placement, AnonymousCapture: anonymous)).Receipt!.Task!;
    }
    private sealed class Clock(DateTimeOffset now) : TimeProvider { public override DateTimeOffset GetUtcNow() => now; }
}
