using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Azure.Core;

namespace BunDo.FoundationGates;

// Opt-in deployment checks, linked into tests but never the normal Function app.
public sealed class FoundationGateChecks(HttpClient client, TokenCredential credential)
{
    public async Task<Dictionary<string, object>> Storage(Uri cosmos, Uri blobs)
    {
        RequireEndpoint(cosmos, "cos-bun-do-dev-", ".documents.azure.com");
        RequireEndpoint(blobs, "stbundosnap", ".blob.core.windows.net");
        var id = "foundation-gate-" + Guid.NewGuid().ToString("N");
        var report = new Dictionary<string, object> { ["syntheticId"] = id };
        var cosmosToken = await credential.GetTokenAsync(new TokenRequestContext(["https://cosmos.azure.com/.default"]), CancellationToken.None);
        var blobToken = await credential.GetTokenAsync(new TokenRequestContext(["https://storage.azure.com/.default"]), CancellationToken.None);
        var documentPath = "dbs/bun-do/colls/workspace-items/docs/" + id;
        var blobPath = "sync-snapshots/" + id;
        var cosmosAttempted = false;
        var blobAttempted = false;
        var payload = JsonSerializer.Serialize(new { id, workspaceId = id, probe = "synthetic-foundation-canary" });
        try
        {
            // POST create, never upsert or overwrite an existing document.
            cosmosAttempted = true;
            using (var request = Cosmos(HttpMethod.Post, "dbs/bun-do/colls/workspace-items/docs"))
            {
                request.Content = new StringContent(payload, Encoding.UTF8, "application/json");
                await Check(request, "cosmosCreate", HttpStatusCode.Created);
            }
            using (var request = Cosmos(HttpMethod.Get, documentPath))
            using (var response = await client.SendAsync(request))
            {
                Record("cosmosRead", response, HttpStatusCode.OK);
                using var body = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
                report["cosmosContentMatches"] = body.RootElement.GetProperty("id").GetString() == id
                    && body.RootElement.GetProperty("workspaceId").GetString() == id
                    && body.RootElement.GetProperty("probe").GetString() == "synthetic-foundation-canary";
                if (!(bool)report["cosmosContentMatches"]) throw new InvalidDataException();
            }
            blobAttempted = true;
            using (var request = Blob(HttpMethod.Put, blobPath))
            {
                request.Headers.TryAddWithoutValidation("x-ms-blob-type", "BlockBlob");
                request.Headers.IfNoneMatch.Add(EntityTagHeaderValue.Any);
                request.Content = new StringContent("synthetic-foundation-canary", Encoding.UTF8, "text/plain");
                await Check(request, "blobCreate", HttpStatusCode.Created);
            }
            using (var request = Blob(HttpMethod.Get, blobPath))
            using (var response = await client.SendAsync(request))
            {
                Record("blobRead", response, HttpStatusCode.OK);
                report["blobContentMatches"] = await response.Content.ReadAsStringAsync() == "synthetic-foundation-canary";
                if (!(bool)report["blobContentMatches"]) throw new InvalidDataException();
            }
            // Read-only account operation outside the container grant.
            using (var request = Blob(HttpMethod.Get, "?comp=list"))
                await Check(request, "blobAccountList", HttpStatusCode.Forbidden);
            report["result"] = "SUCCEEDED";
        }
        catch (Exception error)
        {
            report["result"] = "FAILED";
            report["errorType"] = error.GetType().Name;
        }
        finally
        {
            // A lost create response may still have written our random artifact.
            // Cleanup has no request-abort token and only touches these exact IDs.
            if (blobAttempted) await Cleanup(Blob, blobPath, "blob");
            if (cosmosAttempted) await Cleanup(Cosmos, documentPath, "cosmos");
        }
        return report;

        HttpRequestMessage Cosmos(HttpMethod method, string path)
        {
            var request = new HttpRequestMessage(method, new Uri(cosmos, path));
            request.Headers.TryAddWithoutValidation("Authorization",
                Uri.EscapeDataString("type=aad&ver=1.0&sig=" + cosmosToken.Token));
            request.Headers.TryAddWithoutValidation("x-ms-date", DateTimeOffset.UtcNow.ToString("r", CultureInfo.InvariantCulture));
            request.Headers.TryAddWithoutValidation("x-ms-version", "2018-12-31");
            request.Headers.TryAddWithoutValidation("x-ms-documentdb-partitionkey", JsonSerializer.Serialize(new[] { id }));
            return request;
        }
        HttpRequestMessage Blob(HttpMethod method, string path)
        {
            var request = new HttpRequestMessage(method, new Uri(blobs, path));
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", blobToken.Token);
            request.Headers.TryAddWithoutValidation("x-ms-date", DateTimeOffset.UtcNow.ToString("r", CultureInfo.InvariantCulture));
            request.Headers.TryAddWithoutValidation("x-ms-version", "2023-11-03");
            return request;
        }
        void Record(string name, HttpResponseMessage response, HttpStatusCode expected)
        {
            report[name] = (int)response.StatusCode;
            if (response.StatusCode != expected) throw new InvalidDataException();
        }
        async Task Check(HttpRequestMessage request, string name, HttpStatusCode expected)
        {
            using var response = await client.SendAsync(request);
            Record(name, response, expected);
        }
        async Task Cleanup(Func<HttpMethod, string, HttpRequestMessage> create, string path, string prefix)
        {
            try
            {
                using var delete = create(HttpMethod.Delete, path);
                using var response = await client.SendAsync(delete);
                report[prefix + "Delete"] = (int)response.StatusCode;
                if (response.StatusCode is not (HttpStatusCode.NoContent or HttpStatusCode.Accepted or HttpStatusCode.NotFound))
                    throw new InvalidDataException();
                using var read = create(HttpMethod.Get, path);
                await Check(read, prefix + "AfterDelete", HttpStatusCode.NotFound);
            }
            catch (Exception error)
            {
                report["result"] = "FAILED";
                report[prefix + "CleanupError"] = error.GetType().Name;
            }
        }
    }

    public async Task<AiGateResult> AiFailure(Uri endpoint, string deployment, bool incomplete)
    {
        RequireEndpoint(endpoint, "ai-bun-do-dev-", ".openai.azure.com", "/openai/v1/");
        if (deployment != "bun-do-luna") throw new ArgumentException("Only the dedicated Luna gate is allowed.");
        var token = await credential.GetTokenAsync(new TokenRequestContext(["https://ai.azure.com/.default"]), CancellationToken.None);
        var payload = new
        {
            model = deployment, store = false, tools = Array.Empty<object>(),
            input = "Synthetic deployment test. Write a numbered list of 50 common kitchen objects. Do not abbreviate.",
            max_output_tokens = incomplete ? 16 : 32,
            text = incomplete ? null : new
            {
                format = new { type = "json_schema", name = "intentionally_invalid_gate", strict = true,
                    schema = new { type = "invalid-type-for-configuration-gate" } },
            },
        };
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(endpoint, "responses"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token.Token);
        request.Content = new StringContent(JsonSerializer.Serialize(payload,
            new JsonSerializerOptions { DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull }),
            Encoding.UTF8, "application/json");
        var watch = Stopwatch.StartNew();
        using var response = await client.SendAsync(request); // One attempt, never retry.
        var body = "";
        try { body = await response.Content.ReadAsStringAsync(); }
        catch (HttpRequestException) { /* Preserve the known HTTP status. */ }
        var result = AiGateResult.Classify((int)response.StatusCode, body);
        return result with { ElapsedMs = watch.ElapsedMilliseconds };
    }

    private static void RequireEndpoint(Uri uri, string prefix, string suffix, string path = "/")
    {
        if (uri.Scheme != "https" || !uri.IsDefaultPort || !uri.Host.StartsWith(prefix, StringComparison.Ordinal)
            || !uri.Host.EndsWith(suffix, StringComparison.Ordinal) || uri.UserInfo != ""
            || uri.Query != "" || uri.Fragment != "" || uri.AbsolutePath != path)
            throw new ArgumentException("Endpoint is not the dedicated Bun Do development endpoint.");
    }
}

public sealed record AiGateResult(string Classification, int HttpStatus, long? InputTokens = null,
    long? OutputTokens = null, long? ElapsedMs = null, string? ProviderCode = null,
    string? ProviderParameter = null, string? IncompleteReason = null)
{
    public bool SchemaRejected => HttpStatus == 400 &&
        (ProviderCode == "invalid_json_schema" || ProviderParameter == "text.format.schema");

    public static AiGateResult Classify(int status, string body)
    {
        if (status == 429) return new("THROTTLED", status);
        if (status is >= 400 and < 500)
        {
            // HTTP status remains authoritative if the body is empty or invalid.
            string? code = null, parameter = null;
            try
            {
                using var errorDocument = JsonDocument.Parse(body);
                var error = errorDocument.RootElement.GetProperty("error");
                code = Allowed(error, "code", ["invalid_json_schema", "invalid_request_error", "invalid_parameter"]);
                parameter = Allowed(error, "param", ["text.format.schema", "model", "max_output_tokens"]);
            }
            catch (Exception error) when (error is JsonException or InvalidOperationException or KeyNotFoundException) { }
            return new("FAILED_CONFIGURATION", status, ProviderCode: code, ProviderParameter: parameter);
        }
        if (status is < 200 or >= 300) return new("FAILED_PROVIDER", status);
        long? input = null, output = null;
        try
        {
            using var document = JsonDocument.Parse(body);
            var root = document.RootElement;
            if (root.TryGetProperty("usage", out var usage) && usage.ValueKind == JsonValueKind.Object)
            {
                input = usage.GetProperty("input_tokens").GetInt64();
                output = usage.GetProperty("output_tokens").GetInt64();
            }
            var state = root.GetProperty("status").GetString();
            if (state == "incomplete")
            {
                var reason = root.TryGetProperty("incomplete_details", out var details)
                    ? Allowed(details, "reason", ["max_output_tokens", "content_filter"]) : null;
                return new("INCOMPLETE", status, input, output, IncompleteReason: reason);
            }
            if (state != "completed") return new("UNKNOWN_OUTCOME", status, input, output);
            foreach (var item in root.GetProperty("output").EnumerateArray())
                if (item.TryGetProperty("content", out var content))
                    foreach (var part in content.EnumerateArray())
                        if (part.GetProperty("type").GetString() == "refusal")
                            return new("REFUSED", status, input, output);
            return new("COMPLETED", status, input, output);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or KeyNotFoundException or FormatException)
        {
            return new("UNKNOWN_OUTCOME", status, input, output);
        }
    }

    private static string? Allowed(JsonElement element, string property, string[] values)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(property, out var value)
            || value.ValueKind != JsonValueKind.String) return null;
        var text = value.GetString();
        return values.Contains(text) ? text : "OTHER";
    }
}
