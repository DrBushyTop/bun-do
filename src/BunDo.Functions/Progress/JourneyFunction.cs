using System.Diagnostics;
using System.Text.Json;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using BunDo.Functions.Sync;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Functions.Progress;

public sealed class JourneyFunction(AccessTokens tokens, IServiceProvider services)
{
    [Function("Journey")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "api/journey")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var ct = request.HttpContext.RequestAborted;
        Activity.Current?.SetTag("operation.stage", "authentication");
        var headers = request.Headers.Authorization;
        if (headers.Count != 1 || headers[0] is not { } header ||
            !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || header[7..].Any(char.IsWhiteSpace))
            return Failure("UNAUTHORIZED", 401);
        var auth = await tokens.ValidateAsync(header[7..], ct);
        if (auth.Status != AuthenticationStatus.Accepted)
            return Failure(auth.Status.ToString(), auth.Status switch {
                AuthenticationStatus.MissingScope => 403, AuthenticationStatus.Unavailable => 503, _ => 401 });
        var registrations = services.GetService<IRegistrationStore>();
        var documents = services.GetService<IHouseholdDocuments>();
        if (registrations is null || documents is null) return Failure("UNAVAILABLE", 503);
        Activity.Current?.SetTag("operation.stage", "request");
        var bytes = new byte[1025];
        var length = 0;
        while (length < bytes.Length)
        {
            var read = await request.Body.ReadAsync(bytes.AsMemory(length), ct);
            if (read == 0) break;
            length += read;
        }
        if (length == bytes.Length) return Failure("REQUEST_TOO_LARGE", 413);
        string action;
        Guid workspace, epoch, registration;
        try
        {
            using var json = JsonDocument.Parse(bytes.AsMemory(0, length), new JsonDocumentOptions { MaxDepth = 2 });
            var root = json.RootElement;
            var names = root.EnumerateObject().Select(p => p.Name).ToArray();
            if (names.Length != 4 || names.Distinct().Count() != 4 ||
                names.Except(["action", "workspaceId", "stateEpoch", "registrationId"]).Any())
                return Failure("INVALID_REQUEST", 400);
            action = root.GetProperty("action").GetString() ?? "";
            workspace = root.GetProperty("workspaceId").GetGuid();
            epoch = root.GetProperty("stateEpoch").GetGuid();
            registration = root.GetProperty("registrationId").GetGuid();
            if (action is not ("read" or "enable") || workspace == Guid.Empty || epoch == Guid.Empty || registration == Guid.Empty)
                return Failure("INVALID_REQUEST", 400);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException)
        {
            return Failure("INVALID_REQUEST", 400);
        }
        Activity.Current?.SetTag("journey.action", action);
        Activity.Current?.SetTag("operation.stage", "registration");
        if (!await registrations.IsActiveAsync(auth.Identity!, registration, ct))
            return Failure("REGISTRATION_RETIRED", 403);
        try
        {
            var member = HouseholdIdentity.Member(auth.Identity!);
            Activity.Current?.SetTag("operation.stage", action);
            if (action == "enable") await new JourneyService(documents).EnableAsync(member, workspace, epoch, ct);
            var snapshot = await new ProgressService(documents).ReadAsync(member, workspace, epoch, ct);
            Activity.Current?.SetTag("journey.result", "ACCEPTED");
            Activity.Current?.SetTag("journey.enabled", snapshot.Journey is not null);
            return new ContentResult { ContentType = "application/json", StatusCode = 200,
                Content = JsonSerializer.Serialize(snapshot, SyncJson.Options) };
        }
        catch (SyncException error) { return Failure(error.Code, error.Code == "FORBIDDEN" ? 403 : error.Code == "BUSY" ? 503 : 409); }
        catch (HouseholdStorageFullException) { return Failure("STORAGE_FULL", 409); }
        catch (WorkspaceCommitTooLargeException) { return Failure("STORAGE_FULL", 409); }
    }

    private static ObjectResult Failure(string code, int status)
    {
        Activity.Current?.SetTag("journey.result", code);
        return new(new { code }) { StatusCode = status };
    }
}
