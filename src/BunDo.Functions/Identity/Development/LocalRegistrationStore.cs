using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Domain;

namespace BunDo.Functions.Identity.Development;

/// <summary>Single-process local adapter. Never used by a deployed host.</summary>
public sealed class LocalRegistrationStore(string directory) : IRegistrationStore
{
    private readonly SemaphoreSlim gate = new(1);
    public async Task<InstallationRegistration?> ReadAsync(string partition, Guid registrationId, CancellationToken ct)
    {
        var key = partition.StartsWith("identity:", StringComparison.Ordinal) ? partition[9..] : "";
        if (key.Length != 64 || key.Any(x => !Uri.IsHexDigit(x))) throw new ArgumentException("Invalid registry partition.");
        await gate.WaitAsync(ct);
        try
        {
            var file = Path.Combine(directory, key + ".json");
            return File.Exists(file) ? JsonSerializer.Deserialize<InstallationRegistration[]>(
                await File.ReadAllBytesAsync(file, ct))!.SingleOrDefault(x => x.RegistrationId == registrationId) : null;
        }
        finally { gate.Release(); }
    }
    public async Task<bool> IsActiveAsync(AccountIdentity identity, Guid registrationId, CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            var key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(identity))));
            var file = Path.Combine(directory, key + ".json");
            var records = File.Exists(file)
                ? JsonSerializer.Deserialize<InstallationRegistration[]>(await File.ReadAllBytesAsync(file, cancellationToken))!
                : [];
            return records.Any(x => x.RegistrationId == registrationId && !x.Revoked && x.ExpiresAt > DateTimeOffset.UtcNow);
        }
        finally { gate.Release(); }
    }
    public async Task<RegistrationDecision> RegisterAsync(AccountIdentity identity, Guid installationId,
        Guid? revoke, CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken);
        try
        {
            Directory.CreateDirectory(directory);
            var key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(identity))));
            var file = Path.Combine(directory, key + ".json");
            var records = File.Exists(file)
                ? JsonSerializer.Deserialize<InstallationRegistration[]>(await File.ReadAllBytesAsync(file, cancellationToken))!
                : [];
            var decision = InstallationRegistrations.Register(records, installationId, Guid.NewGuid(), DateTimeOffset.UtcNow, revoke);
            if (decision.Code == "accepted")
            {
                var temporary = file + ".tmp";
                await File.WriteAllBytesAsync(temporary, JsonSerializer.SerializeToUtf8Bytes(decision.Records), cancellationToken);
                File.Move(temporary, file, overwrite: true);
            }
            return decision;
        }
        finally { gate.Release(); }
    }
}
