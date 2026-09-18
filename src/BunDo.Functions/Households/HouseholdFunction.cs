using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;
using BunDo.Domain;
using BunDo.Functions.Sync;
using BunDo.Functions.Identity;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Functions.Households;

public sealed class HouseholdFunction(AccessTokens tokens, IServiceProvider services)
{
    [Function("Households")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "api/households")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var ct = request.HttpContext.RequestAborted;
        var headers = request.Headers.Authorization;
        if (headers.Count != 1 || headers[0] is not { } header ||
            !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || header[7..].Any(char.IsWhiteSpace))
            return new UnauthorizedResult();
        var authentication = await tokens.ValidateAsync(header[7..], ct);
        if (authentication.Status != AuthenticationStatus.Accepted)
            return new StatusCodeResult(authentication.Status switch {
                AuthenticationStatus.MissingScope => 403, AuthenticationStatus.Unavailable => 503, _ => 401 });
        var registrations = services.GetService<IRegistrationStore>();
        var households = services.GetService<HouseholdService>();
        if (registrations is null || households is null) return new StatusCodeResult(503);
        var bytes = new byte[4097];
        var length = 0;
        while (length < bytes.Length)
        {
            var read = await request.Body.ReadAsync(bytes.AsMemory(length), ct);
            if (read == 0) break;
            length += read;
        }
        if (length > 4096) return new StatusCodeResult(413);
        HouseholdRequest body;
        try
        {
            body = JsonSerializer.Deserialize<HouseholdRequest>(bytes.AsSpan(0, length),
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true, MaxDepth = 4 })
                ?? throw new JsonException();
        }
        catch (JsonException) { return new BadRequestResult(); }
        var action = body.Action;
        if (body.RegistrationId == Guid.Empty ||
            action is not ("list" or "personal" or "visibilityPending" or "visibilityCancel" or "visibility" or "create" or "get" or "invite" or "redeem" or "approve" or "cancel" or "remove" or "leave" or "transfer" or "delete") ||
            action is not ("list" or "personal" or "visibilityPending" or "visibilityCancel") && body.WorkspaceId == Guid.Empty ||
            action is not ("list" or "personal" or "visibilityPending" or "visibilityCancel" or "visibility" or "create" or "get") && body.StateEpoch == Guid.Empty ||
            action is ("redeem" or "approve" or "cancel") && body.InvitationId == Guid.Empty ||
            action is ("remove" or "transfer") && body.MemberId == Guid.Empty)
            return new BadRequestResult();
        if (action is ("create" or "redeem") && (string.IsNullOrWhiteSpace(body.DisplayName) ||
            body.DisplayName.Length > 60 || body.DisplayName.Any(char.IsControl))) return new BadRequestResult();
        try
        {
            var registration = body.RegistrationId;
            if (!await registrations.IsActiveAsync(authentication.Identity!, registration, ct))
                return new ObjectResult(new { code = "REGISTRATION_RETIRED" }) { StatusCode = 403 };
            var actor = HouseholdIdentity.Member(authentication.Identity!);
            if (action == "list")
                return new OkObjectResult(new { households = await households.ListAsync(actor, ct) });
            if (action == "personal") return new OkObjectResult(await households.PersonalAsync(actor, ct));
            if (action is "visibility" or "visibilityPending" or "visibilityCancel")
            {
                var documents = services.GetService<IHouseholdDocuments>();
                if (documents is null) return new StatusCodeResult(503);
                var visibility = new TaskVisibilityService(documents);
                if (action == "visibilityCancel") return new OkObjectResult(await visibility.CancelAsync(actor, body.TransferId, ct));
                if (action == "visibilityPending") return new OkObjectResult(new { pending = await visibility.PendingAsync(actor, ct) });
                var result = await visibility.SendAsync(actor, new(body.TransferId, body.WorkspaceId, body.StateEpoch,
                    body.TaskId ?? "", body.ExpectedRevision, body.TargetWorkspaceId, body.TargetEpoch), ct);
                Activity.Current?.SetTag("visibility.result", result.Code);
                return new OkObjectResult(result);
            }
            var workspace = body.WorkspaceId;
            if (workspace == Guid.Empty) return new BadRequestResult();
            HouseholdReply reply;
            if (action == "create") reply = await households.CreateAsync(actor, workspace, ct, body.Name ?? "", body.DisplayName!.Trim());
            else if (action == "get")
            {
                var view = await households.GetAsync(actor, workspace, ct);
                reply = new(view is null ? "FORBIDDEN" : "ACCEPTED", view);
            }
            else
            {
                var epoch = body.StateEpoch;
                if (action == "invite") reply = await households.IssueAsync(actor, workspace, epoch, ct);
                else if (action == "redeem") reply = await households.RedeemAsync(actor, workspace, epoch,
                    body.InvitationId, body.Secret ?? "", ct, body.DisplayName!.Trim());
                else
                {
                    var version = body.ExpectedVersion;
                    MembershipCommand? command = action switch {
                        "approve" => new ApproveHouseholdInvitation(body.InvitationId, version,
                            body.ConfirmationCode ?? ""),
                        "cancel" => new CancelHouseholdInvitation(body.InvitationId, version),
                        "remove" => new RemoveHouseholdMember(body.MemberId, version),
                        "leave" => new LeaveHousehold(version),
                        "transfer" => new TransferHouseholdOwnership(body.MemberId, version),
                        "delete" => new DeleteHousehold(version),
                        _ => null,
                    };
                    if (command is null) return new BadRequestResult();
                    reply = await households.ChangeAsync(actor, workspace, epoch, command, ct);
                }
            }
            Activity.Current?.SetTag("household.result", reply.Code);
            return new ObjectResult(reply) { StatusCode = reply.Code switch {
                "ACCEPTED" => 200, "FORBIDDEN" => 403, "REDEMPTION_LIMIT" => 429, "BUSY" => 503, _ => 409 } };
        }
        catch (SyncException error) { return new ObjectResult(new { code = error.Code }) { StatusCode = 409 }; }
        catch (WorkspaceCommitTooLargeException) { return new ObjectResult(new { code = "STORAGE_FULL" }) { StatusCode = 409 }; }
        catch (HouseholdStorageFullException) { return new ObjectResult(new { code = "STORAGE_FULL" }) { StatusCode = 409 }; }
    }
}

public sealed record HouseholdRequest(string? Action, Guid RegistrationId, Guid WorkspaceId, Guid StateEpoch,
    Guid InvitationId, Guid MemberId, [property: JsonNumberHandling(JsonNumberHandling.AllowReadingFromString)] ulong ExpectedVersion, string? Secret, string? ConfirmationCode, string? Name, string? DisplayName, Guid TransferId = default, string? TaskId = null, Guid TargetWorkspaceId = default, Guid TargetEpoch = default,
    [property: JsonNumberHandling(JsonNumberHandling.AllowReadingFromString)] ulong ExpectedRevision = 0);
