using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class HouseholdMembershipTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-09-13T10:00:00Z");
    private readonly Guid owner = Guid.NewGuid();
    private readonly Guid alice = Guid.NewGuid();
    private readonly Guid bob = Guid.NewGuid();
    private const string Hash = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
    private ulong revision;

    private MembershipDecision Apply(HouseholdMembership state, Guid actor, MembershipCommand command,
        DateTimeOffset? now = null) => MembershipPolicy.Apply(state, actor, command, now ?? Now, ++revision);

    private MembershipDecision Invite(HouseholdMembership state) =>
        Apply(state, owner, new IssueHouseholdInvitation(Guid.NewGuid(), Hash));

    private HouseholdMembership Join(HouseholdMembership state, Guid candidate)
    {
        var issue = Invite(state);
        Assert.Equal("ACCEPTED", issue.Code);
        var pending = Apply(issue.State, candidate,
            new RedeemHouseholdInvitation(issue.Invitation!.Id, Hash, "482951"));
        Assert.Equal("ACCEPTED", pending.Code);
        var approve = Apply(pending.State, owner,
            new ApproveHouseholdInvitation(issue.Invitation.Id, pending.Invitation!.Version, "482951"));
        Assert.Equal("ACCEPTED", approve.Code);
        return approve.State;
    }

    [Fact]
    public void RedemptionBindsOneCandidateWithoutGrantingAccessAndRetriesKeepTheCode()
    {
        var issue = Invite(HouseholdMembership.Create(owner));
        var request = new RedeemHouseholdInvitation(issue.Invitation!.Id, Hash, "482951");
        var pending = Apply(issue.State, alice, request);
        Assert.False(pending.State.CanRead(alice));
        var retry = Apply(pending.State, alice, request with { ConfirmationCode = "000000" });
        Assert.Equal(pending.Invitation, retry.Invitation);
        var wrong = Apply(retry.State, bob, request);
        Assert.Equal("INVITATION_UNAVAILABLE", wrong.Code);
        Assert.False(wrong.State.CanRead(bob));
        Assert.Equal(alice, wrong.State.Invitations[request.InvitationId].CandidateId);
    }

    [Fact]
    public void OnlyOwnerCanApproveAndConfirmationMustMatch()
    {
        var issue = Invite(HouseholdMembership.Create(owner));
        var pending = Apply(issue.State, alice,
            new RedeemHouseholdInvitation(issue.Invitation!.Id, Hash, "482951"));
        var approve = new ApproveHouseholdInvitation(issue.Invitation.Id, pending.Invitation!.Version, "482951");
        Assert.Equal("FORBIDDEN", Apply(pending.State, alice, approve).Code);
        Assert.Equal("CONFIRMATION_MISMATCH",
            Apply(pending.State, owner, approve with { ConfirmationCode = "111111" }).Code);
        Assert.Equal("VERSION_CONFLICT",
            Apply(pending.State, owner, approve with { ExpectedVersion = 0 }).Code);
        var accepted = Apply(pending.State, owner, approve);
        Assert.True(accepted.State.CanRead(alice));
        Assert.Equal("ACCEPTED", Apply(accepted.State, owner, approve).Code);
    }

    [Fact]
    public void ExpiryCancellationAndDeletionPreventJoining()
    {
        var issue = Invite(HouseholdMembership.Create(owner));
        var request = new RedeemHouseholdInvitation(issue.Invitation!.Id, Hash, "482951");
        Assert.Equal("INVITATION_UNAVAILABLE",
            Apply(issue.State, alice, request, Now.AddHours(24)).Code);
        var pending = Apply(issue.State, alice, request);
        Assert.Equal("INVITATION_UNAVAILABLE", Apply(pending.State, owner,
            new ApproveHouseholdInvitation(request.InvitationId, pending.Invitation!.Version, "482951"),
            Now.AddHours(24)).Code);
        var cancel = Apply(pending.State, owner,
            new CancelHouseholdInvitation(request.InvitationId, pending.Invitation.Version));
        Assert.Equal("INVITATION_UNAVAILABLE", Apply(cancel.State, alice, request).Code);
        var deleted = Apply(pending.State, owner, new DeleteHousehold(pending.State.Version));
        Assert.False(deleted.State.CanRead(owner));
        Assert.Equal("INVITATION_UNAVAILABLE", Apply(deleted.State, alice, request).Code);
    }

    [Fact]
    public void OwnerMustTransferToAnActiveMemberBeforeLeaving()
    {
        var state = Join(HouseholdMembership.Create(owner), alice);
        Assert.Equal("OWNER_MUST_TRANSFER",
            Apply(state, owner, new LeaveHousehold(state.Members[owner].Version)).Code);
        Assert.Equal("MEMBER_INACTIVE",
            Apply(state, owner, new TransferHouseholdOwnership(bob, state.Version)).Code);
        Assert.Equal("FORBIDDEN",
            Apply(state, alice, new TransferHouseholdOwnership(alice, state.Version)).Code);
        var transfer = Apply(state, owner, new TransferHouseholdOwnership(alice, state.Version));
        Assert.Equal(alice, transfer.State.OwnerId);
        Assert.True(transfer.State.CanRead(owner));
        var left = Apply(transfer.State, owner, new LeaveHousehold(state.Members[owner].Version));
        Assert.False(left.State.CanRead(owner));
        Assert.True(left.State.CanRead(alice));
    }

    [Fact]
    public void RemovalRevokesReadsAndOldInvitationsCannotReAdmitTheMember()
    {
        var state = Join(HouseholdMembership.Create(owner), alice);
        var approved = state.Invitations.Values.Single();
        var removed = Apply(state, owner,
            new RemoveHouseholdMember(alice, state.Members[alice].Version));
        Assert.False(removed.State.CanRead(alice));
        Assert.Equal("INVITATION_UNAVAILABLE", Apply(removed.State, alice,
            new RedeemHouseholdInvitation(approved.Id, Hash, "482951")).Code);
        Assert.Equal("INVITATION_UNAVAILABLE", Apply(removed.State, owner,
            new ApproveHouseholdInvitation(approved.Id,
                removed.State.Invitations[approved.Id].Version, "482951")).Code);
        Assert.True(Join(removed.State, alice).CanRead(alice));
    }

    [Fact]
    public void AtMostTenActiveMembersAndTenOutstandingInvitations()
    {
        var state = HouseholdMembership.Create(owner);
        for (var i = 0; i < 9; i++) state = Join(state, Guid.NewGuid());
        var extra = Invite(state);
        var pending = Apply(extra.State, alice,
            new RedeemHouseholdInvitation(extra.Invitation!.Id, Hash, "482951"));
        Assert.Equal("MEMBER_LIMIT", Apply(pending.State, owner,
            new ApproveHouseholdInvitation(extra.Invitation.Id, pending.Invitation!.Version, "482951")).Code);
        state = HouseholdMembership.Create(owner);
        for (var i = 0; i < 10; i++) state = Invite(state).State;
        Assert.Equal("INVITATION_LIMIT", Invite(state).Code);
        Assert.Equal("ACCEPTED", Apply(state, owner,
            new IssueHouseholdInvitation(Guid.NewGuid(), Hash), Now.AddHours(24)).Code);
    }

    [Fact]
    public void InvalidRedemptionsConsumeTheRollingHourLimitWithoutBindingAnInvitation()
    {
        var issue = Invite(HouseholdMembership.Create(owner));
        var state = issue.State;
        var wrong = new RedeemHouseholdInvitation(issue.Invitation!.Id, new string('0', 64), "482951");
        for (var i = 0; i < 10; i++) {
            var failure = Apply(state, alice, wrong);
            Assert.Equal("INVITATION_UNAVAILABLE", failure.Code);
            state = failure.State;
        }
        Assert.Null(state.Invitations[issue.Invitation.Id].CandidateId);
        Assert.Equal("REDEMPTION_LIMIT", Apply(state, alice, wrong with { SecretHash = Hash }).Code);
        Assert.Equal("ACCEPTED",
            Apply(state, alice, wrong with { SecretHash = Hash }, Now.AddHours(1)).Code);
    }
}
