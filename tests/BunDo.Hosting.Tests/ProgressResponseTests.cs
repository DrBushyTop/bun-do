using System.Collections.Immutable;
using System.Text;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Progress;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class ProgressResponseTests
{
    [Fact]
    public async Task Near_limit_final_page_keeps_tasks_receipts_and_cursor_then_delivers_progress_on_idle_poll()
    {
        var member = Guid.NewGuid();
        var workspace = Guid.NewGuid();
        var epoch = Guid.NewGuid();
        var now = DateTimeOffset.Parse("2026-09-15T08:00:00Z");
        var events = Enumerable.Range(1, 60).Select(i => new HouseholdActivity((ulong)i,
            Guid.NewGuid().ToString(), member, "CompleteTask", now)).ToImmutableArray();
        var state = new WorkspaceState(workspace, epoch, 60, ImmutableDictionary<Guid, DeviceRegistration>.Empty,
            ImmutableDictionary<string, TaskSnapshot>.Empty, ImmutableDictionary<string, OperationReceipt>.Empty, [],
            HouseholdMembership.Create(member), CursorSecret: Convert.ToBase64String(new byte[32]), RecentActivity: events);
        var groups = Enumerable.Range(1, 60).Select(i => new ChangeGroup((ulong)i,
            Enumerable.Range(1, 16).Select(_ => new TaskSnapshot(Guid.NewGuid().ToString(), "Task",
                new string('x', 2978), new((ulong)i, (ulong)i), new((ulong)i, (ulong)i))).ToImmutableArray())).ToArray();
        var documents = new ReadOnlyDocuments(state, groups);
        var service = new SyncService(documents);
        var receipt = new OperationReceipt("retained-receipt", "fingerprint", "ACCEPTED", 60, groups[^1].Tasks[0]);
        var page = await service.PullAsync(member, workspace, epoch, null, [receipt], "ACCEPTED", default);
        Assert.False(page.HasMore);
        var progress = await new ProgressService(documents).ReadAsync(member, workspace, epoch, default);
        var complete = page with { Progress = progress };
        Assert.True(JsonSerializer.SerializeToUtf8Bytes(complete, SyncJson.Options).Length > SyncJson.MaximumResponseBytes);

        var json = SyncJson.SerializeReply(complete);
        Assert.True(Encoding.UTF8.GetByteCount(json) <= SyncJson.MaximumResponseBytes);
        using var parsed = JsonDocument.Parse(json);
        Assert.Equal(JsonValueKind.Null, parsed.RootElement.GetProperty("progress").ValueKind);
        Assert.Equal(page.Cursor, parsed.RootElement.GetProperty("cursor").GetString());
        Assert.Equal(60, parsed.RootElement.GetProperty("groups").GetArrayLength());
        Assert.Equal(receipt.OperationId, parsed.RootElement.GetProperty("receipts")[0].GetProperty("operationId").GetString());

        var idle = await service.PullAsync(member, workspace, epoch, page.Cursor, [], "ACCEPTED", default);
        using var next = JsonDocument.Parse(SyncJson.SerializeReply(idle with { Progress = progress }));
        Assert.Equal(60, next.RootElement.GetProperty("progress").GetProperty("activity").GetArrayLength());
        Assert.Empty(idle.Groups);
    }

    private sealed class ReadOnlyDocuments(WorkspaceState state, ChangeGroup[] groups) : IHouseholdDocuments
    {
        public Task<StoredDocument<T>?> ReadAsync<T>(string partition, string id, CancellationToken ct) =>
            Task.FromResult<StoredDocument<T>?>(new((T)(object)(id == "state" ? state :
                groups.Single(g => WorkspaceCommit.GroupId(g.Revision) == id)), "stable"));
        public Task<DocumentPage<T>> ReadPageAsync<T>(string partition, string prefix, string? continuation, int limit, CancellationToken ct) =>
            Task.FromResult(new DocumentPage<T>([], null));
        public Task<bool> WriteAsync<T>(string partition, string id, string? version, T value, CancellationToken ct) =>
            throw new NotSupportedException();
        public Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected, WorkspaceState next, CancellationToken ct,
            IReadOnlyList<string>? deletes = null) => throw new NotSupportedException();
    }
}
