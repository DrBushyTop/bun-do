using System.Globalization;
using System.Text;
using System.Text.Json;

namespace BunDo.Domain;

/// <summary>Strict wire protocol 1 parser. It never normalizes the bytes used for retry identity.</summary>
public static class OperationEnvelope
{
    public const int MaximumBytes = 32 * 1024;
    public const int MaximumMetadataBytes = 16 * 1024;

    public static FrozenOperation Parse(ReadOnlyMemory<byte> bytes)
    {
        if (bytes.Length is 0 or > MaximumBytes) throw new EnvelopeException("ENVELOPE_TOO_LARGE");
        try
        {
            using var document = JsonDocument.Parse(bytes, new JsonDocumentOptions { MaxDepth = 8 });
            var root = document.RootElement;
            Fields(root, ["protocolVersion", "commandVersion", "stateEpoch", "workspaceId", "deviceId", "sequence",
                "command", "payload", "dependencies", "observedVersions", "occurredAtContext"]);
            if (root.GetProperty("protocolVersion").GetInt32() != 1)
                throw new EnvelopeException("UNSUPPORTED_PROTOCOL");
            if (root.GetProperty("commandVersion").GetInt32() != 1)
                throw new EnvelopeException("UNSUPPORTED_COMMAND_VERSION");
            var workspace = Uuid(root.GetProperty("workspaceId"));
            var epoch = Uuid(root.GetProperty("stateEpoch"));
            var device = Uuid(root.GetProperty("deviceId"));
            var sequence = Decimal(root.GetProperty("sequence"));
            if (sequence == 0) throw new EnvelopeException("INVALID_SEQUENCE");
            var payload = root.GetProperty("payload");
            if (bytes.Length - Encoding.UTF8.GetByteCount(payload.GetRawText()) > MaximumMetadataBytes)
                throw new EnvelopeException("METADATA_TOO_LARGE");
            var dependencies = root.GetProperty("dependencies").EnumerateArray().Select(x => Dependency(x, device)).ToArray();
            if (dependencies.Length > 32 || dependencies.Distinct().Count() != dependencies.Length ||
                dependencies.Any(x => x == 0 || x >= sequence))
                throw new EnvelopeException("INVALID_DEPENDENCY");
            var observed = root.GetProperty("observedVersions");
            ValidateContext(root.GetProperty("occurredAtContext"));
            TaskCommand command;
            switch (root.GetProperty("command").GetString())
            {
                case "CreateTask":
                    Fields(payload, ["taskId", "title", "description"]);
                    Fields(observed, []);
                    command = new CreateTask(Uuid(payload.GetProperty("taskId")).ToString("D"),
                        Text(payload.GetProperty("title")), NullableText(payload.GetProperty("description")));
                    break;
                case "EditTask":
                    Fields(payload, ["taskId"], ["title", "description"]);
                    var groups = new[] { "title", "description" }.Where(x => payload.TryGetProperty(x, out _)).ToArray();
                    Fields(observed, groups.Append("deletion").ToArray());
                    Fields(observed.GetProperty("deletion"), ["fieldVersion"]);
                    command = new EditTask(Uuid(payload.GetProperty("taskId")).ToString("D"),
                        groups.Contains("title") ? new(Text(payload.GetProperty("title")), Version(observed, "title")) : null,
                        groups.Contains("description") ? new(NullableText(payload.GetProperty("description")), Version(observed, "description")) : null,
                        Decimal(observed.GetProperty("deletion").GetProperty("fieldVersion")));
                    break;
                case "DiscardBlockedIntent":
                    Fields(payload, ["rejectedDependency"]);
                    Fields(observed, []);
                    var rejected = Dependency(payload.GetProperty("rejectedDependency"), device);
                    if (!dependencies.Contains(rejected)) throw new EnvelopeException("INVALID_DEPENDENCY");
                    command = new DiscardBlockedIntent(rejected);
                    break;
                case "ClaimTask":
                case "UnclaimTask":
                case "CompleteTask":
                case "ReopenTask":
                case "CancelTask":
                case "DeleteTask":
                case "RestoreTask":
                case "SetSnooze":
                case "ClearSnooze":
                case "SplitTask":
                case "AddChildren":
                    var kind = root.GetProperty("command").GetString();
                    var checklist = kind is "SplitTask" or "AddChildren";
                    Fields(payload, kind == "CompleteTask" ? ["taskId", "confirmedClaimantId"] :
                        kind == "SetSnooze" ? ["taskId", "until"] : checklist ? ["taskId", "items"] : ["taskId"]);
                    Fields(observed, checklist ? ["lifecycle", "claim", "hierarchy", "deletion", "title", "description"] :
                        ["lifecycle", "claim", "hierarchy", "deletion"], ["subtree", "snooze"]);
                    var taskId = Uuid(payload.GetProperty("taskId")).ToString("D");
                    var expected = new TaskStateVersions(ExactVersion(observed, "lifecycle"), ExactVersion(observed, "claim"),
                        ExactVersion(observed, "hierarchy"), ExactVersion(observed, "deletion"),
                        observed.TryGetProperty("subtree", out _) ? ExactVersion(observed, "subtree") : 0,
                        observed.TryGetProperty("snooze", out _) ? ExactVersion(observed, "snooze") : 0);
                    command = kind switch {
                        "ClaimTask" => new ClaimTask(taskId, expected),
                        "UnclaimTask" => new UnclaimTask(taskId, expected),
                        "CompleteTask" => new CompleteTask(taskId, expected,
                            payload.GetProperty("confirmedClaimantId").ValueKind == JsonValueKind.Null ? null : Uuid(payload.GetProperty("confirmedClaimantId"))),
                        "ReopenTask" => new ReopenTask(taskId, expected),
                        "DeleteTask" => new DeleteTask(taskId, expected),
                        "RestoreTask" => new RestoreTask(taskId, expected),
                        "SetSnooze" => new SetSnooze(taskId, expected, UtcInstant(payload.GetProperty("until"))),
                        "ClearSnooze" => new ClearSnooze(taskId, expected),
                        "SplitTask" => new SplitTask(taskId, expected, payload.GetProperty("items").EnumerateArray().Select(Text).ToArray(),
                            Version(observed, "title"), Version(observed, "description")),
                        "AddChildren" => new AddChildren(taskId, expected, payload.GetProperty("items").EnumerateArray().Select(Text).ToArray(),
                            Version(observed, "title"), Version(observed, "description")),
                        _ => new CancelTask(taskId, expected),
                    };
                    break;
                case "MoveTask":
                    Fields(payload, ["taskId", "expectedParentId", "afterTaskId", "beforeTaskId"]);
                    Fields(observed, ["orderIntent", "deletion"]);
                    command = new MoveTask(Uuid(payload.GetProperty("taskId")).ToString("D"),
                        ExactVersion(observed, "orderIntent"), ExactVersion(observed, "deletion"),
                        NullableUuid(payload.GetProperty("expectedParentId")),
                        NullableUuid(payload.GetProperty("afterTaskId")), NullableUuid(payload.GetProperty("beforeTaskId")));
                    break;
                default: throw new EnvelopeException("UNSUPPORTED_COMMAND");
            }
            return new(workspace, epoch, device, sequence, command, 1, 1, bytes.ToArray(), dependencies)
                { CaptureContext = root.GetProperty("occurredAtContext").Clone() };
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or OverflowException or KeyNotFoundException)
        {
            throw new EnvelopeException("INVALID_ENVELOPE");
        }
    }

    private static ulong Version(JsonElement observed, string group)
    {
        var value = observed.GetProperty(group);
        Fields(value, ["humanVersion"]);
        return Decimal(value.GetProperty("humanVersion"));
    }

    private static ulong ExactVersion(JsonElement observed, string group)
    {
        var value = observed.GetProperty(group);
        Fields(value, ["fieldVersion"]);
        return Decimal(value.GetProperty("fieldVersion"));
    }

    private static ulong Dependency(JsonElement value, Guid device)
    {
        var id = Text(value);
        var prefix = $"{device:D}:";
        if (!id.StartsWith(prefix, StringComparison.Ordinal)) throw new EnvelopeException("INVALID_DEPENDENCY");
        return ParseDecimal(id[prefix.Length..]);
    }

    public static ulong Decimal(JsonElement value) => ParseDecimal(Text(value));

    private static ulong ParseDecimal(string value)
    {
        if (value.Length is 0 or > 20 || value.Length > 1 && value[0] == '0' ||
            !value.All(char.IsAsciiDigit) || !ulong.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out var result))
            throw new EnvelopeException("INVALID_DECIMAL");
        return result;
    }

    private static DateTimeOffset UtcInstant(JsonElement value)
    {
        var text = Text(value);
        if (!text.EndsWith('Z') || !DateTimeOffset.TryParseExact(text, "yyyy-MM-dd'T'HH:mm:ss.FFFFFFF'Z'",
                CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var instant))
            throw new EnvelopeException("INVALID_ENVELOPE");
        return instant;
    }

    private static Guid Uuid(JsonElement value)
    {
        var text = Text(value);
        if (!Guid.TryParseExact(text, "D", out var result) || result == Guid.Empty || result.ToString("D") != text)
            throw new EnvelopeException("INVALID_ID");
        return result;
    }
    private static string? NullableUuid(JsonElement value) => value.ValueKind == JsonValueKind.Null ? null : Uuid(value).ToString("D");

    private static string Text(JsonElement value) =>
        value.ValueKind == JsonValueKind.String ? value.GetString()! : throw new EnvelopeException("INVALID_ENVELOPE");
    private static string? NullableText(JsonElement value) => value.ValueKind == JsonValueKind.Null ? null : Text(value);

    private static void Fields(JsonElement value, string[] required, string[]? optional = null)
    {
        var names = value.EnumerateObject().Select(x => x.Name).ToArray();
        if (names.Distinct(StringComparer.Ordinal).Count() != names.Length ||
            required.Except(names).Any() || names.Except(required.Concat(optional ?? [])).Any())
            throw new EnvelopeException("INVALID_FIELDS");
    }

    private static void ValidateContext(JsonElement value)
    {
        Fields(value, ["capturedInstant", "capturedLocal", "captureZoneId", "captureOffsetSeconds", "zoneSource",
            "locale", "clockConfidence"], ["serverAnchorInstant", "anchorElapsedRealtimeMs", "capturedElapsedRealtimeMs"]);
        var instant = Text(value.GetProperty("capturedInstant"));
        if (!instant.EndsWith('Z') || !DateTimeOffset.TryParse(instant, CultureInfo.InvariantCulture,
                DateTimeStyles.RoundtripKind, out _) ||
            !DateTime.TryParseExact(Text(value.GetProperty("capturedLocal")), "yyyy-MM-dd'T'HH:mm:ss.fff",
                CultureInfo.InvariantCulture, DateTimeStyles.None, out _) ||
            Text(value.GetProperty("captureZoneId")).Length is 0 or > 100 ||
            value.GetProperty("captureOffsetSeconds").GetInt32() is < -64800 or > 64800 ||
            Text(value.GetProperty("zoneSource")) is not ("DEVICE" or "WORKSPACE_FALLBACK") ||
            Text(value.GetProperty("clockConfidence")) is not ("HIGH" or "UNKNOWN" or "SUSPECT") ||
            Text(value.GetProperty("locale")).Length is 0 or > 64)
            throw new EnvelopeException("INVALID_CAPTURE_CONTEXT");
    }
}

public sealed class EnvelopeException(string code) : Exception(code)
{
    public string Code { get; } = code;
}
