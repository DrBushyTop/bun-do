using System.Buffers.Binary;
using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Azure.Core;

namespace BunDo.Functions.Speech;

public sealed class MaiSpeechProvider(HttpClient http, TokenCredential credential, Uri endpoint) : ISpeechProvider
{
    public async Task<string> TranscribeAsync(byte[] pcm, string locale, CancellationToken ct)
    {
        if (endpoint.Scheme != "https" || !endpoint.Host.EndsWith(".cognitiveservices.azure.com", StringComparison.Ordinal) ||
            !endpoint.IsDefaultPort || endpoint.UserInfo.Length != 0) throw new SpeechException("UNAVAILABLE");
        if (pcm.Length < 2 || pcm.Length > SpeechFunction.MaximumAudioBytes || pcm.Length % 2 != 0 ||
            locale is not ("fi-FI" or "en-US")) throw new SpeechException("INVALID_AUDIO");
        // Do not ask a generative recognizer to invent words for digital silence.
        var audible = false;
        for (var i = 0; i < pcm.Length; i += 2)
            if (Math.Abs((int)BinaryPrimitives.ReadInt16LittleEndian(pcm.AsSpan(i, 2))) >= 33) { audible = true; break; }
        if (!audible) return "";
        var token = await credential.GetTokenAsync(new TokenRequestContext(["https://cognitiveservices.azure.com/.default"]), ct);
        using var request = new HttpRequestMessage(HttpMethod.Post,
            new Uri(endpoint, "/speechtotext/transcriptions:transcribe?api-version=2025-10-15"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token.Token);
        using var body = new MultipartFormDataContent();
        body.Add(new StringContent(JsonSerializer.Serialize(new {
            locales = new[] { locale },
            enhancedMode = new { enabled = true, model = "MAI-Transcribe-2",
                modelOptions = new { transcribeStyle = "verbatim", timestamps = "none" } },
        }), Encoding.UTF8, "application/json"), "definition");
        var wav = Wave(pcm);
        try
        {
            var audio = new ByteArrayContent(wav);
            audio.Headers.ContentType = new MediaTypeHeaderValue("audio/wav");
            body.Add(audio, "audio", "recording.wav");
            request.Content = body;
            var clock = Stopwatch.StartNew();
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            Activity.Current?.SetTag("speech.provider_status", (int)response.StatusCode);
            if (!response.IsSuccessStatusCode) throw new SpeechException("UNAVAILABLE");
            await using var stream = await response.Content.ReadAsStreamAsync(ct);
            var bytes = new byte[128 * 1024 + 1];
            try
            {
                var count = 0;
                while (count < bytes.Length)
                {
                    var read = await stream.ReadAsync(bytes.AsMemory(count), ct);
                    if (read == 0) break;
                    count += read;
                }
                if (count == bytes.Length) throw new SpeechException("INVALID_OUTPUT");
                using var json = JsonDocument.Parse(bytes.AsMemory(0, count), new JsonDocumentOptions { MaxDepth = 16 });
                var phrases = json.RootElement.GetProperty("combinedPhrases");
                var text = string.Join(" ", phrases.EnumerateArray().Select(p => p.GetProperty("text").GetString()
                    ?? throw new SpeechException("INVALID_OUTPUT"))).Trim();
                if (text.EnumerateRunes().Count() > SpeechFunction.MaximumTextCharacters) throw new SpeechException("TOO_LONG");
                Activity.Current?.SetTag("speech.provider_ms", clock.ElapsedMilliseconds);
                return text;
            }
            catch (Exception error) when (error is JsonException or InvalidOperationException or KeyNotFoundException)
            { throw new SpeechException("INVALID_OUTPUT"); }
            finally { Array.Clear(bytes); }
        }
        finally { Array.Clear(wav); }
    }

    private static byte[] Wave(byte[] pcm)
    {
        using var stream = new MemoryStream(pcm.Length + 44);
        using var writer = new BinaryWriter(stream, Encoding.ASCII, leaveOpen: true);
        writer.Write("RIFF"u8); writer.Write(pcm.Length + 36); writer.Write("WAVEfmt "u8);
        writer.Write(16); writer.Write((short)1); writer.Write((short)1);
        writer.Write(16000); writer.Write(32000); writer.Write((short)2); writer.Write((short)16);
        writer.Write("data"u8); writer.Write(pcm.Length);
        writer.Write(pcm);
        var result = stream.ToArray();
        Array.Clear(stream.GetBuffer());
        return result;
    }
}
