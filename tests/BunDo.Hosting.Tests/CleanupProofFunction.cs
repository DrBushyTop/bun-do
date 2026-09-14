using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using Azure.Identity;
using BunDo.Domain;
using BunDo.Functions.AI;
using BunDo.Functions.Households;
using BunDo.Functions.Sync;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Cosmos;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.Configuration;

namespace BunDo.Hosting.Tests;

/// <summary>Opt-in deployed adapter test. Copy only into a private verification build.
/// Synthetic partitions require an ownership marker and are deleted after the run.</summary>
public sealed class CleanupProofFunction(CosmosClient client, IConfiguration config)
{
    private Container Container => client.GetContainer(config["WorkspaceStore:DatabaseName"]!, config["WorkspaceStore:ContainerName"]!);
    [Function("CleanupVerification")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "verification/cleanup/{run}/{slot:int}")] HttpRequest request,
        string run, int slot)
    {
        if (!Guid.TryParseExact(run, "D", out var workspace) || workspace == Guid.Empty || slot is < -1 or > 4)
            return new BadRequestResult();
        var ct = request.HttpContext.RequestAborted;
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var documents = new BunDo.Functions.Storage.Cosmos.HouseholdDocuments(Container);
        var member = new Guid(SHA256.HashData(Encoding.UTF8.GetBytes($"cleanup/{run}")).AsSpan(0, 16));
        var metadata = await documents.ReadAsync<WorkspaceState>(run, "state", ct);
        if (metadata is null && slot == 0)
        {
            var state = new WorkspaceState(workspace, workspace, 0, ImmutableDictionary<Guid, DeviceRegistration>.Empty,
                ImmutableDictionary<string, TaskSnapshot>.Empty, ImmutableDictionary<string, OperationReceipt>.Empty,
                [], HouseholdMembership.Create(member), "Synthetic cleanup verification");
            if (!await documents.WriteAsync(run, "state", null, state, ct)) return new ConflictResult();
            await documents.WriteAsync(run, "cleanup-verification", null, member, ct);
        }
        if ((await documents.ReadAsync<Guid>(run, "cleanup-verification", ct))?.Value != member) return new StatusCodeResult(403);
        if (slot == -1)
        {
            var ids = new List<string>();
            using var iterator = Container.GetItemQueryIterator<string>("SELECT VALUE c.id FROM c",
                requestOptions: new QueryRequestOptions { PartitionKey = new PartitionKey(run) });
            while (iterator.HasMoreResults) ids.AddRange(await iterator.ReadNextAsync(ct));
            foreach (var id in ids.Where(id => id != "cleanup-verification"))
                await Container.DeleteItemAsync<object>(id, new PartitionKey(run), cancellationToken: ct);
            await Container.DeleteItemAsync<object>("cleanup-verification", new PartitionKey(run), cancellationToken: ct);
            return new OkObjectResult(new { cleaned = true });
        }
        var inputs = new[] {
            ("öö osta maitoa huomenna", "kaksi litraa laktoositonta maitoa", "fi"),
            ("um buy some milk tomorrow", "two litres of lactose-free milk", "en"),
            ("osta oat milk huomenna", "2 litraa, please", "mixed"),
            ("Älä osta maitoa", "Osta 2 purkkia kaurajuomaa. Ei sokeria.", "fi"),
            ("Call Alex next Friday maybe", "Check which Alex first", "en"),
        };
        var device = new Guid(SHA256.HashData(Encoding.UTF8.GetBytes($"cleanup/{run}/{slot}")).AsSpan(0, 16));
        var sync = new SyncService(documents);
        var created = await sync.SubmitAsync(member, new(workspace, workspace, device, 1,
            new CreateTask(TaskIdentity.ForCreate(device, 1), inputs[slot].Item1, inputs[slot].Item2)), ct);
        var task = created.Receipt!.Task!;
        var accepted = await sync.SubmitAsync(member, new(workspace, workspace, device, 2,
            new RequestCleanup(task.Id, task.TitleVersion.Server, task.DescriptionVersion.Server, task.LifecycleVersion,
                task.HierarchyVersion, task.DeletionVersion, null)), ct);
        if (!accepted.Receipt!.Accepted) return new ConflictResult();
        using var http = new HttpClient();
        var provider = new FoundryCleanupProvider(http,
            new ManagedIdentityCredential(ManagedIdentityId.FromUserAssignedClientId(config["AZURE_CLIENT_ID"]!)),
            new Uri(config["AI:Endpoint"]!), config["AI:LunaDeployment"]!);
        await new CleanupWorker(documents, provider).RunAsync(member, workspace, workspace, ct);
        var result = (await documents.ReadAsync<TaskSnapshot>(run, WorkspaceCommit.TaskId(task.Id), ct))!.Value;
        var proposal = result.Cleanup!.Proposal;
        // Synthetic output is returned to the private evaluator, never to telemetry.
        return new OkObjectResult(new { status = result.Cleanup.Status, error = result.Cleanup.Error,
            title = proposal?.Title ?? result.Title, description = proposal?.Description ?? result.Description,
            language = proposal?.Language ?? result.ContentLanguage, expectedLanguage = inputs[slot].Item3,
            preservedHumanVersion = result.TitleVersion.Human == task.TitleVersion.Human,
            releasedInput = result.Cleanup.InputTitle is null });
    }
}
