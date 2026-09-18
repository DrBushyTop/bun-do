using System.Collections.Immutable;
using System.Text;
using System.Text.Json;

namespace BunDo.Domain;

public sealed class WorkspaceServer(IWorkspaceStore store, TimeProvider? timeProvider = null)
{
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;

    public OutcomePage Outcomes(Guid member, Guid epoch, Guid deviceId, ulong first, int count)
    {
        var state = store.Read();
        OutcomePage Failure(string code) => new(code, state.WorkspaceId, state.StateEpoch, deviceId, 0, []);
        if (!state.Membership.CanRead(member)) return Failure("FORBIDDEN");
        if (state.StateEpoch != epoch) return Failure("EPOCH_CHANGED");
        if (!state.Devices.TryGetValue(deviceId, out var device)) return Failure("DEVICE_UNKNOWN");
        if (device.MemberId != member) return Failure("FORBIDDEN");
        if (!ValidOutcomeRange(first, count)) return Failure("INVALID_RANGE");
        var outcomes = new List<SequenceOutcome>();
        for (var offset = 0; offset < count; offset++)
        {
            var sequence = first + (ulong)offset;
            if (sequence > device.LastTerminalSequence)
                outcomes.Add(new(sequence, "NOT_SEEN"));
            else if (state.Receipts.TryGetValue($"{deviceId:D}:{sequence}", out var receipt))
                outcomes.Add(new(sequence, receipt.Accepted ? "ACCEPTED" : "REJECTED",
                    receipt.Fingerprint, receipt.EffectRevision, receipt.Code, receipt));
            else outcomes.Add(new(sequence, "OUTCOME_EXPIRED"));
        }
        return new("ACCEPTED", state.WorkspaceId, epoch, deviceId, device.LastTerminalSequence, outcomes);
    }

    public static bool ValidOutcomeRange(ulong first, int count) =>
        first != 0 && count is >= 1 and <= 100 && first <= ulong.MaxValue - (ulong)(count - 1);

    public SubmissionResult Handle(Guid authenticatedMemberId, FrozenOperation operation)
    {
        for (var attempt = 0; attempt < 5; attempt++)
        {
            var state = store.Read();
            if (operation.WorkspaceId != state.WorkspaceId) return new("WRONG_WORKSPACE");
            if (operation.StateEpoch != state.StateEpoch) return new("EPOCH_CHANGED");
            // Membership is checked inside every CAS attempt, before receipts or task data.
            if (!state.Membership.CanRead(authenticatedMemberId)) return new("FORBIDDEN");
            if (!state.Devices.TryGetValue(operation.DeviceId, out var device)) return new("DEVICE_UNKNOWN");
            if (device.MemberId != authenticatedMemberId) return new("FORBIDDEN");
            if (operation.ProtocolVersion != 1) return new("UNSUPPORTED_PROTOCOL");
            if (operation.CommandVersion != 1) return new("UNSUPPORTED_COMMAND_VERSION");
            if (operation.Sequence == 0) return new("INVALID_SEQUENCE");
            if (state.Receipts.TryGetValue(operation.OperationId, out var recorded))
                return recorded.Fingerprint == operation.Fingerprint
                    ? new(recorded.Code, recorded)
                    : new("OPERATION_ID_REUSED");
            if (operation.Sequence <= device.LastTerminalSequence) return new("OUTCOME_EXPIRED");
            if (operation.Sequence - device.LastTerminalSequence != 1) return new("SEQUENCE_GAP");
            if (state.Revision == ulong.MaxValue) return new("WORKSPACE_FULL");
            var revision = state.Revision + 1;
            var acceptedAt = clock.GetUtcNow();
            var code = "ACCEPTED";
            var repeats = state.Repeats ?? ImmutableDictionary<string, RepeatSchedule>.Empty;
            var originalOrder = RootOrdering.Current(state);
            var order = originalOrder;
            TaskSnapshot? task;
            TaskSnapshot? changed = null;
            var effects = ImmutableDictionary<string, TaskSnapshot>.Empty;
            if (operation.Command is not DiscardBlockedIntent && operation.Dependencies.Any(sequence =>
                    !state.Receipts.TryGetValue($"{operation.DeviceId:D}:{sequence}", out var prerequisite) || !prerequisite.Accepted))
            {
                task = null;
                code = "INVALID_DEPENDENCY";
            }
            else if (operation.Command is CreateTask create)
            {
                task = new(create.TaskId, create.Title, create.Description, new(revision, revision), new(revision, revision),
                    revision, operation.CaptureContext is { } capture
                        ? new(create.Title, create.Description, capture, acceptedAt) : null,
                    LifecycleVersion: revision, ClaimVersion: revision, HierarchyVersion: revision, OrderIntentVersion: revision,
                    DueVersion: new(revision, revision), Urgent: create.Urgent, UrgencyVersion: revision, ListKind: create.ListKind);
                if (create.AnonymousCapture) task = task with { Capture = new(create.Title, create.Description,
                    create.OriginalCapture?.Context ?? JsonSerializer.SerializeToElement<object?>(null), acceptedAt) };
                if (TaskDates.TryNormalize(create.Due, out var due)) task = task with { Due = due };
                if (create.ListKind is not null and not "FINITE" and not "STANDING") code = "INVALID_LIST";
                else if (!TaskDates.TryNormalize(create.Due, out _)) code = "INVALID_DUE";
                else if (state.TaskCount >= 1024) code = "TASK_LIMIT";
                else if (state.Tasks.ContainsKey(create.TaskId))
                    code = "ENTITY_EXISTS";
                else if (create.TaskId != TaskIdentity.ForCreate(operation.DeviceId, operation.Sequence))
                    code = "INVALID_TASK_ID";
                else
                    code = ValidateText(task);
                if (code == "ACCEPTED")
                {
                    var expedited = create.Placement is not null && TaskDates.Expedited(create.Urgent, task.Due,
                        TaskDates.CapturedAt(task, acceptedAt), state.TimeZoneId);
                    task = task with { Creation = new(create.AnonymousCapture ? null : authenticatedMemberId,
                        create.AnonymousCapture ? create.OriginalCapture?.CapturedAt : TaskDates.CapturedAt(task, acceptedAt), acceptedAt), InitialPlacement = expedited ? "EXPEDITED" : "APPENDED" };
                    if (expedited) order = RootOrdering.Place(order, task.Id, create.Placement!.AfterTaskId, create.Placement.BeforeTaskId);
                    changed = task;
                }
                else task = null;
            }
            else if (operation.Command is DiscardBlockedIntent blocked)
            {
                task = null;
                var dependencyId = $"{operation.DeviceId:D}:{blocked.RejectedDependencySequence}";
                code = blocked.RejectedDependencySequence < operation.Sequence &&
                       state.Receipts.TryGetValue(dependencyId, out var dependency) && !dependency.Accepted
                    ? "BLOCKED_DEPENDENCY"
                    : "INVALID_DEPENDENCY";
            }
            else if (operation.Command is RepeatCommand)
            {
                var result = TaskRepeats.Configure(state, operation, authenticatedMemberId, revision, acceptedAt);
                code = result.Code;
                task = result.Task;
                if (result.Schedule is { } schedule)
                {
                    changed = task;
                    repeats = repeats.SetItem(schedule.Id, schedule);
                }
            }
            else if (operation.Command is CleanupCommand cleanup)
            {
                state.Tasks.TryGetValue(cleanup.TaskId, out task);
                (code, changed) = TaskCleanup.Request(state, task, cleanup, operation.OperationId,
                    authenticatedMemberId, revision);
                task = changed ?? task;
            }
            else if (operation.Command is TaskTransition transition)
            {
                var mutation = ChecklistTasks.Apply(state, operation, authenticatedMemberId, revision, acceptedAt);
                code = mutation.Code;
                task = mutation.Task;
                effects = mutation.Effects.ToImmutableDictionary(value => value.Id);
            }
            else if (operation.Command is MoveTask move)
            {
                state.Tasks.TryGetValue(move.TaskId, out task);
                if (task is null) code = "ENTITY_MISSING";
                else if (move.ExpectedDeletionVersion != task.DeletionVersion) code = "DELETION_CONFLICT";
                else if (task.Deletion is not null) code = "TASK_DELETED";
                else if (move.ExpectedParentId != task.ParentId) code = "HIERARCHY_CONFLICT";
                else if (task.ParentId is { } parentId && (!state.Tasks.TryGetValue(parentId, out var parent) || parent.Deletion is not null))
                    code = "PARENT_UNAVAILABLE";
                else if (move.ExpectedOrderVersion != task.OrderIntentVersion) code = "ORDER_CONFLICT";
                else if (task.Lifecycle != "OPEN") code = "TASK_NOT_OPEN";
                else if (move.AfterTaskId == task.Id || move.BeforeTaskId == task.Id) code = "INVALID_ANCHOR";
                else
                {
                    if (task.ParentId is { } rootId)
                    {
                        var root = state.Tasks[rootId];
                        var children = root.ChildOrder ?? [];
                        var live = children.Where(id => state.Tasks[id].Deletion is null).ToImmutableArray();
                        var moved = RootOrdering.Place(live, task.Id, move.AfterTaskId, move.BeforeTaskId);
                        effects = effects.SetItem(rootId, root with {
                            ChildOrder = moved.AddRange(children.Except(live)), SubtreeVersion = revision,
                        });
                    }
                    else order = RootOrdering.Place(order, task.Id, move.AfterTaskId, move.BeforeTaskId);
                    changed = task = task with { OrderIntentVersion = revision };
                }
            }
            else
            {
                var edit = (EditTask)operation.Command;
                state.Tasks.TryGetValue(edit.TaskId, out task);
                if (task is null) code = "ENTITY_MISSING";
                else if (edit.ExpectedDeletionVersion is { } deletion && deletion != task.DeletionVersion)
                    code = "DELETION_CONFLICT";
                else if (task.Deletion is not null) code = "TASK_DELETED";
                else if (task.ParentId is { } parentId && (!state.Tasks.TryGetValue(parentId, out var parent) || parent.Deletion is not null))
                    code = "PARENT_UNAVAILABLE";
                else if (edit.Title is null && edit.Description is null && edit.Due is null && edit.Urgent is null) code = "EMPTY_EDIT";
                else if (edit.Title is { } t && t.ExpectedHumanVersion != task.TitleVersion.Human ||
                    edit.Description is { } d && d.ExpectedHumanVersion != task.DescriptionVersion.Human ||
                    edit.Due is { } dueEdit && dueEdit.ExpectedHumanVersion != (task.DueVersion?.Human ?? 0) ||
                    edit.Urgent is { } urgency && urgency.ExpectedVersion != task.UrgencyVersion)
                    code = "FIELD_CONFLICT";
                else
                {
                    var proposed = task;
                    if (edit.Title is { } title && title.Value != task.Title)
                        proposed = proposed with { Title = title.Value!, TitleVersion = new(revision, revision) };
                    if (edit.Description is { } description && description.Value != task.Description)
                        proposed = proposed with { Description = description.Value, DescriptionVersion = new(revision, revision) };
                    if (edit.Urgent is { } urgent && urgent.Value != task.Urgent)
                        proposed = proposed with { Urgent = urgent.Value, UrgencyVersion = revision };
                    if (edit.Due is { } newDue && TaskDates.TryNormalize(newDue.Value, out var normalized) && normalized != task.Due)
                        proposed = proposed with { Due = normalized, DueVersion = new(revision, revision) };
                    code = edit.Due is { } invalid && !TaskDates.TryNormalize(invalid.Value, out _) ? "INVALID_DUE" : ValidateText(proposed);
                    if (code == "ACCEPTED")
                    {
                        if (proposed != task) changed = proposed;
                        task = proposed;
                    }
                }
            }
            if (changed is not null) effects = effects.SetItem(changed.Id, changed);
            effects = ChecklistTasks.Reconcile(state, effects, authenticatedMemberId, revision, acceptedAt);
            if (code == "ACCEPTED")
            {
                var repeated = TaskRepeats.Reconcile(state, effects, repeats, revision, acceptedAt);
                code = repeated.Code;
                if (code == "ACCEPTED") { effects = repeated.Effects; repeats = repeated.Schedules; }
                else
                {
                    effects = ImmutableDictionary<string, TaskSnapshot>.Empty;
                    repeats = state.Repeats ?? ImmutableDictionary<string, RepeatSchedule>.Empty;
                    order = originalOrder;
                    if (task is not null) task = state.Tasks.GetValueOrDefault(task.Id);
                }
            }
            foreach (var effect in effects.Values.ToArray())
            {
                if (!state.Tasks.TryGetValue(effect.Id, out var before))
                {
                    if (effect.Creation is null) effects = effects.SetItem(effect.Id, effect with {
                        Creation = new(authenticatedMemberId, TaskDates.CapturedAt(effect, acceptedAt), acceptedAt),
                        DueVersion = new(revision, revision), UrgencyVersion = revision,
                    });
                }
                else if (operation.Command is not (RequestCleanup or RequestSplit or CancelCleanup) && effect with { Cleanup = before.Cleanup } != before)
                    effects = effects.SetItem(effect.Id, effect with { LastChange = new(authenticatedMemberId, acceptedAt, "HUMAN") });
            }
            if (task is not null && effects.TryGetValue(task.Id, out var finalTask)) task = finalTask;
            foreach (var effect in effects.Values.Where(value => value.ParentId is null))
            {
                if (effect.Lifecycle != "OPEN" || effect.Deletion is not null) order = order.Remove(effect.Id);
                else if (!order.Contains(effect.Id)) order = order.Add(effect.Id);
            }
            var receipt = new OperationReceipt(operation.OperationId, operation.Fingerprint, code, revision,
                task, acceptedAt, task is null || !task.IsChecklist && task.ParentId is null ? null : ChecklistTasks.Related(state.Tasks.SetItems(effects), task));
            var next = state with
            {
                Revision = revision,
                RootOrder = order,
                TaskCount = state.TaskCount + effects.Keys.Count(id => !state.Tasks.ContainsKey(id)),
                Tasks = state.Tasks.SetItems(effects),
                Repeats = repeats,
                RecentActivity = code == "ACCEPTED" && task is not null && effects.Count > 0
                    ? HouseholdProgress.Record(state.RecentActivity, revision, task.Id, authenticatedMemberId,
                        operation.Command.GetType().Name, acceptedAt) : state.RecentActivity,
                Receipts = state.Receipts.Add(operation.OperationId, receipt),
                Devices = state.Devices.SetItem(operation.DeviceId,
                    state.Devices[operation.DeviceId] with { LastTerminalSequence = operation.Sequence }),
                Changes = state.Changes.Add(new(revision, effects.Values.OrderBy(value => value.Id).ToImmutableArray(), acceptedAt, operation.OperationId,
                    state.RootOrder is null || !order.SequenceEqual(originalOrder) ? order : null,
                    Repeats: repeats.Values.Where(value => value != state.Repeats?.GetValueOrDefault(value.Id)).ToImmutableArray()))
            };
            if (store.TryCommit(state.Revision, next)) return new(code, receipt);
        }
        return new("BUSY");
    }

    public MembershipResult ChangeMembership(Guid authenticatedMemberId, Guid stateEpoch,
        MembershipCommand command)
    {
        for (var attempt = 0; attempt < 5; attempt++)
        {
            var state = store.Read();
            if (state.StateEpoch != stateEpoch) return new("EPOCH_CHANGED");
            var revision = checked(state.Revision + 1);
            var decision = MembershipPolicy.Apply(state.Membership, authenticatedMemberId, command,
                clock.GetUtcNow(), revision);
            if (decision.State == state.Membership) return new(decision.Code, decision.Invitation);
            var next = state with {
                Revision = revision,
                Membership = decision.State,
                Changes = state.Changes.Add(new(revision, [], clock.GetUtcNow())),
            };
            if (store.TryCommit(state.Revision, next)) return new(decision.Code, decision.Invitation);
        }
        return new("BUSY");
    }

    public ChangePage Pull(Guid authenticatedMemberId, ulong afterRevision, int maxGroups = 100)
    {
        var state = store.Read();
        if (!state.Membership.CanRead(authenticatedMemberId))
            throw new UnauthorizedAccessException("Workspace membership required.");
        ArgumentOutOfRangeException.ThrowIfGreaterThan(afterRevision, state.Revision);
        ArgumentOutOfRangeException.ThrowIfLessThan(maxGroups, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(maxGroups, 100);
        var groups = state.Changes.Where(x => x.Revision > afterRevision).Take(maxGroups).ToImmutableArray();
        return new(afterRevision, groups.IsEmpty ? afterRevision : groups[^1].Revision, state.Revision, groups);
    }

    internal static string ValidateText(TaskSnapshot task)
    {
        if (string.IsNullOrWhiteSpace(task.Title) || task.Title.EnumerateRunes().Count() > 160)
            return "INVALID_TITLE";
        if (task.Description?.EnumerateRunes().Count() > 4000) return "INVALID_DESCRIPTION";
        return JsonSerializer.SerializeToUtf8Bytes(task with { Cleanup = null }).Length > 16 * 1024 ? "TASK_TOO_LARGE" : "ACCEPTED";
    }
}

public sealed record MembershipResult(string Code, HouseholdInvitation? Invitation = null);
public sealed record SequenceOutcome(ulong Sequence, string State, string? Fingerprint = null,
    ulong? EffectRevision = null, string? Code = null, OperationReceipt? Receipt = null);
public sealed record OutcomePage(string Code, Guid WorkspaceId, Guid StateEpoch, Guid DeviceId, ulong HighWater,
    IReadOnlyList<SequenceOutcome> Outcomes);
