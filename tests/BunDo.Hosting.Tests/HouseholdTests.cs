using System.Collections.Concurrent;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
using BunDo.Functions.Identity;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Configuration;
#if DEBUG
using BunDo.Functions.Identity.Development;
#endif

namespace BunDo.Hosting.Tests;

public sealed class HouseholdTests
{
    private readonly Guid alice = Guid.NewGuid();
    private readonly Guid bob = Guid.NewGuid();
    private readonly Guid eve = Guid.NewGuid();

    [Fact]
    public async Task CreateRetryAndDiscoveryNeverAuthorizeAnotherIdentity()
    {
        var service = new HouseholdService(new MemoryDocuments());
        var id = Guid.NewGuid();
        var created = await service.CreateAsync(alice, id, default, "Home");
        Assert.Equal("ACCEPTED", created.Code);
        Assert.Equal(JsonSerializer.Serialize(created.Household), JsonSerializer.Serialize((await service.CreateAsync(alice, id, default, "Renamed")).Household));
        Assert.Equal("FORBIDDEN", (await service.CreateAsync(bob, id, default)).Code);
        Assert.Empty(await service.ListAsync(bob, default));
        Assert.Single(await service.ListAsync(alice, default));
    }

    [Fact]
    public async Task SecretOnlyLeavesInFragmentAndCandidateSeesNoHouseholdContent()
    {
        var documents = new MemoryDocuments();
        var service = new HouseholdService(documents);
        var home = (await service.CreateAsync(alice, Guid.NewGuid(), default, "PRIVATE HOME")).Household!;
        var issue = await service.IssueAsync(alice, home.Id, home.StateEpoch, default);
        var parts = Link(issue);
        Assert.DoesNotContain(parts.Secret, documents.AllJson);
        var pending = await service.RedeemAsync(bob, home.Id, home.StateEpoch, parts.Invitation, parts.Secret, default);
        Assert.False(pending.Household!.Active);
        Assert.Empty(pending.Household.Members);
        Assert.Equal("", pending.Household.Name);
        var invitation = Assert.Single(pending.Household.Invitations);
        Assert.Equal(6, invitation.ConfirmationCode!.Length);
        var retry = await service.RedeemAsync(bob, home.Id, home.StateEpoch, parts.Invitation, parts.Secret, default);
        Assert.Equal(invitation.ConfirmationCode, Assert.Single(retry.Household!.Invitations).ConfirmationCode);
        var wrongIdentity = await service.RedeemAsync(eve, home.Id, home.StateEpoch, parts.Invitation, parts.Secret, default);
        Assert.Null(wrongIdentity.Household);
        Assert.Equal("INVITATION_UNAVAILABLE", wrongIdentity.Code);
        Assert.DoesNotContain("SecretHash", JsonSerializer.Serialize(pending));
        var owner = await service.GetAsync(alice, home.Id, default);
        Assert.Equal(invitation.ConfirmationCode, Assert.Single(owner!.Invitations).ConfirmationCode);
        var approval = await service.ChangeAsync(alice, home.Id, home.StateEpoch,
            new ApproveHouseholdInvitation(parts.Invitation, invitation.Version, invitation.ConfirmationCode), default);
        Assert.Equal("ACCEPTED", approval.Code);
        var joined = await service.GetAsync(bob, home.Id, default);
        Assert.True(joined!.Active);
        Assert.Equal("PRIVATE HOME", joined.Name);
        var member = joined.Members.Single(x => x.Id == bob);
        Assert.Equal("ACCEPTED", (await service.ChangeAsync(alice, home.Id, home.StateEpoch,
            new RemoveHouseholdMember(bob, member.Version), default)).Code);
        Assert.Null(await service.GetAsync(bob, home.Id, default));
        Assert.Empty(await service.ListAsync(bob, default));
        Assert.Equal("FORBIDDEN", (await service.ChangeAsync(bob, home.Id, home.StateEpoch,
            new LeaveHousehold(member.Version), default)).Code);
    }

    [Fact]
    public async Task RedemptionLimitIsGlobalAndSurvivesServiceRestart()
    {
        var documents = new MemoryDocuments();
        var service = new HouseholdService(documents);
        for (var i = 0; i < 10; i++)
            Assert.NotEqual("REDEMPTION_LIMIT", (await service.RedeemAsync(bob, Guid.NewGuid(), Guid.NewGuid(),
                Guid.NewGuid(), new string('A', 64), default)).Code);
        service = new HouseholdService(documents);
        Assert.Equal("REDEMPTION_LIMIT", (await service.RedeemAsync(bob, Guid.NewGuid(), Guid.NewGuid(),
            Guid.NewGuid(), "malformed", default)).Code);
        Assert.NotEqual("REDEMPTION_LIMIT", (await service.RedeemAsync(eve, Guid.NewGuid(), Guid.NewGuid(),
            Guid.NewGuid(), "malformed", default)).Code);
    }

    [Fact]
    public async Task ConcurrentRedemptionsHaveOneCandidateAndConcurrentAdmissionHasTenAttempts()
    {
        var documents = new MemoryDocuments();
        var service = new HouseholdService(documents);
        var home = (await service.CreateAsync(alice, Guid.NewGuid(), default)).Household!;
        var link = Link(await service.IssueAsync(alice, home.Id, home.StateEpoch, default));
        var results = await Task.WhenAll(new[] { bob, eve }.Select(actor => Task.Run(() =>
            service.RedeemAsync(actor, home.Id, home.StateEpoch, link.Invitation, link.Secret, default))));
        Assert.Single(results, x => x.Code == "ACCEPTED");
        var unknown = Guid.NewGuid();
        var attempts = await Task.WhenAll(Enumerable.Range(0, 20).Select(_ => Task.Run(() =>
            service.RedeemAsync(unknown, home.Id, home.StateEpoch, Guid.NewGuid(), "bad", default))));
        Assert.Equal(10, attempts.Count(x => x.Code == "REDEMPTION_LIMIT"));
    }

    [Fact]
    public async Task CancellationAndExpiryAreVisibleOnlyToBoundCandidate()
    {
        var clock = new Clock();
        var service = new HouseholdService(new MemoryDocuments(), clock);
        var home = (await service.CreateAsync(alice, Guid.NewGuid(), default)).Household!;
        var link = Link(await service.IssueAsync(alice, home.Id, home.StateEpoch, default));
        var pending = await service.RedeemAsync(bob, home.Id, home.StateEpoch, link.Invitation, link.Secret, default);
        var invite = Assert.Single(pending.Household!.Invitations);
        await service.ChangeAsync(alice, home.Id, home.StateEpoch,
            new CancelHouseholdInvitation(link.Invitation, invite.Version), default);
        Assert.Equal("Cancelled", Assert.Single((await service.GetAsync(bob, home.Id, default))!.Invitations).Phase);
        Assert.Null(await service.GetAsync(eve, home.Id, default));
        link = Link(await service.IssueAsync(alice, home.Id, home.StateEpoch, default));
        await service.RedeemAsync(eve, home.Id, home.StateEpoch, link.Invitation, link.Secret, default);
        clock.Now = clock.Now.AddHours(24);
        Assert.Equal("Expired", Assert.Single((await service.GetAsync(eve, home.Id, default))!.Invitations).Phase);
        await service.ChangeAsync(alice, home.Id, home.StateEpoch, new DeleteHousehold(home.MembershipVersion), default);
        Assert.Null(await service.GetAsync(eve, home.Id, default));
        var deleted = await service.GetAsync(alice, home.Id, default);
        Assert.NotNull(deleted!.DeletedAt);
        Assert.False(deleted.Active);
        Assert.Empty(deleted.Invitations);
    }

    [Fact]
    public void WireVersionsPreserveUnsignedPrecisionAsDecimalStrings()
    {
        var member = new MemberView(alice, ulong.MaxValue, true, "Alice");
        using var json = JsonDocument.Parse(JsonSerializer.Serialize(member));
        Assert.Equal(ulong.MaxValue.ToString(), json.RootElement.GetProperty("Version").GetString());
        var request = JsonSerializer.Deserialize<HouseholdRequest>(
            "{\"ExpectedVersion\":\"18446744073709551615\"}");
        Assert.Equal(ulong.MaxValue, request!.ExpectedVersion);
    }

    [Fact]
    public void MemberKeyUsesExactIssuerAndSubject()
    {
        var identity = new AccountIdentity("issuer", "subject");
        Assert.Equal(HouseholdIdentity.Member(identity), HouseholdIdentity.Member(identity));
        Assert.NotEqual(HouseholdIdentity.Member(identity), HouseholdIdentity.Member(identity with { Issuer = "Issuer" }));
        Assert.NotEqual(HouseholdIdentity.Member(identity), HouseholdIdentity.Member(identity with { Subject = "Subject" }));
    }

#if DEBUG
    [Fact]
    public async Task LocalDocumentsRoundTripMembershipAndRejectStaleWrites()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        try
        {
            var store = new LocalHouseholdDocuments(directory);
            var service = new HouseholdService(store);
            var home = (await service.CreateAsync(alice, Guid.NewGuid(), default)).Household!;
            var restarted = new HouseholdService(new LocalHouseholdDocuments(directory));
            Assert.Equal(home.Id, Assert.Single(await restarted.ListAsync(alice, default)).Id);
            var state = await store.ReadAsync<WorkspaceState>(home.Id.ToString("D"), "state", default);
            Assert.True(await store.WriteAsync(home.Id.ToString("D"), "state", state!.Version, state.Value, default));
            Assert.False(await store.WriteAsync(home.Id.ToString("D"), "state", state.Version, state.Value, default));
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public async Task HttpRejectsUnregisteredIdentityAndBoundsInputBeforeMutation()
    {
        var local = new LocalIdentity(new Microsoft.Extensions.Configuration.ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?> {
                ["AZURE_FUNCTIONS_ENVIRONMENT"] = "Development",
                ["BunDoIdentity:Mode"] = "Local",
            }).Build());
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        try
        {
            var registrations = new LocalRegistrationStore(directory);
            using var services = new ServiceCollection().AddSingleton<IRegistrationStore>(registrations)
                .AddSingleton(new HouseholdService(new MemoryDocuments())).BuildServiceProvider();
            var function = new HouseholdFunction(local.Validator, services);
            var context = new DefaultHttpContext();
            context.Request.Body = new MemoryStream(System.Text.Encoding.UTF8.GetBytes("{}"));
            Assert.IsType<UnauthorizedResult>(await function.Run(context.Request));
            var token = local.Issue("alice", "valid")!;
            async Task<IActionResult> Send(object body)
            {
                var http = new DefaultHttpContext();
                http.Request.Headers.Authorization = "Bearer " + token;
                http.Request.Body = new MemoryStream(JsonSerializer.SerializeToUtf8Bytes(body));
                return await function.Run(http.Request);
            }
            var missing = await Send(new { action = "list", registrationId = Guid.NewGuid() });
            Assert.Equal(403, Assert.IsType<ObjectResult>(missing).StatusCode);
            var registration = (await registrations.RegisterAsync(new AccountIdentity(LocalIdentity.Issuer, "alice"),
                Guid.NewGuid(), null, default)).Registration!.RegistrationId;
            Assert.IsType<BadRequestResult>(await Send(new { action = "create", registrationId = registration }));
            Assert.Equal(413, Assert.IsType<StatusCodeResult>(await Send(new { padding = new string('x', 4096) })).StatusCode);
            var workspace = Guid.NewGuid();
            var created = Assert.IsType<ObjectResult>(await Send(new { action = "create", registrationId = registration,
                workspaceId = workspace, name = "HTTP test", displayName = "Alice" }));
            Assert.Equal(200, created.StatusCode);
            Assert.Equal("Alice", Assert.Single(Assert.IsType<HouseholdReply>(created.Value).Household!.Members).DisplayName);
            Assert.IsType<OkObjectResult>(await Send(new { action = "list", registrationId = registration }));
            // A different identity's installation proof grants no access.
            token = local.Issue("bob", "valid")!;
            Assert.Equal(403, Assert.IsType<ObjectResult>(await Send(new {
                action = "get", registrationId = registration, workspaceId = workspace })).StatusCode);

        }
        finally { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
    }
#endif

    private static (Guid Invitation, string Secret) Link(HouseholdReply issue)
    {
        Assert.Equal("ACCEPTED", issue.Code);
        var parts = new Uri(issue.InvitationLink!).Fragment[1..].Split('/');
        return (Guid.Parse(parts[2]), parts[3]);
    }

    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now = new(2026, 9, 13, 0, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private sealed class MemoryDocuments : IHouseholdDocuments
    {
        private readonly ConcurrentDictionary<string, (string Json, string Version)> values = new();
        private readonly object gate = new();
        public string AllJson => string.Join("\n", values.Values.Select(x => x.Json));
        public Task<StoredDocument<T>?> ReadAsync<T>(string partition, string id, CancellationToken cancellationToken)
        {
            lock (gate) return Task.FromResult(values.TryGetValue(partition + id, out var stored)
                ? new StoredDocument<T>(JsonSerializer.Deserialize<T>(stored.Json)!, stored.Version) : null);
        }
        public Task<bool> WriteAsync<T>(string partition, string id, string? version, T value, CancellationToken cancellationToken)
        {
            lock (gate)
            {
                values.TryGetValue(partition + id, out var stored);
                if (stored.Version != version) return Task.FromResult(false);
                values[partition + id] = (JsonSerializer.Serialize(value), Guid.NewGuid().ToString());
                return Task.FromResult(true);
            }
        }
    }
}
