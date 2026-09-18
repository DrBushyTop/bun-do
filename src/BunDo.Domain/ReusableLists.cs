using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text.Json;

namespace BunDo.Domain;

public sealed record ListItem(string Title, string? Notes = null);
public sealed record SavedList(Guid Id, string Title, string? Notes, ListItem[] Items, bool Pinned = false);
public sealed record ListLibrary(ulong Version = 0, ImmutableArray<SavedList> Lists = default,
    Guid? LastOperation = null, string? LastFingerprint = null);
public sealed record SaveList(Guid OperationId, ulong ExpectedVersion, Guid Id, SavedList? Value);

/// <summary>Reusable content has no task lifecycle. Instances copy content through ordinary task commands.</summary>
public static class ReusableLists
{
    public const int MaximumLists = 32;
    public static bool Valid(SavedList value) => value.Id != Guid.Empty && Text(value.Title, 160, true) &&
        Text(value.Notes, 4000, false) && value.Items is { Length: > 0 and <= ChecklistTasks.MaximumChildren } &&
        JsonSerializer.SerializeToUtf8Bytes(value).Length <= 16 * 1024 &&
        value.Items.All(item => item is not null && Text(item.Title, 160, true) && Text(item.Notes, 4000, false));
    private static bool Text(string? text, int maximum, bool required) =>
        (!required || !string.IsNullOrWhiteSpace(text)) && (text?.EnumerateRunes().Count() ?? 0) <= maximum;
    public static (string Code, ListLibrary Library) Apply(ListLibrary current, SaveList command)
    {
        if (command.OperationId == Guid.Empty || command.Id == Guid.Empty ||
            command.Value is { } invalid && (invalid.Id != command.Id || !Valid(invalid))) return ("INVALID_LIST", current);
        var fingerprint = Convert.ToHexString(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(command)));
        if (current.LastOperation == command.OperationId)
            return (current.LastFingerprint == fingerprint ? "ACCEPTED" : "OPERATION_ID_REUSED", current);
        if (command.ExpectedVersion != current.Version) return ("LIST_CHANGED", current);
        if (current.Version == ulong.MaxValue) return ("WORKSPACE_FULL", current);
        var lists = (current.Lists.IsDefault ? [] : current.Lists).Where(list => list.Id != command.Id).ToImmutableArray();
        if (command.Value is { } value) lists = lists.Add(value);
        if (lists.Length > MaximumLists) return ("LIST_LIMIT", current);
        return ("ACCEPTED", new(current.Version + 1, lists, command.OperationId, fingerprint));
    }
}
