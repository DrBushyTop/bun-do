using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Sync;

namespace BunDo.Functions.Adventures;

public sealed record ListLibrarySnapshot(Guid WorkspaceId, Guid StateEpoch, ListLibrary Library);

public sealed class ListLibraryService(IHouseholdDocuments documents, Func<CancellationToken, Task<bool>> registrationActive)
{
    public async Task<ListLibrarySnapshot> SendAsync(Guid member, Guid workspace, Guid epoch, SaveList? command, CancellationToken ct)
    {
        for (var attempt = 0; attempt < 8; attempt++)
        {
            if (!await registrationActive(ct)) throw new SyncException("REGISTRATION_RETIRED");
            var stored = await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", ct);
            if (stored is null || !stored.Value.Membership.CanRead(member)) throw new SyncException("FORBIDDEN");
            var state = stored.Value;
            if (state.Membership.PersonalOwnerId is not null) throw new SyncException("PERSONAL_WORKSPACE");
            if (state.StateEpoch != epoch) throw new SyncException("EPOCH_CHANGED");
            var library = state.ListLibrary ?? new(Lists: []);
            if (command is null) return new(workspace, epoch, library);
            var result = ReusableLists.Apply(library, command);
            if (result.Code != "ACCEPTED") throw new SyncException(result.Code);
            if (result.Library == library) return new(workspace, epoch, library);
            if (state.Revision == ulong.MaxValue) throw new SyncException("WORKSPACE_FULL");
            var next = state with { Revision = state.Revision + 1, ListLibrary = result.Library,
                Changes = [new(state.Revision + 1, [])] };
            if (System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(next).Length > HouseholdDocumentLimits.GrowthBytes) throw new HouseholdStorageFullException();
            if (await documents.CommitWorkspaceAsync(stored, next, ct)) return new(workspace, epoch, result.Library);
        }
        throw new SyncException("BUSY");
    }
}
