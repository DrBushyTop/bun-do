using BunDo.Domain;

namespace BunDo.Functions.Identity;

public interface IRegistrationStore
{
    Task<RegistrationDecision> RegisterAsync(AccountIdentity identity, Guid installationId, Guid? revoke, CancellationToken cancellationToken);
}
