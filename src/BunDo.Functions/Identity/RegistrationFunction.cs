using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Functions.Identity;

public sealed class RegistrationFunction(AccessTokens tokens, IServiceProvider services)
{
    [Function("RegisterInstallation")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "identity/registrations")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var headers = request.Headers.Authorization;
        if (headers.Count != 1 || headers[0] is not { } header ||
            !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || header[7..].Any(char.IsWhiteSpace))
            return new UnauthorizedResult();
        var authentication = await tokens.ValidateAsync(header[7..], request.HttpContext.RequestAborted);
        if (authentication.Status != AuthenticationStatus.Accepted)
            return new StatusCodeResult(authentication.Status switch {
                AuthenticationStatus.MissingScope => 403, AuthenticationStatus.Unavailable => 503, _ => 401 });
        var store = services.GetService<IRegistrationStore>();
        if (store is null) return new StatusCodeResult(503);
        // Do not deserialize unbounded input, even for authenticated callers.
        var bytes = new byte[1025];
        var length = 0;
        while (length < bytes.Length)
        {
            var read = await request.Body.ReadAsync(bytes.AsMemory(length), request.HttpContext.RequestAborted);
            if (read == 0) break;
            length += read;
        }
        if (length > 1024) return new StatusCodeResult(413);
        Guid installation;
        Guid? revoke = null;
        try
        {
            using var json = JsonDocument.Parse(bytes.AsMemory(0, length));
            if (!json.RootElement.GetProperty("installationId").TryGetGuid(out installation) || installation == Guid.Empty)
                return new BadRequestResult();
            if (json.RootElement.TryGetProperty("revokeRegistrationId", out var value)) revoke = value.GetGuid();
        }
        catch (Exception error) when (error is JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        { return new BadRequestResult(); }
        var decision = await store.RegisterAsync(authentication.Identity!, installation, revoke, request.HttpContext.RequestAborted);
        System.Diagnostics.Activity.Current?.SetTag("registration.result", decision.Code);
        return decision.Code == "accepted"
            ? new OkObjectResult(new { registrationId = decision.Registration!.RegistrationId, expiresAt = decision.Registration.ExpiresAt })
            : new ConflictObjectResult(new {
                code = decision.Code,
                activeRegistrations = decision.Records.Where(x => !x.Revoked && x.ExpiresAt > DateTimeOffset.UtcNow)
                    .Select(x => new { registrationId = x.RegistrationId, expiresAt = x.ExpiresAt }),
            });
    }
}
