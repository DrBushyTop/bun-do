using System.Security.Claims;
using System.Security.Cryptography;
using Microsoft.Extensions.Configuration;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;

namespace BunDo.Functions.Identity.Development;

/// <summary>Debug-only fictional identities. Keys live for one local host process.</summary>
public sealed class LocalIdentity : IDisposable
{
    public const string Issuer = "urn:bun-do:local";
    public const string Audience = "bun-do-local-api";
    private readonly RSA rsa = RSA.Create(2048);
    private readonly RsaSecurityKey key;
    public AccessTokens Validator { get; }

    public LocalIdentity(IConfiguration configuration)
    {
        if (configuration["AZURE_FUNCTIONS_ENVIRONMENT"] != "Development")
            throw new InvalidOperationException("Local identities require the Development Functions environment.");
        if (configuration["BunDoIdentity:Mode"] != "Local")
            throw new InvalidOperationException("Local identities require explicitly selected Local mode.");
        if (!string.IsNullOrEmpty(configuration["WEBSITE_INSTANCE_ID"]))
            throw new InvalidOperationException("Local identities cannot run on an Azure website instance.");
        // Core Tools supplies WEBSITE_HOSTNAME locally too. Only loopback is permitted.
        var hostname = configuration["WEBSITE_HOSTNAME"];
        if (!string.IsNullOrEmpty(hostname)
            && (!Uri.TryCreate("http://" + hostname, UriKind.Absolute, out var website) || !website.IsLoopback))
            throw new InvalidOperationException("Local identities require a loopback website hostname.");
        key = new RsaSecurityKey(rsa) { KeyId = Guid.NewGuid().ToString("N") };
        Validator = new AccessTokens(Audience,
            new StaticConfigurationManager<OpenIdConnectConfiguration>(new OpenIdConnectConfiguration
            {
                Issuer = Issuer,
                SigningKeys = { new RsaSecurityKey(rsa.ExportParameters(false)) { KeyId = key.KeyId } },
            }), Issuer);
    }

    public string? Issue(string account, string scenario)
    {
        if (account is not ("alice" or "bob")) return null;
        if (scenario is not ("valid" or "expired" or "wrong-audience" or "wrong-scope")) return null;
        var now = DateTime.UtcNow;
        return new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor
        {
            Issuer = Issuer,
            Audience = scenario == "wrong-audience" ? "another-api" : Audience,
            Subject = new ClaimsIdentity([
                new Claim("sub", account),
                new Claim("scp", scenario == "wrong-scope" ? "another_scope" : AccessTokens.RequiredScope),
            ]),
            IssuedAt = now.AddMinutes(-2),
            NotBefore = now.AddMinutes(-2),
            Expires = scenario == "expired" ? now.AddMinutes(-1) : now.AddMinutes(5),
            SigningCredentials = new SigningCredentials(key, SecurityAlgorithms.RsaSha256),
        });
    }

    public void Dispose() => rsa.Dispose();
}
