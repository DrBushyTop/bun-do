#if DEBUG
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Recovery;
using BunDo.Functions.Sync;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Hosting.Tests;

public sealed class SnapshotTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-snapshot-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly Clock clock = new();
    private readonly LocalHouseholdDocuments storage;
    private readonly LocalSnapshotArtifacts artifacts;
    public SnapshotTests() { storage = new(Path.Combine(path, "documents")); artifacts = new(Path.Combine(path, "artifacts")); }
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private async Task<Guid> Create() => (await new HouseholdService(storage).CreateAsync(member, workspace, default)).Household!.StateEpoch;
    private FrozenOperation Capture(Guid epoch, ulong sequence = 1) => new(workspace, epoch, device, sequence,
        new CreateTask(TaskIdentity.ForCreate(device, sequence), "Original private text"));
    private SnapshotService Module(IHouseholdDocuments? documents = null, IRegistrationStore? registrations = null) =>
        new(documents ?? storage, artifacts, registrations, clock);

    [Fact]
    public async Task PublishedSnapshotRemainsImmutableAfterEditsAndLostResponseRetry()
    {
        var epoch = await Create();
        var sync = new SyncService(storage);
        var receipt = (await sync.SubmitAsync(member, Capture(epoch), default)).Receipt!;
        var module = Module(); var id = Guid.NewGuid();
        var manifest = await module.CreateAsync(member, workspace, epoch, device, id, default);
        var chunk = await module.ChunkAsync(member, workspace, epoch, device, id, 0, default);
        Assert.Equal(1, manifest.DocumentCount);
        Assert.Equal(chunk.Length, manifest.TotalBytes);
        Assert.Equal("Original private text", JsonDocument.Parse(chunk).RootElement.GetProperty("tasks")[0].GetProperty("title").GetString());
        await sync.SubmitAsync(member, new(workspace, epoch, device, 2,
            new EditTask(receipt.Task!.Id, new("Changed later", receipt.Task.TitleVersion.Human))), default);
        var retry = await module.CreateAsync(member, workspace, epoch, device, id, default);
        Assert.Equal(JsonSerializer.Serialize(manifest), JsonSerializer.Serialize(retry));
        Assert.Equal(chunk, await module.ChunkAsync(member, workspace, epoch, device, id, 0, default));
        var delta = await sync.PullAsync(member, workspace, epoch, manifest.Cursor, [], "ACCEPTED", default);
        Assert.True(delta.ThroughRevision > manifest.Revision);
    }

    [Fact]
    public async Task ConcurrentMutationDiscardsCandidateAndBoundedContentionNeverPublishesMixedState()
    {
        var epoch = await Create();
        var sync = new SyncService(storage);
        await sync.SubmitAsync(member, Capture(epoch), default);
        ulong sequence = 1;
        var racing = new InterleavedDocuments(storage) { AfterPage = async () => {
            sequence++;
            await sync.SubmitAsync(member, Capture(epoch, sequence), default);
        } };
        var id = Guid.NewGuid();
        Assert.Equal("SNAPSHOT_BUSY", (await Assert.ThrowsAsync<SnapshotException>(() =>
            Module(racing).CreateAsync(member, workspace, epoch, device, id, default))).Code);
        Assert.Equal(4UL, sequence); // Three bounded candidates, each invalidated once.
        Assert.Equal("SNAPSHOT_BUSY", (await Assert.ThrowsAsync<SnapshotException>(() =>
            Module().ManifestAsync(member, workspace, epoch, device, id, default))).Code);
        clock.Now = clock.Now.AddMinutes(3);
        var snapshot = await Module().CreateAsync(member, workspace, epoch, device, id, default);
        Assert.Equal(4, snapshot.DocumentCount);
    }

    [Fact]
    public async Task EveryReadChecksMemberDeviceEpochAndExpiryAndCandidateSlotsAreBounded()
    {
        var epoch = await Create(); var module = Module(); var id = Guid.NewGuid();
        await module.CreateAsync(member, workspace, epoch, device, id, default);
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SnapshotException>(() =>
            module.ManifestAsync(Guid.NewGuid(), workspace, epoch, device, id, default))).Code);
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SnapshotException>(() =>
            module.ManifestAsync(member, workspace, epoch, Guid.NewGuid(), id, default))).Code);
        Assert.Equal("EPOCH_CHANGED", (await Assert.ThrowsAsync<SnapshotException>(() =>
            module.ManifestAsync(member, workspace, Guid.NewGuid(), device, id, default))).Code);
        await module.CreateAsync(member, workspace, epoch, device, Guid.NewGuid(), default);
        Assert.Equal("SNAPSHOT_BUSY", (await Assert.ThrowsAsync<SnapshotException>(() =>
            module.CreateAsync(member, workspace, epoch, device, Guid.NewGuid(), default))).Code);
        clock.Now = clock.Now.AddMinutes(30);
        Assert.Equal("SNAPSHOT_EXPIRED", (await Assert.ThrowsAsync<SnapshotException>(() =>
            module.ManifestAsync(member, workspace, epoch, device, id, default))).Code);
        await module.CreateAsync(member, workspace, epoch, device, Guid.NewGuid(), default);
    }

    [Fact]
    public async Task PruningWaitsForAcknowledgementAndNeverReexecutesAnExpiredOutcome()
    {
        var epoch = await Create(); var sync = new SyncService(storage); var operation = Capture(epoch);
        await sync.SubmitAsync(member, operation, default);
        clock.Now = clock.Now.AddDays(121);
        Assert.Equal(0, await Module().PruneAsync(member, workspace, epoch, default));
        await sync.AcknowledgeAsync(member, workspace, epoch, device, 1, default);
        Assert.True(await Module().PruneAsync(member, workspace, epoch, default) > 0);
        Assert.Equal("OUTCOME_EXPIRED", (await sync.SubmitAsync(member, operation, default)).Code);
        Assert.Equal("OUTCOME_EXPIRED", (await sync.OutcomesAsync(member, workspace, epoch, device, 1, 1, default)).Outcomes.Single().State);
        Assert.Equal("SNAPSHOT_REQUIRED", (await Assert.ThrowsAsync<SyncException>(() =>
            sync.PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default))).Code);
        var snapshot = await Module().CreateAsync(member, workspace, epoch, device, Guid.NewGuid(), default);
        Assert.Equal(1, snapshot.DocumentCount);
    }

    [Fact]
    public async Task CurrentRegistrationRenewalAndSnapshotPinsPreventPrematurePruning()
    {
        var epoch = await Create(); var sync = new SyncService(storage);
        await sync.SubmitAsync(member, Capture(epoch), default, "identity:" + new string('A', 64));
        clock.Now = clock.Now.AddDays(121);
        var registrations = new RegistrationReader { Registration = new(Guid.NewGuid(), device, clock.Now.AddDays(10)) };
        Assert.Equal(0, await Module(registrations: registrations).PruneAsync(member, workspace, epoch, default));
        var snapshot = await Module(registrations: registrations).CreateAsync(member, workspace, epoch, device, Guid.NewGuid(), default);
        registrations.Registration = registrations.Registration with { Revoked = true };
        Assert.Equal(0, await Module(registrations: registrations).PruneAsync(member, workspace, epoch, default));
        clock.Now = clock.Now.AddMinutes(31);
        Assert.True(await Module(registrations: registrations).PruneAsync(member, workspace, epoch, default) > 0);
        Assert.Equal("OUTCOME_EXPIRED", (await sync.OutcomesAsync(member, workspace, epoch, device, 1, 1, default)).Outcomes.Single().State);
    }

    [Fact]
    public async Task SnapshotHttpRequiresActiveOwnerRegistrationForEveryPart()
    {
        using var identity = new LocalIdentity(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> {
            ["AZURE_FUNCTIONS_ENVIRONMENT"] = "Development", ["BunDoIdentity:Mode"] = "Local",
        }).Build());
        var registrations = new LocalRegistrationStore(Path.Combine(path, "registrations"));
        var alice = new AccountIdentity(LocalIdentity.Issuer, "alice");
        var registration = (await registrations.RegisterAsync(alice, Guid.NewGuid(), null, default)).Registration!;
        var epoch = (await new HouseholdService(storage).CreateAsync(HouseholdIdentity.Member(alice), workspace, default)).Household!.StateEpoch;
        using var services = new ServiceCollection().AddSingleton<IHouseholdDocuments>(storage)
            .AddSingleton<IRegistrationStore>(registrations).AddSingleton<ISnapshotArtifacts>(artifacts).BuildServiceProvider();
        var function = new SnapshotFunction(identity.Validator, services);
        var id = Guid.NewGuid();
        async Task<IActionResult> Send(string? account, string part = "manifest", string method = "GET")
        {
            var http = new DefaultHttpContext();
            http.Request.Method = method;
            if (account is not null) http.Request.Headers.Authorization = "Bearer " + identity.Issue(account, "valid");
            http.Request.QueryString = QueryString.Create(new Dictionary<string, string?> {
                ["workspaceId"] = workspace.ToString("D"), ["stateEpoch"] = epoch.ToString("D"),
                ["registrationId"] = registration.RegistrationId.ToString("D"),
            });
            var result = await function.Run(http.Request, id, part);
            Assert.Equal("no-store", http.Response.Headers.CacheControl);
            return result;
        }
        Assert.IsType<UnauthorizedResult>(await Send(null));
        Assert.Equal(403, Assert.IsType<ObjectResult>(await Send("bob", method: "POST")).StatusCode);
        Assert.IsType<ContentResult>(await Send("alice", method: "POST"));
        Assert.Equal(409, Assert.IsType<ObjectResult>(await Send("alice", "0")).StatusCode); // Empty snapshot.
        Assert.Equal(400, Assert.IsType<ObjectResult>(await Send("alice", "0", "POST")).StatusCode);
        Assert.Equal(403, Assert.IsType<ObjectResult>(await Send("bob")).StatusCode);
        await registrations.RegisterAsync(alice, Guid.NewGuid(), registration.RegistrationId, default);
        Assert.Equal(403, Assert.IsType<ObjectResult>(await Send("alice")).StatusCode);
    }

    [Fact]
    public async Task CanonicalPaginationIsCompleteAndCorruptedArtifactNeverLeavesTheApi()
    {
        var epoch = await Create(); var sync = new SyncService(storage);
        for (ulong sequence = 1; sequence <= 130; sequence++) await sync.SubmitAsync(member, Capture(epoch, sequence), default);
        var id = Guid.NewGuid(); var module = Module();
        var manifest = await module.CreateAsync(member, workspace, epoch, device, id, default);
        Assert.Equal(130, manifest.DocumentCount);
        var tasks = new HashSet<string>();
        foreach (var chunk in manifest.Chunks)
        {
            var bytes = await module.ChunkAsync(member, workspace, epoch, device, id, chunk.Index, default);
            using var parsed = JsonDocument.Parse(bytes);
            foreach (var task in parsed.RootElement.GetProperty("tasks").EnumerateArray()) Assert.True(tasks.Add(task.GetProperty("id").GetString()!));
        }
        Assert.Equal(130, tasks.Count);
        var file = Directory.EnumerateFiles(Path.Combine(path, "artifacts"), "0000.json", SearchOption.AllDirectories).Single();
        await File.WriteAllTextAsync(file, "corrupt");
        Assert.Equal("SNAPSHOT_CORRUPT", (await Assert.ThrowsAsync<SnapshotException>(() =>
            module.ChunkAsync(member, workspace, epoch, device, id, 0, default))).Code);
    }

    [Fact]
    public async Task FailedPruningCasKeepsReceiptAndCursorTogether()
    {
        var epoch = await Create(); var sync = new SyncService(storage); var operation = Capture(epoch);
        await sync.SubmitAsync(member, operation, default);
        await sync.AcknowledgeAsync(member, workspace, epoch, device, 1, default);
        clock.Now = clock.Now.AddDays(121);
        var racing = new InterleavedDocuments(storage) { RejectDeletion = true };
        Assert.Equal(0, await Module(racing).PruneAsync(member, workspace, epoch, default));
        Assert.Equal("ACCEPTED", (await sync.SubmitAsync(member, operation, default)).Code);
        Assert.Equal(0UL, (await storage.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", default))!.Value.PrunedThrough);
        Assert.True(await Module().PruneAsync(member, workspace, epoch, default) > 0);
        Assert.Equal("OUTCOME_EXPIRED", (await sync.SubmitAsync(member, operation, default)).Code);
    }

    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => Now;
    }
    private sealed class RegistrationReader : IRegistrationStore
    {
        public required InstallationRegistration Registration;
        public Task<InstallationRegistration?> ReadAsync(string partition, Guid id, CancellationToken ct) => Task.FromResult<InstallationRegistration?>(Registration);
        public Task<bool> IsActiveAsync(AccountIdentity identity, Guid id, CancellationToken ct) => throw new NotSupportedException();
        public Task<RegistrationDecision> RegisterAsync(AccountIdentity identity, Guid id, Guid? revoke, CancellationToken ct) => throw new NotSupportedException();
    }
    private sealed class InterleavedDocuments(IHouseholdDocuments inner) : IHouseholdDocuments
    {
        public Func<Task>? AfterPage;
        public bool RejectDeletion;
        public Task<StoredDocument<T>?> ReadAsync<T>(string partition, string id, CancellationToken ct) => inner.ReadAsync<T>(partition, id, ct);
        public Task<bool> WriteAsync<T>(string partition, string id, string? version, T value, CancellationToken ct) => inner.WriteAsync(partition, id, version, value, ct);
        public Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected, WorkspaceState next, CancellationToken ct, IReadOnlyList<string>? deletes = null) => RejectDeletion && deletes?.Count > 0 ? Task.FromResult(false) : inner.CommitWorkspaceAsync(expected, next, ct, deletes);
        public async Task<DocumentPage<T>> ReadPageAsync<T>(string partition, string prefix, string? continuation, int limit, CancellationToken ct)
        {
            var page = await inner.ReadPageAsync<T>(partition, prefix, continuation, limit, ct);
            if (AfterPage is not null) await AfterPage();
            return page;
        }
    }
}
#endif
