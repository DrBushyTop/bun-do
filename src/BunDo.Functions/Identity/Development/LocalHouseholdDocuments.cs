using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Functions.Households;
using BunDo.Domain;
using BunDo.Functions.Sync;

namespace BunDo.Functions.Identity.Development;

/// <summary>Single-process development storage, outside the Functions source watcher.</summary>
public sealed class LocalHouseholdDocuments(string directory) : IHouseholdDocuments
{
    private readonly SemaphoreSlim gate = new(1);
    private string FileName(string partition, string id) => Path.Combine(directory,
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(partition + "/" + id))) + ".json");

    public async Task<StoredDocument<T>?> ReadAsync<T>(string partition, string id, CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try { return await ReadDocument<T>(partition, id, cancellationToken); }
        finally { gate.Release(); }
    }

    public async Task<bool> WriteAsync<T>(string partition, string id, string? version, T value, CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            var file = FileName(partition, id);
            var previous = await ReadDocument<T>(partition, id, cancellationToken);
            if (previous?.Version != version) return false;
            Directory.CreateDirectory(directory);
            var bytes = JsonSerializer.SerializeToUtf8Bytes(new StoredDocument<T>(value, Guid.NewGuid().ToString()));
            HouseholdDocumentLimits.CheckEncodedSize(value, bytes.Length);
            if (File.Exists(FileName(partition, "transaction")))
            {
                var records = await ReadTransaction(partition, cancellationToken);
                records[id] = JsonSerializer.Deserialize<JsonElement>(bytes);
                await ReplaceTransaction(partition, records, cancellationToken);
                return true;
            }
            await File.WriteAllBytesAsync(file + ".tmp", bytes, cancellationToken);
            File.Move(file + ".tmp", file, overwrite: true);
            return true;
        }
        finally { gate.Release(); }
    }

    public async Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected, WorkspaceState next,
        CancellationToken cancellationToken, IReadOnlyList<string>? deletes = null)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            var partition = next.WorkspaceId.ToString("D");
            var current = await ReadDocument<WorkspaceState>(partition, "state", cancellationToken);
            if (current?.Version != expected.Version) return false;
            var plan = WorkspaceCommit.Plan(expected.Value, next, deletes);
            var records = await ReadTransaction(partition, cancellationToken);
            foreach (var id in plan.Deletes)
                if (!records.Remove(id)) throw new InvalidOperationException("Missing maintenance item.");
            foreach (var write in plan.Writes)
            {
                if (write.CreateOnly && records.ContainsKey(write.Id)) return false;
                records[write.Id] = JsonSerializer.SerializeToElement(
                    new StoredDocument<object>(write.Value, Guid.NewGuid().ToString()));
            }
            records["state"] = JsonSerializer.SerializeToElement(
                new StoredDocument<WorkspaceState>(plan.Metadata, Guid.NewGuid().ToString()));
            await ReplaceTransaction(partition, records, cancellationToken);
            return true;
        }
        finally { gate.Release(); }
    }

    public async Task<DocumentPage<T>> ReadPageAsync<T>(string partition, string prefix, string? continuation,
        int limit, CancellationToken cancellationToken)
    {
        if (limit is < 1 or > 128) throw new ArgumentOutOfRangeException(nameof(limit));
        await gate.WaitAsync(cancellationToken);
        try
        {
            var records = await ReadTransaction(partition, cancellationToken);
            var selected = records.Where(x => x.Key.StartsWith(prefix, StringComparison.Ordinal) &&
                (continuation is null || string.CompareOrdinal(x.Key, continuation) > 0))
                .OrderBy(x => x.Key, StringComparer.Ordinal).Take(limit + 1).ToArray();
            var items = selected.Take(limit).Select(x => {
                var value = x.Value.Deserialize<StoredDocument<T>>()!;
                return new NamedDocument<T>(x.Key, value.Value, value.Version);
            }).ToArray();
            return new(items, selected.Length > limit ? items[^1].Id : null);
        }
        finally { gate.Release(); }
    }

    private async Task<StoredDocument<T>?> ReadDocument<T>(string partition, string id, CancellationToken ct)
    {
        var records = await ReadTransaction(partition, ct);
        return records.TryGetValue(id, out var record) ? record.Deserialize<StoredDocument<T>>() :
            await Read<T>(FileName(partition, id), ct);
    }

    private async Task<Dictionary<string, JsonElement>> ReadTransaction(string partition, CancellationToken ct)
    {
        var file = FileName(partition, "transaction");
        return File.Exists(file) ? JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(
            await File.ReadAllBytesAsync(file, ct))! : [];
    }

    private async Task ReplaceTransaction(string partition, Dictionary<string, JsonElement> records, CancellationToken ct)
    {
        Directory.CreateDirectory(directory);
        var file = FileName(partition, "transaction");
        await File.WriteAllBytesAsync(file + ".tmp", JsonSerializer.SerializeToUtf8Bytes(records), ct);
        File.Move(file + ".tmp", file, overwrite: true);
    }

    private static async Task<StoredDocument<T>?> Read<T>(string file, CancellationToken cancellationToken) =>
        File.Exists(file) ? JsonSerializer.Deserialize<StoredDocument<T>>(
            await File.ReadAllBytesAsync(file, cancellationToken)) : null;
}
