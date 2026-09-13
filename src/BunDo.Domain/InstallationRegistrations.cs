namespace BunDo.Domain;

public sealed record InstallationRegistration(Guid InstallationId, Guid RegistrationId, DateTimeOffset ExpiresAt, bool Revoked = false);
public sealed record RegistrationDecision(string Code, InstallationRegistration? Registration, InstallationRegistration[] Records);

/// <summary>Identity-scoped registrations. Retired installation IDs can never become active again.</summary>
public static class InstallationRegistrations
{
    public static RegistrationDecision Register(InstallationRegistration[] records, Guid installationId,
        Guid newRegistrationId, DateTimeOffset now, Guid? revoke = null)
    {
        if (installationId == Guid.Empty || newRegistrationId == Guid.Empty)
            throw new ArgumentException("Installation and registration IDs must be nonempty.");
        var previous = records.SingleOrDefault(x => x.InstallationId == installationId);
        if (previous is not null)
            return new(previous.Revoked || previous.ExpiresAt <= now ? "registration_retired" : "accepted", previous, records);
        if (revoke is not null && !records.Any(x => x.RegistrationId == revoke && !x.Revoked && x.ExpiresAt > now))
            return new("unknown_registration", null, records);
        var next = records.Select(x => x.RegistrationId == revoke ? x with { Revoked = true } : x).ToArray();
        if (next.Count(x => !x.Revoked && x.ExpiresAt > now) >= 5)
            return new("registration_limit", null, records);
        var registration = new InstallationRegistration(installationId, newRegistrationId, now.AddDays(90));
        return new("accepted", registration, [.. next, registration]);
    }
}
