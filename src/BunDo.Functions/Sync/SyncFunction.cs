using System.Diagnostics;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Functions.Sync;

public sealed class SyncFunction(AccessTokens tokens, IServiceProvider services)
{
    [Function("Sync")]
    public async Task<IActionResult> Run([HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "api/sync")] HttpRequest request)
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
        if (registrations is null || documents is null) return new StatusCodeResult(503);
        var bytes = new byte[1024 * 1024 + 1];
        var count = 0;
        while (count < bytes.Length)
        {
            var read = await request.Body.ReadAsync(bytes.AsMemory(count), ct);
            if (read == 0) break;
            count += read;
        }
        if (count == bytes.Length) return new StatusCodeResult(413);
        var parsed = false;
        try
        {
            using var json = JsonDocument.Parse(bytes.AsMemory(0, count), new JsonDocumentOptions { MaxDepth = 4 });
            var root = json.RootElement;
            var names = root.EnumerateObject().Select(x => x.Name).ToArray();
            if (names.Distinct().Count() != names.Length ||
                names.Except(["workspaceId", "stateEpoch", "registrationId", "cursor", "envelopes", "acknowledgedThrough"]).Any())
                return Failure("INVALID_REQUEST", 400);
            var workspace = root.GetProperty("workspaceId").GetGuid();
            var epoch = root.GetProperty("stateEpoch").GetGuid();
            var registration = root.GetProperty("registrationId").GetGuid();
            var cursor = root.GetProperty("cursor").GetString();
            var values = root.GetProperty("envelopes");
            if (values.GetArrayLength() > 20) return Failure("BATCH_TOO_LARGE", 400);
            var acknowledged = root.TryGetProperty("acknowledgedThrough", out var ack) ? OperationEnvelope.Decimal(ack) : 0;
            var operations = values.EnumerateArray().Select(value =>
                OperationEnvelope.Parse(Convert.FromBase64String(value.GetString() ?? ""))).ToArray();
            if (operations.Any(operation => operation.WorkspaceId != workspace || operation.StateEpoch != epoch ||
                    operation.DeviceId != registration))
                return Failure("WRONG_SCOPE", 400);
            parsed = true;
            if (!await registrations.IsActiveAsync(auth.Identity!, registration, ct))
                return Failure("REGISTRATION_RETIRED", 403);
            var member = HouseholdIdentity.Member(auth.Identity!);
            var sync = new SyncService(documents);
            if (services.GetService<BunDo.Functions.Recovery.ISnapshotArtifacts>() is { } artifacts)
                await new BunDo.Functions.Recovery.SnapshotService(documents, artifacts, registrations)
                    .PruneAsync(member, workspace, epoch, ct);
            // Validate the cursor and membership before any submitted command can consume a sequence.
            await sync.PullAsync(member, workspace, epoch, cursor, [], "ACCEPTED", ct);
            await sync.AcknowledgeAsync(member, workspace, epoch, registration, acknowledged, ct);
            var receipts = new List<OperationReceipt>();
            var code = "ACCEPTED";
            foreach (var operation in operations)
            {
                // Base64 is transport framing only; the original decoded bytes define operation identity.
                var result = await sync.SubmitAsync(member, operation, ct, RegistrationIdentity.Partition(auth.Identity!));
                code = result.Code;
                if (result.Receipt is { } receipt) receipts.Add(receipt);
                else break;
            }
            await new BunDo.Functions.Repeats.RepeatWorker(documents).RunAsync(member, workspace, epoch, ct);
            var provider = services.GetService<BunDo.Functions.AI.ICleanupProvider>() ?? new BunDo.Functions.AI.UnavailableCleanupProvider();
            await new BunDo.Functions.AI.CleanupWorker(documents, provider).RunAsync(member, workspace, epoch, ct);
            var reply = await sync.PullAsync(member, workspace, epoch, cursor, receipts, code, ct);
            if (!reply.HasMore)
            {
                try { reply = reply with { Progress = await new BunDo.Functions.Progress.ProgressService(documents).ReadAsync(member, workspace, epoch, ct) }; }
                // Busy statistics must not prevent delivery of already-accepted task receipts.
                catch (SyncException error) when (error.Code == "BUSY") { }
            }
            Activity.Current?.SetTag("sync.result", code);
            Activity.Current?.SetTag("sync.receipts", receipts.Count);
            Activity.Current?.SetTag("sync.groups", reply.Groups.Count);
            return new ContentResult { ContentType = "application/json", StatusCode = 200,
                Content = SyncJson.SerializeReply(reply) };
        }
        catch (EnvelopeException error) { return Failure(error.Code, 400); }
        catch (SyncException error) { return Failure(error.Code, error.Code == "FORBIDDEN" ? 403 :
            error.Code is "BUSY" or "CHANGE_UNAVAILABLE" ? 503 : 409); }
        catch (BunDo.Functions.Recovery.SnapshotException error) { return Failure(error.Code, error.Code == "FORBIDDEN" ? 403 : 409); }
        catch (Exception error) when (error is JsonException or FormatException or InvalidOperationException or KeyNotFoundException)
        {
            // Storage errors are not malformed requests. Do not turn an ambiguous transaction into a terminal outcome.
            if (parsed && error is InvalidOperationException) throw;
            return Failure("INVALID_REQUEST", 400);
        }
    }

    private static ObjectResult Failure(string code, int status)
    {
        Activity.Current?.SetTag("sync.result", code);
        return new(new { code }) { StatusCode = status };
    }
}
