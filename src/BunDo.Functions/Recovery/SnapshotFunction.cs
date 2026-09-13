using System.Diagnostics;
using System.Globalization;
using System.Text.Json;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using BunDo.Functions.Sync;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Functions.Recovery;

public sealed class SnapshotFunction(AccessTokens tokens, IServiceProvider services)
{
    [Function("Snapshot")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Anonymous, "get", "post", Route = "api/snapshots/{snapshotId:guid}/{part}")]
        HttpRequest request, Guid snapshotId, string part)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var ct = request.HttpContext.RequestAborted;
        var headers = request.Headers.Authorization;
        if (headers.Count != 1 || headers[0] is not { } header ||
            !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || header[7..].Any(char.IsWhiteSpace))
            return new UnauthorizedResult();
        var auth = await tokens.ValidateAsync(header[7..], ct);
        if (auth.Status != AuthenticationStatus.Accepted)
            return new StatusCodeResult(auth.Status switch {
                AuthenticationStatus.MissingScope => 403, AuthenticationStatus.Unavailable => 503, _ => 401 });
        var registrations = services.GetService<IRegistrationStore>();
        var documents = services.GetService<IHouseholdDocuments>();
        var artifacts = services.GetService<ISnapshotArtifacts>();
        if (registrations is null || documents is null || artifacts is null) return new StatusCodeResult(503);
        var query = request.Query;
        if (query.Count != 3 || query.Any(x => x.Value.Count != 1) || snapshotId == Guid.Empty ||
            !Guid.TryParseExact(query["workspaceId"], "D", out var workspace) ||
            !Guid.TryParseExact(query["stateEpoch"], "D", out var epoch) ||
            !Guid.TryParseExact(query["registrationId"], "D", out var registration) ||
            request.Method == "POST" && part != "manifest")
            return Failure("INVALID_SNAPSHOT", 400);
        if (!await registrations.IsActiveAsync(auth.Identity!, registration, ct))
            return Failure("REGISTRATION_RETIRED", 403);
        try
        {
            var module = new SnapshotService(documents, artifacts, registrations);
            var member = HouseholdIdentity.Member(auth.Identity!);
            if (part == "manifest")
            {
                var manifest = request.Method == "POST"
                    ? await module.CreateAsync(member, workspace, epoch, registration, snapshotId, ct)
                    : await module.ManifestAsync(member, workspace, epoch, registration, snapshotId, ct);
                Activity.Current?.SetTag("recovery.result", "SNAPSHOT_READY");
                return new ContentResult { ContentType = "application/json", StatusCode = 200,
                    Content = JsonSerializer.Serialize(manifest, SyncJson.Options) };
            }
            if (!int.TryParse(part, NumberStyles.None, CultureInfo.InvariantCulture, out var index))
                return Failure("INVALID_CHUNK", 400);
            var bytes = await module.ChunkAsync(member, workspace, epoch, registration, snapshotId, index, ct);
            Activity.Current?.SetTag("recovery.result", "CHUNK_READ");
            return new FileContentResult(bytes, "application/json");
        }
        catch (SnapshotException error)
        {
            return Failure(error.Code, error.Code == "FORBIDDEN" ? 403 : error.Code == "SNAPSHOT_BUSY" ? 503 : 409);
        }
    }
    private static ObjectResult Failure(string code, int status)
    {
        Activity.Current?.SetTag("recovery.result", code);
        return new(new { code }) { StatusCode = status };
    }
}
