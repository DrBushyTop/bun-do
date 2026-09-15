#if DEBUG
using System.Text.Json;
using BunDo.Functions.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using BunDo.Domain;
using BunDo.Functions.AI;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class CleanupTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-cleanup-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly LocalHouseholdDocuments documents;
    private Guid epoch;
    private ulong sequence;
    public CleanupTests() { documents = new(path); }
    public void Dispose() { if (Directory.Exists(path)) Directory.Delete(path, true); }
    private async Task<TaskSnapshot> Start()
    {
        epoch = (await new HouseholdService(documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
        var task = await Send(new CreateTask(TaskIdentity.ForCreate(device, 1), "osta maitoa", "kaksi litraa"));
        return await Send(Request(task));
    }
    private static RequestCleanup Request(TaskSnapshot t) => new(t.Id, t.TitleVersion.Server,
        t.DescriptionVersion.Server, t.LifecycleVersion, t.HierarchyVersion, t.DeletionVersion, t.Cleanup?.Id);
    private async Task<TaskSnapshot> Send(TaskCommand command)
    {
        var result = await new SyncService(documents).SubmitAsync(member, new(workspace, epoch, device, ++sequence, command), default);
        Assert.Equal("ACCEPTED", result.Code);
        return result.Receipt!.Task!;
    }
    private async Task<TaskSnapshot> Read(string id) =>
        (await documents.ReadAsync<TaskSnapshot>(workspace.ToString("D"), WorkspaceCommit.TaskId(id), default))!.Value;
    private sealed class Provider(Func<CancellationToken, Task<CleanupProposal>> generate) : ICleanupProvider
    {
        public int Calls;
        public Task<CleanupProposal> GenerateAsync(string title, string? description, CancellationToken ct, JsonElement? captureContext = null) { Calls++; return generate(ct); }
    }
    private static CleanupProposal Good => new("Osta maitoa", "kaksi litraa", "fi", false);

    [Fact]
    public async Task Authenticated_sync_resumes_pending_work_and_never_executes_for_another_account()
    {
        using var identity = new LocalIdentity(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> {
            ["AZURE_FUNCTIONS_ENVIRONMENT"] = "Development", ["BunDoIdentity:Mode"] = "Local",
        }).Build());
        var account = new AccountIdentity(LocalIdentity.Issuer, "alice");
        var actor = HouseholdIdentity.Member(account);
        var registrations = new LocalRegistrationStore(Path.Combine(path, "registrations"));
        var registration = (await registrations.RegisterAsync(account, Guid.NewGuid(), null, default)).Registration!.RegistrationId;
        epoch = (await new HouseholdService(documents).CreateAsync(actor, workspace, default)).Household!.StateEpoch;
        var sync = new SyncService(documents);
        var task = (await sync.SubmitAsync(actor, new(workspace, epoch, registration, 1,
            new CreateTask(TaskIdentity.ForCreate(registration, 1), "osta maitoa")), default)).Receipt!.Task!;
        await sync.SubmitAsync(actor, new(workspace, epoch, registration, 2, Request(task)), default);
        var provider = new Provider(_ => Task.FromResult(Good));
        using var services = new ServiceCollection().AddSingleton<IHouseholdDocuments>(documents)
            .AddSingleton<IRegistrationStore>(registrations).AddSingleton<ICleanupProvider>(provider).BuildServiceProvider();
        async Task<IActionResult> Poll(string who)
        {
            var context = new DefaultHttpContext();
            context.Request.Headers.Authorization = "Bearer " + identity.Issue(who, "valid");
            context.Request.Body = new MemoryStream(JsonSerializer.SerializeToUtf8Bytes(new {
                workspaceId = workspace, stateEpoch = epoch, registrationId = registration,
                cursor = (string?)null, envelopes = Array.Empty<string>(),
            }));
            return await new SyncFunction(identity.Validator, services).Run(context.Request);
        }
        Assert.Equal(403, Assert.IsType<ObjectResult>(await Poll("bob")).StatusCode);
        Assert.Equal(0, provider.Calls);
        var reply = Assert.IsType<ContentResult>(await Poll("alice"));
        Assert.Equal(200, reply.StatusCode);
        Assert.Contains("APPLIED", reply.Content);
        Assert.Contains("\"statistics\"", reply.Content);
        Assert.Equal("APPLIED", (await Read(task.Id)).Cleanup!.Status);
        await Poll("alice");
        Assert.Equal(1, provider.Calls);
    }

    [Fact]
    public async Task Durable_intent_duplicate_execution_and_human_override_of_ai_only_versions()
    {
        var task = await Start();
        Assert.Equal("PENDING", task.Cleanup!.Status);
        var provider = new Provider(_ => Task.FromResult(Good));
        var restarted = new CleanupWorker(new LocalHouseholdDocuments(path), provider);
        await Task.WhenAll(restarted.RunAsync(member, workspace, epoch, default), restarted.RunAsync(member, workspace, epoch, default));
        task = await Read(task.Id);
        Assert.Equal(1, provider.Calls);
        Assert.Equal("APPLIED", task.Cleanup!.Status);
        Assert.Null(task.Cleanup.InputTitle);
        Assert.Equal("Osta maitoa", task.Title);
        Assert.Equal(1UL, task.TitleVersion.Human);
        var edited = await Send(new EditTask(task.Id, new("Osta kauramaitoa", 1), ExpectedDeletionVersion: task.DeletionVersion));
        Assert.Equal("Osta kauramaitoa", edited.Title);
        var page = await new SyncService(documents).PullAsync(member, workspace, epoch, null, [], "ACCEPTED", default);
        Assert.Contains(page.Groups, g => g.Parts[0].Payload.Contains("APPLIED"));
    }

    [Theory]
    [InlineData("edit")][InlineData("complete")][InlineData("delete")][InlineData("cancel")]
    [InlineData("remove")][InlineData("epoch")][InlineData("replace")]
    public async Task Late_results_cannot_overwrite_changed_work_or_authority(string mutation)
    {
        var task = await Start();
        var entered = new TaskCompletionSource(); var release = new TaskCompletionSource();
        var provider = new Provider(async _ => { entered.SetResult(); await release.Task; return Good; });
        var running = new CleanupWorker(documents, provider).ProcessAsync(member, workspace, epoch, task.Id, default);
        await entered.Task;
        task = await Read(task.Id);
        switch (mutation)
        {
            case "edit": task = await Send(new EditTask(task.Id, new("Ihmisen korjaus", 1), ExpectedDeletionVersion: task.DeletionVersion)); break;
            case "complete": task = await Send(new CompleteTask(task.Id, ChecklistTasks.Versions(task))); break;
            case "delete": task = await Send(new DeleteTask(task.Id, ChecklistTasks.Versions(task))); break;
            case "cancel": task = await Send(new CancelCleanup(task.Id, 1, 1, task.LifecycleVersion, task.HierarchyVersion, task.DeletionVersion, task.Cleanup!.Id)); break;
            case "replace": task = await Send(Request(task)); break;
            default:
                var metadata = (await documents.ReadAsync<WorkspaceState>(workspace.ToString("D"), "state", default))!;
                var next = mutation == "epoch" ? metadata.Value with { StateEpoch = Guid.NewGuid() }
                    : metadata.Value with { Membership = metadata.Value.Membership with {
                        Members = metadata.Value.Membership.Members.SetItem(member, metadata.Value.Membership.Members[member] with { Active = false }) } };
                await documents.WriteAsync(workspace.ToString("D"), "state", metadata.Version, next, default);
                break;
        }
        release.SetResult(); await running;
        var result = await Read(task.Id);
        Assert.Equal(task.Title, result.Title);
        if (mutation is "edit" or "complete")
        {
            Assert.Equal("READY", result.Cleanup!.Status);
            Assert.Equal(Good, result.Cleanup.Proposal);
        }
        else Assert.Equal(task, result);
        if (mutation == "edit")
        {
            var accepted = await Send(new ApplyCleanup(result.Id, result.TitleVersion.Server, result.DescriptionVersion.Server,
                result.LifecycleVersion, result.HierarchyVersion, result.DeletionVersion, result.Cleanup!.Id));
            Assert.Equal(Good.Title, accepted.Title);
            Assert.Equal("APPLIED", accepted.Cleanup!.Status);
            Assert.True(accepted.TitleVersion.Human > result.TitleVersion.Human);
        }
    }

    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => Now;
    }
    [Fact]
    public async Task Interruption_expires_to_explicit_retry_without_automatic_paid_retry()
    {
        var task = await Start(); var clock = new Clock();
        using var cancellation = new CancellationTokenSource();
        var provider = new Provider(_ => { cancellation.Cancel(); throw new OperationCanceledException(cancellation.Token); });
        await Assert.ThrowsAsync<OperationCanceledException>(() => new CleanupWorker(documents, provider, clock)
            .RunAsync(member, workspace, epoch, cancellation.Token));
        clock.Now = clock.Now.AddMinutes(3);
        await new CleanupWorker(documents, provider, clock).RunAsync(member, workspace, epoch, default);
        task = await Read(task.Id);
        Assert.Equal("FAILED", task.Cleanup!.Status);
        Assert.Equal("INTERRUPTED", task.Cleanup.Error);
        Assert.Equal(1, provider.Calls);
        Assert.Null(task.Cleanup.InputTitle);
        task = await Send(Request(task));
        await new CleanupWorker(documents, new Provider(_ => Task.FromResult(Good)), clock).RunAsync(member, workspace, epoch, default);
        Assert.Equal("APPLIED", (await Read(task.Id)).Cleanup!.Status);
    }

    [Theory]
    [InlineData("REFUSED")][InlineData("INVALID_OUTPUT")][InlineData("PROVIDER_UNAVAILABLE")]
    public async Task Failure_retains_manual_work_and_exposes_retry(string code)
    {
        var task = await Start();
        await new CleanupWorker(documents, new Provider(_ => throw new CleanupProviderException(code)))
            .RunAsync(member, workspace, epoch, default);
        var failed = await Read(task.Id);
        Assert.Equal(task.Title, failed.Title);
        Assert.Equal("FAILED", failed.Cleanup!.Status);
        Assert.Equal(code, failed.Cleanup.Error);
        await Send(new EditTask(task.Id, new("Manual", 1), ExpectedDeletionVersion: task.DeletionVersion));
    }

    [Fact]
    public async Task Ambiguous_output_requires_explicit_acceptance()
    {
        var task = await Start();
        await new CleanupWorker(documents, new Provider(_ => Task.FromResult(Good with { NeedsReview = true })))
            .RunAsync(member, workspace, epoch, default);
        var ready = await Read(task.Id);
        Assert.Equal(task.Title, ready.Title);
        Assert.Equal("READY", ready.Cleanup!.Status);
    }
}
#endif
