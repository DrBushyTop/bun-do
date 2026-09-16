using System.Diagnostics;
using System.Net;
using System.Text.Json;
using Azure.Core;
using BunDo.Domain;
using BunDo.Functions.Artwork;
using BunDo.Functions.AI;
namespace BunDo.Hosting.Tests;
public sealed class ArtworkTests
{
    private sealed class Store : IArtworkStore {
        public readonly Dictionary<string, StoredArtwork> Entries = []; public readonly Dictionary<string, byte[]> Images = []; private int version;
        public Task<StoredArtwork?> ReadAsync(string key, CancellationToken ct) { lock (Entries) return Task.FromResult(Entries.GetValueOrDefault(key)); }
        public Task<bool> WriteAsync(string key, string? expected, ArtworkEntry entry, CancellationToken ct) { lock (Entries) {
            if (Entries.GetValueOrDefault(key)?.Version != expected) return Task.FromResult(false);
            Entries[key] = new(entry, (++version).ToString()); return Task.FromResult(true); } }
        public Task PutImageAsync(string name, byte[] bytes, CancellationToken ct) { Images.Add(name, bytes); return Task.CompletedTask; }
        public Task<byte[]> ImageAsync(string name, CancellationToken ct) => Task.FromResult(Images[name]);
    }
    private sealed class Generator : IArtworkGenerator { public int Calls; public bool Fail; public Func<Task>? Wait;
        public async Task<byte[]> GenerateAsync(ArtworkBrief brief, CancellationToken ct) { Calls++; if (Wait != null) await Wait();
            if (Fail) throw new CleanupProviderException("Private canary"); return new byte[] { 0xff, 0xd8, 0xff, 0xd9 }; } }
    private sealed class Clock : TimeProvider { public DateTimeOffset Now = DateTimeOffset.UtcNow; public override DateTimeOffset GetUtcNow() => Now; }
    [Fact] public async Task Reuses_generic_artwork_and_fences_concurrent_workers_and_automatic_retries()
    {
        var store = new Store(); var generator = new Generator(); var catalog = new ArtworkCatalog(store, generator);
        var brief = ArtworkBrief.All[0]; await catalog.EnsureAsync(brief, false, default); await catalog.EnsureAsync(brief, false, default);
        var entered = new TaskCompletionSource(); var release = new TaskCompletionSource(); generator.Wait = async () => { entered.SetResult(); await release.Task; };
        var first = catalog.WorkAsync(default); await entered.Task; await catalog.WorkAsync(default); release.SetResult(); await first;
        Assert.Equal(1, generator.Calls); Assert.Equal("READY", (await catalog.EnsureAsync(brief, true, default)).Status);
        Assert.Single(store.Images); Assert.Equal(new byte[] { 0xff, 0xd8, 0xff, 0xd9 }, await catalog.ImageAsync(brief.Key, default));
        var serialized = JsonSerializer.Serialize(store.Entries); Assert.DoesNotContain("household", serialized, StringComparison.OrdinalIgnoreCase); Assert.DoesNotContain("Private", serialized);
        generator.Wait = null; generator.Fail = true; var next = ArtworkBrief.All[1]; await catalog.EnsureAsync(next, false, default); await catalog.WorkAsync(default);
        Assert.Equal("FAILED", (await catalog.EnsureAsync(next, false, default)).Status); await catalog.WorkAsync(default); Assert.Equal(2, generator.Calls);
        await catalog.EnsureAsync(next, true, default); generator.Fail = false; await catalog.WorkAsync(default); Assert.Equal(3, generator.Calls);
    }
    [Fact] public async Task Interrupted_attempt_requires_explicit_retry_and_late_image_cannot_replace_new_attempt()
    {
        var store = new Store(); var clock = new Clock(); var generator = new Generator(); var catalog = new ArtworkCatalog(store, generator, clock);
        var brief = ArtworkBrief.All[0]; await catalog.EnsureAsync(brief, false, default);
        var entered = new TaskCompletionSource(); var release = new TaskCompletionSource(); generator.Wait = async () => { entered.SetResult(); await release.Task; };
        var work = catalog.WorkAsync(default); await entered.Task; clock.Now = clock.Now.AddMinutes(6);
        Assert.Equal("FAILED", (await catalog.EnsureAsync(brief, false, default)).Status);
        await catalog.EnsureAsync(brief, true, default); release.SetResult(); await work;
        Assert.Empty(store.Images); Assert.Equal("PENDING", store.Entries[brief.Key].Entry.Status);
    }
    [Fact] public void Private_content_selects_only_a_fixed_generic_brief_and_assignments_are_stable()
    {
        var draft = new AdventureDraft("Private Alice papers at 42 Secret Street", "", [new("root", "Sort papers", 1, 15)]);
        var brief = ArtworkBrief.For(draft); var prompt = FoundryArtworkGenerator.Prompt(brief);
        Assert.DoesNotContain("Alice", prompt); Assert.DoesNotContain("Secret", prompt); Assert.Contains("sorting scrolls", prompt);
        var id = Guid.NewGuid(); var batch = Guid.NewGuid(); var now = DateTimeOffset.UtcNow;
        var board = new AdventureBoard(new(batch, "CONSUMED", now), new(id, batch, 3, draft, now));
        var assigned = HouseholdAdventure.AssignArtwork(board, batch, id, brief.Key, now)!;
        Assert.Equal(3UL, assigned.Active!.Version); Assert.Equal(brief.Key, assigned.Active.Artwork);
        Assert.Equal(assigned, HouseholdAdventure.AssignArtwork(assigned, batch, id, "other-key", now));
        Assert.Null(HouseholdAdventure.AssignArtwork(assigned, batch, Guid.NewGuid(), brief.Key, now));
    }
    [Fact] public async Task Image_provider_uses_reference_managed_identity_and_content_free_telemetry()
    {
        var handler = new Handler(); using var http = new HttpClient(handler); using var trace = new Activity("image").Start();
        var provider = new FoundryArtworkGenerator(http, new Credential(), new("https://example.openai.azure.com/openai/v1/"), "images", [1,2,3]);
        Assert.Equal(new byte[] { 0xff, 0xd8, 0xff, 0xd9 }, await provider.GenerateAsync(ArtworkBrief.All[0], default));
        Assert.Contains("images/edits?api-version=2025-04-01-preview", handler.Url); Assert.Contains("royal-bun-reference.png", handler.Body);
        Assert.Contains("input_fidelity", handler.Body); Assert.Contains("jpeg", handler.Body); Assert.DoesNotContain("webp", handler.Body); Assert.DoesNotContain("test-token", JsonSerializer.Serialize(trace.TagObjects));
    }
    private sealed class Credential : TokenCredential {
        public override AccessToken GetToken(TokenRequestContext context, CancellationToken ct) { Assert.Equal("https://cognitiveservices.azure.com/.default", context.Scopes.Single()); return new("test-token", DateTimeOffset.UtcNow.AddMinutes(5)); }
        public override ValueTask<AccessToken> GetTokenAsync(TokenRequestContext context, CancellationToken ct) => ValueTask.FromResult(GetToken(context, ct));
    }
    private sealed class Handler : HttpMessageHandler {
        public string? Url, Body;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            Url = request.RequestUri!.ToString(); Assert.Equal("Bearer test-token", request.Headers.Authorization!.ToString()); Body = await request.Content!.ReadAsStringAsync(ct);
            return new(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(new { data = new[] { new { b64_json = Convert.ToBase64String(new byte[] { 0xff, 0xd8, 0xff, 0xd9 }) } } })) };
        }
    }
}
