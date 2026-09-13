using System.Collections.Immutable;

namespace BunDo.Domain;

public sealed record TaskMutation(string Code, TaskSnapshot? Task, ImmutableArray<TaskSnapshot> Effects);

/// <summary>One root and at most sixteen direct items. No recursive task graph.</summary>
public static class ChecklistTasks
{
    public const int MaximumChildren = 16;
    public static TaskStateVersions Versions(TaskSnapshot task) =>
        new(task.LifecycleVersion, task.ClaimVersion, task.HierarchyVersion, task.DeletionVersion,
            task.SubtreeVersion, task.SnoozeVersion);

    public static ImmutableArray<TaskSnapshot> Related(IReadOnlyDictionary<string, TaskSnapshot> tasks, TaskSnapshot task)
    {
        var root = task.ParentId is { } parent ? tasks.GetValueOrDefault(parent) : task;
        return root is null ? [task] : [root, .. (root.ChildOrder ?? []).Where(tasks.ContainsKey).Select(id => tasks[id])];
    }

    public static TaskMutation Apply(WorkspaceState state, FrozenOperation operation, Guid actor, ulong revision, DateTimeOffset now)
    {
        var command = (TaskTransition)operation.Command;
        state.Tasks.TryGetValue(command.TaskId, out var task);
        TaskMutation Fail(string code) => new(code, task, []);
        if (task is null) return Fail("ENTITY_MISSING");
        var expected = command.Expected;
        if (expected.Deletion != task.DeletionVersion) return Fail("DELETION_CONFLICT");
        if (expected.Lifecycle != task.LifecycleVersion) return Fail("LIFECYCLE_CONFLICT");
        if (expected.Hierarchy != task.HierarchyVersion) return Fail("HIERARCHY_CONFLICT");
        if (expected.Claim != task.ClaimVersion) return Fail("CLAIM_CONFLICT");
        if (task.ParentId is { } parentId)
        {
            if (!state.Tasks.TryGetValue(parentId, out var parent) || parent.Deletion is not null)
                return Fail("PARENT_UNAVAILABLE");
            if (parent.SnoozedUntil > now && command is ClaimTask or CompleteTask) return Fail("TASK_SNOOZED");
        }
        if ((task.IsChecklist || command is ChecklistCommand) && expected.Subtree != task.SubtreeVersion)
            return Fail("SUBTREE_CONFLICT");
        var children = (task.ChildOrder ?? []).Select(id => state.Tasks.GetValueOrDefault(id)).ToArray();
        if (children.Any(child => child is null || child.ParentId != task.Id)) return Fail("HIERARCHY_CONFLICT");
        var items = children.Select(child => child!).ToArray();
        var effects = new List<TaskSnapshot>();
        if (command is ChecklistCommand split)
        {
            if (task.ParentId is not null) return Fail("CHECKLIST_DEPTH");
            if (task.Deletion is not null) return Fail("TASK_DELETED");
            if (task.Lifecycle != "OPEN" && !(command is AddChildren && task.EmptyChecklist)) return Fail("TASK_NOT_OPEN");
            if (command is SplitTask && task.IsChecklist || command is AddChildren && !task.IsChecklist)
                return Fail("HIERARCHY_CONFLICT");
            if (split.ExpectedTitleHumanVersion != task.TitleVersion.Human ||
                split.ExpectedDescriptionHumanVersion != task.DescriptionVersion.Human) return Fail("FIELD_CONFLICT");
            if (split.Items.Length == 0 || split.Items.Length + items.Length > MaximumChildren) return Fail("CHECKLIST_LIMIT");
            if (state.TaskCount + split.Items.Length > 1024) return Fail("TASK_LIMIT");
            if (split.Items.Any(title => string.IsNullOrWhiteSpace(title) || title.EnumerateRunes().Count() > 160))
                return Fail("INVALID_TITLE");
            for (var index = 0; index < split.Items.Length; index++)
            {
                var id = TaskIdentity.ForCreate(operation.DeviceId, operation.Sequence, index + 1);
                if (state.Tasks.ContainsKey(id)) return Fail("ENTITY_EXISTS");
                effects.Add(new(id, split.Items[index], null, new(revision, revision), new(revision, revision), revision,
                    Capture: operation.CaptureContext is { } capture ? new(split.Items[index], null, capture, now) : null,
                    LifecycleVersion: revision, ClaimVersion: revision, HierarchyVersion: revision,
                    OrderIntentVersion: revision, ParentId: task.Id));
            }
            var root = task with {
                IsChecklist = true, ChildOrder = (task.ChildOrder ?? []).AddRange(effects.Select(child => child.Id)),
                HierarchyVersion = revision, SubtreeVersion = revision,
                ClaimantId = null, ClaimVersion = task.ClaimantId is null ? task.ClaimVersion : revision,
            };
            effects.Add(root);
            return new("ACCEPTED", root, effects.ToImmutableArray());
        }
        if (task.IsChecklist && command is ClaimTask or UnclaimTask or CompleteTask) return Fail("CHECKLIST_ROOT");
        if (task.IsChecklist && command is ReopenTask && task.CancellationGroupId is null) return Fail("CHECKLIST_DERIVED");
        if (task.IsChecklist && command is CancelTask && task.Lifecycle == "CANCELLED" && task.CancellationGroupId is not null)
            return new("ACCEPTED", task, []);
        var code = TaskLifecycle.Apply(task, command, state.Membership, actor, revision, now, out var proposed, operation.OperationId);
        if (code != "ACCEPTED") return Fail(code);
        if (proposed != task) effects.Add(proposed!);
        if (task.IsChecklist)
        {
            foreach (var child in items)
            {
                TaskTransition? childCommand = command switch {
                    DeleteTask when child.Deletion is null => new DeleteTask(child.Id, Versions(child)),
                    RestoreTask when child.Deletion?.GroupId == task.Deletion?.GroupId => new RestoreTask(child.Id, Versions(child)),
                    CancelTask when child.Deletion is null && child.Lifecycle != "CANCELLED" => new CancelTask(child.Id, Versions(child)),
                    ReopenTask when child.Deletion is null && child.CancellationGroupId == task.CancellationGroupId => new ReopenTask(child.Id, Versions(child)),
                    _ => null,
                };
                if (childCommand is not null)
                {
                    var childCode = TaskLifecycle.Apply(child, childCommand, state.Membership, actor, revision, now,
                        out var changed, operation.OperationId);
                    if (childCode != "ACCEPTED") return Fail(childCode);
                    if (command is CancelTask) changed = changed! with { CancellationGroupId = operation.OperationId };
                    if (changed != child) effects.Add(changed!);
                }
                else if (command is SetSnooze && child.Deletion is null && child.ClaimantId is not null)
                    effects.Add(child with { ClaimantId = null, ClaimVersion = revision });
            }
            if (command is CancelTask or ReopenTask)
            {
                proposed = proposed! with {
                    CancellationGroupId = command is CancelTask ? operation.OperationId : null,
                    LifecycleVersion = revision, LifecycleActorId = actor, LifecycleAt = now,
                };
                effects.RemoveAll(value => value.Id == task.Id);
                effects.Add(proposed);
            }
        }
        return new(code, proposed, effects.ToImmutableArray());
    }

    public static ImmutableDictionary<string, TaskSnapshot> Reconcile(WorkspaceState state,
        ImmutableDictionary<string, TaskSnapshot> effects, Guid actor, ulong revision, DateTimeOffset now)
    {
        var roots = effects.Values.Select(task => task.ParentId ?? (task.IsChecklist ? task.Id : null))
            .Where(id => id is not null).Cast<string>().Distinct().ToArray();
        var merged = state.Tasks.SetItems(effects);
        foreach (var id in roots)
        {
            if (!merged.TryGetValue(id, out var root)) continue;
            root = root with { SubtreeVersion = revision };
            if (root.Deletion is null)
            {
                var eligible = (root.ChildOrder ?? []).Select(child => merged[child])
                    .Where(child => child.Deletion is null && child.Lifecycle != "CANCELLED").ToArray();
                var lifecycle = eligible.Length == 0 ? "CANCELLED" :
                    eligible.All(child => child.Lifecycle == "COMPLETED") ? "COMPLETED" : "OPEN";
                root = root with {
                    EmptyChecklist = eligible.Length == 0 && root.CancellationGroupId is null,
                    Lifecycle = lifecycle,
                    LifecycleVersion = lifecycle == root.Lifecycle ? root.LifecycleVersion : revision,
                    LifecycleActorId = lifecycle == root.Lifecycle ? root.LifecycleActorId : actor,
                    LifecycleAt = lifecycle == root.Lifecycle ? root.LifecycleAt : now,
                    FirstCompletion = root.FirstCompletion ?? (lifecycle == "COMPLETED" ? new(root.Id, actor, now) : null),
                };
            }
            effects = effects.SetItem(id, root);
        }
        return effects;
    }
}
