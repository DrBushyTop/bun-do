using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Diagnostics;
using BunDo.Domain;
using BunDo.Functions.Households;

namespace BunDo.Functions.Sync;

/// <summary>Adapts authenticated transport and point storage to the sole domain command handler.</summary>
public sealed class SyncService(IHouseholdDocuments documents)
{
    public async Task<OutcomePage> OutcomesAsync(Guid member, Guid workspace, Guid epoch, Guid registration,
        ulong first, int count, CancellationToken ct)
    {
        if (!WorkspaceServer.ValidOutcomeRange(first, count)) throw new SyncException("INVALID_RANGE");
        var stored = await Metadata(member, workspace, epoch, ct);
        var partition = workspace.ToString("D");
        var device = (await documents.ReadAsync<DeviceRegistration>(partition,
            WorkspaceCommit.DeviceId(registration), ct))?.Value
            ?? stored.Value.Devices.GetValueOrDefault(registration) ?? new(registration, member);
        if (device.MemberId != member) throw new SyncException("FORBIDDEN");
        var receipts = ImmutableDictionary<string, OperationReceipt>.Empty;
        for (var offset = 0; offset < count; offset++)
        {
            var sequence = first + (ulong)offset;
            if (sequence > device.LastTerminalSequence) break;
            var id = $"{registration:D}:{sequence}";
            var receipt = (await documents.ReadAsync<OperationReceipt>(partition,
                WorkspaceCommit.ReceiptId(id), ct))?.Value ?? stored.Value.Receipts.GetValueOrDefault(id);
            if (receipt is not null) receipts = receipts.Add(id, receipt);
        }
        return new WorkspaceServer(new StagedStore(stored.Value with {
            Devices = ImmutableDictionary<Guid, DeviceRegistration>.Empty.Add(registration, device),
            Receipts = receipts,
        })).Outcomes(member, epoch, registration, first, count);
    }

    public async Task AcknowledgeAsync(Guid member, Guid workspace, Guid epoch, Guid registration, ulong through, CancellationToken ct)
    {
        if (through == 0) return;
        for (var attempt = 0; attempt < 5; attempt++)
        {
            var stored = await Metadata(member, workspace, epoch, ct);
            var device = (await documents.ReadAsync<DeviceRegistration>(workspace.ToString("D"),
                WorkspaceCommit.DeviceId(registration), ct))?.Value ?? stored.Value.Devices.GetValueOrDefault(registration);
            if (device is null || device.MemberId != member) throw new SyncException("INVALID_ACKNOWLEDGEMENT");
            if (through > device.LastTerminalSequence) throw new SyncException("INVALID_ACKNOWLEDGEMENT");
            if (through <= device.AcknowledgedThrough) return;
            if (stored.Value.Revision == ulong.MaxValue) throw new SyncException("WORKSPACE_FULL");
            var next = stored.Value with { Revision = stored.Value.Revision + 1,
                Devices = ImmutableDictionary<Guid, DeviceRegistration>.Empty.Add(registration,
                    device with { AcknowledgedThrough = through }),
                Changes = [new(stored.Value.Revision + 1, [])] };
            if (await documents.CommitWorkspaceAsync(stored, next, ct)) return;
        }
        throw new SyncException("BUSY");
    }

    public async Task<SubmissionResult> SubmitAsync(Guid member, FrozenOperation operation, CancellationToken ct,
        string? registryPartition = null)
    {
        for (var attempt = 0; attempt < 5; attempt++)
        {
            Activity.Current?.SetTag("operation.stage", "workspace_read");
            Activity.Current?.SetTag("sync.cas_collisions", attempt);
            var stored = await Metadata(member, operation.WorkspaceId, operation.StateEpoch, ct);
            var metadata = stored.Value;
            var partition = operation.WorkspaceId.ToString("D");
            var device = (await documents.ReadAsync<DeviceRegistration>(partition,
                WorkspaceCommit.DeviceId(operation.DeviceId), ct))?.Value
                ?? metadata.Devices.GetValueOrDefault(operation.DeviceId)
                ?? new DeviceRegistration(operation.DeviceId, member);
            if (registryPartition is not null) device = device with { RegistryPartition = registryPartition };
            var receipts = ImmutableDictionary<string, OperationReceipt>.Empty;
            foreach (var sequence in operation.Dependencies.Append(operation.Sequence).Distinct())
            {
                var id = $"{operation.DeviceId:D}:{sequence}";
                var receipt = (await documents.ReadAsync<OperationReceipt>(partition, WorkspaceCommit.ReceiptId(id), ct))?.Value
                    ?? metadata.Receipts.GetValueOrDefault(id);
                if (receipt is not null) receipts = receipts.Add(id, receipt);
            }
            var taskId = operation.Command switch {
                CleanupCommand c => c.TaskId, CreateTask c => c.TaskId, EditTask e => e.TaskId, TaskTransition t => t.TaskId, MoveTask m => m.TaskId, _ => null,
            };
            var tasks = ImmutableDictionary<string, TaskSnapshot>.Empty;
            if (metadata.RootOrder is null && (metadata.TaskCount > 0 || metadata.Tasks.Count > 0))
            {
                // Upgrade the old text-only queue once, under the same metadata CAS as the command.
                string? continuation = null;
                do
                {
                    var page = await documents.ReadPageAsync<TaskSnapshot>(partition, "task:", continuation, 64, ct);
                    foreach (var item in page.Items) tasks = tasks.SetItem(item.Value.Id, item.Value);
                    continuation = page.Continuation;
                } while (continuation is not null);
                foreach (var item in metadata.Tasks.Values)
                    if (!tasks.ContainsKey(item.Id)) tasks = tasks.Add(item.Id, item);
            }
            if (taskId is not null)
            {
                var task = (await documents.ReadAsync<TaskSnapshot>(partition, WorkspaceCommit.TaskId(taskId), ct))?.Value
                    ?? metadata.Tasks.GetValueOrDefault(taskId);
                if (task is not null) tasks = tasks.SetItem(taskId, task);
                if (task?.ParentId is { } parentId)
                {
                    var parent = (await documents.ReadAsync<TaskSnapshot>(partition, WorkspaceCommit.TaskId(parentId), ct))?.Value
                        ?? metadata.Tasks.GetValueOrDefault(parentId);
                    if (parent is not null) { tasks = tasks.SetItem(parentId, parent); task = parent; }
                }
                foreach (var childId in task?.ChildOrder ?? [])
                {
                    var child = (await documents.ReadAsync<TaskSnapshot>(partition, WorkspaceCommit.TaskId(childId), ct))?.Value
                        ?? metadata.Tasks.GetValueOrDefault(childId);
                    if (child is not null) tasks = tasks.SetItem(childId, child);
                }
            }
            var staged = new StagedStore(metadata with { Devices = ImmutableDictionary<Guid, DeviceRegistration>.Empty.Add(device.DeviceId, device),
                Tasks = tasks, Receipts = receipts, Changes = [] });
            var result = new WorkspaceServer(staged).Handle(member, operation);
            if (staged.Next is null) return result;
            try
            {
                Activity.Current?.SetTag("operation.stage", "workspace_commit");
                if (await documents.CommitWorkspaceAsync(stored, staged.Next, ct)) return result;
            }
            catch (WorkspaceCommitTooLargeException) { return new("WORKSPACE_FULL"); }
        }
        return new("BUSY");
    }

    public async Task<SyncReply> PullAsync(Guid member, Guid workspace, Guid epoch, string? cursor,
        IReadOnlyList<OperationReceipt> receipts, string code, CancellationToken ct)
    {
        var stored = await Metadata(member, workspace, epoch, ct);
        var state = stored.Value;
        // Upgrade pre-sync household metadata without changing any shared content.
        if (state.CursorSecret is null)
        {
            var next = state with { CursorSecret = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)) };
            if (!await documents.WriteAsync(workspace.ToString("D"), "state", stored.Version, next, ct))
                throw new SyncException("BUSY");
            state = next;
        }
        var position = cursor is null ? new CursorPosition(0, 0) : SyncCursor.Read(state, cursor);
        if (position.After < state.PrunedThrough) throw new SyncException("SNAPSHOT_REQUIRED");
        var target = position.Target == 0 ? state.Revision : position.Target;
        if (position.After > target || target > state.Revision) throw new SyncException("INVALID_CURSOR");
        var groups = new List<SyncGroup>();
        var through = position.After;
        SyncReply Reply() => new(code, workspace, epoch, position.After, through, state.Revision, target,
            SyncCursor.Write(state, new(through, through == target ? 0 : target)), through < target, receipts, groups.ToArray(),
            new(member, state.Membership.OwnerId, state.Membership.Members.Values.OrderBy(x => x.Id).ToArray()));
        var responseBytes = JsonSerializer.SerializeToUtf8Bytes(Reply(), SyncJson.Options).Length;
        while (through < target && groups.Count < 100)
        {
            var revision = through + 1;
            var change = (await documents.ReadAsync<ChangeGroup>(workspace.ToString("D"),
                WorkspaceCommit.GroupId(revision), ct))?.Value
                ?? state.Changes.SingleOrDefault(x => x.Revision == revision)
                ?? throw new SyncException("CHANGE_UNAVAILABLE");
            if (change.Revision != revision) throw new SyncException("CHANGE_UNAVAILABLE");
            var group = SyncGroup.Encode(change);
            var size = JsonSerializer.SerializeToUtf8Bytes(group, SyncJson.Options).Length + 1;
            if (size > 2 * 1024 * 1024) throw new SyncException("CHANGE_UNAVAILABLE");
            if (responseBytes + size > 4 * 1024 * 1024 - 1024) break;
            groups.Add(group);
            through = revision;
            responseBytes += size;
        }
        return Reply();
    }

    private async Task<StoredDocument<WorkspaceState>> Metadata(Guid member, Guid workspace, Guid epoch, CancellationToken ct)
    {
        var state = await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", ct);
        if (state is null || !state.Value.Membership.CanRead(member)) throw new SyncException("FORBIDDEN");
        if (state.Value.StateEpoch != epoch) throw new SyncException("EPOCH_CHANGED");
        return state;
    }

    private sealed class StagedStore(WorkspaceState state) : IWorkspaceStore
    {
        public WorkspaceState? Next { get; private set; }
        public WorkspaceState Read() => state;
        public bool TryCommit(ulong expectedRevision, WorkspaceState next)
        {
            if (expectedRevision != state.Revision || Next is not null) throw new InvalidOperationException();
            Next = next;
            return true;
        }
    }
}

public sealed record SyncReply(string Code, Guid WorkspaceId, Guid StateEpoch, ulong AfterRevision,
    ulong ThroughRevision, ulong HeadRevision, ulong TargetRevision, string Cursor, bool HasMore,
    IReadOnlyList<OperationReceipt> Receipts, IReadOnlyList<SyncGroup> Groups, SyncMembership? Membership = null);
public sealed record SyncMembership(Guid Me, Guid OwnerId, IReadOnlyList<HouseholdMember> Members);
public sealed record SyncPart(int PartIndex, string[] EntityIds, string Payload);
public sealed record SyncGroup(ulong Revision, int PartCount, SyncPart[] Parts, string Digest)
{
    public static SyncGroup Encode(ChangeGroup group)
    {
        var entities = group.Tasks.Select(t => (object)t).ToList();
        var ids = group.Tasks.Select(t => t.Id).ToList();
        foreach (var id in group.PurgedTaskIds ?? [])
        {
            entities.Add(new { id, entityType = "PURGED_TASK", version = group.Revision });
            ids.Add(id);
        }
        if (group.RootOrder is { } order)
        {
            entities.Add(new { id = "root-order", entityType = "ROOT_ORDER", taskIds = order, version = group.Revision });
            ids.Add("root-order");
        }
        var payload = JsonSerializer.Serialize(entities, SyncJson.Options);
        var part = new SyncPart(0, ids.ToArray(), payload);
        return new(group.Revision, 1, [part], Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(payload))));
    }
}

public static class SyncJson
{
    public static readonly JsonSerializerOptions Options = Create();
    private static JsonSerializerOptions Create()
    {
        var result = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        result.Converters.Add(new DecimalVersionConverter());
        result.Converters.Add(new FieldVersionConverter());
        return result;
    }
    private sealed class FieldVersionConverter : JsonConverter<FieldVersion>
    {
        public override FieldVersion Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options) =>
            throw new NotSupportedException();
        public override void Write(Utf8JsonWriter writer, FieldVersion value, JsonSerializerOptions options)
        {
            writer.WriteStartObject();
            writer.WriteString("fieldVersion", value.Server.ToString(System.Globalization.CultureInfo.InvariantCulture));
            writer.WriteString("humanVersion", value.Human.ToString(System.Globalization.CultureInfo.InvariantCulture));
            writer.WriteEndObject();
        }
    }
    private sealed class DecimalVersionConverter : JsonConverter<ulong>
    {
        public override ulong Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options)
        {
            using var document = JsonDocument.ParseValue(ref reader);
            return OperationEnvelope.Decimal(document.RootElement);
        }
        public override void Write(Utf8JsonWriter writer, ulong value, JsonSerializerOptions options) =>
            writer.WriteStringValue(value.ToString(System.Globalization.CultureInfo.InvariantCulture));
    }
}

public sealed class SyncException(string code) : Exception(code)
{
    public string Code { get; } = code;
}

internal sealed record CursorPosition(ulong After, ulong Target);
internal static class SyncCursor
{
    public static string Write(WorkspaceState state, CursorPosition position)
    {
        var text = $"{state.WorkspaceId:D}/{state.StateEpoch:D}/{position.After}/{position.Target}";
        var bytes = Encoding.ASCII.GetBytes(text);
        var signature = HMACSHA256.HashData(Convert.FromBase64String(state.CursorSecret!), bytes);
        return Convert.ToBase64String(bytes) + "." + Convert.ToBase64String(signature);
    }
    public static CursorPosition Read(WorkspaceState state, string cursor)
    {
        try
        {
            if (cursor.Length > 512) throw new FormatException();
            var parts = cursor.Split('.');
            if (parts.Length != 2) throw new FormatException();
            var bytes = Convert.FromBase64String(parts[0]);
            var signature = Convert.FromBase64String(parts[1]);
            if (!CryptographicOperations.FixedTimeEquals(signature,
                HMACSHA256.HashData(Convert.FromBase64String(state.CursorSecret!), bytes))) throw new FormatException();
            var fields = Encoding.ASCII.GetString(bytes).Split('/');
            if (fields.Length != 4 || fields[0] != state.WorkspaceId.ToString("D") || fields[1] != state.StateEpoch.ToString("D"))
                throw new FormatException();
            return new(ulong.Parse(fields[2], System.Globalization.CultureInfo.InvariantCulture),
                ulong.Parse(fields[3], System.Globalization.CultureInfo.InvariantCulture));
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        { throw new SyncException("INVALID_CURSOR"); }
    }
}
