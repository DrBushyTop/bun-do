namespace BunDo.Domain;

public sealed record SplitSource(TaskStateVersions State, FieldVersion Title, FieldVersion Description, string SourceTitle, string? SourceDescription);

public static class TaskSplit
{
    public static bool Valid(CleanupProposal proposal) => proposal.Language is "fi" or "en" or "mixed" or "und" && ValidItems(proposal.Items);
    public static bool ValidItems(string[]? items) => items is { Length: >= 1 and <= ChecklistTasks.MaximumChildren } &&
        items.All(item => !string.IsNullOrWhiteSpace(item) && item == item.Trim() &&
            item.EnumerateRunes().Count() <= 160 && !item.Any(char.IsControl)) &&
        items.Distinct(StringComparer.OrdinalIgnoreCase).Count() == items.Length;
}
