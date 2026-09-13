using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Functions.Identity;
using BunDo.Domain;

namespace BunDo.Functions.Households;

public sealed record StoredDocument<T>(T Value, string Version);
public sealed record NamedDocument<T>(string Id, T Value, string Version);
public sealed record DocumentPage<T>(IReadOnlyList<NamedDocument<T>> Items, string? Continuation);

/// <summary>A point-read and conditional-write boundary. Missing version means create only.</summary>
public interface IHouseholdDocuments
{
    Task<StoredDocument<T>?> ReadAsync<T>(string partition, string id, CancellationToken cancellationToken);
    Task<bool> WriteAsync<T>(string partition, string id, string? version, T value, CancellationToken cancellationToken);
    Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected, WorkspaceState next,
        CancellationToken cancellationToken, IReadOnlyList<string>? deletes = null);
    Task<DocumentPage<T>> ReadPageAsync<T>(string partition, string prefix, string? continuation, int limit,
        CancellationToken cancellationToken) => throw new NotSupportedException();
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
