using System.Net;
using System.Text.Json;
using Azure.Core;
using BunDo.FoundationGates;

namespace BunDo.Hosting.Tests;

public sealed class FoundationGateTests
{
    [Theory]
    [InlineData(429, "<html>private-provider-body</html>", "THROTTLED")]
    [InlineData(400, "", "FAILED_CONFIGURATION")]
    [InlineData(403, "private-provider-body", "FAILED_CONFIGURATION")]
    [InlineData(503, "", "FAILED_PROVIDER")]
    [InlineData(200, "not-json-private-body", "UNKNOWN_OUTCOME")]
    [InlineData(200, """{"status":"failed"}""", "UNKNOWN_OUTCOME")]
    [InlineData(200, """{"status":"completed","output":[]}""", "COMPLETED")]
    [InlineData(200, """{"status":"incomplete","usage":{"input_tokens":12,"output_tokens":16}}""", "INCOMPLETE")]
    [InlineData(200, """{"status":"completed","output":[{"content":[{"type":"refusal","refusal":"private-refusal"}]}]}""", "REFUSED")]
    public void Classifier_never_returns_provider_content(int status, string body, string expected)
    {
        var result = AiGateResult.Classify(status, body);
        Assert.Equal(expected, result.Classification);
        Assert.Equal(status, result.HttpStatus);
        Assert.DoesNotContain("private-", JsonSerializer.Serialize(result));
    }

    [Fact]
    public void Malformed_completion_preserves_known_usage()
    {
        var result = AiGateResult.Classify(200,
            """{"status":"completed","usage":{"input_tokens":12,"output_tokens":16}}""");
        Assert.Equal("UNKNOWN_OUTCOME", result.Classification);
        Assert.Equal(12, result.InputTokens);
        Assert.Equal(16, result.OutputTokens);
    }

    [Fact]
    public void Gate_evidence_distinguishes_schema_rejection_and_token_limit_from_other_failures()
    {
        var schema = AiGateResult.Classify(400,
            """{"error":{"code":"invalid_json_schema","param":"text.format.schema","message":"private-message"}}""");
        Assert.True(schema.SchemaRejected);
        Assert.False(AiGateResult.Classify(400, """{"error":{"param":"model"}}""").SchemaRejected);
        var unknown = AiGateResult.Classify(400, """{"error":{"code":"private-code","param":"private-param"}}""");
        Assert.Equal("OTHER", unknown.ProviderCode);
        Assert.DoesNotContain("private-", JsonSerializer.Serialize(unknown));
        var incomplete = AiGateResult.Classify(200,
            """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}""");
        Assert.Equal("max_output_tokens", incomplete.IncompleteReason);
        Assert.Null(AiGateResult.Classify(200, """{"status":"incomplete"}""").IncompleteReason);
    }

    [Theory]
    [InlineData(429, "THROTTLED")]
    [InlineData(400, "FAILED_CONFIGURATION")]
    public async Task Ai_gate_sends_once_with_store_false_and_no_tools(int status, string expected)
    {
        var calls = 0;
        using var client = new HttpClient(new Handler(async request =>
        {
            calls++;
            Assert.Equal("https://ai-bun-do-dev-test.openai.azure.com/openai/v1/responses", request.RequestUri!.AbsoluteUri);
            Assert.Equal("Bearer", request.Headers.Authorization!.Scheme);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
            Assert.False(body.RootElement.GetProperty("store").GetBoolean());
            Assert.Empty(body.RootElement.GetProperty("tools").EnumerateArray());
            Assert.Equal("bun-do-luna", body.RootElement.GetProperty("model").GetString());
            return Response(status, "private-error-body");
        }));
        var result = await new FoundationGateChecks(client, new Credential()).AiFailure(
            new Uri("https://ai-bun-do-dev-test.openai.azure.com/openai/v1/"), "bun-do-luna", false);
        Assert.Equal(expected, result.Classification);
        Assert.Equal(1, calls);
    }

    [Theory]
    [InlineData("none")]
    [InlineData("cosmos")]
    [InlineData("blob")]
    public async Task Storage_gate_cleans_only_its_random_artifacts_even_after_lost_create_response(string lostCreate)
    {
        string? document = null;
        string? syntheticId = null;
        var blobCreated = false;
        var deleted = new List<string>();
        using var client = new HttpClient(new Handler(async request =>
        {
            var uri = request.RequestUri!;
            if (uri.Host.StartsWith("cos-"))
            {
                var authorization = Uri.UnescapeDataString(request.Headers.GetValues("Authorization").Single());
                Assert.StartsWith("type=aad&ver=1.0&sig=", authorization);
                var partition = JsonSerializer.Deserialize<string[]>(request.Headers.GetValues("x-ms-documentdb-partitionkey").Single())!.Single();
                Assert.StartsWith("foundation-gate-", partition);
                syntheticId ??= partition;
                Assert.Equal(syntheticId, partition);
                if (request.Method == HttpMethod.Post)
                {
                    document = await request.Content!.ReadAsStringAsync();
                    Assert.DoesNotContain("upsert", string.Join(" ", request.Headers.Select(h => h.Key)));
                    if (lostCreate == "cosmos") throw new HttpRequestException("private-transport-error");
                    return Response(201);
                }
                Assert.EndsWith("/docs/" + syntheticId, uri.AbsolutePath);
                if (request.Method == HttpMethod.Delete)
                {
                    deleted.Add("cosmos"); document = null; return Response(204);
                }
                return document is null ? Response(404) : Response(200, document);
            }
            Assert.Equal("Bearer", request.Headers.Authorization!.Scheme);
            if (uri.Query == "?comp=list") return Response(403);
            Assert.Equal("/sync-snapshots/" + syntheticId, uri.AbsolutePath);
            if (request.Method == HttpMethod.Put)
            {
                Assert.Equal("*", request.Headers.IfNoneMatch.Single().Tag);
                blobCreated = true;
                if (lostCreate == "blob") throw new HttpRequestException("private-transport-error");
                return Response(201);
            }
            if (request.Method == HttpMethod.Delete)
            {
                deleted.Add("blob"); blobCreated = false; return Response(202);
            }
            return blobCreated ? Response(200, "synthetic-foundation-canary") : Response(404);
        }));
        var report = await new FoundationGateChecks(client, new Credential()).Storage(
            new Uri("https://cos-bun-do-dev-test.documents.azure.com/"),
            new Uri("https://stbundosnaptest.blob.core.windows.net/"));
        Assert.Equal(lostCreate == "none" ? "SUCCEEDED" : "FAILED", report["result"]);
        Assert.Equal(404, report["cosmosAfterDelete"]);
        Assert.Contains("cosmos", deleted);
        if (lostCreate != "cosmos")
        {
            Assert.Equal(404, report["blobAfterDelete"]);
            Assert.Contains("blob", deleted);
        }
        Assert.DoesNotContain("private-", JsonSerializer.Serialize(report));
        Assert.DoesNotContain("synthetic-foundation-canary", JsonSerializer.Serialize(report));
    }

    [Fact]
    public async Task Gate_rejects_unrelated_endpoints_before_sending()
    {
        using var client = new HttpClient(new Handler(_ => throw new Xunit.Sdk.XunitException("Must not send")));
        var gate = new FoundationGateChecks(client, new Credential());
        await Assert.ThrowsAsync<ArgumentException>(() => gate.Storage(
            new Uri("https://unrelated.documents.azure.com/"),
            new Uri("https://stbundosnaptest.blob.core.windows.net/")));
        await Assert.ThrowsAsync<ArgumentException>(() => gate.AiFailure(
            new Uri("https://ai-bun-do-dev-test.openai.azure.com/openai/v1/?redirect=private"), "bun-do-luna", false));
    }

    private static HttpResponseMessage Response(int status, string body = "") =>
        new((HttpStatusCode)status) { Content = new StringContent(body) };

    private sealed class Handler(Func<HttpRequestMessage, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => send(request);
    }

    private sealed class Credential : TokenCredential
    {
        public override AccessToken GetToken(TokenRequestContext requestContext, CancellationToken cancellationToken) =>
            new("private-test-token", DateTimeOffset.UtcNow.AddHours(1));
        public override ValueTask<AccessToken> GetTokenAsync(TokenRequestContext requestContext, CancellationToken cancellationToken) =>
            ValueTask.FromResult(GetToken(requestContext, cancellationToken));
    }
}
