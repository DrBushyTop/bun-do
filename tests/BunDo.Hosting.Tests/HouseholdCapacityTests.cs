using System.Collections.Immutable;
using System.Text.Json;
using BunDo.Domain;
using BunDo.Functions.Households;
#if DEBUG
using BunDo.Functions.Identity.Development;
#endif

namespace BunDo.Hosting.Tests;

public sealed class HouseholdCapacityTests
{
    private static readonly Guid Owner = Guid.NewGuid();
    private static readonly Guid Member = Guid.NewGuid();
    private static readonly Guid Invitation = Guid.NewGuid();

    private static WorkspaceState NearCapacity()
    {
        var membership = HouseholdMembership.Create(Owner, 1, "Owner");
        var history = Enumerable.Range(0, 2200).Select(_ => new HouseholdInvitation(Guid.NewGuid(), new string('A', 64),
            DateTimeOffset.UtcNow.AddHours(24), 1, InvitationPhase.Cancelled, Member, "123456", "Member"))
            .ToImmutableDictionary(x => x.Id);
        membership = membership with {
            Members = membership.Members.Add(Member, new(Member, 2, DisplayName: "Member")),
            Invitations = history.Add(Invitation, new(Invitation, new string('B', 64), DateTimeOffset.UtcNow.AddHours(24), 1)),
        };
        var state = new WorkspaceState(Guid.NewGuid(), Guid.NewGuid(), 100_000,
            ImmutableDictionary<Guid, DeviceRegistration>.Empty, ImmutableDictionary<string, TaskSnapshot>.Empty,
            ImmutableDictionary<string, OperationReceipt>.Empty, [], membership, "Full household");
        // Complete, contiguous empty change groups fill the remaining bytes without invalid oversized fields.
        var low = 0;
        var high = 40_000;
        while (low < high)
        {
            var count = (low + high + 1) / 2;
            var candidate = state with { Changes = Enumerable.Range(1, count)
                .Select(i => new ChangeGroup((ulong)i, [])).ToImmutableArray(), Revision = (ulong)count };
            if (JsonSerializer.SerializeToUtf8Bytes(candidate).Length < HouseholdDocumentLimits.GrowthBytes - 256)
                low = count;
            else high = count - 1;
        }
        return state with { Changes = Enumerable.Range(1, low).Select(i => new ChangeGroup((ulong)i, [])).ToImmutableArray(),
            Revision = (ulong)low };
    }

    [Fact]
    public void BothAdaptersShareACleanupReserveBelowTheHardDocumentBound()
    {
        var state = NearCapacity();
        Assert.InRange(JsonSerializer.SerializeToUtf8Bytes(state).Length,
            HouseholdDocumentLimits.GrowthBytes - 512, HouseholdDocumentLimits.GrowthBytes);
        var decision = MembershipPolicy.Apply(state.Membership, Owner, new DeleteHousehold(1), DateTimeOffset.UtcNow,
            state.Revision + 1);
        var deleted = state with { Membership = decision.State, Revision = state.Revision + 1,
            Changes = state.Changes.Add(new(state.Revision + 1, [])) };
        var size = JsonSerializer.SerializeToUtf8Bytes(deleted).Length;
        Assert.True(size > HouseholdDocumentLimits.GrowthBytes); // Reproduces the former unconditional adapter rejection.
        Assert.True(HouseholdDocumentLimits.Admits(deleted, new DeleteHousehold(1)));
        HouseholdDocumentLimits.CheckEncodedSize(deleted, size + 256); // Includes either adapter's envelope.
        Assert.False(HouseholdDocumentLimits.Admits(deleted, new TransferHouseholdOwnership(Member, 1)));
        Assert.Throws<HouseholdStorageFullException>(() => HouseholdDocumentLimits.CheckEncodedSize(
            deleted, HouseholdDocumentLimits.WorkspaceDocumentBytes + 1));
    }

#if DEBUG
    [Fact]
    public async Task FullLocalHouseholdRejectsGrowthButCanCancelRemoveAndDelete()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        try
        {
            var state = NearCapacity();
            var documents = new LocalHouseholdDocuments(directory);
            Assert.True(await documents.WriteAsync(state.WorkspaceId.ToString("D"), "state", null, state, default));
            var service = new HouseholdService(documents);
            Assert.Equal("STORAGE_FULL", (await service.IssueAsync(Owner, state.WorkspaceId, state.StateEpoch, default)).Code);
            Assert.Equal("ACCEPTED", (await service.ChangeAsync(Owner, state.WorkspaceId, state.StateEpoch,
                new CancelHouseholdInvitation(Invitation, 1), default)).Code);
            Assert.Equal("ACCEPTED", (await service.ChangeAsync(Owner, state.WorkspaceId, state.StateEpoch,
                new RemoveHouseholdMember(Member, 2), default)).Code);
            Assert.Null(await service.GetAsync(Member, state.WorkspaceId, default));
            Assert.Equal("ACCEPTED", (await service.ChangeAsync(Owner, state.WorkspaceId, state.StateEpoch,
                new DeleteHousehold(1), default)).Code);
            Assert.False((await service.GetAsync(Owner, state.WorkspaceId, default))!.Active);
            Assert.True(new FileInfo(Directory.GetFiles(directory).Single()).Length > HouseholdDocumentLimits.GrowthBytes);
        }
        finally { Directory.Delete(directory, true); }
    }
#endif
}
