using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BunDo.Domain;

public abstract record TaskCommand;
public sealed record CreateTask(string TaskId, string Title, string? Description = null) : TaskCommand;
public sealed record TextEdit(string? Value, ulong ExpectedHumanVersion);
public sealed record EditTask(string TaskId, TextEdit? Title = null, TextEdit? Description = null,
    ulong? ExpectedDeletionVersion = null) : TaskCommand;
public sealed record TaskStateVersions(ulong Lifecycle, ulong Claim, ulong Hierarchy, ulong Deletion);
public abstract record TaskTransition(string TaskId, TaskStateVersions Expected) : TaskCommand;
public sealed record ClaimTask(string TaskId, TaskStateVersions Expected) : TaskTransition(TaskId, Expected);
public sealed record UnclaimTask(string TaskId, TaskStateVersions Expected) : TaskTransition(TaskId, Expected);
public sealed record CompleteTask(string TaskId, TaskStateVersions Expected, Guid? ConfirmedClaimantId = null)
    : TaskTransition(TaskId, Expected);
public sealed record ReopenTask(string TaskId, TaskStateVersions Expected) : TaskTransition(TaskId, Expected);
public sealed record CancelTask(string TaskId, TaskStateVersions Expected) : TaskTransition(TaskId, Expected);
public sealed record DeleteTask(string TaskId, TaskStateVersions Expected) : TaskTransition(TaskId, Expected);
public sealed record RestoreTask(string TaskId, TaskStateVersions Expected) : TaskTransition(TaskId, Expected);
public sealed record MoveTask(string TaskId, ulong ExpectedOrderVersion, ulong ExpectedDeletionVersion,
    string? ExpectedParentId = null, string? AfterTaskId = null, string? BeforeTaskId = null) : TaskCommand;
public sealed record DiscardBlockedIntent(ulong RejectedDependencySequence) : TaskCommand;

/// <summary>Validated command identity. Production fingerprints cover the original wire bytes.</summary>
public sealed class FrozenOperation
{
    public Guid WorkspaceId { get; }
    public Guid StateEpoch { get; }
    public Guid DeviceId { get; }
    public ulong Sequence { get; }
    public TaskCommand Command { get; }
    public int ProtocolVersion { get; }
    public int CommandVersion { get; }
    public string Fingerprint { get; }
    public IReadOnlyList<ulong> Dependencies { get; }
    public JsonElement? CaptureContext { get; internal init; }
    public string OperationId => $"{DeviceId:D}:{Sequence.ToString(CultureInfo.InvariantCulture)}";

    public FrozenOperation(Guid workspaceId, Guid stateEpoch, Guid deviceId, ulong sequence, TaskCommand command,
        int protocolVersion = 1, int commandVersion = 1)
        : this(workspaceId, stateEpoch, deviceId, sequence, command, protocolVersion, commandVersion, null, [])
    {
    }

    internal FrozenOperation(Guid workspaceId, Guid stateEpoch, Guid deviceId, ulong sequence, TaskCommand command,
        int protocolVersion, int commandVersion, byte[]? wireBytes, IReadOnlyList<ulong> dependencies)
    {
        ArgumentNullException.ThrowIfNull(command);
        WorkspaceId = workspaceId;
        StateEpoch = stateEpoch;
        DeviceId = deviceId;
        Sequence = sequence;
        Command = command;
        ProtocolVersion = protocolVersion;
        CommandVersion = commandVersion;
        Dependencies = dependencies.ToArray();
        var kind = command switch
        {
            CreateTask => "CreateTask",
            EditTask => "EditTask",
            ClaimTask => "ClaimTask",
            UnclaimTask => "UnclaimTask",
            CompleteTask => "CompleteTask",
            ReopenTask => "ReopenTask",
            CancelTask => "CancelTask",
            DeleteTask => "DeleteTask",
            RestoreTask => "RestoreTask",
            MoveTask => "MoveTask",
            DiscardBlockedIntent => "DiscardBlockedIntent",
            _ => throw new ArgumentException("Unsupported model command.", nameof(command))
        };
        var payload = JsonSerializer.SerializeToElement(command, command.GetType());
        var bytes = JsonSerializer.SerializeToUtf8Bytes(new
        {
            workspaceId,
            protocolVersion,
            commandVersion,
            stateEpoch,
            deviceId,
            sequence = sequence.ToString(CultureInfo.InvariantCulture),
            kind,
            payload
        });
        Fingerprint = Convert.ToHexStringLower(SHA256.HashData(wireBytes ?? bytes));
    }
}

public static class TaskIdentity
{
    public static string ForCreate(Guid deviceId, ulong sequence, int ordinal = 0)
    {
        ArgumentOutOfRangeException.ThrowIfZero(sequence);
        ArgumentOutOfRangeException.ThrowIfNegative(ordinal);
        var name = Encoding.ASCII.GetBytes(FormattableString.Invariant($"task/{sequence}/{ordinal}"));
        var input = deviceId.ToByteArray(bigEndian: true).Concat(name).ToArray();
        var hash = SHA1.HashData(input); // UUIDv5 identity, not a security credential.
        hash[6] = (byte)((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte)((hash[8] & 0x3f) | 0x80);
        return new Guid(hash.AsSpan(0, 16), bigEndian: true).ToString("D");
    }
}
