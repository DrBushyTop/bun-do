using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class OutcomeLookupTests
{
    [Fact]
    public void LookupDistinguishesCommittedRejectedExpiredAndUnseenWithoutExecutingAnything()
    {
        var member = Guid.NewGuid();
        var device = Guid.NewGuid();
        var store = new InMemoryWorkspaceStore(Guid.NewGuid(), Guid.NewGuid(), HouseholdMembership.Create(member),
            [new(device, member, LastTerminalSequence: 1)]);
        var server = new WorkspaceServer(store);
        var state = store.Read();
        FrozenOperation Create(ulong sequence, string title) => new(state.WorkspaceId, state.StateEpoch, device,
            sequence, new CreateTask(TaskIdentity.ForCreate(device, sequence), title));
        var accepted = server.Handle(member, Create(2, "Saved"));
        var rejected = server.Handle(member, Create(3, ""));
        var before = store.Read();
        var page = server.Outcomes(member, state.StateEpoch, device, 1, 4);
        Assert.Equal("ACCEPTED", page.Code);
        Assert.Equal(3UL, page.HighWater);
        Assert.Equal(["OUTCOME_EXPIRED", "ACCEPTED", "REJECTED", "NOT_SEEN"], page.Outcomes.Select(x => x.State));
        Assert.Equal(accepted.Receipt!.Fingerprint, page.Outcomes[1].Fingerprint);
        Assert.Equal(rejected.Receipt!.EffectRevision, page.Outcomes[2].EffectRevision);
        Assert.Equal("INVALID_TITLE", page.Outcomes[2].Code);
        Assert.Null(page.Outcomes[0].Fingerprint);
        Assert.Equal(before, store.Read());
    }

    [Fact]
    public void LookupChecksEpochMembershipDeviceOwnerAndRangeBeforeReturningReceipts()
    {
        var member = Guid.NewGuid();
        var device = Guid.NewGuid();
        var store = new InMemoryWorkspaceStore(Guid.NewGuid(), Guid.NewGuid(), HouseholdMembership.Create(member),
            [new(device, member)]);
        var server = new WorkspaceServer(store);
        var epoch = store.Read().StateEpoch;
        Assert.Equal("FORBIDDEN", server.Outcomes(Guid.NewGuid(), epoch, device, 1, 1).Code);
        Assert.Equal("EPOCH_CHANGED", server.Outcomes(member, Guid.NewGuid(), device, 1, 1).Code);
        Assert.Equal("DEVICE_UNKNOWN", server.Outcomes(member, epoch, Guid.NewGuid(), 1, 1).Code);
        foreach (var (first, count) in new[] { (0UL, 1), (1UL, 0), (1UL, 101), (ulong.MaxValue, 2) })
            Assert.Equal("INVALID_RANGE", server.Outcomes(member, epoch, device, first, count).Code);
        Assert.Equal("NOT_SEEN", server.Outcomes(member, epoch, device, ulong.MaxValue, 1).Outcomes.Single().State);
    }
}
