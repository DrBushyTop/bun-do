using System.Diagnostics;
using System.Net;
using System.Text.Json;
using Azure.Core;
using BunDo.Functions.Adventures;
using BunDo.Functions.AI;

namespace BunDo.Hosting.Tests;

public sealed class AdventureProviderTests
{
    private static object Draft(string title = "Lempeä seikkailu", string last = "three") => new { title, flavor = "", phases = new[] { "one", "two", last }.Select(rootId =>
        new { rootId, name = "A useful phase", stars = 2, minutes = 15 }).ToArray() };
    private static string Content() => JsonSerializer.Serialize(new { proposals = new[] { Draft(), Draft("A different session", "four") } });
    private static byte[] Response(string text, string status = "completed") => JsonSerializer.SerializeToUtf8Bytes(new {
        status, output = new[] { new { type = "message", content = new[] { new { type = "output_text", text } } } },
        usage = new { input_tokens = 50, output_tokens = 100 },
    });

    [Fact]
    public void Accepts_bilingual_proposals_and_rejects_refusal_and_incomplete_envelopes()
    {
        Assert.Equal(2, FoundryAdventureProvider.ParseResponse(Response(Content())).Length);
        Assert.Equal("INCOMPLETE_OUTPUT", Assert.Throws<CleanupProviderException>(() =>
            FoundryAdventureProvider.ParseResponse(Response(Content(), "incomplete"))).Code);
        Assert.Equal("REFUSED", Assert.Throws<CleanupProviderException>(() => FoundryAdventureProvider.ParseResponse(
            JsonSerializer.SerializeToUtf8Bytes(new { status = "completed", output = new[] {
                new { type = "message", content = new[] { new { type = "refusal", refusal = "Private refusal" } } } } })) ).Code);
    }

    [Theory]
    [InlineData("null")]
    [InlineData("{}")]
    [InlineData("{\"proposals\":[]}")]
    [InlineData("{\"proposals\":[null,null]}")]
    [InlineData("{\"proposals\":[],\"proposals\":[]}")]
    public void Rejects_invalid_output_shapes(string content) => Assert.Equal("INVALID_OUTPUT", Assert.Throws<CleanupProviderException>(() =>
        FoundryAdventureProvider.ParseResponse(Response(content))).Code);

    [Theory]
    [InlineData("\"stars\":2", "\"stars\":4")]
    [InlineData("\"minutes\":15", "\"minutes\":0")]
    [InlineData("\"minutes\":15", "\"minutes\":1.5")]
    [InlineData("\"flavor\":\"\"", "\"flavor\":null")]
    [InlineData("\"flavor\":\"\"", "\"flavor\":\"\",\"extra\":true")]
    [InlineData("\"rootId\":\"one\"", "\"rootId\":\"one\",\"rootId\":\"two\"")]
    public void Rejects_invalid_estimates_nulls_and_unknown_or_duplicate_fields(string before, string after) =>
        Assert.Equal("INVALID_OUTPUT", Assert.Throws<CleanupProviderException>(() =>
            FoundryAdventureProvider.ParseResponse(Response(Content().Replace(before, after)))).Code);

    [Fact]
    public async Task Uses_managed_identity_structured_schema_no_storage_and_content_free_telemetry()
    {
        var handler = new Handler(Response(Content()));
        var credential = new Credential();
        using var http = new HttpClient(handler);
        using var activity = new Activity("adventure-provider").Start();
        var provider = new FoundryAdventureProvider(http, credential, new("https://example.test/openai/v1/"), "luna");
        await provider.GenerateAsync([new("one", "Private task canary", "Private description")], default);
        Assert.Equal("https://example.test/openai/v1/responses", handler.Url);
        Assert.Equal("Bearer test-token", handler.Auth);
        Assert.Equal("https://cognitiveservices.azure.com/.default", credential.Scope);
        using var request = JsonDocument.Parse(handler.Body!);
        var root = request.RootElement;
        Assert.False(root.GetProperty("store").GetBoolean());
        Assert.True(root.GetProperty("text").GetProperty("format").GetProperty("strict").GetBoolean());
        Assert.Equal("household_adventures", root.GetProperty("text").GetProperty("format").GetProperty("name").GetString());
        using var input = JsonDocument.Parse(root.GetProperty("input").GetString()!);
        Assert.Equal("one", input.RootElement.GetProperty("tasks")[0].GetProperty("rootId").GetString());
        Assert.Contains("Private task canary", root.GetProperty("input").GetString());
        var telemetry = JsonSerializer.Serialize(activity.TagObjects.ToDictionary(t => t.Key, t => t.Value));
        Assert.DoesNotContain("Private", telemetry); Assert.DoesNotContain("test-token", telemetry);
        Assert.Equal(50, activity.GetTagItem("ai.input_tokens")); Assert.Equal(100, activity.GetTagItem("ai.output_tokens"));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Bounds_responses_and_does_not_copy_provider_error_bodies(bool oversized)
    {
        var handler = new Handler(oversized ? new byte[128 * 1024 + 1] : "Private upstream error"u8.ToArray()) {
            Status = oversized ? HttpStatusCode.OK : HttpStatusCode.BadGateway };
        using var http = new HttpClient(handler);
        var provider = new FoundryAdventureProvider(http, new Credential(), new("https://example.test/"), "luna");
        var error = await Assert.ThrowsAsync<CleanupProviderException>(() => provider.GenerateAsync([new("one", "Task", null)], default));
        Assert.Equal(oversized ? "INVALID_OUTPUT" : "PROVIDER_UNAVAILABLE", error.Code);
        Assert.DoesNotContain("Private", error.Message);
    }

    private sealed class Credential : TokenCredential
    {
        public string? Scope;
        public override AccessToken GetToken(TokenRequestContext requestContext, CancellationToken cancellationToken)
        { Scope = Assert.Single(requestContext.Scopes); return new("test-token", DateTimeOffset.UtcNow.AddHours(1)); }
        public override ValueTask<AccessToken> GetTokenAsync(TokenRequestContext requestContext, CancellationToken cancellationToken) =>
            ValueTask.FromResult(GetToken(requestContext, cancellationToken));
    }
    private sealed class Handler(byte[] body) : HttpMessageHandler
    {
        public HttpStatusCode Status = HttpStatusCode.OK;
        public string? Body, Auth, Url;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Body = await request.Content!.ReadAsStringAsync(ct); Auth = request.Headers.Authorization!.ToString(); Url = request.RequestUri!.ToString();
            return new(Status) { Content = new ByteArrayContent(body) };
        }
    }
}
