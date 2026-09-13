namespace BunDo.Domain;

public static class TaskLifecycle
{
    public static string Apply(TaskSnapshot? task, TaskTransition command, HouseholdMembership members,
        Guid actor, ulong revision, DateTimeOffset now, out TaskSnapshot? result, string? operationId = null)
    {
        result = task;
        if (task is null) return "ENTITY_MISSING";
        var expected = command.Expected;
        if (expected.Deletion != task.DeletionVersion) return "DELETION_CONFLICT";
        if (expected.Lifecycle != task.LifecycleVersion) return "LIFECYCLE_CONFLICT";
        if (expected.Hierarchy != task.HierarchyVersion) return "HIERARCHY_CONFLICT";
        if (expected.Claim != task.ClaimVersion) return "CLAIM_CONFLICT";
        if (command is RestoreTask)
        {
            if (task.Deletion is null) return "TASK_NOT_DELETED";
            if (task.Deletion.Purging) return "TASK_PURGING";
            result = ClearClaim(task, revision) with { Deletion = null, DeletionVersion = revision };
            return "ACCEPTED";
        }
        if (task.Deletion is not null) return "TASK_DELETED";
        if (command is SetSnooze or ClearSnooze)
        {
            if (expected.Snooze != task.SnoozeVersion) return "SNOOZE_CONFLICT";
            var until = (command as SetSnooze)?.Until;
            if (until is { } instant && instant <= now) return "INVALID_SNOOZE";
            if (task.SnoozedUntil != until)
                result = ClearClaim(task, revision) with { SnoozedUntil = until, SnoozeVersion = revision };
            return "ACCEPTED";
        }
        if (command is DeleteTask)
        {
            // Lifecycle, snooze and first credit stay on the retained record, but claims never return.
            result = ClearClaim(task, revision) with {
                Deletion = new(operationId ?? throw new ArgumentNullException(nameof(operationId)), now),
                DeletionVersion = revision,
            };
            return "ACCEPTED";
        }
        var claimant = task.ClaimantId is { } member && members.CanRead(member) ? task.ClaimantId : null;
        if (command is ClaimTask)
        {
            if (task.SnoozedUntil > now) return "TASK_SNOOZED";
            if (task.Lifecycle != "OPEN") return "TASK_NOT_OPEN";
            if (claimant is not null && claimant != actor) return "ALREADY_CLAIMED";
            if (task.ClaimantId != actor) result = task with { ClaimantId = actor, ClaimVersion = revision };
            return "ACCEPTED";
        }
        if (command is UnclaimTask)
        {
            if (claimant is not null && claimant != actor && members.OwnerId != actor) return "CLAIM_NOT_YOURS";
            result = ClearClaim(task, revision);
            return "ACCEPTED";
        }
        var lifecycle = command switch {
            CompleteTask => "COMPLETED", ReopenTask => "OPEN", CancelTask => "CANCELLED",
            _ => throw new ArgumentException("Unsupported task transition.", nameof(command)),
        };
        if (task.Lifecycle == lifecycle) return "ACCEPTED";
        if (command is CompleteTask complete)
        {
            if (task.SnoozedUntil > now) return "TASK_SNOOZED";
            if (task.Lifecycle != "OPEN") return "TASK_NOT_OPEN";
            if (claimant is not null && claimant != actor && complete.ConfirmedClaimantId != claimant)
                return "CLAIM_CONFIRMATION_REQUIRED";
        }
        result = ClearClaim(task, revision) with {
            Lifecycle = lifecycle, LifecycleVersion = revision, LifecycleActorId = actor, LifecycleAt = now,
            FirstCompletion = task.FirstCompletion ?? (lifecycle == "COMPLETED" && task.ParentId is null ? new(task.Id, actor, now) : null),
            CancellationGroupId = null,
            SnoozedUntil = command is CancelTask ? null : task.SnoozedUntil,
            SnoozeVersion = command is CancelTask && task.SnoozedUntil is not null ? revision : task.SnoozeVersion,
        };
        return "ACCEPTED";
    }

    private static TaskSnapshot ClearClaim(TaskSnapshot task, ulong revision) =>
        task.ClaimantId is null ? task : task with { ClaimantId = null, ClaimVersion = revision };
}
