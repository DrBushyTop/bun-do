using System.Diagnostics;
using BunDo.Functions.Identity;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Functions.Speech;

public interface ISpeechProvider
{
    Task<string> TranscribeAsync(byte[] pcm, string locale, CancellationToken ct);
}

public sealed class SpeechException(string code) : Exception
{
    public string Code { get; } = code;
}

/// <summary>Audio exists only in request memory. Local recovery, not this endpoint, owns retries.</summary>
public sealed class SpeechFunction(AccessTokens tokens, IServiceProvider services)
{
    // Matches the recorder's PCM buffer, not a per-account consumption allowance.
    public const int MaximumAudioBytes = 3_840_000;
    public const int MaximumTextCharacters = 4000;

    [Function("Transcribe")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "api/speech/transcribe")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var ct = request.HttpContext.RequestAborted;
        Activity.Current?.SetTag("operation.stage", "speech.authenticate");
        var headers = request.Headers.Authorization;
        if (headers.Count != 1 || headers[0] is not { } header ||
            !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || header[7..].Any(char.IsWhiteSpace))
            return Failure("SIGN_IN_REQUIRED", 401);
        var auth = await tokens.ValidateAsync(header[7..], ct);
        if (auth.Status != AuthenticationStatus.Accepted)
            return Failure("SIGN_IN_REQUIRED", auth.Status switch {
                AuthenticationStatus.MissingScope => 403, AuthenticationStatus.Unavailable => 503, _ => 401 });
        var registrations = services.GetService<IRegistrationStore>();
        var provider = services.GetService<ISpeechProvider>();
        if (registrations is null || provider is null) return Failure("UNAVAILABLE", 503);
        var registrationHeader = request.Headers["X-BunDo-Registration"];
        if (registrationHeader.Count != 1 || !Guid.TryParse(registrationHeader[0], out var registration) ||
            !await registrations.IsActiveAsync(auth.Identity!, registration, ct))
            return Failure("SIGN_IN_REQUIRED", 403);
        var locale = request.Headers["X-BunDo-Locale"];
        if (locale.Count != 1 || locale[0] is not ("fi-FI" or "en-US") ||
            request.ContentType != "application/octet-stream")
            return Failure("INVALID_AUDIO", 400);
        if (request.ContentLength > MaximumAudioBytes) return Failure("AUDIO_TOO_LARGE", 413);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(110));
        var buffer = new byte[MaximumAudioBytes + 1];
        byte[]? pcm = null;
        try
        {
            Activity.Current?.SetTag("operation.stage", "speech.upload");
            var count = 0;
            while (count < buffer.Length)
            {
                var read = await request.Body.ReadAsync(buffer.AsMemory(count), timeout.Token);
                if (read == 0) break;
                count += read;
            }
            if (count > MaximumAudioBytes) return Failure("AUDIO_TOO_LARGE", 413);
            if (count < 2 || count % 2 != 0) return Failure("INVALID_AUDIO", 400);
            Activity.Current?.SetTag("speech.audio_bytes", count);
            pcm = buffer[..count];
            Activity.Current?.SetTag("operation.stage", "speech.provider");
            var text = await provider.TranscribeAsync(pcm, locale[0]!, timeout.Token);
            timeout.Token.ThrowIfCancellationRequested();
            if (!await registrations.IsActiveAsync(auth.Identity!, registration, timeout.Token))
                return Failure("SIGN_IN_REQUIRED", 403);
            if (text.EnumerateRunes().Count() > MaximumTextCharacters) return Failure("TOO_LONG", 422);
            Activity.Current?.SetTag("speech.result", string.IsNullOrWhiteSpace(text) ? "SILENCE" : "TRANSCRIBED");
            return new OkObjectResult(new { text });
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return Failure("UNAVAILABLE", 504); }
        catch (SpeechException error) { return Failure(error.Code, error.Code == "TOO_LONG" ? 422 : 502); }
        catch (HttpRequestException) { return Failure("UNAVAILABLE", 502); }
        finally
        {
            Array.Clear(buffer);
            if (pcm is not null) Array.Clear(pcm);
        }
    }

    private static ObjectResult Failure(string code, int status)
    {
        Activity.Current?.SetTag("speech.result", code);
        return new(new { code }) { StatusCode = status };
    }
}
