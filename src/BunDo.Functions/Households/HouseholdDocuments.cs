using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Functions.Identity;

namespace BunDo.Functions.Households;

public sealed record StoredDocument<T>(T Value, string Version);

/// <summary>A point-read and conditional-write boundary. Missing version means create only.</summary>
public interface IHouseholdDocuments
{
    Task<StoredDocument<T>?> ReadAsync<T>(string partition, string id, CancellationToken cancellationToken);
    Task<bool> WriteAsync<T>(string partition, string id, string? version, T value, CancellationToken cancellationToken);
}

public sealed record HouseholdDirectory(ImmutableHashSet<Guid> Workspaces, ImmutableArray<DateTimeOffset> Attempts)
{
    public static HouseholdDirectory Empty => new([], []);
}

public static class HouseholdIdentity
{
    // The exact API-validated pair is the authorization key. No email or client-provided subject.
    public static Guid Member(AccountIdentity identity) => new(SHA256.HashData(
        Encoding.UTF8.GetBytes(JsonSerializer.Serialize(identity))).AsSpan(0, 16));
    public static string Partition(Guid member) => $"household-member:{member:D}";
}

public sealed class HouseholdStorageFullException : Exception;
