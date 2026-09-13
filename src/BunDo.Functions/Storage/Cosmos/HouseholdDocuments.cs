using System.Net;
using System.Text.Json;
using BunDo.Functions.Households;
using Microsoft.Azure.Cosmos;

namespace BunDo.Functions.Storage.Cosmos;

/// <summary>Membership and task state share the workspace document and its ETag.</summary>
public sealed class HouseholdDocuments(Container container) : IHouseholdDocuments
{
    private sealed record Document<T>(string id, string workspaceId, int SchemaVersion, T Value);

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
