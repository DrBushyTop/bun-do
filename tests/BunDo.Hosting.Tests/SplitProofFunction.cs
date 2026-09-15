using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
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
public sealed class SplitProofFunction(CosmosClient client, IConfiguration config)
{
    private Container Container => client.GetContainer(config["WorkspaceStore:DatabaseName"]!, config["WorkspaceStore:ContainerName"]!);
    [Function("SplitVerification")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "verification/split/{run}/{slot:int}")] HttpRequest request,
        string run, int slot)
    {
        if (!Guid.TryParseExact(run, "D", out var workspace) || workspace == Guid.Empty || slot is < -1 or > 4)
            return new BadRequestResult();
        var ct = request.HttpContext.RequestAborted;
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var documents = new BunDo.Functions.Storage.Cosmos.HouseholdDocuments(Container);
        var member = new Guid(SHA256.HashData(Encoding.UTF8.GetBytes($"split/{run}")).AsSpan(0, 16));
        var metadata = await documents.ReadAsync<WorkspaceState>(run, "state", ct);
        if (metadata is null && slot == 0)
        {
            var state = new WorkspaceState(workspace, workspace, 0, ImmutableDictionary<Guid, DeviceRegistration>.Empty,
                ImmutableDictionary<string, TaskSnapshot>.Empty, ImmutableDictionary<string, OperationReceipt>.Empty,
                [], HouseholdMembership.Create(member), "Synthetic split verification");
            if (!await documents.WriteAsync(run, "state", null, state, ct)) return new ConflictResult();
            await documents.WriteAsync(run, "split-verification", null, member, ct);
        }
        if ((await documents.ReadAsync<Guid>(run, "split-verification", ct))?.Value != member) return new StatusCodeResult(403);
        if (slot == -1)
        {
            var ids = new List<string>();
            using var iterator = Container.GetItemQueryIterator<string>("SELECT VALUE c.id FROM c",
                requestOptions: new QueryRequestOptions { PartitionKey = new PartitionKey(run) });
            while (iterator.HasMoreResults) ids.AddRange(await iterator.ReadNextAsync(ct));
            foreach (var id in ids.Where(id => id != "split-verification"))
                await Container.DeleteItemAsync<object>(id, new PartitionKey(run), cancellationToken: ct);
            await Container.DeleteItemAsync<object>("split-verification", new PartitionKey(run), cancellationToken: ct);
            return new OkObjectResult(new { cleaned = true });
        }
        var inputs = new[] {
            ("Siivoa keittiö", "Ei vahvoja pesuaineita", "Aloita astioista. Enintään kolme vaihetta.", "fi"),
            ("Prepare the guest room", "Do not buy anything", "Three short steps using what is already at home", "en"),
            ("Siivoa home office", "Keep invoices, älä heitä niitä pois", "Two steps, keep the mixed language", "mixed"),
            ("Clean the kitchen", "Keep the dishes", "Two steps", "en"),
            ("Sort recycling", "Paper and cardboard", "Two steps", "en"),
        };
        var device = new Guid(SHA256.HashData(Encoding.UTF8.GetBytes($"split/{run}/{slot}")).AsSpan(0, 16));
        var sync = new SyncService(documents);
        var created = await sync.SubmitAsync(member, new(workspace, workspace, device, 1,
            new CreateTask(TaskIdentity.ForCreate(device, 1), inputs[slot].Item1, inputs[slot].Item2)), ct);
        var task = created.Receipt!.Task!;
        var accepted = await sync.SubmitAsync(member, new(workspace, workspace, device, 2,
            new RequestSplit(task.Id, task.TitleVersion.Server, task.DescriptionVersion.Server, task.LifecycleVersion,
                task.HierarchyVersion, task.DeletionVersion, null, inputs[slot].Item3)), ct);
        if (!accepted.Receipt!.Accepted) return new ConflictResult();
        using var http = new HttpClient();
        var provider = new FoundryCleanupProvider(http,
            new ManagedIdentityCredential(ManagedIdentityId.FromUserAssignedClientId(config["AZURE_CLIENT_ID"]!)),
            new Uri(config["AI:Endpoint"]!), config["AI:LunaDeployment"]!);
        var adapter = new RaceProvider(provider, async () => {
            var current = (await documents.ReadAsync<TaskSnapshot>(run, WorkspaceCommit.TaskId(task.Id), ct))!.Value;
            if (slot == 3) await sync.SubmitAsync(member, new(workspace, workspace, device, 3,
                new CancelCleanup(task.Id, current.TitleVersion.Server, current.DescriptionVersion.Server,
                    current.LifecycleVersion, current.HierarchyVersion, current.DeletionVersion, current.Cleanup!.Id)), ct);
            if (slot == 4) await sync.SubmitAsync(member, new(workspace, workspace, device, 3,
                new CompleteTask(task.Id, ChecklistTasks.Versions(current))), ct);
        });
        await new CleanupWorker(documents, adapter).RunAsync(member, workspace, workspace, ct);
        var result = (await documents.ReadAsync<TaskSnapshot>(run, WorkspaceCommit.TaskId(task.Id), ct))!.Value;
        var proposal = result.Cleanup!.Proposal;
        var unchangedBeforeAcceptance = result.Title == task.Title && result.Description == task.Description && !result.IsChecklist;
        SubmissionResult? acceptance = null;
        if (proposal?.Items is { } items && result.Cleanup.SplitSource is { } source)
            acceptance = await sync.SubmitAsync(member, new(workspace, workspace, device, slot == 4 ? 4UL : 3UL,
                new SplitTask(task.Id, source.State, items.Take(2).ToArray(), source.Title.Human, source.Description.Human) {
                    ExpectedTitleFieldVersion = source.Title.Server, ExpectedDescriptionFieldVersion = source.Description.Server,
                }), ct);
        return new OkObjectResult(new { status = result.Cleanup.Status, error = result.Cleanup.Error,
            items = proposal?.Items, language = proposal?.Language, expectedLanguage = inputs[slot].Item4,
            unchangedBeforeAcceptance, acceptance = acceptance?.Code,
            children = acceptance?.Receipt?.RelatedTasks?.Count(t => t.ParentId == task.Id),
            releasedInput = result.Cleanup.InputTitle is null && result.Cleanup.Instructions is null });
    }
    private sealed class RaceProvider(ICleanupProvider inner, Func<Task> race) : ICleanupProvider
    {
        public Task<CleanupProposal> GenerateAsync(string title, string? description, CancellationToken ct, JsonElement? context = null) =>
            throw new InvalidOperationException();
        public async Task<CleanupProposal> GenerateSplitAsync(string title, string? description, string? instructions, CancellationToken ct)
        {
            var proposal = await inner.GenerateSplitAsync(title, description, instructions, ct);
            await race();
            return proposal;
        }
    }
}
