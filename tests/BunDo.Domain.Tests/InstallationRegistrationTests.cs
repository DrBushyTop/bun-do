using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class InstallationRegistrationTests
{
    [Fact]
    public void ExistingInstallationCanRetireAnotherDeviceWithoutCreatingAReplacement()
    {
        var now = DateTimeOffset.UtcNow;
        var current = InstallationRegistrations.Register([], Guid.NewGuid(), Guid.NewGuid(), now);
        var other = InstallationRegistrations.Register(current.Records, Guid.NewGuid(), Guid.NewGuid(), now);
        var retired = InstallationRegistrations.Register(other.Records, current.Registration!.InstallationId,
            Guid.NewGuid(), now, other.Registration!.RegistrationId);
        Assert.Equal("accepted", retired.Code);
        Assert.Equal(current.Registration.RegistrationId, retired.Registration!.RegistrationId);
        Assert.Equal(2, retired.Records.Length);
        Assert.True(retired.Records.Single(x => x.RegistrationId == other.Registration.RegistrationId).Revoked);
        Assert.Equal("registration_retired", InstallationRegistrations.Register(retired.Records,
            other.Registration.InstallationId, Guid.NewGuid(), now).Code);
        var retry = InstallationRegistrations.Register(retired.Records, current.Registration.InstallationId,
            Guid.NewGuid(), now, other.Registration.RegistrationId);
        Assert.Equal(retired.Records, retry.Records);
    }

    [Fact]
    public void AuthenticatedRenewalKeepsIdentityAndCannotReviveExpiredRegistration()
    {
        var now = DateTimeOffset.UtcNow;
        var installation = Guid.NewGuid();
        var first = InstallationRegistrations.Register([], installation, Guid.NewGuid(), now);
        var retry = InstallationRegistrations.Register(first.Records, installation, Guid.NewGuid(), now.AddDays(1));
        Assert.Equal(first.Registration!.RegistrationId, retry.Registration!.RegistrationId);
        Assert.Equal(now.AddDays(91), retry.Registration.ExpiresAt);
        Assert.Single(retry.Records);
        Assert.Equal("registration_retired", InstallationRegistrations.Register(first.Records,
            installation, Guid.NewGuid(), now.AddDays(90)).Code);
    }

    [Fact]
    public void InvalidOrSelfRevocationCannotRetireAnUnrelatedDevice()
    {
        var now = DateTimeOffset.UtcNow;
        var current = InstallationRegistrations.Register([], Guid.NewGuid(), Guid.NewGuid(), now);
        foreach (var target in new[] { current.Registration!.RegistrationId, Guid.NewGuid() })
        {
            var rejected = InstallationRegistrations.Register(current.Records,
                current.Registration.InstallationId, Guid.NewGuid(), now, target);
            Assert.NotEqual("accepted", rejected.Code);
            Assert.Equal(current.Records, rejected.Records);
        }
    }

    [Fact]
    public void SixthInstallationRequiresExplicitRevocationAndRetirementIsPermanent()
    {
        var now = DateTimeOffset.UtcNow;
        InstallationRegistration[] records = [];
        for (var i = 0; i < 5; i++)
            records = InstallationRegistrations.Register(records, Guid.NewGuid(), Guid.NewGuid(), now).Records;
        Assert.Equal("registration_limit", InstallationRegistrations.Register(records, Guid.NewGuid(), Guid.NewGuid(), now).Code);
        var admitted = InstallationRegistrations.Register(records, Guid.NewGuid(), Guid.NewGuid(), now, records[0].RegistrationId);
        Assert.Equal("accepted", admitted.Code);
        Assert.Equal(5, admitted.Records.Count(x => !x.Revoked));
        Assert.Equal("registration_retired", InstallationRegistrations.Register(admitted.Records,
            records[0].InstallationId, Guid.NewGuid(), now).Code);
    }
}
