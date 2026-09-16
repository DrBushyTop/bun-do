using System.Diagnostics;
using System.Net;
using System.Text.Json;
using Azure.Core;
using BunDo.Functions.Adventures;
using BunDo.Functions.AI;

namespace BunDo.Hosting.Tests;

public sealed class AdventurePlannerProviderTests
{
    private const string SingleDraft = """{"title":"Nurkka kuntoon","flavor":"","phases":[{"rootId":null,"taskTitle":"Sort papers","name":"Make room","stars":1,"minutes":15}]}""";
    private static string Draft => SingleDraft.Replace("}]}", "},{\"rootId\":null,\"taskTitle\":\"Wipe shelf\",\"name\":\"Fresh start\",\"stars\":1,\"minutes\":5},{\"rootId\":null,\"taskTitle\":\"Put things back\",\"name\":\"Settle in\",\"stars\":1,\"minutes\":5}]}");
    private static byte[] Response(string text) => JsonSerializer.SerializeToUtf8Bytes(new { status = "completed", output = new[] {
        new { type = "message", content = new[] { new { type = "output_text", text } } } } });
    [Fact] public void Strict_plan_rejects_mixed_reference_and_new_task_invalid_estimates_and_unknown_fields()
    {
        Assert.Equal("Sort papers", FoundryAdventurePlanner.ParseResponse(Response(Draft)).Phases[0].TaskTitle);
        foreach (var invalid in new[] { "null", "{}", SingleDraft, Draft.Replace("\"rootId\":null", "\"rootId\":\"existing\""),
            Draft.Replace("\"stars\":1", "\"stars\":4"), Draft.Replace("\"flavor\":\"\"", "\"flavor\":\"\",\"extra\":true") })
            Assert.Equal("INVALID_OUTPUT", Assert.Throws<CleanupProviderException>(() => FoundryAdventurePlanner.ParseResponse(Response(invalid))).Code);
    }
    [Fact] public async Task Planner_uses_strict_schema_managed_identity_and_does_not_log_outcome()
    {
        var handler = new Handler(); using var http = new HttpClient(handler); using var trace = new Activity("plan").Start();
        var planner = new FoundryAdventurePlanner(http, new Credential(), new("https://example.test/openai/v1/"), "luna");
        await planner.PlanAsync("Private outcome canary", 30, [new("existing", "Private task canary", null)], default);
        using var request = JsonDocument.Parse(handler.Body!); var root = request.RootElement;
        Assert.False(root.GetProperty("store").GetBoolean()); Assert.True(root.GetProperty("text").GetProperty("format").GetProperty("strict").GetBoolean());
        Assert.Equal("adventure_plan", root.GetProperty("text").GetProperty("format").GetProperty("name").GetString());
        Assert.Contains("Private outcome canary", root.GetProperty("input").GetString());
        Assert.DoesNotContain("Private", JsonSerializer.Serialize(trace.TagObjects.ToDictionary(t => t.Key, t => t.Value)));
    }
    private sealed class Credential : TokenCredential {
        public override AccessToken GetToken(TokenRequestContext context, CancellationToken ct) => new("test-token", DateTimeOffset.UtcNow.AddMinutes(5));
        public override ValueTask<AccessToken> GetTokenAsync(TokenRequestContext context, CancellationToken ct) => ValueTask.FromResult(GetToken(context, ct));
    }
    private sealed class Handler : HttpMessageHandler {
        public string? Body;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            Assert.Equal("Bearer test-token", request.Headers.Authorization!.ToString()); Body = await request.Content!.ReadAsStringAsync(ct);
            return new(HttpStatusCode.OK) { Content = new ByteArrayContent(Response(Draft)) };
        }
    }
}
