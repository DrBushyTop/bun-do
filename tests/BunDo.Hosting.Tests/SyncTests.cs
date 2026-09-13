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
        await registrations.RegisterAsync(alice, registration.InstallationId, registration.RegistrationId, default);
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
