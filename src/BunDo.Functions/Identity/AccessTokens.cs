using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;

namespace BunDo.Functions.Identity;

public sealed record AccountIdentity(string Issuer, string Subject);

public enum AuthenticationStatus { Accepted, InvalidToken, MissingScope, Unavailable }

public sealed record AuthenticationResult(AuthenticationStatus Status, AccountIdentity? Identity = null);

// Only the authenticated API token defines local account ownership. The Android
// client ID token can carry a different pairwise subject for the same person.
public sealed class AccessTokens(string? audience, BaseConfigurationManager configuration, string expectedIssuer = AccessTokens.Issuer)
{
    public const string Issuer = "https://login.microsoftonline.com/9188040d-6c67-4c5b-b112-36a304b66dad/v2.0";
    public const string Metadata = "https://login.microsoftonline.com/consumers/v2.0/.well-known/openid-configuration";
    public const string RequiredScope = "access_as_user";
    private readonly JsonWebTokenHandler handler = new() { MapInboundClaims = false, MaximumTokenSizeInBytes = 16 * 1024 };

    public static AccessTokens Create(string? audience) => new(audience,
        new ConfigurationManager<OpenIdConnectConfiguration>(Metadata,
            new OpenIdConnectConfigurationRetriever(), new HttpDocumentRetriever { RequireHttps = true }));

    public async Task<AuthenticationResult> ValidateAsync(string token, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(audience))
            return new(AuthenticationStatus.Unavailable);
        if (string.IsNullOrWhiteSpace(token) || token.Length > 16 * 1024)
            return new(AuthenticationStatus.InvalidToken);

        cancellationToken.ThrowIfCancellationRequested();
        // Check discovery availability separately so outages aren't reported as
        // bad credentials. IdentityModel owns cached keys and rollover retries.
        try { await configuration.GetBaseConfigurationAsync(cancellationToken); }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
        catch { return new(AuthenticationStatus.Unavailable); }

        var validation = await handler.ValidateTokenAsync(token, new TokenValidationParameters
        {
            ConfigurationManager = configuration,
            ValidateIssuer = true,
            ValidIssuer = expectedIssuer,
            // Discovery must never broaden the exact issuer selected by the app.
            IssuerValidator = (issuer, _, _) => string.Equals(issuer, expectedIssuer, StringComparison.Ordinal)
                ? issuer : throw new SecurityTokenInvalidIssuerException(),
            ValidateAudience = true,
            ValidAudience = audience,
            IgnoreTrailingSlashWhenValidatingAudience = false,
            RequireSignedTokens = true,
            ValidateIssuerSigningKey = true,
            ValidAlgorithms = [SecurityAlgorithms.RsaSha256],
            RequireExpirationTime = true,
            ValidateLifetime = true,
            ClockSkew = TimeSpan.Zero,
            LogTokenId = false,
            IncludeTokenOnFailedValidation = false,
        });
        cancellationToken.ThrowIfCancellationRequested();
        if (!validation.IsValid)
            return new(AuthenticationStatus.InvalidToken);

        var claims = validation.ClaimsIdentity;
        var subjects = claims.FindAll("sub").ToArray();
        if (subjects.Length != 1 || string.IsNullOrWhiteSpace(subjects[0].Value))
            return new(AuthenticationStatus.InvalidToken);
        var scopes = claims.FindAll("scp").ToArray();
        if (scopes.Length != 1 || !scopes[0].Value.Split(' ', StringSplitOptions.RemoveEmptyEntries)
                .Contains(RequiredScope, StringComparer.Ordinal))
            return new(AuthenticationStatus.MissingScope);
        return new(AuthenticationStatus.Accepted, new AccountIdentity(expectedIssuer, subjects[0].Value));
    }
}
