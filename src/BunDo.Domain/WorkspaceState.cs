using System.Collections.Immutable;

namespace BunDo.Domain;

public sealed record FieldVersion(ulong Server, ulong Human);
public sealed record TaskSnapshot(
    string Id, string Title, string? Description, FieldVersion TitleVersion, FieldVersion DescriptionVersion);
public sealed record DeviceRegistration(Guid DeviceId, Guid MemberId, ulong LastTerminalSequence = 0);
public sealed record OperationReceipt(
    string OperationId, string Fingerprint, string Code, ulong EffectRevision, TaskSnapshot? Task)
{
    public bool Accepted => Code == "ACCEPTED";
}
public sealed record SubmissionResult(string Code, OperationReceipt? Receipt = null);
public sealed record ChangeGroup(ulong Revision, ImmutableArray<TaskSnapshot> Tasks);
public sealed record ChangePage(
    ulong AfterRevision, ulong ThroughRevision, ulong HeadRevision, ImmutableArray<ChangeGroup> Groups);

public sealed record WorkspaceState(
    Guid WorkspaceId,
    Guid StateEpoch,
    ulong Revision,
    ImmutableDictionary<Guid, DeviceRegistration> Devices,
    ImmutableDictionary<string, TaskSnapshot> Tasks,
    ImmutableDictionary<string, OperationReceipt> Receipts,
    ImmutableArray<ChangeGroup> Changes);

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

    public InMemoryWorkspaceStore(Guid workspaceId, Guid stateEpoch, IEnumerable<DeviceRegistration> devices)
    {
        state = new(workspaceId, stateEpoch, 0,
            devices.ToImmutableDictionary(x => x.DeviceId),
            ImmutableDictionary<string, TaskSnapshot>.Empty,
            ImmutableDictionary<string, OperationReceipt>.Empty, []);
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
