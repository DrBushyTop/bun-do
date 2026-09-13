using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Functions.Households;

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
        try { return await Read<T>(FileName(partition, id), cancellationToken); }
        finally { gate.Release(); }
    }

    public async Task<bool> WriteAsync<T>(string partition, string id, string? version, T value, CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            var file = FileName(partition, id);
            var previous = await Read<T>(file, cancellationToken);
            if (previous?.Version != version) return false;
            Directory.CreateDirectory(directory);
            var bytes = JsonSerializer.SerializeToUtf8Bytes(new StoredDocument<T>(value, Guid.NewGuid().ToString()));
            HouseholdDocumentLimits.CheckEncodedSize(value, bytes.Length);
            await File.WriteAllBytesAsync(file + ".tmp", bytes, cancellationToken);
            File.Move(file + ".tmp", file, overwrite: true);
            return true;
        }
        finally { gate.Release(); }
    }

    private static async Task<StoredDocument<T>?> Read<T>(string file, CancellationToken cancellationToken) =>
        File.Exists(file) ? JsonSerializer.Deserialize<StoredDocument<T>>(
            await File.ReadAllBytesAsync(file, cancellationToken)) : null;
}
