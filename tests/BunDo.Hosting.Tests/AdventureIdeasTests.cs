using System.Diagnostics;
using System.Net;
using System.Text.Json;
using Azure.Core;
using BunDo.Domain;
using BunDo.Functions.Adventures;
using BunDo.Functions.AI;
using BunDo.Functions.Households;
using BunDo.Functions.Sync;
#if DEBUG
using BunDo.Functions.Identity.Development;
#endif

namespace BunDo.Hosting.Tests;

public sealed class AdventureIdeasProviderTests
{
    private static byte[] Response(string content) => JsonSerializer.SerializeToUtf8Bytes(new { status = "completed", output = new[] {
        new { type = "message", content = new[] { new { type = "output_text", text = content } } } } });
    [Theory]
    [InlineData("null")]
    [InlineData("{\"ideas\":[\"one\"]}")]
    [InlineData("{\"ideas\":[\"one\",\"ONE\",\"three\"]}")]
    [InlineData("{\"ideas\":[\"one\",\"two\",\" three\"]}")]
    [InlineData("{\"ideas\":[null,\"two\",\"three\"]}")]
    [InlineData("{\"ideas\":[\"one\",\"two\",\"three\"],\"extra\":true}")]
    public void Invalid_ideas_are_rejected(string content) => Assert.Equal("INVALID_OUTPUT",
        Assert.Throws<CleanupProviderException>(() => FoundryAdventureIdeas.ParseResponse(Response(content))).Code);

    [Fact] public async Task Uses_private_structured_context_without_logging_history()
    {
        var handler = new Handler(); using var http = new HttpClient(handler); using var activity = new Activity("ideas").Start();
        var provider = new FoundryAdventureIdeas(http, new Credential(), new("https://example.test/openai/v1/"), "luna");
        var ideas = await provider.SuggestAsync([new("Private completed canary", "Private history", true)], "fi", default);
        Assert.Equal(3, ideas.Length);
        using var json = JsonDocument.Parse(handler.Body!); var request = json.RootElement;
        Assert.False(request.GetProperty("store").GetBoolean());
        Assert.True(request.GetProperty("text").GetProperty("format").GetProperty("strict").GetBoolean());
        using var input = JsonDocument.Parse(request.GetProperty("input").GetString()!);
        Assert.True(input.RootElement.GetProperty("tasks")[0].GetProperty("Completed").GetBoolean());
        Assert.Equal("fi", input.RootElement.GetProperty("language").GetString());
        Assert.DoesNotContain("Private", JsonSerializer.Serialize(activity.TagObjects.ToDictionary(t => t.Key, t => t.Value)));
    }
    private sealed class Handler : HttpMessageHandler {
        public string? Body;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            Assert.Equal("Bearer token", request.Headers.Authorization!.ToString()); Body = await request.Content!.ReadAsStringAsync(ct);
            return new(HttpStatusCode.OK) { Content = new ByteArrayContent(Response("{\"ideas\":[\"Nurkka kuntoon\",\"Parveke viihtyisäksi\",\"Työpiste valmiiksi\"]}")) };
        }
    }
    private sealed class Credential : TokenCredential {
        public override AccessToken GetToken(TokenRequestContext context, CancellationToken ct) => new("token", DateTimeOffset.UtcNow.AddMinutes(5));
        public override ValueTask<AccessToken> GetTokenAsync(TokenRequestContext context, CancellationToken ct) => ValueTask.FromResult(GetToken(context, ct));
    }
}

#if DEBUG
public sealed class AdventureIdeasTests : IDisposable
{
    private readonly string path = Path.Combine(Path.GetTempPath(), "bundo-ideas-" + Guid.NewGuid());
    private readonly Guid member = Guid.NewGuid(), workspace = Guid.NewGuid(), device = Guid.NewGuid();
    private readonly LocalHouseholdDocuments documents;
    private readonly Provider provider = new();
    private Guid epoch;
    private ulong sequence;
    public AdventureIdeasTests() { documents = new(path); }
    public void Dispose() { Directory.Delete(path, true); }
    private AdventureService Service(Func<CancellationToken, Task<bool>>? valid = null) => new(documents,
        registrationActive: valid, ideasProvider: provider);
    private async Task Start() => epoch = (await new HouseholdService(documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
    private async Task<TaskSnapshot> Send(TaskCommand command) {
        var result = await new SyncService(documents).SubmitAsync(member, new(workspace, epoch, device, ++sequence, command), default);
        Assert.Equal("ACCEPTED", result.Code); return result.Receipt!.Task!;
    }
    private Task<TaskSnapshot> Capture(string title) => Send(new CreateTask(TaskIdentity.ForCreate(device, sequence + 1), title));
    private Task<string[]> Ideas() => Service().IdeasAsync(member, workspace, epoch, "en", default);
    [Fact] public async Task Reads_current_and_completed_roots_but_never_deleted_tasks_or_checklist_fragments_and_writes_nothing()
    {
        await Start(); var completed = await Capture("Completed canary");
        await Send(new CompleteTask(completed.Id, ChecklistTasks.Versions(completed)));
        var deleted = await Capture("Deleted canary"); await Send(new DeleteTask(deleted.Id, ChecklistTasks.Versions(deleted)));
        var root = await Capture("Current checklist");
        await Send(new SplitTask(root.Id, ChecklistTasks.Versions(root), ["Private child"], root.TitleVersion.Human, root.DescriptionVersion.Human));
        var before = (await Service().ReadAsync(member, workspace, epoch, default)).Revision;
        Assert.Equal(3, (await Ideas()).Length);
        Assert.Contains(provider.Input!, t => t.Title == "Completed canary" && t.Completed);
        Assert.Contains(provider.Input!, t => t.Title == "Current checklist" && !t.Completed);
        Assert.DoesNotContain(provider.Input!, t => t.Title is "Deleted canary" or "Private child");
        Assert.Equal(before, (await Service().ReadAsync(member, workspace, epoch, default)).Revision);
    }
    [Fact] public async Task Rechecks_registration_and_source_content_after_inference()
    {
        await Start(); var task = await Capture("Before");
        var active = true; provider.Before = () => { active = false; return Task.CompletedTask; };
        Assert.Equal("REGISTRATION_RETIRED", (await Assert.ThrowsAsync<SyncException>(() =>
            Service(_ => Task.FromResult(active)).IdeasAsync(member, workspace, epoch, "en", default))).Code);
        provider.Before = async () => await Send(new DeleteTask(task.Id, ChecklistTasks.Versions(task)));
        Assert.Equal("SOURCE_CHANGED", (await Assert.ThrowsAsync<SyncException>(Ideas)).Code);
    }
    [Fact] public async Task Rejects_unauthorized_requests_bad_language_and_invalid_provider_output()
    {
        await Start();
        Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(() => Service().IdeasAsync(Guid.NewGuid(), workspace, epoch, "en", default))).Code);
        Assert.Equal("EPOCH_CHANGED", (await Assert.ThrowsAsync<SyncException>(() => Service().IdeasAsync(member, workspace, Guid.NewGuid(), "en", default))).Code);
        Assert.Equal("INVALID_REQUEST", (await Assert.ThrowsAsync<SyncException>(() => Service().IdeasAsync(member, workspace, epoch, "unexpected", default))).Code);
        Assert.Equal(0, provider.Calls);
        provider.Output = ["Same", "Same", "Other"];
        Assert.Equal("INVALID_OUTPUT", (await Assert.ThrowsAsync<SyncException>(Ideas)).Code);
    }
    [Fact] public async Task History_context_is_bounded_and_empty_history_is_valid()
    {
        await Start(); await Ideas(); Assert.Empty(provider.Input!);
        for (var i = 0; i < 40; i++) await Capture($"Task {i}");
        await Ideas(); Assert.InRange(provider.Input!.Count, 1, AdventureService.MaximumInputRoots);
        Assert.True(JsonSerializer.SerializeToUtf8Bytes(provider.Input).Length < AdventureService.MaximumInputBytes);
    }
    [Fact] public async Task Deleted_history_cannot_crowd_current_roots_out_of_context()
    {
        await Start();
        for (var i = 0; i < 3; i++) await Capture($"Current {i}");
        for (var i = 0; i < AdventureService.MaximumInputRoots; i++) {
            var task = await Capture($"Deleted {i}");
            await Send(new DeleteTask(task.Id, ChecklistTasks.Versions(task)));
        }
        await Ideas();
        Assert.Equal(3, provider.Input!.Count);
        Assert.All(provider.Input, task => Assert.StartsWith("Current ", task.Title));
    }
    private sealed class Provider : IAdventureIdeasProvider {
        public IReadOnlyList<AdventureIdeaInput>? Input;
        public string[] Output = ["Clear a corner", "Prepare a workspace", "Organize the balcony"];
        public Func<Task>? Before;
        public int Calls;
        public async Task<string[]> SuggestAsync(IReadOnlyList<AdventureIdeaInput> tasks, string language, CancellationToken ct) {
            Calls++; Input = tasks; if (Before != null) await Before(); return Output;
        }
    }
}
#endif
