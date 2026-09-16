using System.Text.Json;
using Azure;
using Azure.Storage.Blobs;
using Azure.Storage.Blobs.Models;
namespace BunDo.Functions.Artwork;
public sealed class BlobArtworkStore(BlobContainerClient catalog, BlobContainerClient images) : IArtworkStore
{
    public async Task<StoredArtwork?> ReadAsync(string key, CancellationToken ct) {
        if (ArtworkBrief.Find(key) is null) throw new ArgumentException("Unknown artwork key");
        try {
            var reply = await catalog.GetBlobClient(key + ".json").DownloadContentAsync(ct);
            if (reply.Value.Content.ToMemory().Length > 4096) throw new InvalidDataException();
            return new(JsonSerializer.Deserialize<ArtworkEntry>(reply.Value.Content)! , reply.Value.Details.ETag.ToString());
        } catch (RequestFailedException e) when (e.Status == 404) { return null; }
    }
    public async Task<bool> WriteAsync(string key, string? version, ArtworkEntry entry, CancellationToken ct) {
        if (ArtworkBrief.Find(key) != entry.Brief) throw new ArgumentException("Unknown artwork brief");
        try {
            await catalog.GetBlobClient(key + ".json").UploadAsync(BinaryData.FromObjectAsJson(entry), new BlobUploadOptions {
                Conditions = version is null ? new BlobRequestConditions { IfNoneMatch = ETag.All } : new BlobRequestConditions { IfMatch = new ETag(version) },
                HttpHeaders = new BlobHttpHeaders { ContentType = "application/json", CacheControl = "no-store" }
            }, ct); return true;
        } catch (RequestFailedException e) when (e.Status is 409 or 412) { return false; }
    }
    public async Task PutImageAsync(string name, byte[] bytes, CancellationToken ct) {
        ValidateName(name); if (bytes.Length > 8 * 1024 * 1024) throw new InvalidDataException();
        await images.GetBlobClient(name).UploadAsync(new BinaryData(bytes), new BlobUploadOptions {
            Conditions = new BlobRequestConditions { IfNoneMatch = ETag.All }, HttpHeaders = new BlobHttpHeaders { ContentType = "image/jpeg", CacheControl = "private, max-age=31536000, immutable" }
        }, ct);
    }
    public async Task<byte[]> ImageAsync(string name, CancellationToken ct) {
        ValidateName(name); var blob = images.GetBlobClient(name); var properties = await blob.GetPropertiesAsync(cancellationToken: ct);
        if (properties.Value.ContentLength > 8 * 1024 * 1024) throw new InvalidDataException();
        return (await blob.DownloadContentAsync(ct)).Value.Content.ToArray();
    }
    private static void ValidateName(string name) {
        var parts = name.Split('/');
        if (parts.Length != 2 || ArtworkBrief.Find(parts[0]) is null || !parts[1].EndsWith(".jpg", StringComparison.Ordinal) ||
            !Guid.TryParseExact(parts[1][..^4], "D", out _)) throw new InvalidDataException();
    }
}
