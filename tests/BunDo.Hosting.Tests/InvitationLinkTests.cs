using System.Text.Json;
using BunDo.Functions.Households;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;

namespace BunDo.Hosting.Tests;

public sealed class InvitationLinkTests
{
    [Theory]
    [InlineData(null, 0)]
    [InlineData("AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA", 1)]
    public void OnlyExplicitSigningCertificatesAreAssociated(string? certificate, int count)
    {
        var links = new InvitationLinks(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> {
            ["BunDoAndroid:SigningCertificates"] = certificate,
        }).Build());
        var result = Assert.IsType<JsonResult>(links.Associations(new DefaultHttpContext().Request));
        using var json = JsonDocument.Parse(JsonSerializer.Serialize(result.Value));
        Assert.Equal(count, json.RootElement.GetArrayLength());
    }

    [Fact]
    public void InvalidCertificateFailsClosedAndLandingPageDoesNotReflectRequest()
    {
        var links = new InvitationLinks(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> {
            ["BunDoAndroid:SigningCertificates"] = "invalid",
        }).Build());
        var context = new DefaultHttpContext();
        context.Request.QueryString = new QueryString("?canary=private-invitation-canary");
        Assert.Equal(503, Assert.IsType<StatusCodeResult>(links.Associations(context.Request)).StatusCode);
        var result = Assert.IsType<ContentResult>(links.Join(context.Request));
        Assert.DoesNotContain("private-invitation-canary", result.Content);
        Assert.Equal("no-referrer", context.Response.Headers["Referrer-Policy"]);
    }
}
