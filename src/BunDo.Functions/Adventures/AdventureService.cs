using System.Diagnostics;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.AI;
using BunDo.Functions.Households;
using BunDo.Functions.Sync;

namespace BunDo.Functions.Adventures;

public sealed record AdventureSnapshot(Guid WorkspaceId, Guid StateEpoch, ulong Revision,
    AdventureBoard Board, AdventureProgress? Progress);

/// <summary>A dedicated online operation. Inference never runs inside ordinary task sync.</summary>
public sealed class AdventureService(IHouseholdDocuments documents, IAdventureProvider? provider = null, TimeProvider? time = null,
    Func<CancellationToken, Task<bool>>? registrationActive = null)
{
    public const int MaximumInputBytes = 64 * 1024;
    public const int MaximumInputRoots = 32;
    private readonly TimeProvider clock = time ?? TimeProvider.System;
    private sealed record View(StoredDocument<WorkspaceState> State, Dictionary<string, TaskSnapshot> Tasks,
        AdventureInput[] Input);

    public async Task<AdventureSnapshot> ReadAsync(Guid member, Guid workspace, Guid epoch, CancellationToken ct)
    {
        var view = await Load(member, workspace, epoch, false, [], ct);
        var board = view.State.Value.Adventures ?? new();
        // Expiration is a read projection, not a background timer or an inference trigger.
        if (board.Batch is { Status: "READY" } batch && batch.ExpiresAt <= clock.GetUtcNow())
            board = board with { Batch = batch with { Status = "EXPIRED", Proposals = null } };
        if (board.Batch is { Status: "RUNNING" } running && running.LeaseUntil <= clock.GetUtcNow())
            board = board with { Batch = running with { Status = "FAILED", Error = "INTERRUPTED" } };
        return new(workspace, epoch, view.State.Value.Revision, board,
            board.Active is { } active ? HouseholdAdventure.Progress(active.Draft, view.Tasks) : null);
    }

    public async Task RefreshAsync(Guid member, Guid workspace, Guid epoch, Guid? expectedBatch, bool retry, CancellationToken ct)
    {
        View? owned = null;
        var id = Guid.NewGuid();
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var view = await Load(member, workspace, epoch, true, [], ct);
            var board = view.State.Value.Adventures ?? new();
            var batch = board.Batch;
            var now = clock.GetUtcNow();
            if (board.Active is not null || batch?.Id != expectedBatch) return;
            if (batch is { Status: "READY" } && batch.ExpiresAt > now ||
                batch is { Status: "RUNNING" } && batch.LeaseUntil > now) return;
            if (batch is { Status: "FAILED" or "RUNNING" } && !retry) return;
            // EMPTY is rechecked on online visits so newly captured tasks can become suggestions.
            var nextBatch = view.Input.Length == 0 ? new AdventureBatch(id, "EMPTY", now) :
                provider is null ? new AdventureBatch(id, "FAILED", now, Error: "PROVIDER_UNAVAILABLE") :
                new AdventureBatch(id, "RUNNING", now, LeaseUntil: now.AddMinutes(2));
            if (!await Commit(view.State, board with { Batch = nextBatch }, ct)) continue;
            if (nextBatch.Status != "RUNNING") return;
            owned = view;
            break;
        }
        if (owned is null) throw new SyncException("BUSY");
        AdventureDraft[]? drafts = null;
        string? failure = null;
        try { drafts = await provider!.GenerateAsync(owned.Input, ct); }
        catch (CleanupProviderException error) { failure = SafeFailure(error.Code); }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { failure = "PROVIDER_TIMEOUT"; }
        catch (HttpRequestException) { failure = "PROVIDER_UNAVAILABLE"; }
        ct.ThrowIfCancellationRequested();
        var inputs = owned.Input.ToDictionary(t => t.RootId, StringComparer.Ordinal);
        if (failure is null && (drafts is not { Length: 2 } || drafts.Any(d => !HouseholdAdventure.Valid(d) ||
            d.Phases.Any(p => !inputs.ContainsKey(p.RootId))))) failure = "INVALID_OUTPUT";
        Activity.Current?.SetTag("ai.mode", "ADVENTURE");
        Activity.Current?.SetTag("ai.result", failure ?? "VALID_OUTPUT");
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var view = await Load(member, workspace, epoch, false, inputs.Keys, ct);
            var board = view.State.Value.Adventures ?? new();
            if (board.Active is not null || board.Batch is not { Status: "RUNNING" } batch || batch.Id != id) return;
            var now = clock.GetUtcNow();
            if (batch.LeaseUntil <= now) failure = "INTERRUPTED";
            if (failure is null && inputs.Keys.Any(root => !view.Tasks.TryGetValue(root, out var task) ||
                !SameSource(owned.Tasks[root], task))) failure = "SOURCE_CHANGED";
            var next = batch with { Status = failure is null ? "READY" : "FAILED", Error = failure,
                LeaseUntil = null, ExpiresAt = failure is null ? now.AddHours(24) : null,
                Proposals = failure is null ? drafts!.Select(d => new AdventureProposal(Guid.NewGuid(), d)).ToArray() : null };
            if (await Commit(view.State, board with { Batch = next }, ct)) return;
        }
        throw new SyncException("BUSY");
    }

    public Task AcceptAsync(Guid member, Guid workspace, Guid epoch, Guid batchId, Guid proposalId, CancellationToken ct) =>
        Change(member, workspace, epoch, [], (view, board) => HouseholdAdventure.Accept(board, batchId, proposalId,
            view.Tasks, checked(view.State.Value.Revision + 1), clock.GetUtcNow()), ct);

    public Task EditAsync(Guid member, Guid workspace, Guid epoch, Guid id, ulong version, AdventureDraft draft, CancellationToken ct)
    {
        if (!HouseholdAdventure.Valid(draft, allowEmpty: true)) throw new SyncException("INVALID_ADVENTURE");
        return Change(member, workspace, epoch, draft.Phases.Select(p => p.RootId), (view, board) =>
            HouseholdAdventure.Edit(board, id, version, draft, view.Tasks, checked(view.State.Value.Revision + 1)), ct);
    }

    public Task CloseAsync(Guid member, Guid workspace, Guid epoch, Guid id, ulong version, bool leave, bool confirmed, CancellationToken ct) =>
        Change(member, workspace, epoch, [], (view, board) => HouseholdAdventure.Close(board, id, version, leave, confirmed, view.Tasks), ct);

    private async Task Change(Guid member, Guid workspace, Guid epoch, IEnumerable<string> extra,
        Func<View, AdventureBoard, (string Code, AdventureBoard Board)> change, CancellationToken ct)
    {
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var view = await Load(member, workspace, epoch, false, extra, ct);
            var board = view.State.Value.Adventures ?? new();
            var result = change(view, board);
            if (result.Code != "ACCEPTED") throw new SyncException(result.Code);
            if (result.Board == board || await Commit(view.State, result.Board, ct)) return;
        }
        throw new SyncException("BUSY");
    }

    private async Task<bool> Commit(StoredDocument<WorkspaceState> stored, AdventureBoard board, CancellationToken ct)
    {
        var state = stored.Value;
        var revision = checked(state.Revision + 1);
        var next = state with { Revision = revision, Adventures = board,
            Changes = state.Changes.Add(new(revision, [], RecordedAt: clock.GetUtcNow())) };
        // Adventure growth cannot spend the storage reserve needed to remove members or delete a household.
        var size = JsonSerializer.SerializeToUtf8Bytes(next).Length;
        if (size > HouseholdDocumentLimits.GrowthBytes && size > JsonSerializer.SerializeToUtf8Bytes(state).Length)
            throw new HouseholdStorageFullException();
        return await documents.CommitWorkspaceAsync(stored, next, ct);
    }

    private async Task<View> Load(Guid member, Guid workspace, Guid epoch, bool candidates, IEnumerable<string> extra, CancellationToken ct)
    {
        var partition = workspace.ToString("D");
        async Task<StoredDocument<WorkspaceState>> State()
        {
            if (registrationActive is not null && !await registrationActive(ct)) throw new SyncException("REGISTRATION_RETIRED");
            var state = await documents.ReadAsync<WorkspaceState>(partition, "state", ct);
            if (state is null || !state.Value.Membership.CanRead(member)) throw new SyncException("FORBIDDEN");
            if (state.Value.StateEpoch != epoch) throw new SyncException("EPOCH_CHANGED");
            return state;
        }
        for (var attempt = 0; attempt < 3; attempt++)
        {
            var before = await State();
            var tasks = new Dictionary<string, TaskSnapshot>(StringComparer.Ordinal);
            var input = new Dictionary<string, AdventureInput>(StringComparer.Ordinal);
            var inputBytes = 64;
            void Consider(TaskSnapshot task)
            {
                if (!HouseholdAdventure.Candidate(task) || input.ContainsKey(task.Id) || input.Count >= MaximumInputRoots) return;
                var item = new AdventureInput(task.Id, task.Title, task.Description);
                var bytes = JsonSerializer.SerializeToUtf8Bytes(item).Length + 1;
                if (inputBytes + bytes > MaximumInputBytes) return;
                inputBytes += bytes;
                input.Add(task.Id, item);
                tasks[task.Id] = task;
            }
            if (candidates && before.Value.Adventures?.Active is null)
            {
                string? continuation = null;
                do
                {
                    var page = await documents.ReadPageAsync<TaskSnapshot>(partition, "task:", continuation, 64, ct);
                    foreach (var item in page.Items) Consider(item.Value);
                    continuation = page.Continuation;
                } while (continuation is not null && input.Count < MaximumInputRoots);
                foreach (var legacy in before.Value.Tasks.Values)
                    Consider((await documents.ReadAsync<TaskSnapshot>(partition, WorkspaceCommit.TaskId(legacy.Id), ct))?.Value ?? legacy);
            }
            var board = before.Value.Adventures;
            var roots = (board?.Active?.Draft.Phases ?? []).Select(p => p.RootId)
                .Concat((board?.Batch?.Proposals ?? []).SelectMany(p => p.Draft.Phases.Select(phase => phase.RootId)))
                .Concat(extra).Distinct(StringComparer.Ordinal).ToArray();
            async Task ReadTask(string id)
            {
                var task = (await documents.ReadAsync<TaskSnapshot>(partition, WorkspaceCommit.TaskId(id), ct))?.Value ??
                    before.Value.Tasks.GetValueOrDefault(id);
                if (task is not null) tasks[id] = task;
            }
            foreach (var root in roots) await ReadTask(root);
            foreach (var child in roots.Where(tasks.ContainsKey).SelectMany(id => tasks[id].ChildOrder ?? []).Distinct().ToArray())
                await ReadTask(child);
            var after = await State();
            if (before.Version == after.Version) return new(after, tasks, input.Values.ToArray());
        }
        throw new SyncException("BUSY");
    }

    private static bool SameSource(TaskSnapshot before, TaskSnapshot after) => HouseholdAdventure.Candidate(after) &&
        before.TitleVersion == after.TitleVersion && before.DescriptionVersion == after.DescriptionVersion &&
        before.LifecycleVersion == after.LifecycleVersion && before.HierarchyVersion == after.HierarchyVersion &&
        before.DeletionVersion == after.DeletionVersion && before.SubtreeVersion == after.SubtreeVersion;

    private static string SafeFailure(string code) => code is "INVALID_OUTPUT" or "INCOMPLETE_OUTPUT" or "REFUSED" or
        "PROVIDER_TIMEOUT" or "PROVIDER_UNAVAILABLE" ? code : "PROVIDER_UNAVAILABLE";
}
