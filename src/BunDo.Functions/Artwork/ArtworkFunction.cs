using System.Diagnostics;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Adventures;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using BunDo.Functions.Sync;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;
namespace BunDo.Functions.Artwork;

public sealed class ArtworkFunction(AccessTokens tokens, IServiceProvider services)
{
    [Function("AdventureArtwork")]
    public async Task<IActionResult> Run([HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "api/adventure-artwork")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var ct = request.HttpContext.RequestAborted; var header = request.Headers.Authorization;
        if (header.Count != 1 || header[0] is not { } bearer || !bearer.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || bearer[7..].Any(char.IsWhiteSpace)) return Failure("UNAUTHORIZED", 401);
        var auth = await tokens.ValidateAsync(bearer[7..], ct);
        if (auth.Status != AuthenticationStatus.Accepted) return Failure(auth.Status.ToString(),
            auth.Status == AuthenticationStatus.MissingScope ? 403 : auth.Status == AuthenticationStatus.Unavailable ? 503 : 401);
        var registrations = services.GetService<IRegistrationStore>(); var documents = services.GetService<IHouseholdDocuments>();
        var catalog = services.GetService<ArtworkCatalog>();
        if (registrations is null || documents is null || catalog is null) return Failure("UNAVAILABLE", 503);
        var bytes = new byte[2049]; var count = 0;
        while (count < bytes.Length) { var n = await request.Body.ReadAsync(bytes.AsMemory(count), ct); if (n == 0) break; count += n; }
        if (count == bytes.Length) return Failure("REQUEST_TOO_LARGE", 413);
        Guid workspace, epoch, registration, batch, choice; string action;
        try {
            using var json = JsonDocument.Parse(bytes.AsMemory(0, count)); var root = json.RootElement;
            FoundryAdventureProvider.Fields(root, ["workspaceId", "stateEpoch", "registrationId", "batchId", "choiceId", "action"]);
            Guid Id(string key) => root.GetProperty(key).GetGuid() is var id && id != Guid.Empty ? id : throw new JsonException();
            workspace = Id("workspaceId"); epoch = Id("stateEpoch"); registration = Id("registrationId"); batch = Id("batchId"); choice = Id("choiceId");
            action = root.GetProperty("action").GetString()!; if (action is not ("read" or "retry" or "image")) throw new JsonException();
        } catch (Exception e) when (e is JsonException or InvalidOperationException or FormatException or KeyNotFoundException) { return Failure("INVALID_REQUEST", 400); }
        try {
            var member = HouseholdIdentity.Member(auth.Identity!);
            var service = new AdventureService(documents, registrationActive: token => registrations.IsActiveAsync(auth.Identity!, registration, token));
            var snapshot = await service.ReadAsync(member, workspace, epoch, ct);
            var selected = Select(snapshot, batch, choice);
            if (action == "image") {
                if (ArtworkBrief.Find(selected.Artwork) is null) return Failure("ARTWORK_UNAVAILABLE", 409);
                var image = await catalog.ImageAsync(selected.Artwork, ct);
                Select(await service.ReadAsync(member, workspace, epoch, ct), batch, choice);
                return new FileContentResult(image, "image/jpeg");
            }
            var brief = ArtworkBrief.Find(selected.Artwork) ?? ArtworkBrief.For(selected.Draft);
            var entry = await catalog.EnsureAsync(brief, action == "retry", ct);
            Activity.Current?.SetTag("artwork.status", entry.Status); Activity.Current?.SetTag("artwork.theme", brief.Theme);
            if (entry.Status == "READY") await service.AssignArtworkAsync(member, workspace, epoch, batch, choice, brief.Key, ct);
            snapshot = await service.ReadAsync(member, workspace, epoch, ct); selected = Select(snapshot, batch, choice);
            return new ContentResult { StatusCode = 200, ContentType = "application/json", Content = JsonSerializer.Serialize(new {
                status = entry.Status, key = selected.Artwork, snapshot
            }, SyncJson.Options) };
        } catch (SyncException e) { return Failure(e.Code, e.Code is "FORBIDDEN" or "REGISTRATION_RETIRED" ? 403 : 409); }
    }
    private static AdventureProposal Select(AdventureSnapshot snapshot, Guid batch, Guid choice) {
        if (snapshot.Board.Active is { } active && active.BatchId == batch && active.Id == choice) return new(active.Id, active.Draft, active.Artwork);
        if (snapshot.Board.Active is null && snapshot.Board.Batch is { Status: "READY" } proposals && proposals.Id == batch &&
            proposals.ExpiresAt > DateTimeOffset.UtcNow &&
            proposals.Proposals?.SingleOrDefault(p => p.Id == choice) is { } selected) return selected;
        throw new SyncException("ADVENTURE_CHANGED");
    }
    private static ObjectResult Failure(string code, int status) { Activity.Current?.SetTag("artwork.result", code); return new(new { code }) { StatusCode = status }; }
}
public sealed class ArtworkWorker(IServiceProvider services)
{
    [Function("GenerateAdventureArtwork")]
    public async Task Run([TimerTrigger("0 * * * * *")] TimerInfo timer, CancellationToken ct)
    {
        var catalog = services.GetService<ArtworkCatalog>();
        if (catalog is not null) await catalog.WorkAsync(ct);
    }
}
