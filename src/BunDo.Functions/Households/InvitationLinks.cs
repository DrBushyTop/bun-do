using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.Configuration;

namespace BunDo.Functions.Households;

public sealed class InvitationLinks(IConfiguration configuration)
{
    public const string HttpsJoinUrl = "https://func-bun-do-dev-qrquvcgmhocc6.azurewebsites.net/api/join";

    [Function("AndroidAppLinks")]
    public IActionResult Associations(
        [HttpTrigger(AuthorizationLevel.Anonymous, "get", Route = ".well-known/assetlinks.json")] HttpRequest request)
    {
        // Signing certificates are public. Missing release setup fails closed, never trusts any certificate.
        var fingerprints = (configuration["BunDoAndroid:SigningCertificates"] ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries);
        if (fingerprints.Any(x => x.Length != 95 || x.Split(':').Length != 32 ||
            x.Split(':').Any(part => part.Length != 2 || !part.All(char.IsAsciiHexDigitUpper))))
            return new StatusCodeResult(503);
        return new JsonResult(fingerprints.Length == 0 ? Array.Empty<object>() : new object[] { new {
            relation = new[] { "delegate_permission/common.handle_all_urls" },
            target = new { @namespace = "android_app", package_name = "fi.bundo", sha256_cert_fingerprints = fingerprints },
        } });
    }

    [Function("JoinHouseholdLink")]
    public IActionResult Join(
        [HttpTrigger(AuthorizationLevel.Anonymous, "get", Route = "api/join")] HttpRequest request)
    {
        request.HttpContext.Response.Headers.CacheControl = "no-store";
        request.HttpContext.Response.Headers["Referrer-Policy"] = "no-referrer";
        request.HttpContext.Response.Headers["Content-Security-Policy"] = "default-src 'none'; frame-ancestors 'none'";
        // The fragment never reaches this endpoint. No scripts, redirects or third-party assets.
        return new ContentResult { ContentType = "text/plain; charset=utf-8", Content =
            "Bun Do\n\nAvaa kutsu Bun Do -sovelluksessa. Voit myös kopioida koko linkin sovelluksen Liity kutsulla -kenttään.\n\n" +
            "Open this invitation in Bun Do. You can also copy the full link into Join with an invitation in the app." };
    }
}
