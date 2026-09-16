using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Sync;

namespace BunDo.Functions.Progress;

internal sealed record CanonicalCompletionSnapshot(StoredDocument<WorkspaceState> State,
    IReadOnlyList<FirstCompletion> Credits);

/// <summary>One fenced view of live, legacy and purged credits, shared by statistics and journey enablement.</summary>
internal sealed class CanonicalCompletions(IHouseholdDocuments documents)
{
    public async Task<CanonicalCompletionSnapshot> ReadAsync(Guid member, Guid workspace, Guid epoch, CancellationToken ct)
    {
        var partition = workspace.ToString("D");
        async Task<StoredDocument<WorkspaceState>> State()
        {
            var state = await documents.ReadAsync<WorkspaceState>(partition, "state", ct);
            if (state is null || !state.Value.Membership.CanRead(member)) throw new SyncException("FORBIDDEN");
            if (state.Value.StateEpoch != epoch) throw new SyncException("EPOCH_CHANGED");
            return state;
        }
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var before = await State();
            var credits = before.Value.Tasks.Values.Where(t => t.FirstCompletion != null).Select(t => t.FirstCompletion!).ToList();
            string? continuation = null;
            do
            {
                var page = await documents.ReadPageAsync<TaskSnapshot>(partition, "task:", continuation, 64, ct);
                credits.AddRange(page.Items.Where(t => t.Value.FirstCompletion != null).Select(t => t.Value.FirstCompletion!));
                continuation = page.Continuation;
            } while (continuation != null);
            do
            {
                var page = await documents.ReadPageAsync<FirstCompletion>(partition, "completion:", continuation, 64, ct);
                credits.AddRange(page.Items.Select(c => c.Value));
                continuation = page.Continuation;
            } while (continuation != null);
            var after = await State();
            if (before.Version != after.Version) continue;
            return new(after, credits.GroupBy(c => c.RootId).Select(g => g.MinBy(c => c.AcceptedAt)!).ToArray());
        }
        throw new SyncException("BUSY");
    }
}
