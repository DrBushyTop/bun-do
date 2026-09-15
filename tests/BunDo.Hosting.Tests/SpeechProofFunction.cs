using BunDo.Functions.Speech;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;

namespace BunDo.Hosting.Tests;

/// <summary>Opt-in managed-identity adapter proof. Copy into a private verification build only.
/// Function-key authorization is required; normal app authentication is tested separately.</summary>
public sealed class SpeechProofFunction(ISpeechProvider provider)
{
    [Function("SpeechVerification")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "verification/speech")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var locale = request.Headers["X-BunDo-Locale"].ToString();
        if (locale is not ("fi-FI" or "en-US")) return new BadRequestResult();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(request.HttpContext.RequestAborted);
        timeout.CancelAfter(TimeSpan.FromSeconds(110));
        var bytes = new byte[SpeechFunction.MaximumAudioBytes + 1];
        byte[]? pcm = null;
        try
        {
            var count = 0;
            while (count < bytes.Length)
            {
                var read = await request.Body.ReadAsync(bytes.AsMemory(count), timeout.Token);
                if (read == 0) break;
                count += read;
            }
            if (count > SpeechFunction.MaximumAudioBytes) return new StatusCodeResult(413);
            if (count < 2 || count % 2 != 0) return new BadRequestResult();
            pcm = bytes[..count];
            return new OkObjectResult(new { text = await provider.TranscribeAsync(pcm, locale, timeout.Token) });
        }
        finally { Array.Clear(bytes); if (pcm is not null) Array.Clear(pcm); }
    }
}
