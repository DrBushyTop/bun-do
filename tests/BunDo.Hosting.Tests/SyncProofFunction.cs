using System.Collections.Immutable;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Sync;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Cosmos;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.Configuration;

namespace BunDo.Hosting.Tests;

/// <summary>
/// Temporary live-test entrypoint, not part of the production Function project.
/// Copy into a private verification build, invoke with a Function key, clean the run, then deploy the ordinary build.
/// Each run owns a random partition. No existing household can be adopted as a fixture.
/// </summary>
public sealed class SyncProofFunction(CosmosClient client, IConfiguration configuration)
{
    private sealed record Document<T>(string id, string workspaceId, int SchemaVersion, T Value);
    private Container Container => client.GetContainer(configuration["WorkspaceStore:DatabaseName"]!,
        configuration["WorkspaceStore:ContainerName"]!);
    private static Guid Identity(Guid run, string name) =>
        new(SHA256.HashData(Encoding.UTF8.GetBytes($"{run:D}/{name}")).AsSpan(0, 16));

    [Function("SyncVerification")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "verification/sync/{run}/{action}/{slot:int}")] HttpRequest request,
        string run, string action, int slot)
    {
        if (!Guid.TryParseExact(run, "D", out var workspace) || workspace == Guid.Empty || workspace.ToString("D") != run ||
            slot is < 0 or > 31 || action is not ("setup" or "submit" or "rollback" or "cleanup"))
            return new BadRequestResult();
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var ct = request.HttpContext.RequestAborted;
        var actor = Identity(workspace, "actor");
        var epoch = Identity(workspace, "epoch");
        var documents = new BunDo.Functions.Storage.Cosmos.HouseholdDocuments(Container);
        var stored = await documents.ReadAsync<WorkspaceState>(run, "state", ct);
        if (action == "setup")
        {
            if (stored is null)
            {
                var empty = new WorkspaceState(workspace, epoch, 0, ImmutableDictionary<Guid, DeviceRegistration>.Empty,
                    ImmutableDictionary<string, TaskSnapshot>.Empty, ImmutableDictionary<string, OperationReceipt>.Empty,
                    [], HouseholdMembership.Create(actor), "Synthetic sync verification",
                    Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)));
                await documents.WriteAsync(run, "state", null, empty, ct);
                stored = await documents.ReadAsync<WorkspaceState>(run, "state", ct);
            }
            if (stored?.Value.Membership.OwnerId != actor || stored.Value.StateEpoch != epoch)
                return new StatusCodeResult(403);
            await documents.WriteAsync(run, "verification-run", null, run, ct);
            return new OkObjectResult(new { ready = true });
        }
        var marker = await documents.ReadAsync<string>(run, "verification-run", ct);
        if (marker?.Value != run) return new StatusCodeResult(403);
        if (action == "cleanup")
        {
            var ids = new List<string>();
            using var iterator = Container.GetItemQueryIterator<string>("SELECT VALUE c.id FROM c",
                requestOptions: new QueryRequestOptions { PartitionKey = new PartitionKey(run) });
            while (iterator.HasMoreResults) ids.AddRange(await iterator.ReadNextAsync(ct));
            foreach (var id in ids.Where(x => x != "verification-run"))
                await Container.DeleteItemAsync<object>(id, new PartitionKey(run), cancellationToken: ct);
            await Container.DeleteItemAsync<object>("verification-run", new PartitionKey(run), cancellationToken: ct);
            return new OkObjectResult(new { cleaned = true, count = ids.Count });
        }
        if (stored is null) return new StatusCodeResult(409);
        if (action == "rollback")
        {
            // Fail after metadata replacement and another write have been planned. Cosmos must roll both back.
            var next = stored.Value with { Revision = checked(stored.Value.Revision + 1) };
            using var result = await Container.CreateTransactionalBatch(new PartitionKey(run))
                .ReplaceItem("state", new Document<WorkspaceState>("state", run, 1, next),
                    new TransactionalBatchItemRequestOptions { IfMatchEtag = stored.Version })
                .CreateItem(new Document<string>("rollback-candidate", run, 1, "must not exist"))
                .CreateItem(new Document<string>("verification-run", run, 1, "intentional conflict"))
                .ExecuteAsync(ct);
            var after = await documents.ReadAsync<WorkspaceState>(run, "state", ct);
            var candidate = await documents.ReadAsync<string>(run, "rollback-candidate", ct);
            var passed = result.StatusCode == HttpStatusCode.Conflict && after!.Value.Revision == stored.Value.Revision &&
                after.Version == stored.Version && candidate is null;
            return new ObjectResult(new { rolledBack = passed, revision = after!.Value.Revision.ToString() })
                { StatusCode = passed ? 200 : 500 };
        }
        // Hold enough concurrent HTTP invocations to exercise the app's existing multi-instance scaling.
        await Task.Delay(TimeSpan.FromSeconds(20), ct);
        var device = Identity(workspace, $"device/{slot}");
        var operation = new FrozenOperation(workspace, epoch, device, 1,
            new CreateTask(TaskIdentity.ForCreate(device, 1), $"Synthetic task {slot}"));
        var outcome = await new SyncService(documents).SubmitAsync(actor, operation, ct);
        var instance = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            Environment.GetEnvironmentVariable("WEBSITE_INSTANCE_ID") ?? Environment.MachineName)));
        return new OkObjectResult(new { outcome.Code, revision = outcome.Receipt?.EffectRevision.ToString(),
            instance, process = Environment.ProcessId, slot });
    }
}
