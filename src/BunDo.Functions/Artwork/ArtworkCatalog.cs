using System.Diagnostics;
using BunDo.Domain;

namespace BunDo.Functions.Artwork;

public sealed record ArtworkBrief(string Key, string Theme, string Setting, string Activity, string Style)
{
    public static readonly ArtworkBrief[] All = [
        new("dojo-v1-home", "home", "timber dojo courtyard", "preparing a quiet work session", "royal-bun-v1"),
        new("dojo-v1-storage", "storage", "dojo storeroom", "sorting scrolls into wooden shelves", "royal-bun-v1"),
        new("dojo-v1-garden", "garden", "dojo moss garden", "tending small potted plants", "royal-bun-v1"),
        new("dojo-v1-kitchen", "kitchen", "dojo kitchen", "putting ceramic cups away", "royal-bun-v1"),
        new("dojo-v1-clean", "cleaning", "dojo courtyard", "sweeping fallen leaves", "royal-bun-v1") ];
    public static ArtworkBrief? Find(string key) => All.SingleOrDefault(b => b.Key == key);
    public static ArtworkBrief For(AdventureDraft draft)
    {
        // Only this finite generic vocabulary crosses into shared metadata or an image prompt.
        var text = (draft.Title + " " + string.Join(" ", draft.Phases.Select(p => p.Name))).ToLowerInvariant();
        return All[text.Contains("garden") || text.Contains("balcon") || text.Contains("parvek") || text.Contains("puutar") ? 2 :
            text.Contains("kitchen") || text.Contains("keitti") || text.Contains("cook") || text.Contains("ruoka") ? 3 :
            text.Contains("clean") || text.Contains("siivo") || text.Contains("sweep") ? 4 :
            text.Contains("sort") || text.Contains("storage") || text.Contains("paper") || text.Contains("järjest") || text.Contains("nurk") ? 1 : 0];
    }
}
public sealed record ArtworkEntry(ArtworkBrief Brief, string Status, Guid Attempt, DateTimeOffset? LeaseUntil = null,
    string? Blob = null, string? Error = null);
public sealed record StoredArtwork(ArtworkEntry Entry, string Version);
public interface IArtworkStore
{
    Task<StoredArtwork?> ReadAsync(string key, CancellationToken ct);
    Task<bool> WriteAsync(string key, string? version, ArtworkEntry entry, CancellationToken ct);
    Task PutImageAsync(string name, byte[] bytes, CancellationToken ct);
    Task<byte[]> ImageAsync(string name, CancellationToken ct);
}
public interface IArtworkGenerator { Task<byte[]> GenerateAsync(ArtworkBrief brief, CancellationToken ct); }

/// <summary>A finite generic library shared across households. No household data enters its records.</summary>
public sealed class ArtworkCatalog(IArtworkStore store, IArtworkGenerator? generator = null, TimeProvider? time = null)
{
    private readonly TimeProvider clock = time ?? TimeProvider.System;
    public async Task<ArtworkEntry> EnsureAsync(ArtworkBrief brief, bool retry, CancellationToken ct)
    {
        for (var i = 0; i < 5; i++) {
            var saved = await store.ReadAsync(brief.Key, ct);
            var entry = saved?.Entry;
            if (entry?.Status == "RUNNING" && entry.LeaseUntil <= clock.GetUtcNow()) entry = entry with { Status = "FAILED", Error = "INTERRUPTED" };
            if (entry is not null && (!retry || entry.Status != "FAILED")) return entry;
            var next = new ArtworkEntry(brief, "PENDING", Guid.NewGuid());
            if (await store.WriteAsync(brief.Key, saved?.Version, next, ct)) return next;
        }
        throw new InvalidOperationException("Artwork contention");
    }
    public async Task WorkAsync(CancellationToken ct)
    {
        if (generator is null) return;
        foreach (var brief in ArtworkBrief.All) {
            var saved = await store.ReadAsync(brief.Key, ct);
            if (saved?.Entry.Status != "PENDING") continue;
            var owned = saved.Entry with { Status = "RUNNING", LeaseUntil = clock.GetUtcNow().AddMinutes(5) };
            if (!await store.WriteAsync(brief.Key, saved.Version, owned, ct)) continue;
            string? error = null; byte[]? image = null;
            Activity.Current?.SetTag("ai.mode", "ARTWORK");
            try { image = await generator.GenerateAsync(brief, ct); }
            catch (AI.CleanupProviderException e) { error = e.Code is "REFUSED" or "INVALID_OUTPUT" or "PROVIDER_TIMEOUT" ? e.Code : "PROVIDER_UNAVAILABLE"; }
            catch (HttpRequestException) { error = "PROVIDER_UNAVAILABLE"; }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { error = "PROVIDER_TIMEOUT"; }
            ct.ThrowIfCancellationRequested();
            var current = await store.ReadAsync(brief.Key, ct);
            if (current?.Entry.Attempt != owned.Attempt || current.Entry.Status != "RUNNING") return;
            if (owned.LeaseUntil <= clock.GetUtcNow()) error = "INTERRUPTED";
            var blob = $"{brief.Key}/{owned.Attempt:D}.jpg";
            if (error is null) await store.PutImageAsync(blob, image!, ct);
            await store.WriteAsync(brief.Key, current.Version, owned with { Status = error is null ? "READY" : "FAILED", Blob = error is null ? blob : null,
                LeaseUntil = null, Error = error }, ct);
            Activity.Current?.SetTag("ai.result", error ?? "READY");
            return; // Bound one invocation; other entries remain queued, without a paid automatic retry.
        }
    }
    public async Task<byte[]> ImageAsync(string key, CancellationToken ct)
    {
        if (ArtworkBrief.Find(key) is null) throw new Sync.SyncException("ARTWORK_UNAVAILABLE");
        var entry = await store.ReadAsync(key, ct);
        if (entry?.Entry is not { Status: "READY", Blob: not null } ready) throw new Sync.SyncException("ARTWORK_UNAVAILABLE");
        return await store.ImageAsync(ready.Blob, ct);
    }
}
