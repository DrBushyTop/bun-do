using System.Security.Claims;
using System.Security.Cryptography;
using BunDo.Functions.Identity;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using Microsoft.Extensions.DependencyInjection;
using BunDo.Domain;
using System.Text;

namespace BunDo.Hosting.Tests;

public sealed class IdentityTests : IDisposable
{
    private readonly RSA rsa = RSA.Create(2048);
    private const string Audience = "test-bun-do-api";
    private RsaSecurityKey Key => new(rsa) { KeyId = "test-key" };
    private AccessTokens Validator => new(Audience,
        new StaticConfigurationManager<OpenIdConnectConfiguration>(new OpenIdConnectConfiguration
        {
            Issuer = AccessTokens.Issuer,
            SigningKeys = { Key },
        }));

    private string Token(string? issuer = null, string? audience = null, string scope = "access_as_user",
        bool expired = false, string? subject = "account-a", RSA? signer = null)
    {
        var claims = new List<Claim> { new("scp", scope) };
        if (subject is not null) claims.Add(new("sub", subject));
        return new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = issuer ?? AccessTokens.Issuer,
            Audience = audience ?? Audience,
            Subject = new ClaimsIdentity(claims),
            IssuedAt = DateTime.UtcNow.AddHours(-2),
            NotBefore = DateTime.UtcNow.AddHours(-2),
            Expires = expired ? DateTime.UtcNow.AddHours(-1) : DateTime.UtcNow.AddMinutes(5),
            SigningCredentials = new SigningCredentials(signer is null ? Key : new RsaSecurityKey(signer)
                { KeyId = "test-key" }, SecurityAlgorithms.RsaSha256),
        });
    }

    [Fact]
    public async Task ReturnsOnlyApiValidatedIdentity()
    {
        var result = await Validator.ValidateAsync(Token());
        Assert.Equal(AuthenticationStatus.Accepted, result.Status);
        Assert.Equal(new AccountIdentity(AccessTokens.Issuer, "account-a"), result.Identity);
    }

    [Theory]
    [InlineData("issuer")]
    [InlineData("audience")]
    [InlineData("expiry")]
    [InlineData("subject")]
    [InlineData("signature")]
    [InlineData("malformed")]
    public async Task RejectsInvalidTokens(string fault)
    {
        using var other = RSA.Create(2048);
        var token = fault == "malformed" ? "not-a-token" : Token(
            issuer: fault == "issuer" ? "https://untrusted.example" : null,
            audience: fault == "audience" ? "other-api" : null,
            expired: fault == "expiry", subject: fault == "subject" ? null : "account-a",
            signer: fault == "signature" ? other : null);
        var result = await Validator.ValidateAsync(token);
        Assert.Equal(AuthenticationStatus.InvalidToken, result.Status);
        Assert.Null(result.Identity);
    }

    [Fact]
    public async Task RequiresExactScope()
    {
        Assert.Equal(AuthenticationStatus.MissingScope,
            (await Validator.ValidateAsync(Token(scope: "other_access_as_user"))).Status);
        Assert.Equal(AuthenticationStatus.Accepted,
            (await Validator.ValidateAsync(Token(scope: "other access_as_user"))).Status);
    }

    [Fact]
    public async Task MissingConfigurationFailsClosed()
    {
        var validator = new AccessTokens(null,
            new StaticConfigurationManager<OpenIdConnectConfiguration>(new()));
        Assert.Equal(AuthenticationStatus.Unavailable, (await validator.ValidateAsync(Token())).Status);
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("[]")]
    [InlineData("{\"installationId\":null}")]
    [InlineData("{\"installationId\":\"not-a-uuid\"}")]
    [InlineData("{\"installationId\":\"00000000-0000-0000-0000-000000000000\"}")]
    public async Task RegistrationRejectsMalformedInputBeforeStorage(string body)
    {
        using var services = new ServiceCollection().AddSingleton<IRegistrationStore>(new UnexpectedRegistrationStore())
            .BuildServiceProvider();
        var context = new DefaultHttpContext();
        context.Request.Headers.Authorization = "Bearer " + Token();
        context.Request.Body = new MemoryStream(Encoding.UTF8.GetBytes(body));
        Assert.IsType<BadRequestResult>(await new RegistrationFunction(Validator, services).Run(context.Request));
    }

    [Fact]
    public async Task RegistrationAuthenticatesBeforeReadingAndBoundsAuthenticatedInput()
    {
        using var services = new ServiceCollection().AddSingleton<IRegistrationStore>(new UnexpectedRegistrationStore())
            .BuildServiceProvider();
        var context = new DefaultHttpContext();
        context.Request.Body = new MemoryStream(new byte[2048]);
        var function = new RegistrationFunction(Validator, services);
        Assert.IsType<UnauthorizedResult>(await function.Run(context.Request));
        Assert.Equal(0, context.Request.Body.Position);
        context.Request.Headers.Authorization = "Bearer " + Token();
        Assert.Equal(413, Assert.IsType<StatusCodeResult>(await function.Run(context.Request)).StatusCode);
        Assert.Equal(1025, context.Request.Body.Position);
        Assert.Equal("no-store", context.Response.Headers.CacheControl);
    }

    private sealed class UnexpectedRegistrationStore : IRegistrationStore
    {
        public Task<bool> IsActiveAsync(AccountIdentity identity, Guid registrationId, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Unauthenticated request reached storage.");
        public Task<RegistrationDecision> RegisterAsync(AccountIdentity identity, Guid installationId,
            Guid? revoke, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Invalid requests must not reach registration storage.");
    }

    [Theory]
    [InlineData(null)]
    [InlineData("Basic abc")]
    [InlineData("Bearer ")]
    [InlineData("Bearer a b")]
    public async Task EndpointRejectsInvalidAuthorization(string? authorization)
    {
        var context = new DefaultHttpContext();
        if (authorization is not null) context.Request.Headers.Authorization = authorization;
        Assert.IsType<UnauthorizedObjectResult>(await new IdentityFunction(Validator).Run(context.Request));
        Assert.Equal("no-store", context.Response.Headers.CacheControl);
        Assert.Equal("Bearer", context.Response.Headers.WWWAuthenticate);
    }

    [Fact]
    public async Task EndpointAcceptsTokenAndForbidsMissingScope()
    {
        var context = new DefaultHttpContext();
        context.Request.Headers.Authorization = "Bearer " + Token();
        Assert.IsType<OkObjectResult>(await new IdentityFunction(Validator).Run(context.Request));
        context.Request.Headers.Authorization = "Bearer " + Token(scope: "other");
        var forbidden = Assert.IsType<ObjectResult>(await new IdentityFunction(Validator).Run(context.Request));
        Assert.Equal(403, forbidden.StatusCode);
    }

    public void Dispose() => rsa.Dispose();
}
