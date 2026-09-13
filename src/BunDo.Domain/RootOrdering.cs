using System.Collections.Immutable;

namespace BunDo.Domain;

/// <summary>The bounded root list owns placement. Task versions track intentional moves, not ranks.</summary>
public static class RootOrdering
{
    public static ImmutableArray<string> Current(WorkspaceState state) => state.RootOrder ??
        state.Tasks.Values.Where(t => t.Lifecycle == "OPEN" && t.Deletion is null)
            .OrderBy(t => t.Capture?.ReceivedAt ?? DateTimeOffset.MinValue)
            .ThenBy(t => t.Id, StringComparer.Ordinal).Select(t => t.Id).ToImmutableArray();

    public static ImmutableArray<string> Place(ImmutableArray<string> order, string id, string? after, string? before)
    {
        var remaining = order.Remove(id);
        var afterIndex = after is null ? -1 : remaining.IndexOf(after);
        var beforeIndex = before is null ? -1 : remaining.IndexOf(before);
        // After wins even if anchors are inverted. Missing anchors never use historical neighbors.
        var index = afterIndex >= 0 ? afterIndex + 1 : beforeIndex >= 0 ? beforeIndex : remaining.Length;
        return remaining.Insert(index, id);
    }
}
