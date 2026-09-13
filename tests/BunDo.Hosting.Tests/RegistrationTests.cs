using BunDo.Domain;
using BunDo.Functions.Identity;
#if DEBUG
using BunDo.Functions.Identity.Development;
#endif

namespace BunDo.Hosting.Tests;

public sealed class RegistrationTests
{
#if DEBUG
    [Fact]
    public async Task LocalAdapterPersistsAndIsolatesAccountsAcrossHostInstances()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        try
        {
            var store = new LocalRegistrationStore(directory);
            var installation = Guid.NewGuid();
            var alice = new AccountIdentity(LocalIdentity.Issuer, "alice");
            var bob = alice with { Subject = "bob" };
            var first = await store.RegisterAsync(alice, installation, null, default);
            var restarted = new LocalRegistrationStore(directory);
            var retry = await restarted.RegisterAsync(alice, installation, null, default);
            Assert.Equal(first.Registration!.RegistrationId, retry.Registration!.RegistrationId);
            Assert.True(retry.Registration.ExpiresAt >= first.Registration.ExpiresAt);
            Assert.True(await restarted.IsActiveAsync(alice, first.Registration!.RegistrationId, default));
            Assert.False(await restarted.IsActiveAsync(bob, first.Registration.RegistrationId, default));
            var other = await restarted.RegisterAsync(bob, installation, null, default);
            Assert.NotEqual(first.Registration!.RegistrationId, other.Registration!.RegistrationId);
            for (var i = 0; i < 4; i++) await restarted.RegisterAsync(alice, Guid.NewGuid(), null, default);
            var rejected = await restarted.RegisterAsync(alice, Guid.NewGuid(), null, default);
            Assert.Equal("registration_limit", rejected.Code);
            var admitted = await restarted.RegisterAsync(alice, Guid.NewGuid(), first.Registration.RegistrationId, default);
            Assert.Equal("accepted", admitted.Code);
            Assert.Equal("registration_retired", (await restarted.RegisterAsync(alice, installation, null, default)).Code);
            Assert.False(await restarted.IsActiveAsync(alice, first.Registration.RegistrationId, default));
        }
        finally { Directory.Delete(directory, recursive: true); }
    }
#endif
}
