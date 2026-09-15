using System.Diagnostics;
using System.Net;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Azure.Core;
using BunDo.Domain;
using BunDo.Functions.Identity;
using BunDo.Functions.Speech;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;

namespace BunDo.Hosting.Tests;

public sealed class SpeechTests : IDisposable
{
    private readonly RSA rsa = RSA.Create(2048);
    private readonly Guid registration = Guid.NewGuid();
    private AccessTokens Tokens => new("speech-test", new StaticConfigurationManager<OpenIdConnectConfiguration>(new() {
        Issuer = AccessTokens.Issuer, SigningKeys = { new RsaSecurityKey(rsa) { KeyId = "speech" } },
    }));
    private string Token(string scope = "access_as_user", bool expired = false) => new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor {
        Issuer = AccessTokens.Issuer, Audience = "speech-test", Subject = new ClaimsIdentity([new Claim("sub", "speech-test"), new Claim("scp", scope)]),
        IssuedAt = DateTime.UtcNow.AddHours(-2), NotBefore = DateTime.UtcNow.AddHours(-2),
        Expires = DateTime.UtcNow.AddMinutes(expired ? -5 : 5),
        SigningCredentials = new(new RsaSecurityKey(rsa) { KeyId = "speech" }, SecurityAlgorithms.RsaSha256),
    });
    private HttpRequest Request(byte[] bytes, string? token = null) {
        var request = new DefaultHttpContext().Request;
        request.Headers.Authorization = "Bearer " + (token ?? Token());
        request.Headers["X-BunDo-Registration"] = registration.ToString();
        request.Headers["X-BunDo-Locale"] = "fi-FI";
        request.ContentType = "application/octet-stream";
        request.Body = new MemoryStream(bytes);
        return request;
    }
    private async Task<ObjectResult> Run(HttpRequest request, Provider provider, Registrations? registrations = null) {
        using var services = new ServiceCollection().AddSingleton<ISpeechProvider>(provider)
            .AddSingleton<IRegistrationStore>(registrations ?? new Registrations(registration)).BuildServiceProvider();
        return Assert.IsAssignableFrom<ObjectResult>(await new SpeechFunction(Tokens, services).Run(request));
    }
    [Theory]
    [InlineData("missing", 401)][InlineData("expired", 401)][InlineData("scope", 403)][InlineData("registration", 403)]
    public async Task Auth_and_registration_fail_before_reading_audio(string fault, int status) {
        var request = Request([0, 1], fault == "expired" ? Token(expired: true) : fault == "scope" ? Token("wrong") : Token());
        if (fault == "missing") request.Headers.Remove("Authorization");
        if (fault == "registration") request.Headers["X-BunDo-Registration"] = Guid.NewGuid().ToString();
        request.Body = new NoRead();
        var provider = new Provider();
        Assert.Equal(status, (await Run(request, provider)).StatusCode);
        Assert.Equal(0, provider.Calls);
    }
    [Theory]
    [InlineData(0, 400)][InlineData(3, 400)][InlineData(SpeechFunction.MaximumAudioBytes + 1, 413)]
    public async Task Bounds_chunked_audio_before_provider(int count, int status) {
        var provider = new Provider();
        Assert.Equal(status, (await Run(Request(new byte[count]), provider)).StatusCode);
        Assert.Equal(0, provider.Calls);
    }
    [Fact] public async Task Valid_transcript_is_not_a_task_write_and_audio_buffers_are_cleared() {
        var provider = new Provider();
        var request = Request([0, 1]);
        using var activity = new Activity("speech").Start();
        var result = await Run(request, provider);
        Assert.Equal(200, result.StatusCode);
        Assert.Equal("Älä osta 2, osta 3", JsonSerializer.SerializeToElement(result.Value).GetProperty("text").GetString());
        Assert.All(provider.Audio!, value => Assert.Equal(0, value));
        Assert.Equal("no-store", request.HttpContext.Response.Headers.CacheControl);
        var exported = JsonSerializer.Serialize(activity.TagObjects);
        Assert.Contains("speech.audio_bytes", exported);
        Assert.DoesNotContain("Älä", exported);
        Assert.DoesNotContain("Bearer", exported);
    }
    [Fact] public async Task Retired_registration_cannot_receive_late_transcript() {
        var registrations = new Registrations(registration);
        var provider = new Provider { BeforeReturn = () => registrations.Active = false };
        Assert.Equal(403, (await Run(Request([0, 1]), provider, registrations)).StatusCode);
    }
    [Fact] public async Task Cancellation_propagates_without_retry_and_clears_audio() {
        using var cancel = new CancellationTokenSource();
        var request = Request([0, 1]); request.HttpContext.RequestAborted = cancel.Token;
        var provider = new Provider { BeforeReturn = cancel.Cancel };
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Run(request, provider));
        Assert.Equal(1, provider.Calls);
        Assert.All(provider.Audio!, value => Assert.Equal(0, value));
    }
    [Theory]
    [InlineData(429)][InlineData(500)][InlineData(307)]
    public async Task Provider_failure_is_not_retried_or_echoed(int status) {
        var handler = new Handler(status, "private provider canary");
        var provider = new MaiSpeechProvider(new HttpClient(handler), new Credential(), new Uri("https://test.cognitiveservices.azure.com"));
        Assert.Equal("UNAVAILABLE", (await Assert.ThrowsAsync<SpeechException>(() => provider.TranscribeAsync([0, 1], "fi-FI", default))).Code);
        Assert.Equal(1, handler.Calls);
    }
    [Theory]
    [InlineData("{}")][InlineData("{\"combinedPhrases\":null}")][InlineData("{\"combinedPhrases\":[{\"text\":null}]}")]
    public async Task Malformed_output_is_recoverable(string json) {
        var provider = new MaiSpeechProvider(new HttpClient(new Handler(200, json)), new Credential(), new Uri("https://test.cognitiveservices.azure.com"));
        Assert.Equal("INVALID_OUTPUT", (await Assert.ThrowsAsync<SpeechException>(() => provider.TranscribeAsync([0, 1], "en-US", default))).Code);
    }
    [Fact] public async Task Provider_contract_silence_and_output_bounds() {
        var handler = new Handler(200, "{\"combinedPhrases\":[{\"text\":\"Buy milk\"}]}");
        var provider = new MaiSpeechProvider(new HttpClient(handler), new Credential(), new Uri("https://test.cognitiveservices.azure.com"));
        Assert.Equal("", await provider.TranscribeAsync(new byte[100], "fi-FI", default));
        Assert.Equal(0, handler.Calls);
        Assert.Equal("Buy milk", await provider.TranscribeAsync([0, 1], "en-US", default));
        Assert.Contains("MAI-Transcribe-2", handler.Body); Assert.Contains("verbatim", handler.Body);
        Assert.Contains("RIFF", handler.Body); Assert.Contains("en-US", handler.Body);
        Assert.Equal("https://test.cognitiveservices.azure.com/speechtotext/transcriptions:transcribe?api-version=2025-10-15", handler.Uri);
        handler.Json = JsonSerializer.Serialize(new { combinedPhrases = new[] { new { text = new string('x', 4001) } } });
        Assert.Equal("TOO_LONG", (await Assert.ThrowsAsync<SpeechException>(() => provider.TranscribeAsync([0, 1], "fi-FI", default))).Code);
        handler.Json = new string('x', 128 * 1024 + 1);
        Assert.Equal("INVALID_OUTPUT", (await Assert.ThrowsAsync<SpeechException>(() => provider.TranscribeAsync([0, 1], "fi-FI", default))).Code);
    }
    private sealed class Provider : ISpeechProvider {
        public int Calls; public byte[]? Audio; public Action? BeforeReturn;
        public Task<string> TranscribeAsync(byte[] pcm, string locale, CancellationToken ct) {
            Calls++; Audio = pcm; BeforeReturn?.Invoke(); return Task.FromResult("Älä osta 2, osta 3");
        }
    }
    private sealed class Registrations(Guid id) : IRegistrationStore {
        public bool Active = true;
        public Task<bool> IsActiveAsync(AccountIdentity identity, Guid registrationId, CancellationToken ct) => Task.FromResult(Active && registrationId == id);
        public Task<RegistrationDecision> RegisterAsync(AccountIdentity identity, Guid installation, Guid? revoke, CancellationToken ct) => throw new NotSupportedException();
    }
    private sealed class NoRead : MemoryStream {
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) => throw new Exception("Must authenticate first");
    }
    private sealed class Handler(int status, string json) : HttpMessageHandler {
        public int Calls; public string Body = "", Uri = "", Json = json;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            Calls++; Body = await request.Content!.ReadAsStringAsync(ct); Uri = request.RequestUri!.ToString();
            Assert.Equal("Bearer", request.Headers.Authorization!.Scheme);
            return new HttpResponseMessage((HttpStatusCode)status) { Content = new StringContent(Json) };
        }
    }
    private sealed class Credential : TokenCredential {
        public override AccessToken GetToken(TokenRequestContext context, CancellationToken ct) => new("test-secret", DateTimeOffset.UtcNow.AddMinutes(5));
        public override ValueTask<AccessToken> GetTokenAsync(TokenRequestContext context, CancellationToken ct) => ValueTask.FromResult(GetToken(context, ct));
    }
    public void Dispose() => rsa.Dispose();
}
