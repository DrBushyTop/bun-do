using System.Collections.Immutable;

namespace BunDo.Domain;

public sealed record FieldVersion(ulong Server, ulong Human);
public sealed record TaskCapture(string Title, string? Description, System.Text.Json.JsonElement Context, DateTimeOffset ReceivedAt);
public sealed record FirstCompletion(string RootId, Guid MemberId, DateTimeOffset AcceptedAt);
public sealed record TaskDeletion(string GroupId, DateTimeOffset DeletedAt, bool Purging = false);
public sealed record TaskSnapshot(
    string Id, string Title, string? Description, FieldVersion TitleVersion, FieldVersion DescriptionVersion,
    ulong DeletionVersion = 0, TaskCapture? Capture = null,
    string Lifecycle = "OPEN", ulong LifecycleVersion = 0, Guid? ClaimantId = null,
    ulong ClaimVersion = 0, ulong HierarchyVersion = 0, Guid? LifecycleActorId = null,
    DateTimeOffset? LifecycleAt = null, FirstCompletion? FirstCompletion = null, ulong OrderIntentVersion = 0,
    TaskDeletion? Deletion = null, DateTimeOffset? SnoozedUntil = null,
    string? ParentId = null, bool IsChecklist = false, ImmutableArray<string>? ChildOrder = null,
    ulong SubtreeVersion = 0, ulong SnoozeVersion = 0, string? CancellationGroupId = null,
    bool EmptyChecklist = false);
public sealed record DeviceRegistration(Guid DeviceId, Guid MemberId, ulong LastTerminalSequence = 0,
    ulong AcknowledgedThrough = 0, string? RegistryPartition = null);
public sealed record OperationReceipt(
    string OperationId, string Fingerprint, string Code, ulong EffectRevision, TaskSnapshot? Task,
    DateTimeOffset? RecordedAt = null, ImmutableArray<TaskSnapshot>? RelatedTasks = null)
{
    public bool Accepted => Code == "ACCEPTED";
}
public sealed record SubmissionResult(string Code, OperationReceipt? Receipt = null);
public sealed record ChangeGroup(ulong Revision, ImmutableArray<TaskSnapshot> Tasks,
    DateTimeOffset? RecordedAt = null, string? OperationId = null, ImmutableArray<string>? RootOrder = null,
    ImmutableArray<string>? PurgedTaskIds = null, ImmutableArray<FirstCompletion>? RetainedCompletions = null);
public sealed record SnapshotPin(Guid Id, Guid MemberId, Guid DeviceId, Guid ArtifactId, ulong Revision,
    DateTimeOffset ExpiresAt, DateTimeOffset BuildUntil, string? ManifestHash = null);
public sealed record ChangePage(
    ulong AfterRevision, ulong ThroughRevision, ulong HeadRevision, ImmutableArray<ChangeGroup> Groups);

public sealed record WorkspaceState(
    Guid WorkspaceId,
    Guid StateEpoch,
    ulong Revision,
    ImmutableDictionary<Guid, DeviceRegistration> Devices,
    ImmutableDictionary<string, TaskSnapshot> Tasks,
    ImmutableDictionary<string, OperationReceipt> Receipts,
    ImmutableArray<ChangeGroup> Changes,
    HouseholdMembership Membership,
    string Name = "Household",
    string? CursorSecret = null,
    int TaskCount = 0,
    ulong PrunedThrough = 0,
    ImmutableDictionary<Guid, SnapshotPin>? SnapshotPins = null, ImmutableArray<string>? RootOrder = null);

/// <summary>The transaction seam; a failed compare-and-swap must have no effects.</summary>
public interface IWorkspaceStore
{
    WorkspaceState Read();
    bool TryCommit(ulong expectedRevision, WorkspaceState next);
}

/// <summary>Atomic in-memory model adapter. It makes no disk or Cosmos durability promise.</summary>
public sealed class InMemoryWorkspaceStore : IWorkspaceStore
{
    private readonly object gate = new();
    private WorkspaceState state;

    public InMemoryWorkspaceStore(Guid workspaceId, Guid stateEpoch, HouseholdMembership membership,
        IEnumerable<DeviceRegistration> devices)
    {
        state = new(workspaceId, stateEpoch, 0,
            devices.ToImmutableDictionary(x => x.DeviceId),
            ImmutableDictionary<string, TaskSnapshot>.Empty,
            ImmutableDictionary<string, OperationReceipt>.Empty, [], membership);
    }

    public WorkspaceState Read()
    {
        lock (gate) return state;
    }

    public bool TryCommit(ulong expectedRevision, WorkspaceState next)
    {
        lock (gate)
        {
            if (state.Revision != expectedRevision) return false;
            if (expectedRevision == ulong.MaxValue || next.Revision != expectedRevision + 1 ||
                next.WorkspaceId != state.WorkspaceId || next.StateEpoch != state.StateEpoch)
                throw new ArgumentException("Commit must advance exactly one revision in the same workspace epoch.", nameof(next));
            state = next;
            return true;
        }
    }
}
