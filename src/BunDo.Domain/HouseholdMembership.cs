using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;

namespace BunDo.Domain;

public sealed record HouseholdMember(Guid Id, ulong Version, bool Active = true, string DisplayName = "");
public enum InvitationPhase { Issued, Pending, Approved, Cancelled }
public sealed record HouseholdInvitation(
    Guid Id, string SecretHash, DateTimeOffset ExpiresAt, ulong Version,
    InvitationPhase Phase = InvitationPhase.Issued, Guid? CandidateId = null, string? ConfirmationCode = null, string CandidateName = "");

/// <summary>Persist with task state in the same workspace revision transaction.</summary>
public sealed record HouseholdMembership(
    Guid OwnerId,
    ulong Version,
    ImmutableDictionary<Guid, HouseholdMember> Members,
    ImmutableDictionary<Guid, HouseholdInvitation> Invitations,
    ImmutableDictionary<Guid, ImmutableArray<DateTimeOffset>> RedemptionAttempts,
    DateTimeOffset? DeletedAt = null, [property: System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingDefault)] Guid? PersonalOwnerId = null)
{
    public static HouseholdMembership Create(Guid ownerId, ulong version = 0, string displayName = "")
    {
        if (ownerId == Guid.Empty) throw new ArgumentException("Owner is required.", nameof(ownerId));
        return new(ownerId, version,
            ImmutableDictionary<Guid, HouseholdMember>.Empty.Add(ownerId, new(ownerId, version, DisplayName: displayName)),
            ImmutableDictionary<Guid, HouseholdInvitation>.Empty,
            ImmutableDictionary<Guid, ImmutableArray<DateTimeOffset>>.Empty);
    }

    public bool CanRead(Guid memberId) =>
        (PersonalOwnerId is null || PersonalOwnerId == memberId) && DeletedAt is null && Members.TryGetValue(memberId, out var member) && member.Active;
}

public abstract record MembershipCommand;
// The host creates a random secret and stores only its hash. Neither is accepted from an invitee.
public sealed record IssueHouseholdInvitation(Guid InvitationId, string SecretHash) : MembershipCommand;
// The host supplies the random confirmation code, never the candidate's HTTP request.
public sealed record RedeemHouseholdInvitation(
    Guid InvitationId, string SecretHash, string ConfirmationCode, string DisplayName = "") : MembershipCommand;
public sealed record ApproveHouseholdInvitation(
    Guid InvitationId, ulong ExpectedVersion, string ConfirmationCode) : MembershipCommand;
public sealed record CancelHouseholdInvitation(Guid InvitationId, ulong ExpectedVersion) : MembershipCommand;
public sealed record RemoveHouseholdMember(Guid MemberId, ulong ExpectedVersion) : MembershipCommand;
public sealed record LeaveHousehold(ulong ExpectedVersion) : MembershipCommand;
public sealed record TransferHouseholdOwnership(Guid MemberId, ulong ExpectedVersion) : MembershipCommand;
public sealed record DeleteHousehold(ulong ExpectedVersion) : MembershipCommand;
public sealed record MembershipDecision(string Code, HouseholdMembership State, HouseholdInvitation? Invitation = null);

/// <summary>
/// Deterministic policy. The caller conditionally commits returned state with the workspace revision,
/// including failed redemption attempts, and retries the decision after a lost compare-and-swap.
/// </summary>
public static class MembershipPolicy
{
    public static MembershipDecision Apply(HouseholdMembership state, Guid actor, MembershipCommand command,
        DateTimeOffset now, ulong revision)
    {
        if (state.PersonalOwnerId is not null) return new("PERSONAL_WORKSPACE", state);
        if (actor == Guid.Empty) return new("FORBIDDEN", state);
        if (command is RedeemHouseholdInvitation redeem)
            return Redeem(state, actor, redeem, now, revision);
        if (!state.CanRead(actor)) return new("FORBIDDEN", state);
        if (command is LeaveHousehold leave)
            return Remove(state, actor, actor, leave.ExpectedVersion, revision);
        if (actor != state.OwnerId) return new("FORBIDDEN", state);

        switch (command)
        {
            case IssueHouseholdInvitation issue:
                if (issue.InvitationId == Guid.Empty || !ValidHash(issue.SecretHash))
                    return new("INVALID_INVITATION", state);
                if (state.Invitations.TryGetValue(issue.InvitationId, out var existing))
                    return SameSecret(existing.SecretHash, issue.SecretHash)
                        ? new("ACCEPTED", state, existing) : new("INVITATION_ID_REUSED", state);
                if (state.Invitations.Values.Count(x => x.ExpiresAt > now &&
                    x.Phase is InvitationPhase.Issued or InvitationPhase.Pending) >= 10)
                    return new("INVITATION_LIMIT", state);
                var invitation = new HouseholdInvitation(issue.InvitationId, issue.SecretHash,
                    now.AddHours(24), revision);
                return new("ACCEPTED", state with {
                    Invitations = state.Invitations.Add(invitation.Id, invitation),
                }, invitation);

            case ApproveHouseholdInvitation approve:
                if (!state.Invitations.TryGetValue(approve.InvitationId, out var pending))
                    return new("INVITATION_UNAVAILABLE", state);
                if (pending.Phase == InvitationPhase.Approved && pending.CandidateId is { } approved &&
                    state.CanRead(approved) && pending.ConfirmationCode == approve.ConfirmationCode)
                    return new("ACCEPTED", state, pending);
                if (pending.Version != approve.ExpectedVersion) return new("VERSION_CONFLICT", state);
                if (pending.Phase != InvitationPhase.Pending || pending.ExpiresAt <= now ||
                    pending.CandidateId is not { } candidate)
                    return new("INVITATION_UNAVAILABLE", state);
                if (pending.ConfirmationCode != approve.ConfirmationCode)
                    return new("CONFIRMATION_MISMATCH", state);
                if (!state.CanRead(candidate) && state.Members.Values.Count(x => x.Active) >= 10)
                    return new("MEMBER_LIMIT", state);
                var accepted = pending with { Phase = InvitationPhase.Approved, Version = revision };
                return new("ACCEPTED", state with {
                    Invitations = state.Invitations.SetItem(accepted.Id, accepted),
                    Members = state.CanRead(candidate) ? state.Members :
                        state.Members.SetItem(candidate, new(candidate, revision, DisplayName: pending.CandidateName)),
                }, accepted);

            case CancelHouseholdInvitation cancel:
                if (!state.Invitations.TryGetValue(cancel.InvitationId, out var cancelled))
                    return new("INVITATION_UNAVAILABLE", state);
                if (cancelled.Phase == InvitationPhase.Cancelled) return new("ACCEPTED", state, cancelled);
                if (cancelled.Version != cancel.ExpectedVersion) return new("VERSION_CONFLICT", state);
                if (cancelled.Phase == InvitationPhase.Approved) return new("INVITATION_ALREADY_USED", state);
                cancelled = cancelled with { Phase = InvitationPhase.Cancelled, Version = revision };
                return new("ACCEPTED", state with {
                    Invitations = state.Invitations.SetItem(cancelled.Id, cancelled),
                }, cancelled);

            case RemoveHouseholdMember remove:
                return Remove(state, actor, remove.MemberId, remove.ExpectedVersion, revision);

            case TransferHouseholdOwnership transfer:
                if (state.Version != transfer.ExpectedVersion) return new("VERSION_CONFLICT", state);
                if (!state.CanRead(transfer.MemberId)) return new("MEMBER_INACTIVE", state);
                if (transfer.MemberId == state.OwnerId) return new("ACCEPTED", state);
                return new("ACCEPTED", state with { OwnerId = transfer.MemberId, Version = revision });

            case DeleteHousehold delete:
                if (state.Version != delete.ExpectedVersion) return new("VERSION_CONFLICT", state);
                return new("ACCEPTED", state with {
                    Version = revision,
                    DeletedAt = now,
                    Invitations = state.Invitations.ToImmutableDictionary(x => x.Key, x =>
                        x.Value with { Phase = InvitationPhase.Cancelled, Version = revision }),
                });

            default:
                return new("UNKNOWN_COMMAND", state);
        }
    }

    private static MembershipDecision Remove(HouseholdMembership state, Guid actor, Guid target,
        ulong expectedVersion, ulong revision)
    {
        if (actor != state.OwnerId && actor != target) return new("FORBIDDEN", state);
        if (target == state.OwnerId) return new("OWNER_MUST_TRANSFER", state);
        if (!state.Members.TryGetValue(target, out var member)) return new("MEMBER_INACTIVE", state);
        if (!member.Active) return new("ACCEPTED", state);
        if (member.Version != expectedVersion) return new("VERSION_CONFLICT", state);
        return new("ACCEPTED", state with {
            Members = state.Members.SetItem(target, member with { Active = false, Version = revision }),
            // A second pending invitation cannot silently re-admit the removed identity.
            Invitations = state.Invitations.ToImmutableDictionary(x => x.Key, x =>
                x.Value.CandidateId == target
                    ? x.Value with { Phase = InvitationPhase.Cancelled, Version = revision }
                    : x.Value),
        });
    }

    private static MembershipDecision Redeem(HouseholdMembership state, Guid actor,
        RedeemHouseholdInvitation request, DateTimeOffset now, ulong revision)
    {
        if (state.DeletedAt is not null) return new("INVITATION_UNAVAILABLE", state);
        // Retain only the rolling-hour attempts. Failed attempts commit too.
        var attempts = state.RedemptionAttempts
            .Select(x => KeyValuePair.Create(x.Key, x.Value.Where(t => t > now.AddHours(-1)).ToImmutableArray()))
            .Where(x => !x.Value.IsEmpty).ToImmutableDictionary();
        var recent = attempts.GetValueOrDefault(actor, []);
        if (recent.Length >= 10) return new("REDEMPTION_LIMIT", state);
        state = state with { RedemptionAttempts = attempts.SetItem(actor, recent.Add(now)) };
        if (!state.Invitations.TryGetValue(request.InvitationId, out var invitation) ||
            !SameSecret(invitation.SecretHash, request.SecretHash))
            return new("INVITATION_UNAVAILABLE", state);
        if (invitation.CandidateId is { } bound && bound != actor)
            return new("INVITATION_UNAVAILABLE", state);
        if (invitation.Phase == InvitationPhase.Approved)
            return state.CanRead(actor)
                ? new("ACCEPTED", state, invitation) : new("INVITATION_UNAVAILABLE", state);
        if (invitation.Phase == InvitationPhase.Cancelled || invitation.ExpiresAt <= now)
            return new("INVITATION_UNAVAILABLE", state);
        if (invitation.Phase == InvitationPhase.Pending)
            return new("ACCEPTED", state, invitation);
        if (state.CanRead(actor)) return new("ALREADY_MEMBER", state);
        if (request.ConfirmationCode.Length != 6 || !request.ConfirmationCode.All(char.IsAsciiDigit))
            return new("INVALID_CONFIRMATION_CODE", state);
        var pending = invitation with {
            Phase = InvitationPhase.Pending, CandidateId = actor,
            ConfirmationCode = request.ConfirmationCode, CandidateName = request.DisplayName, Version = revision,
        };
        return new("ACCEPTED", state with {
            Invitations = state.Invitations.SetItem(pending.Id, pending),
        }, pending);
    }

    private static bool ValidHash(string value) =>
        value.Length == 64 && value.All(char.IsAsciiHexDigitUpper);

    private static bool SameSecret(string expected, string supplied) =>
        ValidHash(supplied) && CryptographicOperations.FixedTimeEquals(
            Encoding.ASCII.GetBytes(expected), Encoding.ASCII.GetBytes(supplied));
}
