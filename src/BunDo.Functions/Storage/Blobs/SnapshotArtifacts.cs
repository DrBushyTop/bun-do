using Azure;
using Azure.Storage.Blobs;
using Azure.Storage.Blobs.Models;
using BunDo.Functions.Recovery;

namespace BunDo.Functions.Storage.Blobs;

public sealed class SnapshotArtifacts(BlobContainerClient container) : ISnapshotArtifacts
{
    public async Task PutAsync(ArtifactKey key, int index, byte[] content, CancellationToken ct)
    {
        if (content.Length > SnapshotService.MaximumChunkBytes) throw new ArgumentOutOfRangeException(nameof(content));
        try { await container.GetBlobClient(key.Name(index)).UploadAsync(BinaryData.FromBytes(content), overwrite: false, ct); }
        catch (RequestFailedException error) when (error.Status == 409)
        {
            if (!(await GetAsync(key, index, ct)).AsSpan().SequenceEqual(content))
                throw new InvalidOperationException("Snapshot artifact cannot change.");
        }
    }
    public async Task<byte[]> GetAsync(ArtifactKey key, int index, CancellationToken ct)
    {
        try
        {
            var response = await container.GetBlobClient(key.Name(index)).DownloadStreamingAsync(cancellationToken: ct);
            await using var stream = response.Value.Content;
            var length = response.Value.Details.ContentLength;
            if (length is < 0 or > SnapshotService.MaximumChunkBytes) throw new SnapshotException("SNAPSHOT_CORRUPT");
            var bytes = new byte[(int)length];
            await stream.ReadExactlyAsync(bytes, ct);
            return bytes;
        }
        catch (RequestFailedException error) when (error.Status == 404) { throw new SnapshotException("SNAPSHOT_EXPIRED"); }
    }
    public async Task DeleteAsync(ArtifactKey key, CancellationToken ct)
    {
        await foreach (var blob in container.GetBlobsAsync(BlobTraits.None, BlobStates.None, prefix: key.Prefix, cancellationToken: ct))
            await container.DeleteBlobIfExistsAsync(blob.Name, cancellationToken: ct);
    }
}
