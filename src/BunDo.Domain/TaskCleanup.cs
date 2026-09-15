using System.Text.Json;

namespace BunDo.Domain;

public sealed record CleanupProposal(string Title, string? Description, string Language, bool NeedsReview, TaskDue? Due = null, string[]? Items = null);
public sealed record CleanupRequest(string Id, Guid Requester, Guid Epoch, string Status,
    string? InputTitle, string? InputDescription, ulong TitleVersion, ulong DescriptionVersion,
    ulong LifecycleVersion, ulong HierarchyVersion, ulong DeletionVersion,
    Guid? Lease = null, DateTimeOffset? LeaseUntil = null, CleanupProposal? Proposal = null, string? Error = null, ulong? ExpectedDueVersion = null, string Mode = "CLEANUP", string? Instructions = null, SplitSource? SplitSource = null);

public static class TaskCleanup
{
    public static (string Code, TaskSnapshot? Changed) Request(WorkspaceState state, TaskSnapshot? task,
        CleanupCommand command, string id, Guid member, ulong revision)
    {
        if (task is null) return ("ENTITY_MISSING", null);
        if (task.Deletion is not null) return ("TASK_DELETED", null);
        if (task.ParentId is { } parent && (!state.Tasks.TryGetValue(parent, out var root) || root.Deletion is not null))
            return ("PARENT_UNAVAILABLE", null);
        if (task.Cleanup?.Id != command.RequestId) return ("CLEANUP_CONFLICT", null);
        if (command is CancelCleanup)
            return ("ACCEPTED", task with { Cleanup = task.Cleanup is { } old
                ? old with { Status = "SUPERSEDED", Lease = null, LeaseUntil = null, InputTitle = null, InputDescription = null, Instructions = null } : null });
        if (task.TitleVersion.Server != command.TitleVersion || task.DescriptionVersion.Server != command.DescriptionVersion ||
            command.ExpectedDueVersion is { } expectedDue && (task.DueVersion?.Server ?? 0) != expectedDue)
            return ("FIELD_CONFLICT", null);
        if (task.LifecycleVersion != command.LifecycleVersion || task.HierarchyVersion != command.HierarchyVersion ||
            task.DeletionVersion != command.DeletionVersion) return ("LIFECYCLE_CONFLICT", null);
        if (task.Lifecycle != "OPEN") return ("TASK_NOT_OPEN", null);
        if (command is RequestSplit split && (task.ParentId is not null || task.IsChecklist ||
            split.Instructions?.EnumerateRunes().Count() > 2000)) return ("SPLIT_UNAVAILABLE", null);
        if (command is ApplyCleanup)
        {
            if (task.Cleanup?.Mode == "SPLIT") return ("CLEANUP_UNAVAILABLE", null);
            if (task.Cleanup is not { Status: "READY", Proposal: { } proposal } || !Valid(proposal) ||
                WorkspaceServer.ValidateText(Patch(task, proposal, revision, human: true)) != "ACCEPTED" ||
                proposal.Due is not null && command.ExpectedDueVersion is null)
                return ("CLEANUP_UNAVAILABLE", null);
            return ("ACCEPTED", Patch(task, proposal, revision, human: true) with {
                Cleanup = task.Cleanup with { Status = "APPLIED", Proposal = null },
            });
        }
        return ("ACCEPTED", task with { Cleanup = new(id, member, state.StateEpoch, "PENDING", task.Title,
            task.Description, task.TitleVersion.Server, task.DescriptionVersion.Server, task.LifecycleVersion,
            task.HierarchyVersion, task.DeletionVersion, ExpectedDueVersion: command.ExpectedDueVersion,
            Mode: command is RequestSplit ? "SPLIT" : "CLEANUP", Instructions: (command as RequestSplit)?.Instructions,
            SplitSource: command is RequestSplit ? new(ChecklistTasks.Versions(task), task.TitleVersion, task.DescriptionVersion, task.Title, task.Description) : null) });
    }

    public static bool Current(WorkspaceState state, TaskSnapshot task, CleanupRequest request) =>
        state.StateEpoch == request.Epoch && state.Membership.CanRead(request.Requester) && task.Deletion is null &&
        task.Lifecycle == "OPEN" && task.LifecycleVersion == request.LifecycleVersion &&
        task.HierarchyVersion == request.HierarchyVersion && task.DeletionVersion == request.DeletionVersion &&
        (task.ParentId is not { } parent || state.Tasks.TryGetValue(parent, out var root) && root.Deletion is null);

    public static TaskSnapshot Finish(WorkspaceState state, TaskSnapshot task, Guid lease,
        CleanupProposal? proposal, string? error, ulong revision, DateTimeOffset now)
    {
        if (task.Cleanup is not { Status: "RUNNING" } request || request.Lease != lease || request.LeaseUntil <= now)
            return task;
        if (state.StateEpoch != request.Epoch || !state.Membership.CanRead(request.Requester) || task.Deletion is not null) return task;
        var permitted = Current(state, task, request);
        if (proposal is not null && (request.Mode == "SPLIT" ? !TaskSplit.Valid(proposal) :
            !Valid(proposal) || WorkspaceServer.ValidateText(Patch(task, proposal, revision, human: false)) != "ACCEPTED"))
        { proposal = null; error = "INVALID_OUTPUT"; }
        if (proposal?.Due is not null && task.Capture?.Context.ValueKind != JsonValueKind.Object)
            proposal = proposal with { NeedsReview = true };
        if (proposal?.Due is { } due && TaskDates.TryNormalize(due, out var normalized)) proposal = proposal with { Due = normalized };
        var automatic = request.Mode == "CLEANUP" && permitted && proposal is { NeedsReview: false } &&
            (proposal.Due is null || request.ExpectedDueVersion is { } expectedDue && (task.DueVersion?.Server ?? 0) == expectedDue) &&
            task.TitleVersion.Server == request.TitleVersion && task.DescriptionVersion.Server == request.DescriptionVersion;
        var result = automatic ? Patch(task, proposal!, revision, human: false) : task;
        if (result != task) result = result with { LastChange = new(null, now, "AI") };
        return result with { Cleanup = request with {
            Status = automatic ? "APPLIED" : proposal is not null ? "READY" : "FAILED",
            InputTitle = null, InputDescription = null, Instructions = null, Lease = null, LeaseUntil = null,
            Proposal = automatic ? null : proposal, Error = error,
        } };
    }

    public static bool Valid(CleanupProposal value) =>
        !string.IsNullOrWhiteSpace(value.Title) && value.Title == value.Title.Trim() &&
        value.Title.EnumerateRunes().Count() <= 160 && value.Description?.EnumerateRunes().Count() is not > 4000 &&
        !value.Title.Any(char.IsControl) && !(value.Description?.Any(c => char.IsControl(c) && c is not ('\n' or '\t')) ?? false) &&
        (value.Language is "fi" or "en" or "mixed" or "und") && TaskDates.TryNormalize(value.Due, out _);

    private static TaskSnapshot Patch(TaskSnapshot task, CleanupProposal proposal, ulong revision, bool human)
    {
        TaskDates.TryNormalize(proposal.Due, out var normalized);
        return task with {
        Title = proposal.Title, Description = proposal.Description, ContentLanguage = proposal.Language,
        Due = normalized ?? task.Due,
        DueVersion = proposal.Due is null || normalized == task.Due ? task.DueVersion : new(revision, human ? revision : task.DueVersion?.Human ?? 0),
        TitleVersion = proposal.Title == task.Title ? task.TitleVersion : new(revision, human ? revision : task.TitleVersion.Human),
        DescriptionVersion = proposal.Description == task.Description ? task.DescriptionVersion : new(revision, human ? revision : task.DescriptionVersion.Human),
        };
    }
}
