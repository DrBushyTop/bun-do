using BunDo.Domain;

namespace BunDo.Functions.Identity;

public interface IRegistrationStore
{
    Task<RegistrationDecision> RegisterAsync(AccountIdentity identity, Guid installationId, Guid? revoke, CancellationToken cancellationToken);
    Task<bool> IsActiveAsync(AccountIdentity identity, Guid registrationId, CancellationToken cancellationToken);
    Task<InstallationRegistration?> ReadAsync(string partition, Guid registrationId, CancellationToken cancellationToken) =>
        Task.FromResult<InstallationRegistration?>(null);
}

public static class RegistrationIdentity
{
    public static string Partition(AccountIdentity identity) => "identity:" + Convert.ToHexString(
        System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(
            System.Text.Json.JsonSerializer.Serialize(identity))));
}
