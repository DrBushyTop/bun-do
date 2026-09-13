using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class MembershipConcurrencyTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-09-13T10:00:00Z");
    private readonly Guid workspace = Guid.NewGuid();
    private readonly Guid epoch = Guid.NewGuid();
    private readonly Guid owner = Guid.NewGuid();
    private readonly Guid candidate = Guid.NewGuid();
    private readonly Guid device = Guid.NewGuid();
    private const string Hash = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    private InMemoryWorkspaceStore Store() => new(workspace, epoch, HouseholdMembership.Create(owner),
        [new(device, candidate)]);

    private HouseholdInvitation Pending(WorkspaceServer server)
    {
        var issued = server.ChangeMembership(owner, epoch,
            new IssueHouseholdInvitation(Guid.NewGuid(), Hash));
        Assert.Equal("ACCEPTED", issued.Code);
        return server.ChangeMembership(candidate, epoch,
            new RedeemHouseholdInvitation(issued.Invitation!.Id, Hash, "482951")).Invitation!;
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void RemovalAndTaskMutationHaveOneOrderIncludingReceiptRetries(bool removalWins)
    {
        var store = Store();
        var server = new WorkspaceServer(store, new TestClock());
        var pending = Pending(server);
        Assert.Equal("ACCEPTED", server.ChangeMembership(owner, epoch,
            new ApproveHouseholdInvitation(pending.Id, pending.Version, "482951")).Code);
        void Remove() => Assert.Equal("ACCEPTED", server.ChangeMembership(owner, epoch,
            new RemoveHouseholdMember(candidate, store.Read().Membership.Members[candidate].Version)).Code);
        var operation = new FrozenOperation(workspace, epoch, device, 1,
            new CreateTask(TaskIdentity.ForCreate(device, 1), "Candidate task"));
        var writer = removalWins
            ? new WorkspaceServer(new BeforeCommitStore(store, Remove), new TestClock())
            : server;
        var result = writer.Handle(candidate, operation);
        if (!removalWins) Remove();
        Assert.Equal(removalWins ? "FORBIDDEN" : "ACCEPTED", result.Code);
        Assert.Equal(removalWins ? 0 : 1, store.Read().Tasks.Count);
        Assert.Equal("FORBIDDEN", server.Handle(candidate, operation).Code);
        Assert.Throws<UnauthorizedAccessException>(() => server.Pull(candidate, 0));
        Assert.Equal(store.Read().Revision, server.Pull(owner, 0).HeadRevision);
    }

    [Fact]
    public void CancellationWinningTheCasCannotBeOverwrittenByAnApproval()
    {
        var store = Store();
        var server = new WorkspaceServer(store, new TestClock());
        var pending = Pending(server);
        var racing = new WorkspaceServer(new BeforeCommitStore(store, () =>
            Assert.Equal("ACCEPTED", server.ChangeMembership(owner, epoch,
                new CancelHouseholdInvitation(pending.Id, pending.Version)).Code)), new TestClock());
        var approval = racing.ChangeMembership(owner, epoch,
            new ApproveHouseholdInvitation(pending.Id, pending.Version, "482951"));
        Assert.Equal("VERSION_CONFLICT", approval.Code);
        Assert.False(store.Read().Membership.CanRead(candidate));
        Assert.Equal(InvitationPhase.Cancelled, store.Read().Membership.Invitations[pending.Id].Phase);
    }

    [Fact]
    public void FailedRedemptionAttemptsPersistWithoutConsumingTaskSequences()
    {
        var store = Store();
        var server = new WorkspaceServer(store, new TestClock());
        var missing = new RedeemHouseholdInvitation(Guid.NewGuid(), Hash, "482951");
        for (var i = 0; i < 10; i++)
            Assert.Equal("INVITATION_UNAVAILABLE",
                server.ChangeMembership(candidate, epoch, missing).Code);
        Assert.Equal("REDEMPTION_LIMIT",
            server.ChangeMembership(candidate, epoch, missing).Code);
        Assert.Equal(0UL, store.Read().Devices[device].LastTerminalSequence);
        Assert.Empty(store.Read().Receipts);
        Assert.All(server.Pull(owner, 0).Groups, group => Assert.Empty(group.Tasks));
    }

    [Fact]
    public void ACompareAndSwapRetryRechecksExpiryRatherThanReusingTheRequestStartTime()
    {
        var store = Store();
        var clock = new TestClock();
        var server = new WorkspaceServer(store, clock);
        var pending = Pending(server);
        var racing = new WorkspaceServer(new BeforeCommitStore(store, () => {
            // An unrelated committed change forces approval to reconsider the newer state.
            Assert.Equal("ACCEPTED", server.ChangeMembership(owner, epoch,
                new IssueHouseholdInvitation(Guid.NewGuid(), Hash)).Code);
            clock.Current = Now.AddHours(24);
        }), clock);
        Assert.Equal("INVITATION_UNAVAILABLE", racing.ChangeMembership(owner, epoch,
            new ApproveHouseholdInvitation(pending.Id, pending.Version, "482951")).Code);
        Assert.False(store.Read().Membership.CanRead(candidate));
    }

    private sealed class TestClock : TimeProvider
    {
        public DateTimeOffset Current { get; set; } = Now;
        public override DateTimeOffset GetUtcNow() => Current;
    }

    private sealed class BeforeCommitStore(IWorkspaceStore inner, Action beforeFirstCommit) : IWorkspaceStore
    {
        private bool invoked;
        public WorkspaceState Read() => inner.Read();
        public bool TryCommit(ulong expectedRevision, WorkspaceState next)
        {
            if (!invoked) {
                invoked = true;
                beforeFirstCommit();
            }
            return inner.TryCommit(expectedRevision, next);
        }
    }
}
