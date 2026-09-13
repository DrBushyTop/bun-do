using BunDo.Functions.Recovery;

namespace BunDo.Functions.Identity.Development;

public sealed class LocalSnapshotArtifacts(string directory) : ISnapshotArtifacts
{
    public async Task PutAsync(ArtifactKey key, int index, byte[] content, CancellationToken ct)
    {
        if (content.Length > SnapshotService.MaximumChunkBytes) throw new ArgumentOutOfRangeException(nameof(content));
        var path = Path.Combine(directory, key.Name(index));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + "." + Guid.NewGuid().ToString("N");
        try
        {
            await File.WriteAllBytesAsync(temporary, content, ct);
            try { File.Move(temporary, path); }
            catch (IOException) when (File.Exists(path))
            {
                if (!(await File.ReadAllBytesAsync(path, ct)).AsSpan().SequenceEqual(content))
                    throw new InvalidOperationException("Snapshot artifact cannot change.");
            }
        }
        finally { File.Delete(temporary); }
    }
    public async Task<byte[]> GetAsync(ArtifactKey key, int index, CancellationToken ct)
    {
        var path = Path.Combine(directory, key.Name(index));
        if (!File.Exists(path)) throw new SnapshotException("SNAPSHOT_EXPIRED");
        if (new FileInfo(path).Length > SnapshotService.MaximumChunkBytes) throw new SnapshotException("SNAPSHOT_CORRUPT");
        return await File.ReadAllBytesAsync(path, ct);
    }
    public Task DeleteAsync(ArtifactKey key, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();
        var path = Path.Combine(directory, key.Prefix);
        if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
        return Task.CompletedTask;
    }
}
