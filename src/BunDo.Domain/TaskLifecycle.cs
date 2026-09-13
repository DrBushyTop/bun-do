namespace BunDo.Domain;

public static class TaskLifecycle
{
    public static string Apply(TaskSnapshot? task, TaskTransition command, HouseholdMembership members,
        Guid actor, ulong revision, DateTimeOffset now, out TaskSnapshot? result)
    {
        result = task;
        if (task is null) return "ENTITY_MISSING";
        var expected = command.Expected;
        if (expected.Deletion != task.DeletionVersion) return "DELETION_CONFLICT";
        if (expected.Lifecycle != task.LifecycleVersion) return "LIFECYCLE_CONFLICT";
        if (expected.Hierarchy != task.HierarchyVersion) return "HIERARCHY_CONFLICT";
        if (expected.Claim != task.ClaimVersion) return "CLAIM_CONFLICT";
        var claimant = task.ClaimantId is { } member && members.CanRead(member) ? task.ClaimantId : null;
        if (command is ClaimTask)
        {
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
            if (task.Lifecycle != "OPEN") return "TASK_NOT_OPEN";
            if (claimant is not null && claimant != actor && complete.ConfirmedClaimantId != claimant)
                return "CLAIM_CONFIRMATION_REQUIRED";
        }
        result = ClearClaim(task, revision) with {
            Lifecycle = lifecycle, LifecycleVersion = revision, LifecycleActorId = actor, LifecycleAt = now,
            FirstCompletion = task.FirstCompletion ?? (lifecycle == "COMPLETED" ? new(task.Id, actor, now) : null),
        };
        return "ACCEPTED";
    }

    private static TaskSnapshot ClearClaim(TaskSnapshot task, ulong revision) =>
        task.ClaimantId is null ? task : task with { ClaimantId = null, ClaimVersion = revision };
}
