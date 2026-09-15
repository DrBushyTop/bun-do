using System.Collections.Immutable;
using System.Diagnostics;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Sync;

namespace BunDo.Functions.AI;

public interface ICleanupProvider
{
    Task<CleanupProposal> GenerateAsync(string title, string? description, CancellationToken ct, JsonElement? captureContext = null);
    Task<CleanupProposal> GenerateSplitAsync(string title, string? description, string? instructions, CancellationToken ct) =>
        throw new CleanupProviderException("PROVIDER_UNAVAILABLE");
}

/// <summary>Foreground and periodic sync resume durable intent, one inference per sync.
/// An expired lease becomes a visible retry, never an unbounded paid retry loop.</summary>
public sealed class CleanupWorker(IHouseholdDocuments documents, ICleanupProvider provider, TimeProvider? time = null)
{
    private readonly TimeProvider clock = time ?? TimeProvider.System;

    public async Task RunAsync(Guid member, Guid workspace, Guid epoch, CancellationToken ct)
    {
        string? continuation = null;
        do
        {
            var page = await documents.ReadPageAsync<TaskSnapshot>(workspace.ToString("D"), "task:", continuation, 64, ct);
            foreach (var item in page.Items)
            {
                if (item.Value.Cleanup is not { } request || request.Requester != member ||
                    request.Status is not ("PENDING" or "RUNNING")) continue;
                if (request.Status == "RUNNING" && request.LeaseUntil > clock.GetUtcNow()) continue;
                await ProcessAsync(member, workspace, epoch, item.Value.Id, ct);
                return;
            }
            continuation = page.Continuation;
        } while (continuation is not null);
    }

    public async Task ProcessAsync(Guid member, Guid workspace, Guid epoch, string taskId, CancellationToken ct)
    {
        var lease = Guid.NewGuid();
        var claimed = await Change(member, workspace, epoch, taskId, (state, task, revision) => {
            if (task.Cleanup is not { } request || request.Requester != member) return task;
            if (request.Status == "RUNNING" && request.LeaseUntil <= clock.GetUtcNow())
                return task with { Cleanup = request with { Status = "FAILED", Error = "INTERRUPTED",
                    InputTitle = null, InputDescription = null, Instructions = null, Lease = null, LeaseUntil = null } };
            if (request.Status != "PENDING") return task;
            if (!TaskCleanup.Current(state, task, request)) return task with { Cleanup = request with {
                Status = "SUPERSEDED", InputTitle = null, InputDescription = null, Instructions = null } };
            return task with { Cleanup = request with { Status = "RUNNING", Lease = lease,
                LeaseUntil = clock.GetUtcNow().AddMinutes(2) } };
        }, ct);
        if (claimed?.Cleanup is not { Status: "RUNNING" } owned || owned.Lease != lease) return;
        CleanupProposal? proposal = null;
        string? error = null;
        try { proposal = owned.Mode == "SPLIT"
            ? await provider.GenerateSplitAsync(owned.InputTitle!, owned.InputDescription, owned.Instructions, ct)
            : await provider.GenerateAsync(owned.InputTitle!, owned.InputDescription, ct, claimed.Capture?.Context); }
        catch (CleanupProviderException failure) { error = failure.Code; }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { error = "PROVIDER_TIMEOUT"; }
        catch (HttpRequestException) { error = "PROVIDER_UNAVAILABLE"; }
        // Cancellation leaves the persisted lease for the next authenticated sync to expose Retry.
        ct.ThrowIfCancellationRequested();
        Activity.Current?.SetTag("ai.mode", owned.Mode);
        Activity.Current?.SetTag("ai.result", error ?? "VALID_OUTPUT");
        await Change(member, workspace, epoch, taskId, (state, task, revision) =>
            TaskCleanup.Finish(state, task, lease, proposal, error, revision, clock.GetUtcNow()), ct);
    }

    private async Task<TaskSnapshot?> Change(Guid member, Guid workspace, Guid epoch, string id,
        Func<WorkspaceState, TaskSnapshot, ulong, TaskSnapshot> mutate, CancellationToken ct)
    {
        var partition = workspace.ToString("D");
        for (var attempt = 0; attempt < 5; attempt++)
        {
            var metadata = await documents.ReadAsync<WorkspaceState>(partition, "state", ct);
            if (metadata is null || metadata.Value.StateEpoch != epoch || !metadata.Value.Membership.CanRead(member)) return null;
            var task = (await documents.ReadAsync<TaskSnapshot>(partition, WorkspaceCommit.TaskId(id), ct))?.Value;
            if (task is null || task.Deletion is not null || task.Cleanup?.Epoch != epoch) return null;
            var tasks = ImmutableDictionary<string, TaskSnapshot>.Empty.Add(id, task);
            if (task.ParentId is { } parentId &&
                (await documents.ReadAsync<TaskSnapshot>(partition, WorkspaceCommit.TaskId(parentId), ct))?.Value is { } parent)
                tasks = tasks.Add(parentId, parent);
            var state = metadata.Value with { Tasks = tasks };
            var revision = checked(state.Revision + 1);
            var changed = mutate(state, task, revision);
            if (changed == task) return null;
            var effects = ImmutableDictionary<string, TaskSnapshot>.Empty.Add(id, changed);
            if (task.ParentId is { } root && tasks.TryGetValue(root, out var rootTask))
                effects = effects.Add(root, rootTask with { SubtreeVersion = revision });
            var next = state with { Revision = revision, Tasks = tasks.SetItems(effects),
                Changes = [new(revision, effects.Values.ToImmutableArray(), clock.GetUtcNow())] };
            if (await documents.CommitWorkspaceAsync(metadata, next, ct)) return changed;
        }
        return null;
    }
}

public sealed class UnavailableCleanupProvider : ICleanupProvider
{
    public Task<CleanupProposal> GenerateAsync(string title, string? description, CancellationToken ct, JsonElement? captureContext = null) =>
        throw new CleanupProviderException("PROVIDER_UNAVAILABLE");
}

public sealed class CleanupProviderException(string code) : Exception(code)
{
    public string Code { get; } = code;
}
