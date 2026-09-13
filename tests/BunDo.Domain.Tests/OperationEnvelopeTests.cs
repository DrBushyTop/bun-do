using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class OperationEnvelopeTests
{
    private static readonly Guid Workspace = Guid.NewGuid(), Epoch = Guid.NewGuid(), Device = Guid.NewGuid();
    private static JsonObject Envelope() => JsonSerializer.SerializeToNode(new {
        protocolVersion = 1, commandVersion = 1, workspaceId = Workspace, stateEpoch = Epoch, deviceId = Device,
        sequence = "1", command = "CreateTask",
        payload = new { taskId = TaskIdentity.ForCreate(Device, 1), title = "Coffee", description = (string?)null },
        dependencies = Array.Empty<string>(), observedVersions = new { },
        occurredAtContext = new { capturedInstant = "2026-09-13T10:00:00Z", capturedLocal = "2026-09-13T13:00:00.000",
            captureZoneId = "Europe/Helsinki", captureOffsetSeconds = 10800, zoneSource = "DEVICE", locale = "fi-FI", clockConfidence = "UNKNOWN" },
    })!.AsObject();
    private static FrozenOperation Parse(JsonNode node) => OperationEnvelope.Parse(Encoding.UTF8.GetBytes(node.ToJsonString()));

    [Fact]
    public void FingerprintIncludesExactBytesRatherThanNormalizedJson()
    {
        var text = Envelope().ToJsonString();
        var first = OperationEnvelope.Parse(Encoding.UTF8.GetBytes(text));
        var spaced = OperationEnvelope.Parse(Encoding.UTF8.GetBytes(" " + text));
        Assert.Equal(first.Command, spaced.Command);
        Assert.Equal(first.OperationId, spaced.OperationId);
        Assert.NotEqual(first.Fingerprint, spaced.Fingerprint);
        Assert.Equal(first.Fingerprint, OperationEnvelope.Parse(Encoding.UTF8.GetBytes(text)).Fingerprint);
    }

    [Theory]
    [InlineData("01")][InlineData("-1")][InlineData("+1")][InlineData("1.0")][InlineData("18446744073709551616")]
    public void SequenceMustBeCanonicalUnsignedDecimalString(string value)
    {
        var node = Envelope(); node["sequence"] = value;
        Assert.Throws<EnvelopeException>(() => Parse(node));
    }

    [Fact]
    public void MaximumUnsignedSequenceIsNotRoundedThroughFloatingPoint()
    {
        var node = Envelope(); node["sequence"] = "18446744073709551615";
        Assert.Equal(ulong.MaxValue, Parse(node).Sequence);
        node["sequence"] = 1;
        Assert.Throws<EnvelopeException>(() => Parse(node));
    }

    [Theory]
    [InlineData("protocolVersion")][InlineData("commandVersion")]
    public void UnknownVersionDoesNotReachTheCommandHandler(string field)
    {
        var node = Envelope(); node[field] = 2;
        Assert.StartsWith("UNSUPPORTED_", Assert.Throws<EnvelopeException>(() => Parse(node)).Code);
    }

    [Fact]
    public void DuplicateOrUnknownFieldsCannotChangeValidationMeaning()
    {
        var node = Envelope();
        var text = node.ToJsonString().Insert(1, "\"sequence\":\"2\",");
        Assert.Throws<EnvelopeException>(() => OperationEnvelope.Parse(Encoding.UTF8.GetBytes(text)));
        node["extra"] = "ignored?";
        Assert.Throws<EnvelopeException>(() => Parse(node));
    }

    [Fact]
    public void DependencyMustBelongToThisDeviceAndPrecedeThisSequence()
    {
        var node = Envelope(); node["dependencies"] = new JsonArray($"{Device:D}:1");
        Assert.Throws<EnvelopeException>(() => Parse(node));
        node["sequence"] = "2";
        Assert.Equal([1UL], Parse(node).Dependencies);
        node["dependencies"] = new JsonArray($"{Guid.NewGuid():D}:1");
        Assert.Throws<EnvelopeException>(() => Parse(node));
    }

    [Fact]
    public void CaptureContextAndOriginalTextSurviveAnEdit()
    {
        var member = Guid.NewGuid();
        var store = new InMemoryWorkspaceStore(Workspace, Epoch, HouseholdMembership.Create(member), [new(Device, member)]);
        var server = new WorkspaceServer(store);
        var create = Parse(Envelope());
        Assert.Equal("ACCEPTED", server.Handle(member, create).Code);
        var task = Assert.Single(store.Read().Tasks.Values);
        Assert.Equal("Coffee", task.Capture!.Title);
        Assert.Equal("Europe/Helsinki", task.Capture.Context.GetProperty("captureZoneId").GetString());
        Assert.Equal("ACCEPTED", server.Handle(member, new(Workspace, Epoch, Device, 2,
            new EditTask(task.Id, new("Tea", 1), ExpectedDeletionVersion: task.DeletionVersion))).Code);
        Assert.Equal("Coffee", store.Read().Tasks[task.Id].Capture!.Title);
        Assert.Equal("Tea", store.Read().Tasks[task.Id].Title);
    }
}
