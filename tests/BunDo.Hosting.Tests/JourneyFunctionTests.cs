using System.Collections.Immutable;
using System.Diagnostics;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using BunDo.Functions.Progress;
using BunDo.Functions.Sync;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;

namespace BunDo.Hosting.Tests;

public sealed class JourneyFunctionTests : IDisposable
{
    private readonly RSA rsa = RSA.Create(2048);
    private readonly AccountIdentity identity = new(AccessTokens.Issuer, "private-subject-canary");
    private readonly Guid workspace = Guid.NewGuid(), epoch = Guid.NewGuid(), registration = Guid.NewGuid();
    private RsaSecurityKey Key => new(rsa) { KeyId = "journey-test" };
    private AccessTokens Validator => new("journey-api", new StaticConfigurationManager<OpenIdConnectConfiguration>(
        new() { Issuer = AccessTokens.Issuer, SigningKeys = { Key } }));
    private string Token(string scope = "access_as_user") => new JsonWebTokenHandler().CreateToken(new SecurityTokenDescriptor {
        Issuer = AccessTokens.Issuer, Audience = "journey-api",
        Subject = new ClaimsIdentity([new Claim("sub", identity.Subject), new Claim("scp", scope)]),
        Expires = DateTime.UtcNow.AddMinutes(5),
        SigningCredentials = new(Key, SecurityAlgorithms.RsaSha256),
    });
    private string Body(string action = "enable") => JsonSerializer.Serialize(new {
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
        var function = new JourneyFunction(Validator, services);
        var request = Request(new string('x', 2048), null);
        Assert.Equal(401, Assert.IsType<ObjectResult>(await function.Run(request.Request)).StatusCode);
        Assert.Equal(0, request.Request.Body.Position);
        request.Request.Headers.Authorization = "Bearer " + Token();
        Assert.Equal(413, Assert.IsType<ObjectResult>(await function.Run(request.Request)).StatusCode);
        Assert.Equal(1025, request.Request.Body.Position);
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
        var result = await new JourneyFunction(Validator, services).Run(Request(body, Token()).Request);
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
            _ => body.Replace("\"enable\"", "true"),
        };
        var documents = Storage();
        using var services = Services(documents);
        Assert.Equal(400, Assert.IsType<ObjectResult>(
            await new JourneyFunction(Validator, services).Run(Request(body, Token()).Request)).StatusCode);
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
        var result = await new JourneyFunction(Validator, services).Run(Request(body, Token(fault == "scope" ? "other" : "access_as_user")).Request);
        Assert.Equal(status, Assert.IsType<ObjectResult>(result).StatusCode);
        Assert.Equal(0, documents.Writes);
    }

    [Fact]
    public async Task Read_does_not_enable_and_enable_retries_return_one_persisted_start_in_decimal_revision_contract()
    {
        var documents = Storage();
        using var services = Services(documents);
        var function = new JourneyFunction(Validator, services);
        var read = Assert.IsType<ContentResult>(await function.Run(Request(Body("read"), Token()).Request));
        using var before = JsonDocument.Parse(read.Content!);
        Assert.Equal(JsonValueKind.Null, before.RootElement.GetProperty("journey").ValueKind);
        Assert.Equal(0, documents.Writes);
        using var activity = new Activity("Journey").Start();
        var enabled = Assert.IsType<ContentResult>(await function.Run(Request(Body(), Token()).Request));
        var retry = Assert.IsType<ContentResult>(await function.Run(Request(Body(), Token()).Request));
        using var response = JsonDocument.Parse(enabled.Content!);
        using var second = JsonDocument.Parse(retry.Content!);
        Assert.Equal("1", response.RootElement.GetProperty("revision").GetString());
        Assert.Equal(response.RootElement.GetProperty("journey").GetRawText(), second.RootElement.GetProperty("journey").GetRawText());
        Assert.Equal(1, documents.Writes);
        Assert.Equal("ACCEPTED", activity.GetTagItem("journey.result"));
        var telemetry = JsonSerializer.Serialize(activity.TagObjects.ToDictionary(t => t.Key, t => t.Value));
        Assert.DoesNotContain("canary", telemetry);
        Assert.DoesNotContain("Private", telemetry);
        Assert.DoesNotContain(workspace.ToString(), telemetry);
        Assert.DoesNotContain("Baseline", enabled.Content!);
        Assert.DoesNotContain("Private", enabled.Content!);
    }

    [Fact]
    public async Task Retry_after_ambiguous_committed_failure_keeps_enablement()
    {
        var documents = Storage();
        using var services = Services(documents);
        var function = new JourneyFunction(Validator, services);
        documents.FailAfterCommit = true;
        await Assert.ThrowsAsync<IOException>(() => function.Run(Request(Body(), Token()).Request));
        var start = documents.State.Journey;
        Assert.NotNull(start);
        Assert.IsType<ContentResult>(await function.Run(Request(Body(), Token()).Request));
        Assert.Equal(start, documents.State.Journey);
        Assert.Equal(1, documents.Writes);
    }

    [Fact]
    public async Task Cancellation_is_not_reported_as_success()
    {
        using var services = Services(Storage());
        var request = Request(Body(), Token());
        request.RequestAborted = new CancellationToken(true);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new JourneyFunction(Validator, services).Run(request.Request));
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
        public bool FailAfterCommit;
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
            if (FailAfterCommit) { FailAfterCommit = false; throw new IOException("Lost reply"); }
            return Task.FromResult(true);
        }
    }
}
