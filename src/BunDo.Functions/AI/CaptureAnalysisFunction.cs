using System.Diagnostics;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Identity;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Functions.AI;

/// <summary>One cancellable suggestion request. Never creates a task or stores the transcript.</summary>
public sealed class CaptureAnalysisFunction(AccessTokens tokens, IServiceProvider services)
{
    [Function("AnalyzeCapture")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "api/speech/analyze")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var ct = request.HttpContext.RequestAborted;
        Activity.Current?.SetTag("operation.stage", "capture.authenticate");
        var headers = request.Headers.Authorization;
        if (headers.Count != 1 || headers[0] is not { } header ||
            !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || header[7..].Any(char.IsWhiteSpace))
            return Failure("SIGN_IN_REQUIRED", 401);
        var auth = await tokens.ValidateAsync(header[7..], ct);
        if (auth.Status != AuthenticationStatus.Accepted)
            return Failure("SIGN_IN_REQUIRED", auth.Status switch {
                AuthenticationStatus.MissingScope => 403, AuthenticationStatus.Unavailable => 503, _ => 401 });
        var registrations = services.GetService<IRegistrationStore>();
        var provider = services.GetService<ICleanupProvider>();
        if (registrations is null || provider is null) return Failure("UNAVAILABLE", 503);
        var registrationHeader = request.Headers["X-BunDo-Registration"];
        if (registrationHeader.Count != 1 || !Guid.TryParse(registrationHeader[0], out var registration) ||
            !await registrations.IsActiveAsync(auth.Identity!, registration, ct))
            return Failure("SIGN_IN_REQUIRED", 403);
        if (request.ContentType != "application/json") return Failure("INVALID_INPUT", 400);
        const int maximum = 32 * 1024;
        if (request.ContentLength > maximum) return Failure("TOO_LONG", 413);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(90));
        var buffer = new byte[maximum + 1];
        try
        {
            var count = 0;
            while (count < buffer.Length)
            {
                var read = await request.Body.ReadAsync(buffer.AsMemory(count), timeout.Token);
                if (read == 0) break;
                count += read;
            }
            if (count > maximum) return Failure("TOO_LONG", 413);
            string transcript;
            try
            {
                using var body = JsonDocument.Parse(buffer.AsMemory(0, count));
                transcript = body.RootElement.GetProperty("transcript").GetString()!;
                if (string.IsNullOrWhiteSpace(transcript) || transcript.EnumerateRunes().Count() > 4000)
                    return Failure("INVALID_INPUT", 400);
            }
            catch (Exception error) when (error is JsonException or InvalidOperationException or KeyNotFoundException)
            { return Failure("INVALID_INPUT", 400); }
            Activity.Current?.SetTag("operation.stage", "capture.provider");
            var proposal = await provider.GenerateCaptureAsync(transcript, timeout.Token);
            timeout.Token.ThrowIfCancellationRequested();
            if (!await registrations.IsActiveAsync(auth.Identity!, registration, timeout.Token))
                return Failure("SIGN_IN_REQUIRED", 403);
            if (!TaskCleanup.Valid(proposal) || proposal.Description is null || proposal.Items is not { Length: <= 16 } ||
                proposal.Items.Length > 0 && !TaskSplit.Valid(proposal)) return Failure("INVALID_OUTPUT", 502);
            Activity.Current?.SetTag("capture.result", "PREVIEW");
            Activity.Current?.SetTag("capture.item_count", proposal.Items.Length);
            return new OkObjectResult(new { title = proposal.Title, description = proposal.Description,
                items = proposal.Items, language = proposal.Language });
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return Failure("UNAVAILABLE", 504); }
        catch (CleanupProviderException error) { return Failure(error.Code, 502); }
        catch (HttpRequestException) { return Failure("UNAVAILABLE", 502); }
        finally { Array.Clear(buffer); }
    }

    private static ObjectResult Failure(string code, int status)
    {
        Activity.Current?.SetTag("capture.result", code);
        return new(new { code }) { StatusCode = status };
    }
}
