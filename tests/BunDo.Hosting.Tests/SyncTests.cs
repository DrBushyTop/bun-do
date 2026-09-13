#if DEBUG
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Sync;
using BunDo.Functions.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace BunDo.Hosting.Tests;

public sealed class SyncTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-sync-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly LocalHouseholdDocuments documents;
    public SyncTests() { documents = new(path); }
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private async Task<Guid> Create() =>
        (await new HouseholdService(documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
    private FrozenOperation Operation(Guid epoch, ulong sequence, string title = "Coffee") =>
        new(workspace, epoch, device, sequence, new CreateTask(TaskIdentity.ForCreate(device, sequence), title));

    [Fact]
    public async Task Checklist_family_is_atomic_retryable_and_loaded_for_child_commands()
    {
        var epoch = await Create(); var sync = new SyncService(documents);
        var root = (await sync.SubmitAsync(member, Operation(epoch, 1), default)).Receipt!.Task!;
        var operation = new FrozenOperation(workspace, epoch, device, 2,
            new SplitTask(root.Id, ChecklistTasks.Versions(root), Enumerable.Repeat("Step", 16).ToArray(), 1, 1));
        var split = await sync.SubmitAsync(member, operation, default);
        Assert.Equal("ACCEPTED", split.Code);
        Assert.Equal(17, split.Receipt!.RelatedTasks!.Value.Length);
        Assert.Equal(JsonSerializer.Serialize(split), JsonSerializer.Serialize(
            await new SyncService(new LocalHouseholdDocuments(path)).SubmitAsync(member, operation, default)));
        var page = await sync.PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.Equal(17, JsonDocument.Parse(page.Groups.Last().Parts.Single().Payload).RootElement.GetArrayLength());
        var child = split.Receipt.RelatedTasks.Value.First(t => t.ParentId == root.Id);
        var complete = await sync.SubmitAsync(member, new(workspace, epoch, device, 3,
            new CompleteTask(child.Id, ChecklistTasks.Versions(child))), default);
        Assert.Equal("ACCEPTED", complete.Code);
        Assert.Null(complete.Receipt!.Task!.FirstCompletion);
        root = complete.Receipt.RelatedTasks!.Value.Single(t => t.Id == root.Id);
        Assert.Equal(3UL, root.SubtreeVersion);
        var deletion = await sync.SubmitAsync(member, new(workspace, epoch, device, 4,
            new DeleteTask(root.Id, ChecklistTasks.Versions(root))), default);
        Assert.Equal("ACCEPTED", deletion.Code);
        Assert.All(deletion.Receipt!.RelatedTasks!.Value, task => Assert.Equal(deletion.Receipt.Task!.Deletion, task.Deletion));
        var restore = await sync.SubmitAsync(member, new(workspace, epoch, device, 5,
            new RestoreTask(root.Id, ChecklistTasks.Versions(deletion.Receipt.Task!))), default);
        Assert.Equal("ACCEPTED", restore.Code);
        Assert.All(restore.Receipt!.RelatedTasks!.Value, task => Assert.Null(task.Deletion));
        Assert.Equal("COMPLETED", restore.Receipt.RelatedTasks.Value.Single(t => t.Id == child.Id).Lifecycle);
    }

    [Fact]
    public async Task TaskActionsReadCanonicalDocumentsAndCommitCreditOrderAndReceiptsTogether()
    {
        var epoch = await Create();
        var sync = new SyncService(documents);
        var create = (await sync.SubmitAsync(member, Operation(epoch, 1), default)).Receipt!.Task!;
        var second = (await sync.SubmitAsync(member, Operation(epoch, 2), default)).Receipt!.Task!;
        var claim = (await sync.SubmitAsync(member, new(workspace, epoch, device, 3,
            new ClaimTask(create.Id, new(1, 1, 1, 1))), default)).Receipt!;
        Assert.Equal(member, claim.Task!.ClaimantId);
        var operation = new FrozenOperation(workspace, epoch, device, 4, new CompleteTask(create.Id, new(1, 3, 1, 1)));
        var complete = await sync.SubmitAsync(member, operation, default);
        Assert.Equal("ACCEPTED", complete.Code);
        Assert.Equal(complete, await new SyncService(new LocalHouseholdDocuments(path)).SubmitAsync(member, operation, default));
        var credit = complete.Receipt!.Task!.FirstCompletion!;
        Assert.Equal(complete.Receipt.RecordedAt, credit.AcceptedAt);
        Assert.Equal(create.Id, credit.RootId);
        var stored = (await documents.ReadAsync<TaskSnapshot>(workspace.ToString("D"), WorkspaceCommit.TaskId(create.Id), default))!.Value;
        Assert.Equal(complete.Receipt.Task, stored);
        Assert.Equal(new[] { second.Id }, (await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", default))!.Value.RootOrder);
        await sync.SubmitAsync(member, new(workspace, epoch, device, 5, new ReopenTask(create.Id, new(4, 4, 1, 1))), default);
        var again = await sync.SubmitAsync(member, new(workspace, epoch, device, 6,
            new CompleteTask(create.Id, new(5, 4, 1, 1))), default);
        Assert.Equal(credit, again.Receipt!.Task!.FirstCompletion);
        var page = await sync.PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.Equal(member, page.Membership!.Me);
        var entities = JsonDocument.Parse(page.Groups.Last().Parts[0].Payload).RootElement;
        Assert.Equal("ROOT_ORDER", entities[1].GetProperty("entityType").GetString());
        Assert.Equal(second.Id, entities[1].GetProperty("taskIds")[0].GetString());
    }

    [Fact]
    public async Task CompetingHandlersCannotBothClaimTheSameObservedTask()
    {
        var epoch = await Create();
        var other = Guid.NewGuid();
        var meta = (await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", default))!;
        await documents.WriteAsync(workspace.ToString("D"), "state", meta.Version, meta.Value with {
            Membership = meta.Value.Membership with { Members = meta.Value.Membership.Members.Add(other, new(other, 0, DisplayName: "Bob")) },
        }, default);
        await new SyncService(documents).SubmitAsync(member, Operation(epoch, 1), default);
        var id = TaskIdentity.ForCreate(device, 1);
        var results = await Task.WhenAll(
            new SyncService(documents).SubmitAsync(member, new(workspace, epoch, device, 2, new ClaimTask(id, new(1, 1, 1, 1))), default),
            new SyncService(documents).SubmitAsync(other, new(workspace, epoch, Guid.NewGuid(), 1, new ClaimTask(id, new(1, 1, 1, 1))), default));
        Assert.Single(results, r => r.Code == "ACCEPTED");
        Assert.Single(results, r => r.Code == "CLAIM_CONFLICT");
    }

    [Theory]
    [InlineData("{}")][InlineData("[]")][InlineData("null")]
    [InlineData("{\"workspaceId\":0}")][InlineData("{\"envelopes\":null}")]
    public async Task HttpMalformedRequestsAreBadRequestsNotStorageFailures(string body)
    {
        using var local = new LocalIdentity(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> {
            ["AZURE_FUNCTIONS_ENVIRONMENT"] = "Development", ["BunDoIdentity:Mode"] = "Local",
        }).Build());
        using var services = new ServiceCollection().AddSingleton<IHouseholdDocuments>(documents)
            .AddSingleton<IRegistrationStore>(new LocalRegistrationStore(Path.Combine(path, "registrations")))
            .BuildServiceProvider();
        var http = new DefaultHttpContext();
        http.Request.Headers.Authorization = "Bearer " + local.Issue("alice", "valid");
        http.Request.Body = new MemoryStream(Encoding.UTF8.GetBytes(body));
        var result = Assert.IsType<ObjectResult>(await new SyncFunction(local.Validator, services).Run(http.Request));
        Assert.Equal(400, result.StatusCode);
        Assert.Equal("no-store", http.Response.Headers.CacheControl);
    }

    [Fact]
    public async Task HttpRequiresActiveRegistrationBoundToValidatedIdentity()
    {
        var epoch = await Create();
        using var local = new LocalIdentity(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> {
            ["AZURE_FUNCTIONS_ENVIRONMENT"] = "Development", ["BunDoIdentity:Mode"] = "Local",
        }).Build());
        var registrations = new LocalRegistrationStore(Path.Combine(path, "registrations"));
        var alice = new AccountIdentity(LocalIdentity.Issuer, "alice");
        var registration = (await registrations.RegisterAsync(alice, Guid.NewGuid(), null, default)).Registration!;
        using var services = new ServiceCollection().AddSingleton<IHouseholdDocuments>(documents)
            .AddSingleton<IRegistrationStore>(registrations).BuildServiceProvider();
        var function = new SyncFunction(local.Validator, services);
        async Task<IActionResult> Send(string? token)
        {
            var http = new DefaultHttpContext();
            if (token is not null) http.Request.Headers.Authorization = "Bearer " + token;
            http.Request.Body = new MemoryStream(JsonSerializer.SerializeToUtf8Bytes(new {
                workspaceId = workspace, stateEpoch = epoch, registrationId = registration.RegistrationId,
                cursor = (string?)null, envelopes = Array.Empty<string>(),
            }));
            return await function.Run(http.Request);
        }
        Assert.IsType<UnauthorizedResult>(await Send(null));
        Assert.Equal(403, Assert.IsType<ObjectResult>(await Send(local.Issue("bob", "valid"))).StatusCode);
        await registrations.RegisterAsync(alice, Guid.NewGuid(), registration.RegistrationId, default);
        Assert.Equal(403, Assert.IsType<ObjectResult>(await Send(local.Issue("alice", "valid"))).StatusCode);
    }

    [Fact]
    public async Task IndependentHandlersRetryLostResponseWithoutRepeatingEffects()
    {
        var epoch = await Create();
        var first = new SyncService(documents);
        var second = new SyncService(documents);
        var operation = Operation(epoch, 1);
        var result = await first.SubmitAsync(member, operation, default);
        Assert.Equal("ACCEPTED", result.Code);
        Assert.Equal(result, await second.SubmitAsync(member, operation, default));
        Assert.Equal("OPERATION_ID_REUSED", (await second.SubmitAsync(member, Operation(epoch, 1, "Tea"), default)).Code);
        var page = await second.PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.Equal(1UL, page.ThroughRevision);
        var group = Assert.Single(page.Groups);
        var part = Assert.Single(group.Parts);
        Assert.Equal(1, group.PartCount);
        Assert.Equal(Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(part.Payload))), group.Digest);
        Assert.Equal(0, part.PartIndex);
        var task = JsonDocument.Parse(part.Payload).RootElement[0];
        Assert.Equal("1", task.GetProperty("titleVersion").GetProperty("fieldVersion").GetString());
        Assert.Equal("Coffee", task.GetProperty("title").GetString());
        var meta = (await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", default))!.Value;
        Assert.Empty(meta.Tasks); Assert.Empty(meta.Receipts); Assert.Empty(meta.Devices); Assert.Empty(meta.Changes);
    }

    [Fact]
    public async Task AcknowledgementIsMonotonicAndRevisionedWithoutRepeatingEffects()
    {
        var epoch = await Create();
        var sync = new SyncService(documents);
        await sync.SubmitAsync(member, Operation(epoch, 1), default);
        await sync.AcknowledgeAsync(member, workspace, epoch, device, 1, default);
        await sync.AcknowledgeAsync(member, workspace, epoch, device, 1, default);
        var page = await sync.PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.Equal(2UL, page.ThroughRevision);
        Assert.Equal("[]", page.Groups[1].Parts[0].Payload);
        Assert.Equal("INVALID_ACKNOWLEDGEMENT", (await Assert.ThrowsAsync<SyncException>(() =>
            sync.AcknowledgeAsync(member, workspace, epoch, device, 2, default))).Code);
        var registered = await documents.ReadAsync<DeviceRegistration>(workspace.ToString("D"),
            WorkspaceCommit.DeviceId(device), default);
        Assert.Equal(1UL, registered!.Value.AcknowledgedThrough);
    }

    [Fact]
    public async Task ConcurrentHandlersAllocateUniqueRevisions()
    {
        var epoch = await Create();
        var operations = Enumerable.Range(0, 5).Select(i => {
            var id = Guid.NewGuid();
            return new FrozenOperation(workspace, epoch, id, 1, new CreateTask(TaskIdentity.ForCreate(id, 1), $"Task {i}"));
        }).ToArray();
        var results = await Task.WhenAll(operations.Select(x => new SyncService(documents).SubmitAsync(member, x, default)));
        Assert.All(results, r => Assert.Equal("ACCEPTED", r.Code));
        Assert.Equal(5, results.Select(r => r.Receipt!.EffectRevision).Distinct().Count());
        var page = await new SyncService(documents).PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.Equal([1UL, 2, 3, 4, 5], page.Groups.Select(x => x.Revision));
    }

    [Fact]
    public async Task SequenceGapAndRemovedMembershipDoNotConsumeCommands()
    {
        var epoch = await Create();
        var sync = new SyncService(documents);
        Assert.Equal("SEQUENCE_GAP", (await sync.SubmitAsync(member, Operation(epoch, 2), default)).Code);
        var home = new HouseholdService(documents);
        var view = (await home.GetAsync(member, workspace, default))!;
        Assert.Equal("ACCEPTED", (await home.ChangeAsync(member, workspace, epoch,
            new DeleteHousehold(view.MembershipVersion), default)).Code);
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(
            () => sync.SubmitAsync(member, Operation(epoch, 1), default))).Code);
    }

    [Fact]
    public async Task ContinuationKeepsItsOriginalTargetAndRejectsForgedCursor()
    {
        var epoch = await Create();
        var sync = new SyncService(documents);
        for (ulong i = 1; i <= 101; i++) Assert.Equal("ACCEPTED", (await sync.SubmitAsync(member, Operation(epoch, i), default)).Code);
        var first = await sync.PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.Equal(100UL, first.ThroughRevision); Assert.True(first.HasMore);
        await sync.SubmitAsync(member, Operation(epoch, 102), default);
        var second = await sync.PullAsync(member, workspace, epoch, first.Cursor, [], "ACCEPTED", default);
        Assert.Equal(101UL, second.ThroughRevision); Assert.Equal(102UL, second.HeadRevision); Assert.False(second.HasMore);
        var third = await sync.PullAsync(member, workspace, epoch, second.Cursor, [], "ACCEPTED", default);
        Assert.Equal(102UL, third.ThroughRevision);
        Assert.Equal("INVALID_CURSOR", (await Assert.ThrowsAsync<SyncException>(() =>
            sync.PullAsync(member, workspace, epoch, first.Cursor + "x", [], "ACCEPTED", default))).Code);
    }

    [Fact]
    public async Task OutcomeHttpBindsTheRegistrationToTheCallerAndRejectsUnboundedRanges()
    {
        using var local = new LocalIdentity(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> {
            ["AZURE_FUNCTIONS_ENVIRONMENT"] = "Development", ["BunDoIdentity:Mode"] = "Local",
        }).Build());
        var registrations = new LocalRegistrationStore(Path.Combine(path, "registrations"));
        var alice = new AccountIdentity(LocalIdentity.Issuer, "alice");
        var registration = (await registrations.RegisterAsync(alice, Guid.NewGuid(), null, default)).Registration!;
        var epoch = (await new HouseholdService(documents).CreateAsync(HouseholdIdentity.Member(alice), workspace, default)).Household!.StateEpoch;
        using var services = new ServiceCollection().AddSingleton<IHouseholdDocuments>(documents)
            .AddSingleton<IRegistrationStore>(registrations).BuildServiceProvider();
        var function = new OutcomesFunction(local.Validator, services);
        async Task<IActionResult> Send(string? account, string count = "2")
        {
            var http = new DefaultHttpContext();
            if (account is not null) http.Request.Headers.Authorization = "Bearer " + local.Issue(account, "valid");
            http.Request.QueryString = QueryString.Create(new Dictionary<string, string?> {
                ["workspaceId"] = workspace.ToString("D"), ["stateEpoch"] = epoch.ToString("D"),
                ["registrationId"] = registration.RegistrationId.ToString("D"), ["firstSequence"] = "1", ["count"] = count,
            });
            return await function.Run(http.Request);
        }
        Assert.IsType<UnauthorizedResult>(await Send(null));
        Assert.Equal(403, Assert.IsType<ObjectResult>(await Send("bob")).StatusCode);
        Assert.Equal(409, Assert.IsType<ObjectResult>(await Send("alice", "101")).StatusCode);
        var response = Assert.IsType<ContentResult>(await Send("alice"));
        var page = JsonDocument.Parse(response.Content!).RootElement;
        Assert.Equal("0", page.GetProperty("highWater").GetString());
        Assert.All(page.GetProperty("outcomes").EnumerateArray(), item => Assert.Equal("NOT_SEEN", item.GetProperty("state").GetString()));
        await registrations.RegisterAsync(alice, Guid.NewGuid(), registration.RegistrationId, default);
        Assert.Equal(403, Assert.IsType<ObjectResult>(await Send("alice")).StatusCode);
    }

    [Fact]
    public async Task OutcomeLookupReturnsTheOriginalReceiptForReconciliationWithoutReplaying()
    {
        var epoch = await Create();
        var sync = new SyncService(documents);
        var receipt = (await sync.SubmitAsync(member, Operation(epoch, 1, "Private task text"), default)).Receipt!;
        var page = await sync.OutcomesAsync(member, workspace, epoch, device, 1, 2, default);
        Assert.Equal(1UL, page.HighWater);
        Assert.Equal(["ACCEPTED", "NOT_SEEN"], page.Outcomes.Select(x => x.State));
        Assert.Equal(receipt.Fingerprint, page.Outcomes[0].Fingerprint);
        var encoded = JsonSerializer.Serialize(page, SyncJson.Options);
        Assert.Equal(receipt, page.Outcomes[0].Receipt);
        Assert.Equal("1", JsonDocument.Parse(encoded).RootElement.GetProperty("highWater").GetString());
        Assert.Equal(1UL, (await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", default))!.Value.Revision);
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(() =>
            sync.OutcomesAsync(Guid.NewGuid(), workspace, epoch, device, 1, 2, default))).Code);
    }

    [Fact]
    public async Task FailedMetadataCasCannotLeakAnyEntityReceiptOrChange()
    {
        var epoch = await Create();
        var stale = (await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", default))!;
        await new SyncService(documents).SubmitAsync(member, Operation(epoch, 1), default);
        var task = new TaskSnapshot(TaskIdentity.ForCreate(device, 2), "Must not appear", null, new(1, 1), new(1, 1));
        var next = stale.Value with { Revision = 1, Changes = [new(1, [task])] };
        Assert.False(await documents.CommitWorkspaceAsync(stale, next, default));
        Assert.Null(await documents.ReadAsync<TaskSnapshot>(workspace.ToString("D"), WorkspaceCommit.TaskId(task.Id), default));
        Assert.Equal(1UL, (await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", default))!.Value.Revision);
    }
}
#endif
