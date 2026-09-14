using System.Text.Json;

namespace BunDo.Domain.Tests;

public sealed class CleanupCommandTests
{
    [Fact]
    public void Wire_cleanup_preserves_capture_and_has_a_stable_receipt()
    {
        var workspace = Guid.NewGuid(); var epoch = Guid.NewGuid(); var member = Guid.NewGuid(); var device = Guid.NewGuid();
        var store = new InMemoryWorkspaceStore(workspace, epoch, HouseholdMembership.Create(member), [new(device, member)]);
        var server = new WorkspaceServer(store);
        var context = new { capturedInstant = "2026-09-14T08:00:00Z", capturedLocal = "2026-09-14T11:00:00.000",
            captureZoneId = "Europe/Helsinki", captureOffsetSeconds = 10800, zoneSource = "DEVICE", locale = "fi", clockConfidence = "UNKNOWN" };
        byte[] Wire(string command, string sequence, object payload, object observed) => JsonSerializer.SerializeToUtf8Bytes(new {
            protocolVersion = 1, commandVersion = 1, stateEpoch = epoch, workspaceId = workspace, deviceId = device,
            sequence, command, payload, dependencies = Array.Empty<string>(), observedVersions = observed, occurredAtContext = context,
        });
        var id = TaskIdentity.ForCreate(device, 1);
        var created = server.Handle(member, OperationEnvelope.Parse(Wire("CreateTask", "1",
            new { taskId = id, title = "öö osta maitoa", description = (string?)null }, new { }))).Receipt!.Task!;
        var version = new { fieldVersion = "1" };
        var request = OperationEnvelope.Parse(Wire("RequestCleanup", "2", new { taskId = id, requestId = (string?)null },
            new { title = version, description = version, lifecycle = version, hierarchy = version, deletion = version }));
        var accepted = server.Handle(member, request);
        Assert.Equal("ACCEPTED", accepted.Code);
        Assert.Equal(accepted, server.Handle(member, request));
        Assert.Equal(created.Capture, accepted.Receipt!.Task!.Capture);
        Assert.Equal("öö osta maitoa", accepted.Receipt.Task.Capture!.Title);
        Assert.Equal(request.OperationId, accepted.Receipt.Task.Cleanup!.Id);
        var stale = new FrozenOperation(workspace, epoch, device, 3, new RequestCleanup(id, 1, 1, 1, 1, 1, null));
        Assert.Equal("CLEANUP_CONFLICT", server.Handle(member, stale).Code);
    }

    [Fact]
    public void Large_proposal_cannot_make_a_task_uneditable()
    {
        var workspace = Guid.NewGuid(); var epoch = Guid.NewGuid(); var member = Guid.NewGuid(); var device = Guid.NewGuid();
        var store = new InMemoryWorkspaceStore(workspace, epoch, HouseholdMembership.Create(member), [new(device, member)]);
        var server = new WorkspaceServer(store);
        var id = TaskIdentity.ForCreate(device, 1);
        var created = server.Handle(member, new(workspace, epoch, device, 1, new CreateTask(id, "Title"))).Receipt!.Task!;
        var lease = Guid.NewGuid(); var now = DateTimeOffset.UtcNow;
        var running = created with { Cleanup = new("request", member, epoch, "RUNNING", "Title", null, 1, 1, 1, 1, 1,
            lease, now.AddMinutes(1)) };
        var result = TaskCleanup.Finish(store.Read(), running, lease, new("Title", new string('ä', 4000), "fi", false), null, 3, now);
        Assert.Equal("FAILED", result.Cleanup!.Status);
        Assert.Equal("INVALID_OUTPUT", result.Cleanup.Error);
        Assert.Null(result.Description);
    }
}
