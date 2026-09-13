using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Identity;
using Microsoft.Azure.Cosmos;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Azure.Identity;

namespace BunDo.Functions.Storage.Cosmos;

/// <summary>Strong point reads and ETag replacement serialize the five-device admission check.</summary>
public sealed class IdentityRegistrations(Container container) : IRegistrationStore
{
    public static void Configure(IServiceCollection services, IConfiguration configuration)
    {
        services.AddSingleton(_ => new CosmosClient(configuration["WorkspaceStore:Endpoint"],
            new ManagedIdentityCredential(ManagedIdentityId.FromUserAssignedClientId(
                configuration["AZURE_CLIENT_ID"] ?? throw new InvalidOperationException("Backend identity required."))),
            new CosmosClientOptions {
                UseSystemTextJsonSerializerWithOptions = new JsonSerializerOptions(),
                ConnectionMode = ConnectionMode.Gateway,
            }));
        services.AddSingleton<BunDo.Functions.Households.IHouseholdDocuments>(provider => new HouseholdDocuments(
            provider.GetRequiredService<CosmosClient>().GetContainer(
                configuration["WorkspaceStore:DatabaseName"], configuration["WorkspaceStore:ContainerName"])));
        services.AddSingleton(provider => new BunDo.Functions.Households.HouseholdService(
            provider.GetRequiredService<BunDo.Functions.Households.IHouseholdDocuments>(),
            invitationUrl: BunDo.Functions.Households.InvitationLinks.HttpsJoinUrl));
        services.AddSingleton<IRegistrationStore>(provider => new IdentityRegistrations(
            provider.GetRequiredService<CosmosClient>().GetContainer(
                configuration["WorkspaceStore:DatabaseName"], configuration["WorkspaceStore:ContainerName"])));
    }

    private sealed record Registry(string id, string workspaceId, InstallationRegistration[] Records);

    public async Task<bool> IsActiveAsync(AccountIdentity identity, Guid registrationId, CancellationToken cancellationToken)
    {
        var partition = "identity:" + Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(identity))));
        try
        {
            var read = await container.ReadItemAsync<Registry>("registrations", new PartitionKey(partition),
                cancellationToken: cancellationToken);
            return read.Resource.Records.Any(x =>
                x.RegistrationId == registrationId && !x.Revoked && x.ExpiresAt > DateTimeOffset.UtcNow);
        }
        catch (CosmosException error) when (error.StatusCode == HttpStatusCode.NotFound) { return false; }
    }

    public async Task<RegistrationDecision> RegisterAsync(AccountIdentity identity, Guid installationId,
        Guid? revoke, CancellationToken cancellationToken)
    {
        var partition = "identity:" + Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(identity))));
        for (var attempt = 0; attempt < 8; attempt++)
        {
            Registry registry;
            string? etag = null;
            try
            {
                var read = await container.ReadItemAsync<Registry>("registrations", new PartitionKey(partition),
                    cancellationToken: cancellationToken);
                registry = read.Resource;
                etag = read.ETag;
            }
            catch (CosmosException error) when (error.StatusCode == HttpStatusCode.NotFound)
            {
                registry = new("registrations", partition, []);
            }
            var decision = InstallationRegistrations.Register(registry.Records, installationId,
                Guid.NewGuid(), DateTimeOffset.UtcNow, revoke);
            if (decision.Code != "accepted" || decision.Records == registry.Records) return decision;
            var updated = registry with { Records = decision.Records };
            try
            {
                if (etag is null)
                    await container.CreateItemAsync(updated, new PartitionKey(partition), cancellationToken: cancellationToken);
                else
                    await container.ReplaceItemAsync(updated, updated.id, new PartitionKey(partition),
                        new ItemRequestOptions { IfMatchEtag = etag }, cancellationToken);
                return decision;
            }
            catch (CosmosException error) when (error.StatusCode is HttpStatusCode.Conflict or HttpStatusCode.PreconditionFailed)
            { }
        }
        throw new InvalidOperationException("Registration contention exceeded retry bound.");
    }
}
