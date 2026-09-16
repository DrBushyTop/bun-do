using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text.Json;
using Azure.Core;
using BunDo.Functions.AI;
namespace BunDo.Functions.Artwork;

public sealed class FoundryArtworkGenerator(HttpClient http, TokenCredential credential, Uri endpoint, string deployment, byte[] reference) : IArtworkGenerator
{
    public static string Prompt(ArtworkBrief brief) {
        if (ArtworkBrief.Find(brief.Key) != brief) throw new ArgumentException("Unknown visual brief");
        return $"""
            Create one finished landscape illustration for Bun Do, matching the provided approved character and material reference.
            Royal Bun is a calm rabbit martial-arts master in a simple gi and tied belt with a small restrained crown.
            Setting: {brief.Setting}. Activity: {brief.Activity}. Theme: {brief.Theme}. Style: {brief.Style}.
            Warm paper, restrained ink-green, Japanese-inspired timber dojo, stone and moss. Quiet secular household work.
            Keep the rabbit recognizable and anatomically consistent with the reference. No weapons, combat, guilt, scores,
            letters, labels, logos, names or addresses. This is reusable generic artwork, not a depiction of any household.
            Compose the rabbit and meaningful props in the middle third with quiet space near the edges for cropping.
            """;
    }
    public async Task<byte[]> GenerateAsync(ArtworkBrief brief, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromMinutes(3));
        try {
            var root = endpoint.GetLeftPart(UriPartial.Authority);
            using var request = new HttpRequestMessage(HttpMethod.Post,
                $"{root}/openai/deployments/{Uri.EscapeDataString(deployment)}/images/edits?api-version=2025-04-01-preview");
            var token = await credential.GetTokenAsync(new(["https://cognitiveservices.azure.com/.default"]), timeout.Token);
            request.Headers.Authorization = new("Bearer", token.Token);
            using var form = new MultipartFormDataContent();
            foreach (var (key, value) in new Dictionary<string, string> { ["prompt"] = Prompt(brief), ["n"] = "1", ["size"] = "1536x1024",
                ["quality"] = "high", ["input_fidelity"] = "high", ["output_format"] = "jpeg", ["output_compression"] = "80" }) form.Add(new StringContent(value), key);
            var image = new ByteArrayContent(reference); image.Headers.ContentType = new MediaTypeHeaderValue("image/png");
            form.Add(image, "image", "royal-bun-reference.png"); request.Content = form;
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
            Activity.Current?.SetTag("ai.provider_status", (int)response.StatusCode);
            if (!response.IsSuccessStatusCode) throw new CleanupProviderException("PROVIDER_UNAVAILABLE");
            await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token);
            using var buffer = new MemoryStream(); var block = new byte[8192];
            while (true) { var count = await stream.ReadAsync(block, timeout.Token); if (count == 0) break;
                if (buffer.Length + count > 12 * 1024 * 1024) throw new CleanupProviderException("INVALID_OUTPUT"); buffer.Write(block, 0, count); }
            using var json = JsonDocument.Parse(buffer.ToArray()); var data = json.RootElement.GetProperty("data");
            if (data.GetArrayLength() != 1) throw new CleanupProviderException("INVALID_OUTPUT");
            var bytes = Convert.FromBase64String(data[0].GetProperty("b64_json").GetString()!);
            if (bytes.Length is < 4 or > 8 * 1024 * 1024 || bytes[0] != 0xff || bytes[1] != 0xd8 || bytes[^2] != 0xff || bytes[^1] != 0xd9)
                throw new CleanupProviderException("INVALID_OUTPUT");
            Activity.Current?.SetTag("artwork.bytes", bytes.Length);
            return bytes;
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new CleanupProviderException("PROVIDER_TIMEOUT"); }
        catch (Exception e) when (e is JsonException or FormatException or InvalidOperationException or KeyNotFoundException) { throw new CleanupProviderException("INVALID_OUTPUT"); }
    }
}
