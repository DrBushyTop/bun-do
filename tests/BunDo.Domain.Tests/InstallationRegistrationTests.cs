using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class InstallationRegistrationTests
{
    [Fact]
    public void RetryDoesNotRenewExpiryOrCreateAnotherRegistration()
    {
        var now = DateTimeOffset.UtcNow;
        var installation = Guid.NewGuid();
        var first = InstallationRegistrations.Register([], installation, Guid.NewGuid(), now);
        var retry = InstallationRegistrations.Register(first.Records, installation, Guid.NewGuid(), now.AddDays(1));
        Assert.Equal(first.Registration, retry.Registration);
        Assert.Single(retry.Records);
        Assert.Equal("registration_retired", InstallationRegistrations.Register(first.Records,
            installation, Guid.NewGuid(), now.AddDays(90)).Code);
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
