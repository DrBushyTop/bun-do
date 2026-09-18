using System.Collections.Immutable;
using System.Diagnostics;
using BunDo.Domain;
using BunDo.Functions.Sync;

namespace BunDo.Functions.Households;

public sealed record VisibilityRequest(Guid Id, Guid Source, Guid SourceEpoch, string TaskId,
    ulong ExpectedRevision, Guid Target, Guid TargetEpoch);
public sealed record VisibilityJournal(VisibilityRequest Request, Guid Actor, VisibilityContent Content, string Status = "PENDING");
public sealed record VisibilityReply(string Code, Guid Id, string? TaskId = null);

/// <summary>The owner's private journal survives a crash or household removal between partition commits.</summary>
public sealed class TaskVisibilityService(IHouseholdDocuments documents, TimeProvider? time = null)
{
    private readonly TimeProvider clock = time ?? TimeProvider.System;
    private static string Partition(Guid id) => id.ToString("D");
    private static string JournalId(Guid id) => $"visibility:{id:D}";
    private static string ReceiptId(Guid id) => $"visibility-receipt:{id:D}";

    public async Task<VisibilityReply> SendAsync(Guid actor, VisibilityRequest request, CancellationToken ct)
    {
        if (actor == Guid.Empty || request.Id == Guid.Empty || request.Source == request.Target ||
            request.Source == Guid.Empty || request.Target == Guid.Empty || string.IsNullOrEmpty(request.TaskId))
            return new("INVALID_REQUEST", request.Id);
        var personal = HouseholdService.PersonalId(actor);
        if (request.Source != personal && request.Target != personal) return new("INVALID_AUDIENCE", request.Id);
        var personalState = await ReadAsync(personal, ct);
        if (personalState?.Value.Membership.PersonalOwnerId != actor || !personalState.Value.Membership.CanRead(actor))
            return new("FORBIDDEN", request.Id);
        var journal = await documents.ReadAsync<VisibilityJournal>(Partition(personal), JournalId(request.Id), ct);
        if (journal is not null && (journal.Value.Actor != actor || journal.Value.Request != request))
            return new("REQUEST_ID_REUSED", request.Id);
        if (journal?.Value.Status == "CANCELLED") return new("CANCELLED", request.Id);
        if (journal?.Value.Status == "COMPLETED") return new("ACCEPTED", request.Id, TaskIdentity.ForCreate(request.Id, 1));
        if (journal?.Value.Status == "SOURCE_CHANGED") return new("SOURCE_CHANGED", request.Id);
        if (journal is null)
        {
            var source = await LoadSource(actor, request, ct);
            if (source.Value.Revision != request.ExpectedRevision) return new("SOURCE_CHANGED", request.Id);
            var code = TaskVisibility.Validate(source.Value, actor, request.TaskId);
            if (code != "ACCEPTED") return new(code, request.Id);
            var target = await Target(actor, request, ct);
            var content = TaskVisibility.Export(source.Value, request.TaskId);
            if (target.Value.TaskCount + content.Tasks.Length > 1024) return new("TASK_LIMIT", request.Id);
            var proposed = new VisibilityJournal(request, actor, content);
            await documents.WriteAsync(Partition(personal), JournalId(request.Id), null, proposed, ct);
            journal = (await documents.ReadAsync<VisibilityJournal>(Partition(personal), JournalId(request.Id), ct))!;
            if (journal.Value.Request != request || journal.Value.Actor != actor) return new("REQUEST_ID_REUSED", request.Id);
        }
        Activity.Current?.SetTag("operation.stage", "visibility_remove");
        var retired = await documents.ReadAsync<VisibilityReceipt>(Partition(request.Source), ReceiptId(request.Id), ct);
        if (retired is not null && (retired.Value.Actor != actor || retired.Value.Stage != "REMOVED")) return new("REQUEST_ID_REUSED", request.Id);
        if (retired is null)
        {
            // Prepare is not approval to read future source edits. The reviewed revision must still match.
            var source = await LoadSource(actor, request, ct);
            if (source.Value.Revision != request.ExpectedRevision)
            {
                retired = await documents.ReadAsync<VisibilityReceipt>(Partition(request.Source), ReceiptId(request.Id), ct);
                if (retired is null) return await Changed(personal, journal, ct);
                if (retired.Value.Actor != actor || retired.Value.Stage != "REMOVED") return new("REQUEST_ID_REUSED", request.Id);
                return await SendAsync(actor, request, ct);
            }
            var code = TaskVisibility.Validate(source.Value, actor, request.TaskId);
            if (code != "ACCEPTED") return new(code, request.Id);
            await Target(actor, request, ct);
            var next = TaskVisibility.Retire(source.Value, actor, request.Id, request.TaskId, clock.GetUtcNow());
            if (!await documents.CommitWorkspaceAsync(source.Stored, next, ct))
            {
                retired = await documents.ReadAsync<VisibilityReceipt>(Partition(request.Source), ReceiptId(request.Id), ct);
                if (retired is null) return new("BUSY", request.Id);
            }
        }
        // After retirement, this immutable private journal is sufficient even if the owner left the household.
        Activity.Current?.SetTag("operation.stage", "visibility_import");
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var imported = await documents.ReadAsync<VisibilityReceipt>(Partition(request.Target), ReceiptId(request.Id), ct);
            if (imported is not null)
            {
                if (imported.Value.Actor == actor && imported.Value.Stage == "CANCELLED") return await CancelAsync(actor, request.Id, ct);
                if (imported.Value.Actor != actor || imported.Value.Stage != "IMPORTED") return new("REQUEST_ID_REUSED", request.Id);
                await documents.WriteAsync(Partition(personal), JournalId(request.Id), journal.Version,
                    journal.Value with { Status = "COMPLETED", Content = new([], null) }, ct);
                return new("ACCEPTED", request.Id, TaskIdentity.ForCreate(request.Id, 1));
            }
            var target = await Target(actor, request, ct);
            if (target.Value.TaskCount + journal.Value.Content.Tasks.Length > 1024) return new("TASK_LIMIT", request.Id);
            var next = TaskVisibility.Import(target.Value, actor, request.Id, journal.Value.Content, clock.GetUtcNow());
            if (await documents.CommitWorkspaceAsync(target, next, ct)) continue;
        }
        return new("BUSY", request.Id);
    }

    public async Task<VisibilityReply> CancelAsync(Guid actor, Guid id, CancellationToken ct)
    {
        var personal = HouseholdService.PersonalId(actor);
        var journal = await documents.ReadAsync<VisibilityJournal>(Partition(personal), JournalId(id), ct);
        if (journal is null || journal.Value.Actor != actor || journal.Value.Request.Source != personal)
            return new("FORBIDDEN", id);
        if (journal.Value.Status == "COMPLETED") return new("ACCEPTED", id, TaskIdentity.ForCreate(id, 1));
        if (journal.Value.Status is "CANCELLED" or "SOURCE_CHANGED") return new(journal.Value.Status, id);
        var request = journal.Value.Request;
        // Fence import with a receipt in the destination partition. No household content is read or edited.
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var target = await ReadAsync(request.Target, ct);
            if (target is null) return new("BUSY", id);
            var imported = await documents.ReadAsync<VisibilityReceipt>(Partition(request.Target), ReceiptId(id), ct);
            if (imported is not null)
            {
                if (imported.Value.Actor != actor) return new("REQUEST_ID_REUSED", id);
                if (imported.Value.Stage == "IMPORTED") return await SendAsync(actor, request, ct);
                if (imported.Value.Stage == "CANCELLED") break;
                return new("REQUEST_ID_REUSED", id);
            }
            var revision = checked(target.Value.Revision + 1);
            if (await documents.CommitWorkspaceAsync(target, target.Value with { Revision = revision,
                VisibilityReceipts = [new(id, actor, "CANCELLED")], Changes = [new(revision, [])] }, ct)) break;
            if (attempt == 7) return new("BUSY", id);
        }
        var retired = await documents.ReadAsync<VisibilityReceipt>(Partition(personal), ReceiptId(id), ct);
        if (retired is null)
        {
            // A prepare without retirement still has its original private task.
            // Fence a concurrent retirement by advancing the personal workspace revision.
            var source = await ReadAsync(personal, ct);
            if (source is null || source.Value.Membership.PersonalOwnerId != actor) return new("FORBIDDEN", id);
            var revision = checked(source.Value.Revision + 1);
            if (!await documents.CommitWorkspaceAsync(source, source.Value with { Revision = revision, Changes = [new(revision, [])] }, ct))
                return new("BUSY", id);
            retired = await documents.ReadAsync<VisibilityReceipt>(Partition(personal), ReceiptId(id), ct);
        }
        if (retired is not null)
        {
            var restoreId = Guid.Parse(TaskIdentity.ForCreate(id, 200));
            for (var attempt = 0; attempt < 8; attempt++)
            {
                if (await documents.ReadAsync<VisibilityReceipt>(Partition(personal), ReceiptId(restoreId), ct) is not null) break;
                var source = await ReadAsync(personal, ct);
                if (source is null || source.Value.Membership.PersonalOwnerId != actor) return new("FORBIDDEN", id);
                var existing = ImmutableDictionary<string, TaskSnapshot>.Empty;
                foreach (var task in journal.Value.Content.Tasks)
                    if ((await documents.ReadAsync<TaskSnapshot>(Partition(personal), WorkspaceCommit.TaskId(task.Id), ct))?.Value is { } value)
                        existing = existing.Add(task.Id, value);
                var missing = journal.Value.Content.Tasks.Count(t => !existing.ContainsKey(t.Id));
                if (source.Value.TaskCount + missing > 1024) return new("TASK_LIMIT", id);
                if (await documents.CommitWorkspaceAsync(source, TaskVisibility.Import(source.Value with { Tasks = existing }, actor, restoreId,
                    journal.Value.Content, clock.GetUtcNow(), restoreOriginalIds: true), ct)) break;
                if (attempt == 7) return new("BUSY", id);
            }
        }
        await documents.WriteAsync(Partition(personal), JournalId(id), journal.Version,
            journal.Value with { Status = "CANCELLED", Content = new([], null) }, ct);
        return new("CANCELLED", id);
    }

    public async Task<VisibilityJournal[]> PendingAsync(Guid actor, CancellationToken ct)
    {
        var personal = HouseholdService.PersonalId(actor);
        var state = await ReadAsync(personal, ct);
        if (state?.Value.Membership.PersonalOwnerId != actor) throw new SyncException("FORBIDDEN");
        var pending = new List<VisibilityJournal>();
        string? continuation = null;
        do
        {
            var page = await documents.ReadPageAsync<VisibilityJournal>(Partition(personal), "visibility:", continuation, 64, ct);
            pending.AddRange(page.Items.Where(x => x.Value.Actor == actor && x.Value.Status == "PENDING").Select(x => x.Value));
            if (pending.Count >= 1) return pending.Take(1).ToArray();
            continuation = page.Continuation;
        } while (continuation is not null);
        return pending.ToArray();
    }

    private async Task<VisibilityReply> Changed(Guid personal, StoredDocument<VisibilityJournal> journal, CancellationToken ct)
    {
        await documents.WriteAsync(Partition(personal), JournalId(journal.Value.Request.Id), journal.Version,
            journal.Value with { Status = "SOURCE_CHANGED", Content = new([], null) }, ct);
        return new("SOURCE_CHANGED", journal.Value.Request.Id);
    }

    private Task<StoredDocument<WorkspaceState>?> ReadAsync(Guid workspace, CancellationToken ct) =>
        documents.ReadAsync<WorkspaceState>(Partition(workspace), "state", ct);

    private async Task<StoredDocument<WorkspaceState>> Target(Guid actor, VisibilityRequest request, CancellationToken ct)
    {
        var state = await ReadAsync(request.Target, ct);
        if (state is null || !state.Value.Membership.CanRead(actor)) throw new SyncException("FORBIDDEN");
        if (state.Value.StateEpoch != request.TargetEpoch) throw new SyncException("EPOCH_CHANGED");
        if (state.Value.Membership.PersonalOwnerId is { } owner && owner != actor) throw new SyncException("FORBIDDEN");
        return state;
    }

    private sealed record SourceView(StoredDocument<WorkspaceState> Stored, WorkspaceState Value);

    private async Task<SourceView> LoadSource(Guid actor, VisibilityRequest request, CancellationToken ct)
    {
        var state = await ReadAsync(request.Source, ct);
        if (state is null || !state.Value.Membership.CanRead(actor)) throw new SyncException("FORBIDDEN");
        if (state.Value.StateEpoch != request.SourceEpoch) throw new SyncException("EPOCH_CHANGED");
        var tasks = ImmutableDictionary<string, TaskSnapshot>.Empty;
        async Task<TaskSnapshot?> Task(string id) =>
            (await documents.ReadAsync<TaskSnapshot>(Partition(request.Source), WorkspaceCommit.TaskId(id), ct))?.Value ?? state.Value.Tasks.GetValueOrDefault(id);
        var root = await Task(request.TaskId);
        if (root is not null)
        {
            tasks = tasks.Add(root.Id, root);
            foreach (var id in root.ChildOrder ?? [])
                if (await Task(id) is { } child) tasks = tasks.SetItem(id, child);
        }
        var repeats = ImmutableDictionary<string, RepeatSchedule>.Empty;
        if (root?.Repeat is { } link && (await documents.ReadAsync<RepeatSchedule>(Partition(request.Source), $"repeat:{link.Id}", ct))?.Value is { } repeat)
            repeats = repeats.Add(repeat.Id, repeat);
        return new(state, state.Value with { Tasks = tasks, Repeats = repeats });
    }
}
