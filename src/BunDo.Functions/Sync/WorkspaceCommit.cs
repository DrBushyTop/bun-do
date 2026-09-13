using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;

namespace BunDo.Functions.Sync;

public sealed record WorkspaceWrite(string Id, object Value, bool CreateOnly);

/// <summary>Every writer replaces the same metadata ETag with its effects in one partition transaction.</summary>
public sealed record WorkspaceCommit(WorkspaceState Metadata, IReadOnlyList<WorkspaceWrite> Writes, IReadOnlyList<string> Deletes)
{
    public const int MaximumOperations = 90;
    public const int MaximumBytes = 1792 * 1024;
    public static string DeviceId(Guid device) => $"device:{device:D}";
    public static string ReceiptId(string operation) => $"receipt:{operation}";
    public static string TaskId(string task) => $"task:{task}";
    public static string GroupId(ulong revision) => $"change:{revision:D20}";

    public static WorkspaceCommit Plan(WorkspaceState current, WorkspaceState next, IReadOnlyList<string>? deletes = null)
    {
        if (current.Revision == ulong.MaxValue || next.Revision != current.Revision + 1 ||
            current.WorkspaceId != next.WorkspaceId || current.StateEpoch != next.StateEpoch)
            throw new ArgumentException("Commit must advance one revision within its workspace epoch.");
        var group = next.Changes.Single(x => x.Revision == next.Revision);
        group = group with { RecordedAt = group.RecordedAt ?? DateTimeOffset.UtcNow };
        deletes ??= [];
        if (deletes.Distinct().Count() != deletes.Count || deletes.Any(x => x == "state"))
            throw new ArgumentException("Invalid maintenance deletion set.");
        var writes = new List<WorkspaceWrite> { new(GroupId(next.Revision), group, true) };
        foreach (var task in group.Tasks) writes.Add(new(TaskId(task.Id), task, false));
        // Completion credit outlives task content and remains available to household statistics.
        foreach (var completion in group.RetainedCompletions ?? [])
            writes.Add(new($"completion:{completion.RootId}", completion, true));
        foreach (var receipt in next.Receipts.Values.Where(x => x.EffectRevision == next.Revision))
            writes.Add(new(ReceiptId(receipt.OperationId), receipt, true));
        foreach (var device in next.Devices.Values)
            if (!current.Devices.TryGetValue(device.DeviceId, out var old) || old != device)
                writes.Add(new(DeviceId(device.DeviceId), device, false));
        // Existing pre-sync records remain readable. New history and entities never accumulate in metadata.
        var metadata = next with { Changes = current.Changes, Tasks = current.Tasks,
            Devices = current.Devices, Receipts = current.Receipts };
        var bytes = JsonSerializer.SerializeToUtf8Bytes(metadata).Length +
            writes.Sum(x => JsonSerializer.SerializeToUtf8Bytes(x.Value, x.Value.GetType()).Length + 512) + 512;
        bytes += deletes.Sum(x => System.Text.Encoding.UTF8.GetByteCount(x) + 512);
        if (writes.Count + deletes.Count + 1 > MaximumOperations || bytes > MaximumBytes ||
            writes.Any(x => deletes.Contains(x.Id)))
            throw new WorkspaceCommitTooLargeException();
        HouseholdDocumentLimits.CheckEncodedSize(metadata, JsonSerializer.SerializeToUtf8Bytes(metadata).Length + 256);
        return new(metadata, writes, deletes);
    }
}

public sealed class WorkspaceCommitTooLargeException : Exception;
