using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace BunDo.Domain;

public abstract record TaskCommand;
public sealed record CreateTask(string TaskId, string Title, string? Description = null) : TaskCommand;
public sealed record TextEdit(string? Value, ulong ExpectedHumanVersion);
public sealed record EditTask(string TaskId, TextEdit? Title = null, TextEdit? Description = null) : TaskCommand;
public sealed record DiscardBlockedIntent(ulong RejectedDependencySequence) : TaskCommand;

/// <summary>A frozen model envelope. It is not yet the production HTTP wire parser.</summary>
public sealed class FrozenOperation
{
    public Guid WorkspaceId { get; }
    public Guid StateEpoch { get; }
    public Guid DeviceId { get; }
    public ulong Sequence { get; }
    public TaskCommand Command { get; }
    public string Fingerprint { get; }
    public string OperationId => $"{DeviceId:D}:{Sequence.ToString(CultureInfo.InvariantCulture)}";

    public FrozenOperation(Guid workspaceId, Guid stateEpoch, Guid deviceId, ulong sequence, TaskCommand command)
    {
        ArgumentNullException.ThrowIfNull(command);
        WorkspaceId = workspaceId;
        StateEpoch = stateEpoch;
        DeviceId = deviceId;
        Sequence = sequence;
        Command = command;
        var kind = command switch
        {
            CreateTask => "CreateTask",
            EditTask => "EditTask",
            DiscardBlockedIntent => "DiscardBlockedIntent",
            _ => throw new ArgumentException("Unsupported model command.", nameof(command))
        };
        var payload = JsonSerializer.SerializeToElement(command, command.GetType());
        var bytes = JsonSerializer.SerializeToUtf8Bytes(new
        {
            workspaceId,
            stateEpoch,
            deviceId,
            sequence = sequence.ToString(CultureInfo.InvariantCulture),
            kind,
            payload
        });
        Fingerprint = Convert.ToHexStringLower(SHA256.HashData(bytes));
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
