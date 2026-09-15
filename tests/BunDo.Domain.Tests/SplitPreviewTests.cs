using System.Text.Json;

namespace BunDo.Domain.Tests;

public sealed class SplitPreviewTests
{
    [Fact]
    public void AI_only_parent_change_rejects_exact_preview_acceptance_atomically()
    {
        var workspace = Guid.NewGuid(); var epoch = Guid.NewGuid(); var member = Guid.NewGuid(); var device = Guid.NewGuid();
        var store = new InMemoryWorkspaceStore(workspace, epoch, HouseholdMembership.Create(member), [new(device, member)]);
        var server = new WorkspaceServer(store); var id = TaskIdentity.ForCreate(device, 1);
        var task = server.Handle(member, new(workspace, epoch, device, 1, new CreateTask(id, "Kitchen"))).Receipt!.Task!;
        var before = store.Read();
        Assert.True(store.TryCommit(before.Revision, before with { Revision = 2,
            Tasks = before.Tasks.SetItem(id, task with { Title = "Clean kitchen", TitleVersion = new(2, 1) }) }));
        var version = new { fieldVersion = "1" };
        var context = new { capturedInstant = "2026-09-15T08:00:00Z", capturedLocal = "2026-09-15T11:00:00.000",
            captureZoneId = "Europe/Helsinki", captureOffsetSeconds = 10800, zoneSource = "DEVICE", locale = "en", clockConfidence = "UNKNOWN" };
        var wire = JsonSerializer.SerializeToUtf8Bytes(new { protocolVersion = 1, commandVersion = 1, stateEpoch = epoch,
            workspaceId = workspace, deviceId = device, sequence = "2", command = "SplitTask", dependencies = Array.Empty<string>(),
            payload = new { taskId = id, items = new[] { "Wash dishes" } }, occurredAtContext = context,
            observedVersions = new { lifecycle = version, claim = version, hierarchy = version, deletion = version,
                title = new { humanVersion = "1", fieldVersion = "1" }, description = new { humanVersion = "1", fieldVersion = "1" } } });
        var command = OperationEnvelope.Parse(wire);
        Assert.Equal((ulong)1, Assert.IsType<SplitTask>(command.Command).ExpectedTitleFieldVersion);
        var rejected = server.Handle(member, command);
        Assert.Equal("FIELD_CONFLICT", rejected.Code); Assert.Equal(rejected, server.Handle(member, command));
        Assert.Single(store.Read().Tasks); Assert.False(store.Read().Tasks[id].IsChecklist);
    }
}
