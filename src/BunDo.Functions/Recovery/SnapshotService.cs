using System.Collections.Immutable;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using BunDo.Functions.Sync;

namespace BunDo.Functions.Recovery;

public sealed record SnapshotChunk(int Index, int Bytes, int Documents, string Digest);
public sealed record SnapshotManifest(Guid SnapshotId, Guid WorkspaceId, Guid StateEpoch, ulong Revision,
    int SchemaVersion, DateTimeOffset ExpiresAt, long TotalBytes, int DocumentCount,
    IReadOnlyList<SnapshotChunk> Chunks, string Cursor);
public sealed class SnapshotException(string code) : Exception(code) { public string Code { get; } = code; }

/// <summary>Revision-fenced private snapshots and bounded retention, using the same metadata CAS as commands.</summary>
public sealed class SnapshotService(IHouseholdDocuments documents, ISnapshotArtifacts artifacts,
    IRegistrationStore? registrations = null, TimeProvider? timeProvider = null)
{
    public const int MaximumChunkBytes = 4 * 1024 * 1024;
    public const long MaximumSnapshotBytes = 1024L * 1024 * 1024;
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;
    private static ImmutableDictionary<Guid, SnapshotPin> Pins(WorkspaceState state) => state.SnapshotPins ?? ImmutableDictionary<Guid, SnapshotPin>.Empty;
    private static ArtifactKey Key(Guid workspace, SnapshotPin pin) => new(workspace, pin.Id, pin.ArtifactId);
    private static string Hash(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));
    private WorkspaceState Advance(WorkspaceState state)
    {
        if (state.Revision == ulong.MaxValue) throw new SnapshotException("WORKSPACE_FULL");
        return state with { Revision = state.Revision + 1, Changes = [new(state.Revision + 1, [], clock.GetUtcNow())] };
    }
    private async Task<StoredDocument<WorkspaceState>> State(Guid member, Guid workspace, Guid epoch, CancellationToken ct)
    {
        var stored = await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", ct);
        if (stored is null || !stored.Value.Membership.CanRead(member)) throw new SnapshotException("FORBIDDEN");
        if (stored.Value.StateEpoch != epoch) throw new SnapshotException("EPOCH_CHANGED");
        return stored;
    }
    private SnapshotPin Authorize(WorkspaceState state, Guid member, Guid device, Guid id, bool ready)
    {
        if (!Pins(state).TryGetValue(id, out var pin) || pin.ExpiresAt <= clock.GetUtcNow())
            throw new SnapshotException("SNAPSHOT_EXPIRED");
        if (pin.MemberId != member || pin.DeviceId != device) throw new SnapshotException("FORBIDDEN");
        if (ready && pin.ManifestHash is null) throw new SnapshotException("SNAPSHOT_BUSY");
        return pin;
    }

    public async Task<SnapshotManifest> CreateAsync(Guid member, Guid workspace, Guid epoch, Guid device, Guid id, CancellationToken ct)
    {
        if (id == Guid.Empty) throw new SnapshotException("INVALID_SNAPSHOT");
        await PruneAsync(member, workspace, epoch, ct);
        Guid? owned = null;
        for (var candidate = 0; candidate < 3; candidate++)
        {
            SnapshotPin? pin = null;
            WorkspaceState? start = null;
            for (var collision = 0; collision < 5; collision++)
            {
                var stored = await State(member, workspace, epoch, ct);
                var pins = Pins(stored.Value);
                if (pins.TryGetValue(id, out var existing))
                {
                    Authorize(stored.Value, member, device, id, false);
                    if (existing.ManifestHash is not null) return await ManifestAsync(member, workspace, epoch, device, id, ct);
                    if (existing.ArtifactId != owned && existing.BuildUntil > clock.GetUtcNow()) throw new SnapshotException("SNAPSHOT_BUSY");
                }
                if (pins.Values.Count(x => x.ExpiresAt > clock.GetUtcNow() && x.Id != id) >= 2)
                    throw new SnapshotException("SNAPSHOT_BUSY");
                var next = Advance(stored.Value);
                pin = new(id, member, device, Guid.NewGuid(), next.Revision, existing?.ExpiresAt ?? clock.GetUtcNow().AddMinutes(30), clock.GetUtcNow().AddMinutes(2));
                next = next with { SnapshotPins = pins.SetItem(id, pin),
                    CursorSecret = next.CursorSecret ?? Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)) };
                if (!await documents.CommitWorkspaceAsync(stored, next, ct)) { pin = null; continue; }
                start = next;
                owned = pin.ArtifactId;
                // A timed-out builder cannot publish after this claim, even if it later finishes writing its private bytes.
                if (existing is not null) await artifacts.DeleteAsync(Key(workspace, existing), ct);
                break;
            }
            if (pin is null || start is null) throw new SnapshotException("SNAPSHOT_BUSY");
            Activity.Current?.SetTag("operation.stage", "snapshot_enumerate");
            var key = Key(workspace, pin);
            var chunks = new List<SnapshotChunk>();
            var batch = new List<TaskSnapshot>();
            var seen = new HashSet<string>(StringComparer.Ordinal);
            var estimatedBytes = 64;
            long totalBytes = 0;
            async Task Flush()
            {
                if (batch.Count == 0) return;
                var bytes = JsonSerializer.SerializeToUtf8Bytes(new { schemaVersion = 1, tasks = batch }, SyncJson.Options);
                if (bytes.Length > MaximumChunkBytes || totalBytes + bytes.Length > MaximumSnapshotBytes || chunks.Count >= 4096)
                    throw new SnapshotException("SNAPSHOT_TOO_LARGE");
                await artifacts.PutAsync(key, chunks.Count, bytes, ct);
                chunks.Add(new(chunks.Count, bytes.Length, batch.Count, Hash(bytes)));
                totalBytes += bytes.Length;
                batch.Clear(); estimatedBytes = 64;
            }
            async Task Add(TaskSnapshot task)
            {
                if (!seen.Add(task.Id)) return;
                var bytes = JsonSerializer.SerializeToUtf8Bytes(task, SyncJson.Options).Length + 1;
                if (bytes + 64 > MaximumChunkBytes) throw new SnapshotException("SNAPSHOT_TOO_LARGE");
                if (estimatedBytes + bytes > MaximumChunkBytes) await Flush();
                batch.Add(task); estimatedBytes += bytes;
            }
            string? continuation = null;
            do
            {
                var page = await documents.ReadPageAsync<TaskSnapshot>(workspace.ToString("D"), "task:", continuation, 64, ct);
                foreach (var task in page.Items) await Add(task.Value);
                continuation = page.Continuation;
            } while (continuation is not null);
            foreach (var task in start.Tasks.Values) await Add(task);
            await Flush();
            var after = await State(member, workspace, epoch, ct);
            var currentPin = Authorize(after.Value, member, device, id, false);
            if (currentPin.ArtifactId != pin.ArtifactId || pin.BuildUntil <= clock.GetUtcNow()) throw new SnapshotException("SNAPSHOT_BUSY");
            if (after.Value.Revision == pin.Revision)
            {
                var manifest = new SnapshotManifest(id, workspace, epoch, pin.Revision, 1, pin.ExpiresAt, totalBytes,
                    seen.Count, chunks, SyncCursor.Write(after.Value, new(pin.Revision, 0)));
                var bytes = JsonSerializer.SerializeToUtf8Bytes(manifest, SyncJson.Options);
                await artifacts.PutAsync(key, -1, bytes, ct);
                var next = Advance(after.Value) with { SnapshotPins = Pins(after.Value).SetItem(id, pin with { ManifestHash = Hash(bytes) }) };
                if (await documents.CommitWorkspaceAsync(after, next, ct))
                {
                    Activity.Current?.SetTag("recovery.snapshot_documents", seen.Count);
                    Activity.Current?.SetTag("recovery.snapshot_bytes", totalBytes);
                    return manifest;
                }
            }
            await artifacts.DeleteAsync(key, ct);
        }
        throw new SnapshotException("SNAPSHOT_BUSY");
    }

    public async Task<SnapshotManifest> ManifestAsync(Guid member, Guid workspace, Guid epoch, Guid device, Guid id, CancellationToken ct)
    {
        var stored = await State(member, workspace, epoch, ct);
        var pin = Authorize(stored.Value, member, device, id, true);
        var bytes = await artifacts.GetAsync(Key(workspace, pin), -1, ct);
        if (Hash(bytes) != pin.ManifestHash) throw new SnapshotException("SNAPSHOT_CORRUPT");
        var manifest = JsonSerializer.Deserialize<SnapshotManifest>(bytes, SyncJson.Options) ?? throw new SnapshotException("SNAPSHOT_CORRUPT");
        if (manifest.SchemaVersion != 1) throw new SnapshotException("UNSUPPORTED_SNAPSHOT");
        if (manifest.SnapshotId != id || manifest.WorkspaceId != workspace || manifest.StateEpoch != epoch ||
            manifest.Revision != pin.Revision || manifest.ExpiresAt != pin.ExpiresAt) throw new SnapshotException("SNAPSHOT_CORRUPT");
        return manifest;
    }
    public async Task<byte[]> ChunkAsync(Guid member, Guid workspace, Guid epoch, Guid device, Guid id, int index, CancellationToken ct)
    {
        var manifest = await ManifestAsync(member, workspace, epoch, device, id, ct);
        if (index < 0 || index >= manifest.Chunks.Count) throw new SnapshotException("INVALID_CHUNK");
        var state = await State(member, workspace, epoch, ct);
        var pin = Authorize(state.Value, member, device, id, true);
        var bytes = await artifacts.GetAsync(Key(workspace, pin), index, ct);
        var chunk = manifest.Chunks[index];
        if (chunk.Index != index || bytes.Length != chunk.Bytes || Hash(bytes) != chunk.Digest) throw new SnapshotException("SNAPSHOT_CORRUPT");
        return bytes;
    }

    public async Task<int> PruneAsync(Guid member, Guid workspace, Guid epoch, CancellationToken ct)
    {
        var stored = await State(member, workspace, epoch, ct);
        var expired = Pins(stored.Value).Values.Where(x => x.ExpiresAt <= clock.GetUtcNow()).ToArray();
        if (expired.Length > 0)
        {
            var next = Advance(stored.Value) with { SnapshotPins = Pins(stored.Value).RemoveRange(expired.Select(x => x.Id)) };
            if (!await documents.CommitWorkspaceAsync(stored, next, ct)) return 0;
            foreach (var pin in expired) await artifacts.DeleteAsync(Key(workspace, pin), ct);
            stored = await State(member, workspace, epoch, ct);
        }
        if (Pins(stored.Value).Values.Any(x => x.ExpiresAt > clock.GetUtcNow())) return 0;
        var through = stored.Value.PrunedThrough;
        var deletes = new List<string>();
        var cutoff = clock.GetUtcNow().AddDays(-120);
        var partition = workspace.ToString("D");
        for (var count = 0; count < 32 && through < stored.Value.Revision; count++)
        {
            var groupId = WorkspaceCommit.GroupId(through + 1);
            var group = (await documents.ReadAsync<ChangeGroup>(partition, groupId, ct))?.Value;
            // Unknown-age legacy records are retained, never guessed old enough to remove.
            if (group?.RecordedAt is not { } at || at > cutoff || group.Revision != through + 1) break;
            if (group.OperationId is { } operation)
            {
                var receiptId = WorkspaceCommit.ReceiptId(operation);
                var receipt = (await documents.ReadAsync<OperationReceipt>(partition, receiptId, ct))?.Value;
                if (receipt?.RecordedAt is not { } received || received > cutoff) break;
                var parts = operation.Split(':');
                if (parts.Length != 2 || !Guid.TryParse(parts[0], out var deviceId) || !ulong.TryParse(parts[1], out var sequence))
                    throw new SnapshotException("SYNC_HISTORY_CORRUPT");
                var device = (await documents.ReadAsync<DeviceRegistration>(partition, WorkspaceCommit.DeviceId(deviceId), ct))?.Value;
                if (device is null || device.LastTerminalSequence < sequence) break;
                if (device.AcknowledgedThrough < sequence)
                {
                    var registration = device.RegistryPartition is { } registry && registrations is not null
                        ? await registrations.ReadAsync(registry, deviceId, ct) : null;
                    if (registration is null || !registration.Revoked && registration.ExpiresAt > clock.GetUtcNow()) break;
                }
                deletes.Add(receiptId);
            }
            else if (!group.Tasks.IsEmpty) break;
            deletes.Add(groupId); through++;
        }
        if (through == stored.Value.PrunedThrough) return 0;
        var changed = Advance(stored.Value) with { PrunedThrough = through };
        return await documents.CommitWorkspaceAsync(stored, changed, ct, deletes) ? deletes.Count : 0;
    }
}
