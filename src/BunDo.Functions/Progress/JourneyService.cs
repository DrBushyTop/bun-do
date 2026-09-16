using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Sync;

namespace BunDo.Functions.Progress;

public sealed class JourneyService(IHouseholdDocuments documents, TimeProvider? time = null)
{
    private readonly TimeProvider clock = time ?? TimeProvider.System;

    public async Task EnableAsync(Guid member, Guid workspace, Guid epoch, CancellationToken ct)
    {
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var snapshot = await new CanonicalCompletions(documents).ReadAsync(member, workspace, epoch, ct);
            var state = snapshot.State.Value;
            if (state.Journey is not null) return;
            var revision = checked(state.Revision + 1);
            var now = clock.GetUtcNow();
            var next = state with {
                Revision = revision,
                Journey = new(snapshot.Credits.Count, now, revision),
                Changes = state.Changes.Add(new(revision, [], RecordedAt: now)),
            };
            // The same metadata ETag fences completion, purge, membership and competing enable requests.
            if (await documents.CommitWorkspaceAsync(snapshot.State, next, ct)) return;
        }
        throw new SyncException("BUSY");
    }
}
