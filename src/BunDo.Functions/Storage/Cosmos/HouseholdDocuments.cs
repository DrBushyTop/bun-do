using System.Net;
using System.Text.Json;
using BunDo.Functions.Households;
using Microsoft.Azure.Cosmos;
using BunDo.Domain;
using BunDo.Functions.Sync;

namespace BunDo.Functions.Storage.Cosmos;

/// <summary>Membership and task state share the workspace document and its ETag.</summary>
public sealed class HouseholdDocuments(Container container) : IHouseholdDocuments
{
    private sealed record Document<T>(string id, string workspaceId, int SchemaVersion, T Value);

    public async Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected, WorkspaceState next,
        CancellationToken cancellationToken, IReadOnlyList<string>? deletes = null)
    {
        var plan = WorkspaceCommit.Plan(expected.Value, next, deletes);
        var partition = next.WorkspaceId.ToString("D");
        var streams = new List<MemoryStream>();
        try
        {
            var batch = container.CreateTransactionalBatch(new PartitionKey(partition))
                .ReplaceItem("state", new Document<WorkspaceState>("state", partition, 1, plan.Metadata),
                    new TransactionalBatchItemRequestOptions { IfMatchEtag = expected.Version });
            foreach (var write in plan.Writes)
            {
                var stream = new MemoryStream(JsonSerializer.SerializeToUtf8Bytes(
                    new Document<object>(write.Id, partition, 1, write.Value)));
                streams.Add(stream);
                if (write.CreateOnly) batch.CreateItemStream(stream);
                else batch.UpsertItemStream(stream);
            }
            foreach (var id in plan.Deletes) batch.DeleteItem(id);
            using var response = await batch.ExecuteAsync(cancellationToken);
            if (response.IsSuccessStatusCode) return true;
            if (response.StatusCode is HttpStatusCode.Conflict or HttpStatusCode.PreconditionFailed) return false;
            throw new InvalidOperationException($"Workspace transaction failed with status {(int)response.StatusCode}.");
        }
        finally { foreach (var stream in streams) stream.Dispose(); }
    }

    private sealed record QueryDocument<T>(string id, T payload, string version);
    public async Task<DocumentPage<T>> ReadPageAsync<T>(string partition, string prefix, string? continuation,
        int limit, CancellationToken cancellationToken)
    {
        if (limit is < 1 or > 128) throw new ArgumentOutOfRangeException(nameof(limit));
        using var iterator = container.GetItemQueryIterator<QueryDocument<T>>(
            new QueryDefinition("SELECT c.id, c[\"Value\"] AS payload, c._etag AS version FROM c WHERE STARTSWITH(c.id, @prefix) ORDER BY c.id")
                .WithParameter("@prefix", prefix), continuation,
            new QueryRequestOptions { PartitionKey = new(partition), MaxItemCount = limit });
        var page = await iterator.ReadNextAsync(cancellationToken);
        return new(page.Select(x => new NamedDocument<T>(x.id, x.payload, x.version)).ToArray(), page.ContinuationToken);
    }

    public async Task<StoredDocument<T>?> ReadAsync<T>(string partition, string id, CancellationToken cancellationToken)
    {
        try
        {
            var read = await container.ReadItemAsync<Document<T>>(id, new PartitionKey(partition),
                cancellationToken: cancellationToken);
            if (read.Resource.SchemaVersion != 1) throw new InvalidOperationException("Unsupported household schema.");
            return new(read.Resource.Value, read.ETag);
        }
        catch (CosmosException error) when (error.StatusCode == HttpStatusCode.NotFound) { return null; }
    }

    public async Task<bool> WriteAsync<T>(string partition, string id, string? version, T value, CancellationToken cancellationToken)
    {
        var document = new Document<T>(id, partition, 1, value);
        HouseholdDocumentLimits.CheckEncodedSize(value, JsonSerializer.SerializeToUtf8Bytes(document).Length);
        try
        {
            if (version is null)
                await container.CreateItemAsync(document, new PartitionKey(partition), cancellationToken: cancellationToken);
            else
                await container.ReplaceItemAsync(document, id, new PartitionKey(partition),
                    new ItemRequestOptions { IfMatchEtag = version }, cancellationToken);
            return true;
        }
        catch (CosmosException error) when (error.StatusCode is HttpStatusCode.Conflict or HttpStatusCode.PreconditionFailed)
        { return false; }
    }
}
