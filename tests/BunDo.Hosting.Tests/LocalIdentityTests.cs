#if DEBUG
using BunDo.Functions.Identity;
using BunDo.Functions.Identity.Development;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;

namespace BunDo.Hosting.Tests;

public sealed class LocalIdentityTests
{
    private static IConfiguration Settings(string environment = "Development", string? site = null) =>
        new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["AZURE_FUNCTIONS_ENVIRONMENT"] = environment,
            ["BunDoIdentity:Mode"] = "Local",
            ["WEBSITE_INSTANCE_ID"] = site,
        }).Build();

    [Fact]
    public void RejectsCloudAndNonDevelopmentHosts()
    {
        Assert.Throws<InvalidOperationException>(() => new LocalIdentity(Settings("Production")));
        Assert.Throws<InvalidOperationException>(() => new LocalIdentity(Settings(site: "cloud-host")));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("Microsoft")]
    [InlineData("local")]
    public void RequiresExplicitLocalMode(string? mode)
    {
        var settings = Settings();
        settings["BunDoIdentity:Mode"] = mode;
        Assert.Throws<InvalidOperationException>(() => new LocalIdentity(settings));
    }

    [Theory]
    [InlineData("localhost:7275")]
    [InlineData("127.0.0.1:7275")]
    [InlineData("[::1]:7275")]
    public void AllowsCoreToolsLoopbackHostname(string hostname)
    {
        var settings = Settings();
        settings["WEBSITE_HOSTNAME"] = hostname;
        using var local = new LocalIdentity(settings);
        Assert.NotNull(local.Issue("alice", "valid"));
    }

    [Theory]
    [InlineData("bundo.azurewebsites.net")]
    [InlineData("192.168.1.2:7275")]
    [InlineData("localhost.attacker.example")]
    [InlineData("not a hostname")]
    public void RejectsNonLoopbackHostname(string hostname)
    {
        var settings = Settings();
        settings["WEBSITE_HOSTNAME"] = hostname;
        Assert.Throws<InvalidOperationException>(() => new LocalIdentity(settings));
    }

    [Fact]
    public void TokenRouteIsDisabledWithoutLocalIssuerRegistration()
    {
        using var services = new ServiceCollection().BuildServiceProvider();
        var request = new DefaultHttpContext().Request;
        var result = new LocalTokenFunction(services).Run(request, "alice", "valid");
        Assert.IsType<NotFoundResult>(result);
        Assert.Equal("no-store", request.HttpContext.Response.Headers.CacheControl);
    }

    [Theory]
    [InlineData("alice")]
    [InlineData("bob")]
    public async Task AccountsRemainStableAcrossFreshKeys(string account)
    {
        using var first = new LocalIdentity(Settings());
        using var second = new LocalIdentity(Settings());
        var identity = (await first.Validator.ValidateAsync(first.Issue(account, "valid")!)).Identity;
        Assert.Equal(new AccountIdentity(LocalIdentity.Issuer, account), identity);
        Assert.Equal(identity, (await second.Validator.ValidateAsync(second.Issue(account, "valid")!)).Identity);
        Assert.Equal(AuthenticationStatus.InvalidToken,
            (await second.Validator.ValidateAsync(first.Issue(account, "valid")!)).Status);
    }

    [Theory]
    [InlineData("expired", AuthenticationStatus.InvalidToken)]
    [InlineData("wrong-audience", AuthenticationStatus.InvalidToken)]
    [InlineData("wrong-scope", AuthenticationStatus.MissingScope)]
    public async Task ScenariosExerciseRealValidation(string scenario, AuthenticationStatus expected)
    {
        using var local = new LocalIdentity(Settings());
        Assert.Equal(expected, (await local.Validator.ValidateAsync(local.Issue("alice", scenario)!)).Status);
    }

    [Fact]
    public async Task MicrosoftValidatorDoesNotTrustLocalTokens()
    {
        using var local = new LocalIdentity(Settings());
        var configuration = new OpenIdConnectConfiguration { Issuer = LocalIdentity.Issuer };
        var jwt = new Microsoft.IdentityModel.JsonWebTokens.JsonWebTokenHandler()
            .ReadJsonWebToken(local.Issue("alice", "valid")!);
        var validator = new AccessTokens(LocalIdentity.Audience,
            new StaticConfigurationManager<OpenIdConnectConfiguration>(configuration));
        Assert.Equal(AuthenticationStatus.InvalidToken,
            (await validator.ValidateAsync(jwt.EncodedToken)).Status);
    }

    [Fact]
    public void UnknownAccountsAndScenariosCannotMintTokens()
    {
        using var local = new LocalIdentity(Settings());
        Assert.Null(local.Issue("admin", "valid"));
        Assert.Null(local.Issue("alice", "arbitrary"));
    }
}
#endif
