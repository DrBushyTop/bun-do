using System.Diagnostics;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Azure.Core;

namespace BunDo.Functions.AI;

internal sealed class FoundryResponses(HttpClient http, TokenCredential credential, Uri endpoint, string deployment)
{
    public async Task<byte[]> GenerateAsync(string instructions, JsonElement schema, string name, object input, CancellationToken ct)
    {
        Activity.Current?.SetTag("ai.deployment", deployment);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(75));
        var token = await credential.GetTokenAsync(new TokenRequestContext(["https://cognitiveservices.azure.com/.default"]), timeout.Token);
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(endpoint, "responses"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token.Token);
        request.Content = JsonContent.Create(new {
            model = deployment, store = false, instructions,
            input = JsonSerializer.Serialize(input), max_output_tokens = 4096,
            reasoning = new { effort = "low" },
            text = new { format = new { type = "json_schema", name, strict = true, schema } },
        });
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        Activity.Current?.SetTag("ai.provider_status", (int)response.StatusCode);
        if (!response.IsSuccessStatusCode) throw new CleanupProviderException("PROVIDER_UNAVAILABLE");
        await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token);
        using var bytes = new MemoryStream();
        var buffer = new byte[8192];
        int count;
        while ((count = await stream.ReadAsync(buffer, timeout.Token)) > 0)
        {
            if (bytes.Length + count > 128 * 1024) throw new CleanupProviderException("INVALID_OUTPUT");
            bytes.Write(buffer, 0, count);
        }
        return bytes.ToArray();
    }

    public static JsonElement Output(byte[] bytes)
    {
            using var document = JsonDocument.Parse(bytes);
            var root = document.RootElement;
            if (root.GetProperty("status").GetString() != "completed") throw new CleanupProviderException("INCOMPLETE_OUTPUT");
            var output = root.GetProperty("output").EnumerateArray()
                .Where(item => item.GetProperty("type").GetString() == "message")
                .SelectMany(item => item.GetProperty("content").EnumerateArray()).ToArray();
            if (output.Any(item => item.GetProperty("type").GetString() == "refusal")) throw new CleanupProviderException("REFUSED");
            if (output.Length != 1 || output[0].GetProperty("type").GetString() != "output_text") throw new CleanupProviderException("INVALID_OUTPUT");
            using var text = JsonDocument.Parse(output[0].GetProperty("text").GetString()!);
            if (root.TryGetProperty("usage", out var usage))
            {
                if (usage.TryGetProperty("input_tokens", out var input) && input.TryGetInt32(out var i)) Activity.Current?.SetTag("ai.input_tokens", i);
                if (usage.TryGetProperty("output_tokens", out var result) && result.TryGetInt32(out var o)) Activity.Current?.SetTag("ai.output_tokens", o);
            }
        return text.RootElement.Clone();
    }

}
