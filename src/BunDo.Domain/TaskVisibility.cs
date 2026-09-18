using System.Collections.Immutable;

namespace BunDo.Domain;

public sealed record SharedCompletion(Guid WorkspaceId, FirstCompletion Credit);
public sealed record VisibilityReceipt(Guid Id, Guid Actor, string Stage);
public sealed record VisibilityContent(ImmutableArray<TaskSnapshot> Tasks, RepeatSchedule? Repeat);

/// <summary>Move a creator-owned root and its direct children. Content is copied, never private provenance.</summary>
public static class TaskVisibility
{
    public static string Validate(WorkspaceState state, Guid actor, string rootId)
    {
        if (!state.Membership.CanRead(actor)) return "FORBIDDEN";
        if (!state.Tasks.TryGetValue(rootId, out var root)) return "ENTITY_MISSING";
        if (root.ParentId is not null) return "CHECKLIST_ROOT";
        if (root.Deletion is not null) return "TASK_DELETED";
        if (root.Creation?.ActorId != actor) return "NOT_CREATOR";
        if (state.Adventures?.Active?.Draft.Phases.Any(p => p.RootId == root.Id) == true) return "ADVENTURE_ACTIVE";
        foreach (var id in root.ChildOrder ?? [])
        {
            if (!state.Tasks.TryGetValue(id, out var child) || child.ParentId != root.Id) return "HIERARCHY_CONFLICT";
            if (child.Creation?.ActorId != actor) return "NOT_CREATOR";
        }
        if (root.Repeat is { Active: true } link &&
            state.Repeats?.GetValueOrDefault(link.Id)?.CurrentTaskId != root.Id) return "REPEAT_MOVED";
        return "ACCEPTED";
    }

    public static VisibilityContent Export(WorkspaceState state, string rootId)
    {
        var root = state.Tasks[rootId];
        var liveChildren = (root.ChildOrder ?? []).Where(id => state.Tasks[id].Deletion is null).ToImmutableArray();
        root = root with { ChildOrder = root.ChildOrder is null ? null : liveChildren };
        var tasks = new[] { root }.Concat(liveChildren.Select(id => state.Tasks[id]));
        return new(tasks.Select(task => task with {
            Capture = null, Cleanup = null, LastChange = null, ClaimantId = null, LifecycleActorId = null,
            PriorSharedCompletion = state.Membership.PersonalOwnerId is null && task.FirstCompletion is { } credit
                ? new(state.WorkspaceId, credit) : task.PriorSharedCompletion,
        }).ToImmutableArray(), root.Repeat is { Active: true } repeat ? state.Repeats?.GetValueOrDefault(repeat.Id) : null);
    }

    public static WorkspaceState Retire(WorkspaceState state, Guid actor, Guid transfer, string rootId, DateTimeOffset now)
    {
        var revision = checked(state.Revision + 1);
        var root = state.Tasks[rootId];
        var tasks = new[] { root }.Concat((root.ChildOrder ?? []).Select(id => state.Tasks[id]))
            .Select(t => t with { Deletion = new(transfer.ToString("D"), now, true), DeletionVersion = revision,
                VisibilityTransferId = transfer, ClaimantId = null, ClaimVersion = revision, Cleanup = null }).ToImmutableArray();
        var stopped = root.Repeat is { } link && state.Repeats?.GetValueOrDefault(link.Id) is { } repeat && repeat.CurrentTaskId == root.Id
            ? new[] { repeat with { Active = false, PendingDate = null, OpenTaskId = null, Version = revision } }.ToImmutableArray() : [];
        tasks = tasks.Select(t => t.Repeat is not null && stopped.Length > 0 ? t with { Repeat = stopped[0].Info } : t).ToImmutableArray();
        var order = RootOrdering.Current(state).Remove(rootId);
        return state with { Revision = revision, Tasks = state.Tasks.SetItems(tasks.Select(t => KeyValuePair.Create(t.Id, t))),
            RootOrder = order, Repeats = (state.Repeats ?? ImmutableDictionary<string, RepeatSchedule>.Empty).SetItems(stopped.Select(r => KeyValuePair.Create(r.Id, r))),
            VisibilityReceipts = [new(transfer, actor, "REMOVED")],
            Changes = [new(revision, tasks, now, RootOrder: order, Repeats: stopped)] };
    }

    public static WorkspaceState Import(WorkspaceState state, Guid actor, Guid transfer, VisibilityContent content, DateTimeOffset now, bool restoreOriginalIds = false)
    {
        var revision = checked(state.Revision + 1);
        var ids = content.Tasks.Select((t, i) => (t.Id, NewId: restoreOriginalIds ? t.Id : TaskIdentity.ForCreate(transfer, (ulong)i + 1)))
            .ToDictionary(t => t.Id, t => t.NewId);
        var rootId = ids[content.Tasks[0].Id];
        var repeat = content.Repeat is { } source ? source with {
            Id = TaskIdentity.ForCreate(transfer, 100), Version = revision, CurrentTaskId = rootId,
            OpenTaskId = source.OpenTaskId is null ? null : rootId, CreatorId = actor,
        } : null;
        var tasks = content.Tasks.Select(t => t with {
            Id = ids[t.Id], ParentId = t.ParentId is null ? null : rootId,
            ChildOrder = t.ChildOrder?.Select(id => ids[id]).ToImmutableArray(),
            TitleVersion = new(revision, revision), DescriptionVersion = new(revision, revision), DueVersion = new(revision, revision),
            LifecycleVersion = revision, DeletionVersion = revision, HierarchyVersion = revision, SubtreeVersion = revision,
            ClaimVersion = revision, OrderIntentVersion = revision, SnoozeVersion = revision, UrgencyVersion = revision,
            Deletion = t.Deletion is null ? null : new(transfer.ToString("D"), now), VisibilityTransferId = null,
            Capture = null, Cleanup = null, LastChange = null, ClaimantId = null, LifecycleActorId = null,
            LifecycleAt = t.Lifecycle == "OPEN" ? null : now,
            Creation = new(actor, now, now), InitialPlacement = "APPENDED",
            CancellationGroupId = t.CancellationGroupId is not null && t.CancellationGroupId == content.Tasks[0].CancellationGroupId
                ? transfer.ToString("D") : null,
            Repeat = t.ParentId is null ? repeat?.Info : null,
            FirstCompletion = state.Membership.PersonalOwnerId is null && t.PriorSharedCompletion?.WorkspaceId == state.WorkspaceId
                ? t.PriorSharedCompletion.Credit : null,
            PriorSharedCompletion = state.Membership.PersonalOwnerId is not null ? t.PriorSharedCompletion : null,
        }).ToImmutableArray();
        var order = RootOrdering.Current(state);
        if (tasks[0].Lifecycle == "OPEN") order = order.Add(rootId);
        return state with { Revision = revision, RootOrder = order, TaskCount = state.TaskCount + tasks.Count(t => !restoreOriginalIds || !state.Tasks.ContainsKey(t.Id)),
            Tasks = state.Tasks.SetItems(tasks.Select(t => KeyValuePair.Create(t.Id, t))),
            VisibilityReceipts = [new(transfer, actor, "IMPORTED")],
            Changes = [new(revision, tasks, now, RootOrder: order, Repeats: repeat is null ? [] : [repeat])] };
    }
}
