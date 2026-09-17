using System.Diagnostics;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.AI;
using BunDo.Functions.Identity;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;

namespace BunDo.Hosting.Tests;

public sealed class CaptureAnalysisTests : IDisposable
{
    private readonly RSA rsa = RSA.Create(2048);
    private readonly Guid registration = Guid.NewGuid();
    private AccessTokens Tokens => new("capture-test", new StaticConfigurationManager<OpenIdConnectConfiguration>(new() {
        Issuer = AccessTokens.Issuer, SigningKeys = { new RsaSecurityKey(rsa) { KeyId = "capture" } },
    }));
    private HttpRequest Request(string json = "{\"transcript\":\"Ostoslista: maitoa, 6 munaa, ei sokeria\"}") {
        var request = new DefaultHttpContext().Request;
        var token = new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor {
            Issuer = AccessTokens.Issuer, Audience = "capture-test",
            Subject = new ClaimsIdentity([new Claim("sub", "capture-test"), new Claim("scp", "access_as_user")]),
            Expires = DateTime.UtcNow.AddMinutes(5),
            SigningCredentials = new(new RsaSecurityKey(rsa) { KeyId = "capture" }, SecurityAlgorithms.RsaSha256),
        });
        request.Headers.Authorization = "Bearer " + token;
        request.Headers["X-BunDo-Registration"] = registration.ToString();
        request.ContentType = "application/json";
        request.Body = new MemoryStream(Encoding.UTF8.GetBytes(json));
        return request;
    }
    private async Task<ObjectResult> Run(HttpRequest request, Provider provider, Registrations? registrations = null) {
        using var services = new ServiceCollection().AddSingleton<ICleanupProvider>(provider)
            .AddSingleton<IRegistrationStore>(registrations ?? new Registrations(registration)).BuildServiceProvider();
        return Assert.IsAssignableFrom<ObjectResult>(await new CaptureAnalysisFunction(Tokens, services).Run(request));
    }
    [Theory]
    [InlineData("auth", 401)][InlineData("registration", 403)]
    public async Task Auth_precedes_reading_private_transcript(string fault, int status) {
        var request = Request();
        if (fault == "auth") request.Headers.Remove("Authorization"); else request.Headers.Remove("X-BunDo-Registration");
        request.Body = new NoRead();
        var provider = new Provider();
        Assert.Equal(status, (await Run(request, provider)).StatusCode);
        Assert.Equal(0, provider.Calls);
    }
    [Theory]
    [InlineData("{}")] [InlineData("null")] [InlineData("{\"transcript\":42}")]
    [InlineData("{\"transcript\":null}")] [InlineData("{\"transcript\":\" \"}")]
    public async Task Invalid_input_never_reaches_provider(string input) {
        var provider = new Provider();
        Assert.Equal(400, (await Run(Request(input), provider)).StatusCode);
        Assert.Equal(0, provider.Calls);
    }
    [Fact] public async Task Bounds_chunked_input_and_transcript_characters() {
        var provider = new Provider();
        Assert.Equal(413, (await Run(Request(new string('x', 128 * 1024 + 1)), provider)).StatusCode);
        Assert.Equal(400, (await Run(Request(JsonSerializer.Serialize(new { transcript = new string('x', 4001) })), provider)).StatusCode);
        Assert.Equal(0, provider.Calls);
    }
    [Fact] public async Task Preview_has_no_task_writes_and_telemetry_has_no_private_content() {
        var provider = new Provider(); var request = Request();
        using var activity = new Activity("capture").Start();
        var result = await Run(request, provider);
        Assert.Equal(200, result.StatusCode);
        Assert.Equal("no-store", request.HttpContext.Response.Headers.CacheControl);
        Assert.Equal("Ostoslista", JsonSerializer.SerializeToElement(result.Value).GetProperty("title").GetString());
        var tags = JsonSerializer.Serialize(activity.TagObjects);
        Assert.Contains("capture.item_count", tags);
        Assert.DoesNotContain("maitoa", tags); Assert.DoesNotContain("Bearer", tags);
        Assert.Equal(1, provider.Calls);
    }
    [Fact] public async Task Revocation_and_cancellation_fence_late_results_without_retry() {
        var registrations = new Registrations(registration);
        Assert.Equal(403, (await Run(Request(), new Provider { BeforeReturn = () => registrations.Active = false }, registrations)).StatusCode);
        using var canceled = new CancellationTokenSource();
        var request = Request(); request.HttpContext.RequestAborted = canceled.Token;
        var provider = new Provider { BeforeReturn = canceled.Cancel };
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Run(request, provider));
        Assert.Equal(1, provider.Calls);
    }
    [Fact] public async Task Revision_passes_current_edits_and_returns_multiple_steps_without_writes() {
        var provider = new Provider();
        var input = new { transcript = "Add three phases, keep my notes", currentDraft = new {
            title = "Manual title", description = "Private notes", items = new[] { "Existing phase" },
        } };
        using var activity = new Activity("revision").Start();
        var result = await Run(Request(JsonSerializer.Serialize(input)), provider);
        Assert.Equal(200, result.StatusCode);
        Assert.Equal(("Manual title", "Private notes", "Add three phases, keep my notes"), provider.Revision);
        Assert.Equal(new[] { "Existing phase" }, provider.CurrentItems);
        Assert.Equal(4, JsonSerializer.SerializeToElement(result.Value).GetProperty("items").GetArrayLength());
        Assert.DoesNotContain("Private notes", JsonSerializer.Serialize(activity.TagObjects));
        Assert.DoesNotContain("Manual title", JsonSerializer.Serialize(activity.TagObjects));
        Assert.Equal(1, provider.Calls);
    }
    [Theory]
    [InlineData("null")] [InlineData("[]")]
    [InlineData("{\"title\":null,\"description\":\"\",\"items\":[]}")]
    [InlineData("{\"title\":\"Task\",\"description\":null,\"items\":[]}")]
    [InlineData("{\"title\":\"Task\",\"description\":\"\",\"items\":[null]}")]
    [InlineData("{\"title\":\"Task\",\"description\":\"\",\"items\":[\"same\",\"SAME\"]}")]
    public async Task Revision_rejects_malformed_current_drafts(string draft) {
        var provider = new Provider();
        Assert.Equal(400, (await Run(Request("{\"transcript\":\"Add steps\",\"currentDraft\":" + draft + "}"), provider)).StatusCode);
        Assert.Equal(0, provider.Calls);
    }
    [Fact] public async Task Revision_accepts_maximum_unicode_draft_and_instruction() {
        var input = new { transcript = string.Concat(Enumerable.Repeat("🌱", 4000)), currentDraft = new {
            title = string.Concat(Enumerable.Repeat("🌱", 160)), description = string.Concat(Enumerable.Repeat("🌱", 4000)),
            items = Enumerable.Range(0, 16).Select(i => i.ToString("00") + string.Concat(Enumerable.Repeat("🌱", 158))).ToArray(),
        } };
        Assert.Equal(200, (await Run(Request(JsonSerializer.Serialize(input)), new Provider())).StatusCode);
    }
    [Fact] public async Task Revision_rechecks_registration_after_provider_returns() {
        var registrations = new Registrations(registration);
        var request = Request(JsonSerializer.Serialize(new { transcript = "Add three steps", currentDraft = new { title = "Task", description = "", items = Array.Empty<string>() } }));
        Assert.Equal(403, (await Run(request, new Provider { BeforeReturn = () => registrations.Active = false }, registrations)).StatusCode);
    }
    private static byte[] Response(object value) => JsonSerializer.SerializeToUtf8Bytes(new {
        status = "completed", output = new[] { new { type = "message", content = new[] { new { type = "output_text", text = JsonSerializer.Serialize(value) } } } },
    });
    [Fact] public void Parses_shopping_items_and_single_tasks_without_forcing_steps() {
        var proposal = FoundryCleanupProvider.ParseCaptureResponse(Response(new {
            title = "Ostoslista", description = "Ei sokeria", items = new[] { "maitoa", "6 munaa", "ruisleipää" }, language = "fi",
        }));
        Assert.Equal(new[] { "maitoa", "6 munaa", "ruisleipää" }, proposal.Items);
        Assert.Equal("Ei sokeria", proposal.Description);
        Assert.Empty(FoundryCleanupProvider.ParseCaptureResponse(Response(new {
            title = "Book bike service", description = "", items = Array.Empty<string>(), language = "en",
        })).Items!);
    }
    [Theory]
    [InlineData("duplicate")][InlineData("blank")][InlineData("too-many")][InlineData("too-long")][InlineData("control")][InlineData("null-item")]
    public void Rejects_invalid_suggestions(string fault) {
        string?[] items = fault switch {
            "duplicate" => ["milk", "Milk"], "blank" => [" "], "too-many" => Enumerable.Range(1, 17).Select(i => i.ToString()).ToArray(),
            "too-long" => [new string('x', 161)], "null-item" => new string?[] { null }, _ => ["milk\neggs"],
        };
        Assert.Throws<CleanupProviderException>(() => FoundryCleanupProvider.ParseCaptureResponse(Response(new {
            title = "Shopping list", description = "", items, language = "en",
        })));
    }
    private sealed class Provider : ICleanupProvider {
        public int Calls; public Action? BeforeReturn;
        public (string, string, string)? Revision;
        public string[]? CurrentItems;
        public Task<CleanupProposal> ReviseCaptureAsync(string title, string description, string[] items, string instruction, CancellationToken ct) {
            Calls++; Revision = (title, description, instruction); CurrentItems = items; BeforeReturn?.Invoke();
            return Task.FromResult(new CleanupProposal(title, description, "en", true, Items: [..items.Take(1), "Prepare", "Do", "Check"]));
        }
        public Task<CleanupProposal> GenerateAsync(string title, string? description, CancellationToken ct, JsonElement? captureContext = null) => throw new NotSupportedException();
        public Task<CleanupProposal> GenerateCaptureAsync(string transcript, CancellationToken ct) {
            Calls++; BeforeReturn?.Invoke();
            return Task.FromResult(new CleanupProposal("Ostoslista", "Ei sokeria", "fi", true, Items: ["maitoa", "6 munaa"]));
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
    public void Dispose() => rsa.Dispose();
}
