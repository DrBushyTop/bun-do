using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;

namespace BunDo.Functions.Identity;

public sealed class IdentityFunction(AccessTokens tokens)
{
    [Function("Identity")]
    public async Task<IActionResult> Run(
        [HttpTrigger(AuthorizationLevel.Anonymous, "get", Route = "identity")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        var headers = request.Headers.Authorization;
        if (headers.Count != 1 || headers[0] is not { } header
            || !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)
            || header[7..].Any(char.IsWhiteSpace))
            return Unauthorized(request);

        var result = await tokens.ValidateAsync(header[7..], request.HttpContext.RequestAborted);
        return result.Status switch
        {
            AuthenticationStatus.Accepted => new OkObjectResult(new
            {
                issuer = result.Identity!.Issuer,
                subject = result.Identity.Subject,
            }),
            AuthenticationStatus.MissingScope => new ObjectResult(new { code = "insufficient_scope" }) { StatusCode = 403 },
            AuthenticationStatus.Unavailable => new ObjectResult(new { code = "authentication_unavailable" }) { StatusCode = 503 },
            _ => Unauthorized(request),
        };
    }

    private static IActionResult Unauthorized(HttpRequest request)
    {
        request.HttpContext.Response.Headers.WWWAuthenticate = "Bearer";
        return new UnauthorizedObjectResult(new { code = "invalid_token" });
    }
}
