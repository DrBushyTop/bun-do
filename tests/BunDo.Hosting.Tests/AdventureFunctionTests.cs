using System.Collections.Immutable;
using System.Diagnostics;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using BunDo.Functions.Adventures;
using BunDo.Functions.Artwork;
using BunDo.Functions.Sync;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;

namespace BunDo.Hosting.Tests;

public sealed class AdventureFunctionTests : IDisposable
{
    private readonly RSA rsa = RSA.Create(2048);
    private readonly AccountIdentity identity = new(AccessTokens.Issuer, "private-subject-canary");
    private readonly Guid workspace = Guid.NewGuid(), epoch = Guid.NewGuid(), registration = Guid.NewGuid();
    private RsaSecurityKey Key => new(rsa) { KeyId = "adventure-test" };
    private AccessTokens Validator => new("adventure-api", new StaticConfigurationManager<OpenIdConnectConfiguration>(
        new() { Issuer = AccessTokens.Issuer, SigningKeys = { Key } }));
    private string Token(string scope = "access_as_user") => new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor {
        Issuer = AccessTokens.Issuer, Audience = "adventure-api",
        Subject = new ClaimsIdentity([new Claim("sub", identity.Subject), new Claim("scp", scope)]),
        Expires = DateTime.UtcNow.AddMinutes(5),
        SigningCredentials = new(Key, SecurityAlgorithms.RsaSha256),
    });
    private string Body(string action = "read") => JsonSerializer.Serialize(new {
        action, workspaceId = workspace, stateEpoch = epoch, registrationId = registration,
    });
    private DefaultHttpContext Request(string body, string? token)
    {
        var context = new DefaultHttpContext();
        context.Request.Method = "POST";
        context.Request.Body = new MemoryStream(Encoding.UTF8.GetBytes(body));
        if (token != null) context.Request.Headers.Authorization = "Bearer " + token;
        return context;
    }
    private Documents Storage() => new(new(workspace, epoch, 0,
        ImmutableDictionary<Guid, DeviceRegistration>.Empty, ImmutableDictionary<string, TaskSnapshot>.Empty,
        ImmutableDictionary<string, OperationReceipt>.Empty, [], HouseholdMembership.Create(HouseholdIdentity.Member(identity)),
        "Private household canary"));
    private ServiceProvider Services(Documents documents, bool active = true) => new ServiceCollection()
        .AddSingleton<IHouseholdDocuments>(documents)
        .AddSingleton<IRegistrationStore>(new Registration(identity, registration, active)).BuildServiceProvider();
    public void Dispose() => rsa.Dispose();

    [Fact]
    public async Task Authenticates_before_body_reads_and_bounds_authenticated_input()
    {
        using var services = Services(Storage());
        var function = new AdventureFunction(Validator, services);
        var request = Request(new string('x', 20 * 1024), null);
        Assert.Equal(401, Assert.IsType<ObjectResult>(await function.Run(request.Request)).StatusCode);
        Assert.Equal(0, request.Request.Body.Position);
        request.Request.Headers.Authorization = "Bearer " + Token();
        Assert.Equal(413, Assert.IsType<ObjectResult>(await function.Run(request.Request)).StatusCode);
        Assert.Equal(16 * 1024 + 1, request.Request.Body.Position);
        Assert.Equal("no-store", request.Response.Headers.CacheControl);
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("[]")]
    [InlineData("null")]
    [InlineData("{\"action\":\"enable\"}")]
    public async Task Invalid_shapes_are_rejected_without_writes(string body)
    {
        var documents = Storage();
        using var services = Services(documents);
        var result = await new AdventureFunction(Validator, services).Run(Request(body, Token()).Request);
        Assert.Equal(400, Assert.IsType<ObjectResult>(result).StatusCode);
        Assert.Equal(0, documents.Writes);
    }

    [Theory]
    [InlineData("extra")]
    [InlineData("duplicate")]
    [InlineData("empty")]
    [InlineData("action")]
    [InlineData("type")]
    public async Task Rejects_unknown_duplicate_or_invalid_fields(string fault)
    {
        var body = Body();
        body = fault switch {
            "extra" => body.Replace("}", ",\"privateText\":\"do not log\"}"),
            "duplicate" => body.Replace("}", ",\"action\":\"read\"}"),
            "empty" => body.Replace(workspace.ToString(), Guid.Empty.ToString()),
            "action" => Body("reset"),
            _ => body.Replace("\"read\"", "true"),
        };
        var documents = Storage();
        using var services = Services(documents);
        Assert.Equal(400, Assert.IsType<ObjectResult>(
            await new AdventureFunction(Validator, services).Run(Request(body, Token()).Request)).StatusCode);
        Assert.Equal(0, documents.Writes);
    }

    [Theory]
    [InlineData("scope", 403)]
    [InlineData("registration", 403)]
    [InlineData("member", 403)]
    [InlineData("epoch", 409)]
    public async Task Scope_registration_membership_and_epoch_are_required(string fault, int status)
    {
        var documents = Storage();
        if (fault == "member") documents.State = documents.State with { Membership = HouseholdMembership.Create(Guid.NewGuid()) };
        using var services = Services(documents, fault != "registration");
        var body = fault == "epoch" ? Body().Replace(epoch.ToString(), Guid.NewGuid().ToString()) : Body();
        var result = await new AdventureFunction(Validator, services).Run(Request(body, Token(fault == "scope" ? "other" : "access_as_user")).Request);
        Assert.Equal(status, Assert.IsType<ObjectResult>(result).StatusCode);
        Assert.Equal(0, documents.Writes);
    }

    [Fact]
    public async Task Read_is_side_effect_free_and_refresh_returns_decimal_revision_without_private_diagnostics()
    {
        var documents = Storage();
        using var services = Services(documents);
        var function = new AdventureFunction(Validator, services);
        using var activity = new Activity("Adventure").Start();
        var read = Assert.IsType<ContentResult>(await function.Run(Request(Body(), Token()).Request));
        Assert.Equal(0, documents.Writes);
        var body = Body("refresh").Replace("}", ",\"batchId\":null}");
        var refresh = Assert.IsType<ContentResult>(await function.Run(Request(body, Token()).Request));
        using var response = JsonDocument.Parse(refresh.Content!);
        Assert.Equal("1", response.RootElement.GetProperty("revision").GetString());
        Assert.Equal("EMPTY", response.RootElement.GetProperty("board").GetProperty("batch").GetProperty("status").GetString());
        Assert.Equal(1, documents.Writes);
        Assert.Equal("ACCEPTED", activity.GetTagItem("adventure.result"));
        var telemetry = JsonSerializer.Serialize(activity.TagObjects.ToDictionary(t => t.Key, t => t.Value));
        Assert.DoesNotContain("canary", telemetry); Assert.DoesNotContain(workspace.ToString(), telemetry);
        Assert.DoesNotContain("Private", refresh.Content!);
    }

    [Theory]
    [InlineData("refresh", "\"batchId\":true")]
    [InlineData("accept", "\"batchId\":null,\"proposalId\":null")]
    [InlineData("edit", "\"adventureId\":null,\"version\":1,\"draft\":{}")]
    [InlineData("leave", "\"adventureId\":null,\"version\":\"01\",\"confirmed\":true")]
    public async Task Action_payloads_require_typed_identifiers_and_decimal_versions(string action, string fields)
    {
        var documents = Storage(); using var services = Services(documents);
        Assert.Equal(400, Assert.IsType<ObjectResult>(await new AdventureFunction(Validator, services).Run(
            Request(Body(action).Replace("}", "," + fields + "}"), Token()).Request)).StatusCode);
        Assert.Equal(0, documents.Writes);
    }

    [Fact]
    public async Task Ideas_route_returns_private_suggestions_without_task_or_board_writes()
    {
        var documents = Storage();
        using var services = new ServiceCollection().AddSingleton<IHouseholdDocuments>(documents)
            .AddSingleton<IRegistrationStore>(new Registration(identity, registration, true))
            .AddSingleton<IAdventureIdeasProvider>(new Ideas()).BuildServiceProvider();
        using var trace = new Activity("ideas-function").Start();
        var request = Request(Body("ideas").Replace("}", ",\"language\":\"fi\"}"), Token());
        var result = Assert.IsType<ContentResult>(await new AdventureFunction(Validator, services).Run(request.Request));
        Assert.Equal(200, result.StatusCode); Assert.Equal("no-store", request.Response.Headers.CacheControl);
        using var json = JsonDocument.Parse(result.Content!);
        Assert.Equal(3, json.RootElement.GetProperty("ideas").GetArrayLength());
        Assert.Equal("0", json.RootElement.GetProperty("snapshot").GetProperty("revision").GetString());
        Assert.Equal(0, documents.Writes);
        Assert.Equal("ADVENTURE_IDEAS", trace.GetTagItem("ai.mode"));
        Assert.DoesNotContain("Private", JsonSerializer.Serialize(trace.TagObjects.ToDictionary(t => t.Key, t => t.Value)));
    }
    private sealed class Ideas : IAdventureIdeasProvider {
        public Task<string[]> SuggestAsync(IReadOnlyList<AdventureIdeaInput> tasks, string language, CancellationToken ct) =>
            Task.FromResult(new[] { "Private corner idea", "Private balcony idea", "Private workspace idea" });
    }

    [Fact]
    public async Task Cancellation_is_not_reported_as_success()
    {
        using var services = Services(Storage());
        var request = Request(Body(), Token());
        request.RequestAborted = new CancellationToken(true);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new AdventureFunction(Validator, services).Run(request.Request));
    }

    [Fact]
    public async Task Artwork_delivery_authenticates_bounds_requests_and_rechecks_membership_after_blob_read()
    {
        var documents = Storage(); var images = new Images();
        using var services = new ServiceCollection().AddSingleton<IHouseholdDocuments>(documents)
            .AddSingleton<IRegistrationStore>(new Registration(identity, registration, true))
            .AddSingleton(new ArtworkCatalog(images)).BuildServiceProvider();
        var function = new ArtworkFunction(Validator, services);
        var oversized = Request(new string('x', 3000), null);
        Assert.Equal(401, Assert.IsType<ObjectResult>(await function.Run(oversized.Request)).StatusCode); Assert.Equal(0, oversized.Request.Body.Position);
        oversized.Request.Headers.Authorization = "Bearer " + Token();
        Assert.Equal(413, Assert.IsType<ObjectResult>(await function.Run(oversized.Request)).StatusCode);
        var batch = Guid.NewGuid(); var choice = Guid.NewGuid();
        documents.State = documents.State with { Adventures = new(new(batch, "CONSUMED", DateTimeOffset.UtcNow),
            new(choice, batch, 1, new("Private title", "", []), DateTimeOffset.UtcNow, "dojo-v1-home")) };
        var body = JsonSerializer.Serialize(new { workspaceId = workspace, stateEpoch = epoch, registrationId = registration, batchId = batch, choiceId = choice, action = "image" });
        var request = Request(body, Token());
        Assert.IsType<FileContentResult>(await function.Run(request.Request)); Assert.Equal("no-store", request.Response.Headers.CacheControl);
        var active = documents.State.Adventures;
        documents.State = documents.State with { Adventures = new(new(batch, "READY", DateTimeOffset.UtcNow.AddDays(-2),
            DateTimeOffset.UtcNow.AddDays(-1), Proposals: [new(choice, active!.Active!.Draft, "dojo-v1-home")])) };
        Assert.Equal(409, Assert.IsType<ObjectResult>(await function.Run(Request(body, Token()).Request)).StatusCode);
        documents.State = documents.State with { Adventures = active };
        images.AfterRead = () => documents.State = documents.State with { Membership = HouseholdMembership.Create(Guid.NewGuid()) };
        Assert.Equal(403, Assert.IsType<ObjectResult>(await function.Run(Request(body, Token()).Request)).StatusCode);
    }
    private sealed class Images : IArtworkStore {
        public Action? AfterRead;
        public Task<StoredArtwork?> ReadAsync(string key, CancellationToken ct) => Task.FromResult<StoredArtwork?>(new(new(ArtworkBrief.All[0], "READY", Guid.NewGuid(), Blob: "image"), "1"));
        public Task<bool> WriteAsync(string key, string? version, ArtworkEntry entry, CancellationToken ct) => throw new NotSupportedException();
        public Task PutImageAsync(string name, byte[] bytes, CancellationToken ct) => throw new NotSupportedException();
        public Task<byte[]> ImageAsync(string name, CancellationToken ct) { AfterRead?.Invoke(); return Task.FromResult("RIFFxxxxWEBP"u8.ToArray()); }
    }

    private sealed class Registration(AccountIdentity identity, Guid registration, bool active) : IRegistrationStore
    {
        public Task<bool> IsActiveAsync(AccountIdentity candidate, Guid id, CancellationToken ct) =>
            Task.FromResult(active && candidate == identity && id == registration);
        public Task<RegistrationDecision> RegisterAsync(AccountIdentity i, Guid id, Guid? revoke, CancellationToken ct) =>
            throw new NotSupportedException();
    }

    private sealed class Documents(WorkspaceState state) : IHouseholdDocuments
    {
        public WorkspaceState State = state;
        public int Writes;
        public Task<StoredDocument<T>?> ReadAsync<T>(string partition, string id, CancellationToken ct)
        {
            Assert.Equal(State.WorkspaceId.ToString(), partition); Assert.Equal("state", id);
            return Task.FromResult<StoredDocument<T>?>(new((T)(object)State, Writes.ToString()));
        }
        public Task<DocumentPage<T>> ReadPageAsync<T>(string p, string prefix, string? continuation, int limit, CancellationToken ct) =>
            Task.FromResult(new DocumentPage<T>([], null));
        public Task<bool> WriteAsync<T>(string p, string id, string? version, T value, CancellationToken ct) => throw new NotSupportedException();
        public Task<bool> CommitWorkspaceAsync(StoredDocument<WorkspaceState> expected, WorkspaceState next, CancellationToken ct,
            IReadOnlyList<string>? deletes = null)
        {
            if (expected.Version != Writes.ToString()) return Task.FromResult(false);
            State = WorkspaceCommit.Plan(State, next).Metadata; Writes++;
            return Task.FromResult(true);
        }
    }
}
