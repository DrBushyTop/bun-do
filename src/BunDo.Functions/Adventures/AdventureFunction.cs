using System.Diagnostics;
using System.Globalization;
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

public sealed class AdventureFunction(AccessTokens tokens, IServiceProvider services)
{
    [Function("Adventure")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "api/adventure")] HttpRequest request)
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
        var bytes = new byte[16 * 1024 + 1];
        var length = 0;
        while (length < bytes.Length)
        {
            var read = await request.Body.ReadAsync(bytes.AsMemory(length), ct);
            if (read == 0) break;
            length += read;
        }
        if (length == bytes.Length) return Failure("REQUEST_TOO_LARGE", 413);
        string action;
        Guid workspace, epoch, registration, id = default, proposal = default;
        Guid? batch = null;
        ulong version = 0;
        bool confirmed = false;
        AdventureDraft? draft = null;
        GuidedDraft? plan = null;
        string? language = null; string? outcome = null; int? minutes = null; string[] roots = [];
        try
        {
            using var json = JsonDocument.Parse(bytes.AsMemory(0, length), new JsonDocumentOptions { MaxDepth = 8 });
            var root = json.RootElement;
            action = root.GetProperty("action").GetString() ?? "";
            string[] fields = action switch {
                "ideas" => ["language"], "plan" => ["outcome", "minutes"], "beginCreation" => ["creationId", "version", "draft"],
                "finishCreation" => ["creationId", "roots"], "cancelCreation" => ["creationId", "confirmed"],
                "read" => [], "refresh" or "retry" => ["batchId"], "accept" => ["batchId", "proposalId"],
                "edit" => ["adventureId", "version", "draft"], "leave" => ["adventureId", "version", "confirmed"],
                "dismiss" => ["adventureId", "version"], _ => throw new JsonException(),
            };
            FoundryAdventureProvider.Fields(root, ["action", "workspaceId", "stateEpoch", "registrationId", ..fields]);
            workspace = Id(root, "workspaceId"); epoch = Id(root, "stateEpoch"); registration = Id(root, "registrationId");
            if (action is "refresh" or "retry" or "accept")
                batch = root.GetProperty("batchId").ValueKind == JsonValueKind.Null && action != "accept" ? null : Id(root, "batchId");
            if (action == "accept") proposal = Id(root, "proposalId");
            if (action is "beginCreation" or "finishCreation" or "cancelCreation") id = Id(root, "creationId");
            if (action is "edit" or "leave" or "dismiss" or "beginCreation")
            {
                if (action != "beginCreation") id = Id(root, "adventureId");
                var text = root.GetProperty("version").GetString();
                if (!ulong.TryParse(text, NumberStyles.None, CultureInfo.InvariantCulture, out version) ||
                    text != version.ToString(CultureInfo.InvariantCulture)) throw new JsonException();
            }
            if (action is "leave" or "cancelCreation") confirmed = root.GetProperty("confirmed").GetBoolean();
            if (action == "ideas") language = root.GetProperty("language").GetString() ?? throw new JsonException();
            if (action == "plan") {
                outcome = root.GetProperty("outcome").GetString();
                minutes = root.GetProperty("minutes").ValueKind == JsonValueKind.Null ? null : root.GetProperty("minutes").GetInt32();
                if (outcome is null) throw new JsonException();
            }
            if (action == "beginCreation") {
                plan = FoundryAdventurePlanner.ParseDraft(root.GetProperty("draft"));
                if (!GuidedAdventure.Valid(plan)) throw new JsonException();
            }
            if (action == "finishCreation") roots = root.GetProperty("roots").EnumerateArray().Select(r => r.GetString() ?? throw new JsonException()).ToArray();
            if (action == "edit")
            {
                draft = FoundryAdventureProvider.ParseDraft(root.GetProperty("draft"));
                if (!HouseholdAdventure.Valid(draft, allowEmpty: true)) throw new JsonException();
            }
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or FormatException or KeyNotFoundException or OverflowException)
        { return Failure("INVALID_REQUEST", 400); }
        Activity.Current?.SetTag("adventure.action", action);
        Activity.Current?.SetTag("operation.stage", "registration");
        if (!await registrations.IsActiveAsync(auth.Identity!, registration, ct)) return Failure("REGISTRATION_RETIRED", 403);
        try
        {
            var member = HouseholdIdentity.Member(auth.Identity!);
            var service = new AdventureService(documents, services.GetService<IAdventureProvider>(),
                registrationActive: token => registrations.IsActiveAsync(auth.Identity!, registration, token),
                planner: services.GetService<IAdventurePlanner>(), ideasProvider: services.GetService<IAdventureIdeasProvider>());
            Activity.Current?.SetTag("operation.stage", action);
            GuidedDraft? generated = null;
            string[]? ideas = null;
            switch (action)
            {
                case "ideas": ideas = await service.IdeasAsync(member, workspace, epoch, language!, ct); break;
                case "plan": generated = await service.PlanAsync(member, workspace, epoch, outcome!, minutes, ct); break;
                case "beginCreation": await service.BeginCreationAsync(member, workspace, epoch, registration, id, version, plan!, ct); break;
                case "finishCreation": await service.FinishCreationAsync(member, workspace, epoch, registration, id, roots, ct); break;
                case "cancelCreation": await service.CancelCreationAsync(member, workspace, epoch, id, confirmed, ct); break;
                case "refresh": case "retry": await service.RefreshAsync(member, workspace, epoch, batch, action == "retry", ct); break;
                case "accept": await service.AcceptAsync(member, workspace, epoch, batch!.Value, proposal, ct); break;
                case "edit": await service.EditAsync(member, workspace, epoch, id, version, draft!, ct); break;
                case "leave": case "dismiss": await service.CloseAsync(member, workspace, epoch, id, version, action == "leave", confirmed, ct); break;
            }
            var snapshot = await service.ReadAsync(member, workspace, epoch, ct);
            Activity.Current?.SetTag("adventure.result", "ACCEPTED");
            Activity.Current?.SetTag("adventure.batch_status", snapshot.Board.Batch?.Status ?? "NONE");
            Activity.Current?.SetTag("adventure.active", snapshot.Board.Active is not null);
            return new ContentResult { ContentType = "application/json", StatusCode = 200,
                Content = ideas is not null ? JsonSerializer.Serialize(new { snapshot, ideas }, SyncJson.Options) : generated is null ? JsonSerializer.Serialize(snapshot, SyncJson.Options) :
                    JsonSerializer.Serialize(new { snapshot, draft = generated }, SyncJson.Options) };
        }
        catch (SyncException error) { return Failure(error.Code, error.Code is "FORBIDDEN" or "REGISTRATION_RETIRED" ? 403 : error.Code == "BUSY" ? 503 : 409); }
        catch (HouseholdStorageFullException) { return Failure("STORAGE_FULL", 409); }
        catch (WorkspaceCommitTooLargeException) { return Failure("STORAGE_FULL", 409); }
    }

    private static Guid Id(JsonElement root, string name) => root.GetProperty(name).GetGuid() is var id && id != Guid.Empty ? id : throw new JsonException();
    private static ObjectResult Failure(string code, int status)
    {
        Activity.Current?.SetTag("adventure.result", code);
        return new(new { code }) { StatusCode = status };
    }
}
