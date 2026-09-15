using System.Collections.Immutable;
using System.Diagnostics;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Sync;

namespace BunDo.Functions.Repeats;

/// <summary>Authenticated sync consumes persisted generation slots. Duplicate workers share the metadata CAS.</summary>
public sealed class RepeatWorker(IHouseholdDocuments documents, TimeProvider? time = null)
{
    private readonly TimeProvider clock = time ?? TimeProvider.System;

    public async Task RunAsync(Guid member, Guid workspace, Guid epoch, CancellationToken ct)
    {
        var generated = 0;
        string? continuation = null;
        do
        {
            var page = await documents.ReadPageAsync<RepeatSchedule>(workspace.ToString("D"), "repeat:", continuation, 64, ct);
            foreach (var item in page.Items.Where(item => item.Value.Active && item.Value.PendingDate is not null))
                if (await ProcessAsync(member, workspace, epoch, item.Value.Id, ct)) generated++;
            continuation = page.Continuation;
        } while (continuation is not null);
        Activity.Current?.SetTag("repeat.generated", generated);
    }

    public async Task<bool> ProcessAsync(Guid member, Guid workspace, Guid epoch, string id, CancellationToken ct)
    {
        var partition = workspace.ToString("D");
        for (var attempt = 0; attempt < 5; attempt++)
        {
            var metadata = await documents.ReadAsync<WorkspaceState>(partition, "state", ct);
            if (metadata is null || metadata.Value.StateEpoch != epoch || !metadata.Value.Membership.CanRead(member)) return false;
            var repeat = (await documents.ReadAsync<RepeatSchedule>(partition, $"repeat:{id}", ct))?.Value;
            if (repeat is not { Active: true, PendingDate: not null }) return false;
            var existing = await documents.ReadAsync<TaskSnapshot>(partition, WorkspaceCommit.TaskId(TaskRepeats.OccurrenceId(repeat)), ct);
            if (existing is not null) return false;
            var state = metadata.Value with { Tasks = ImmutableDictionary<string, TaskSnapshot>.Empty,
                Repeats = ImmutableDictionary<string, RepeatSchedule>.Empty.Add(id, repeat), Changes = [] };
            var next = TaskRepeats.Generate(state, id, clock.GetUtcNow());
            if (next is null) return false;
            try { if (await documents.CommitWorkspaceAsync(metadata, next, ct)) return true; }
            catch (WorkspaceCommitTooLargeException) { return false; }
        }
        return false;
    }
}
