using System.Diagnostics;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using BunDo.Functions.Sync;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Functions.Adventures;

public sealed class ListLibraryFunction(AccessTokens tokens, IServiceProvider services)
{
    [Function("ListLibrary")]
    public async Task<IActionResult> Run([HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "api/lists")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var ct = request.HttpContext.RequestAborted;
        var headers = request.Headers.Authorization;
        if (headers.Count != 1 || headers[0] is not { } header ||
            !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || header[7..].Any(char.IsWhiteSpace)) return Failure("UNAUTHORIZED", 401);
        var auth = await tokens.ValidateAsync(header[7..], ct);
        if (auth.Status != AuthenticationStatus.Accepted) return Failure(auth.Status.ToString(),
            auth.Status == AuthenticationStatus.Unavailable ? 503 : auth.Status == AuthenticationStatus.MissingScope ? 403 : 401);
        var registrations = services.GetService<IRegistrationStore>();
        var documents = services.GetService<IHouseholdDocuments>();
        if (registrations is null || documents is null) return Failure("UNAVAILABLE", 503);
        var bytes = new byte[64 * 1024 + 1];
        var length = 0;
        while (length < bytes.Length)
        {
            var read = await request.Body.ReadAsync(bytes.AsMemory(length), ct);
            if (read == 0) break;
            length += read;
        }
        if (length == bytes.Length) return Failure("REQUEST_TOO_LARGE", 413);
        Guid workspace, epoch, registration;
        SaveList? command;
        try
        {
            using var json = JsonDocument.Parse(bytes.AsMemory(0, length), new JsonDocumentOptions { MaxDepth = 8 });
            var root = json.RootElement;
            var action = root.GetProperty("action").GetString();
            if (action is not "read" and not "save") throw new JsonException();
            FoundryAdventureProvider.Fields(root, action == "save" ? ["action", "workspaceId", "stateEpoch", "registrationId", "command"] : ["action", "workspaceId", "stateEpoch", "registrationId"]);
            workspace = root.GetProperty("workspaceId").GetGuid();
            epoch = root.GetProperty("stateEpoch").GetGuid();
            registration = root.GetProperty("registrationId").GetGuid();
            if (workspace == Guid.Empty || epoch == Guid.Empty || registration == Guid.Empty) throw new JsonException();
            if (action == "save")
            {
                var change = root.GetProperty("command");
                FoundryAdventureProvider.Fields(change, ["operationId", "expectedVersion", "id", "value"]);
                var value = change.GetProperty("value");
                if (value.ValueKind != JsonValueKind.Null)
                {
                    FoundryAdventureProvider.Fields(value, ["id", "title", "notes", "items", "pinned"]);
                    foreach (var item in value.GetProperty("items").EnumerateArray()) FoundryAdventureProvider.Fields(item, ["title", "notes"]);
                }
            }
            command = action == "save" ? root.GetProperty("command").Deserialize<SaveList>(SyncJson.Options) ?? throw new JsonException() : null;
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or OverflowException)
        { return Failure("INVALID_REQUEST", 400); }
        try
        {
            Activity.Current?.SetTag("operation.stage", "list_library");
            var service = new ListLibraryService(documents, token => registrations.IsActiveAsync(auth.Identity!, registration, token));
            var snapshot = await service.SendAsync(HouseholdIdentity.Member(auth.Identity!), workspace, epoch, command, ct);
            Activity.Current?.SetTag("lists.result", "ACCEPTED");
            return new ContentResult { StatusCode = 200, ContentType = "application/json", Content = JsonSerializer.Serialize(snapshot, SyncJson.Options) };
        }
        catch (SyncException error) { return Failure(error.Code, error.Code is "FORBIDDEN" or "REGISTRATION_RETIRED" ? 403 : error.Code == "BUSY" ? 503 : 409); }
        catch (HouseholdStorageFullException) { return Failure("STORAGE_FULL", 409); }
        catch (WorkspaceCommitTooLargeException) { return Failure("STORAGE_FULL", 409); }
    }
    private static ObjectResult Failure(string code, int status)
    {
        Activity.Current?.SetTag("lists.result", code);
        return new(new { code }) { StatusCode = status };
    }
}
