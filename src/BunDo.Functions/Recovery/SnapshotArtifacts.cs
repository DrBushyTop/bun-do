namespace BunDo.Functions.Recovery;

public sealed record ArtifactKey(Guid Workspace, Guid Snapshot, Guid Candidate)
{
    public string Prefix => $"{Workspace:D}/{Snapshot:D}/{Candidate:D}/";
    public string Name(int index)
    {
        if (index is < -1 or > 4095) throw new ArgumentOutOfRangeException(nameof(index));
        return Prefix + (index < 0 ? "manifest.json" : $"{index:D4}.json");
    }
}

/// <summary>Private immutable bytes. Authorization, expiry and publication belong to SnapshotService.</summary>
public interface ISnapshotArtifacts
{
    Task PutAsync(ArtifactKey key, int index, byte[] content, CancellationToken ct);
    Task<byte[]> GetAsync(ArtifactKey key, int index, CancellationToken ct);
    Task DeleteAsync(ArtifactKey key, CancellationToken ct);
}
