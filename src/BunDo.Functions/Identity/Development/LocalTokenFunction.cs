using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;

namespace BunDo.Functions.Identity.Development;

public sealed class LocalTokenFunction(IServiceProvider services)
{
    [Function("LocalToken")]
    public IActionResult Run(
        [HttpTrigger(AuthorizationLevel.Anonymous, "post", Route = "api/dev/token/{account}/{scenario}")] HttpRequest request,
        string account, string scenario)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        // Debug Microsoft mode still has no token-issuing capability.
        var issuer = services.GetService<LocalIdentity>();
        if (issuer is null) return new NotFoundResult();
        if (scenario == "refresh-failure" && account is "alice" or "bob")
            return new ObjectResult(new { code = "local_refresh_unavailable" }) { StatusCode = 503 };
        var token = issuer.Issue(account, scenario);
        return token is null ? new BadRequestObjectResult(new { code = "unknown_local_scenario" })
            : new OkObjectResult(new { access_token = token, token_type = "Bearer" });
    }
}
