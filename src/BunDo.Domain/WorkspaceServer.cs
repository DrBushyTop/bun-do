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
            var code = "ACCEPTED";
            TaskSnapshot? task;
            TaskSnapshot? changed = null;
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
                        ? new(create.Title, create.Description, capture, clock.GetUtcNow()) : null);
                if (state.TaskCount >= 1024) code = "TASK_LIMIT";
                else if (state.Tasks.ContainsKey(create.TaskId))
                    code = "ENTITY_EXISTS";
                else if (create.TaskId != TaskIdentity.ForCreate(operation.DeviceId, operation.Sequence))
                    code = "INVALID_TASK_ID";
                else
                    code = ValidateText(task);
                if (code == "ACCEPTED") changed = task;
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
            else
            {
                var edit = (EditTask)operation.Command;
                state.Tasks.TryGetValue(edit.TaskId, out task);
                if (task is null) code = "ENTITY_MISSING";
                else if (edit.ExpectedDeletionVersion is { } deletion && deletion != task.DeletionVersion)
                    code = "DELETION_CONFLICT";
                else if (edit.Title is null && edit.Description is null) code = "EMPTY_EDIT";
                else if (edit.Title is { } t && t.ExpectedHumanVersion != task.TitleVersion.Human ||
                    edit.Description is { } d && d.ExpectedHumanVersion != task.DescriptionVersion.Human)
                    code = "FIELD_CONFLICT";
                else
                {
                    var proposed = task;
                    if (edit.Title is { } title && title.Value != task.Title)
                        proposed = proposed with { Title = title.Value!, TitleVersion = new(revision, revision) };
                    if (edit.Description is { } description && description.Value != task.Description)
                        proposed = proposed with { Description = description.Value, DescriptionVersion = new(revision, revision) };
                    code = ValidateText(proposed);
                    if (code == "ACCEPTED")
                    {
                        if (proposed != task) changed = proposed;
                        task = proposed;
                    }
                }
            }
            var receipt = new OperationReceipt(operation.OperationId, operation.Fingerprint, code, revision,
                task, clock.GetUtcNow());
            var next = state with
            {
                Revision = revision,
                TaskCount = state.TaskCount + (changed is not null && operation.Command is CreateTask ? 1 : 0),
                Tasks = changed is not null ? state.Tasks.SetItem(changed.Id, changed) : state.Tasks,
                Receipts = state.Receipts.Add(operation.OperationId, receipt),
                Devices = state.Devices.SetItem(operation.DeviceId,
                    state.Devices[operation.DeviceId] with { LastTerminalSequence = operation.Sequence }),
                Changes = state.Changes.Add(new(revision, changed is not null ? [changed] : [], clock.GetUtcNow(), operation.OperationId))
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

    private static string ValidateText(TaskSnapshot task)
    {
        if (string.IsNullOrWhiteSpace(task.Title) || task.Title.EnumerateRunes().Count() > 160)
            return "INVALID_TITLE";
        if (task.Description?.EnumerateRunes().Count() > 4000) return "INVALID_DESCRIPTION";
        return JsonSerializer.SerializeToUtf8Bytes(task).Length > 16 * 1024 ? "TASK_TOO_LARGE" : "ACCEPTED";
    }
}

public sealed record MembershipResult(string Code, HouseholdInvitation? Invitation = null);
public sealed record SequenceOutcome(ulong Sequence, string State, string? Fingerprint = null,
    ulong? EffectRevision = null, string? Code = null, OperationReceipt? Receipt = null);
public sealed record OutcomePage(string Code, Guid WorkspaceId, Guid StateEpoch, Guid DeviceId, ulong HighWater,
    IReadOnlyList<SequenceOutcome> Outcomes);
