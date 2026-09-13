using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Serialization;
using BunDo.Domain;

namespace BunDo.Functions.Households;

// These are deliberately separate from persistence records. Never return secret hashes or other candidates.
public sealed record MemberView(Guid Id, [property: JsonNumberHandling(JsonNumberHandling.WriteAsString)] ulong Version, bool Owner, string DisplayName);
public sealed record InvitationView(Guid Id, [property: JsonNumberHandling(JsonNumberHandling.WriteAsString)] ulong Version, string Phase, DateTimeOffset ExpiresAt,
    Guid? CandidateId, string? ConfirmationCode, string CandidateName);
public sealed record HouseholdView(Guid Id, Guid StateEpoch,
    [property: JsonNumberHandling(JsonNumberHandling.WriteAsString)] ulong Revision,
    [property: JsonNumberHandling(JsonNumberHandling.WriteAsString)] ulong MembershipVersion,
    Guid Me, bool Owner, bool Active, MemberView[] Members, InvitationView[] Invitations,
    string Name, DateTimeOffset? DeletedAt);
public sealed record HouseholdReply(string Code, HouseholdView? Household = null, string? InvitationLink = null);

public sealed class HouseholdService(IHouseholdDocuments documents, TimeProvider? timeProvider = null, string invitationUrl = "bundo://join")
{
    private readonly TimeProvider clock = timeProvider ?? TimeProvider.System;
    private static string Partition(Guid workspace) => workspace.ToString("D");

    public async Task<HouseholdView[]> ListAsync(Guid actor, CancellationToken cancellationToken)
    {
        var directory = await documents.ReadAsync<HouseholdDirectory>(HouseholdIdentity.Partition(actor), "directory", cancellationToken);
        var views = new List<HouseholdView>();
        foreach (var workspace in directory?.Value.Workspaces ?? [])
        {
            var view = await GetAsync(actor, workspace, cancellationToken);
            if (view is not null) views.Add(view);
        }
        return views.ToArray();
    }

    public async Task<HouseholdView?> GetAsync(Guid actor, Guid workspace, CancellationToken cancellationToken)
    {
        var stored = await documents.ReadAsync<WorkspaceState>(Partition(workspace), "state", cancellationToken);
        return stored is null ? null : View(stored.Value, actor);
    }

    public async Task<HouseholdReply> CreateAsync(Guid actor, Guid workspace, CancellationToken cancellationToken,
        string name = "Household", string displayName = "")
    {
        name = name.Trim();
        if (actor == Guid.Empty || workspace == Guid.Empty || name.Length is < 1 or > 80 || name.Any(char.IsControl))
            return new("INVALID_REQUEST");
        // Write discovery before creation. A crash can leave a harmless reference, never an undiscoverable household.
        await RememberAsync(actor, workspace, false, cancellationToken);
        var current = await documents.ReadAsync<WorkspaceState>(Partition(workspace), "state", cancellationToken);
        if (current is null)
        {
            var state = new WorkspaceState(workspace, Guid.NewGuid(), 0,
                ImmutableDictionary<Guid, DeviceRegistration>.Empty,
                ImmutableDictionary<string, TaskSnapshot>.Empty,
                ImmutableDictionary<string, OperationReceipt>.Empty, [], HouseholdMembership.Create(actor, displayName: displayName), name);
            await documents.WriteAsync(Partition(workspace), "state", null, state, cancellationToken);
            current = await documents.ReadAsync<WorkspaceState>(Partition(workspace), "state", cancellationToken);
        }
        return current?.Value.Membership.OwnerId == actor && current.Value.Membership.CanRead(actor)
            ? new("ACCEPTED", View(current.Value, actor)) : new("FORBIDDEN");
    }

    public async Task<HouseholdReply> IssueAsync(Guid actor, Guid workspace, Guid epoch, CancellationToken cancellationToken)
    {
        var invitation = Guid.NewGuid();
        var secret = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var reply = await ChangeAsync(actor, workspace, epoch,
            new IssueHouseholdInvitation(invitation, Hash(secret)), cancellationToken);
        // No reusable secret at rest. If delivery fails, the owner cancels that invite and creates another.
        return reply.Code == "ACCEPTED" ? reply with {
            InvitationLink = $"{invitationUrl}#{workspace:D}/{epoch:D}/{invitation:D}/{secret}",
        } : reply;
    }

    public async Task<HouseholdReply> RedeemAsync(Guid actor, Guid workspace, Guid epoch, Guid invitation,
        string secret, CancellationToken cancellationToken, string displayName = "")
    {
        // Admission is identity-scoped, including missing/wrong workspaces and malformed secrets.
        if (!await RememberAsync(actor, null, true, cancellationToken)) return new("REDEMPTION_LIMIT");
        if (secret.Length != 64 || !secret.All(char.IsAsciiHexDigitUpper)) return new("INVITATION_UNAVAILABLE");
        // Retrying after a crash can always find the candidate's bound invitation.
        await RememberAsync(actor, workspace, false, cancellationToken);
        var code = RandomNumberGenerator.GetInt32(1_000_000).ToString("D6", System.Globalization.CultureInfo.InvariantCulture);
        return await ChangeAsync(actor, workspace, epoch,
            new RedeemHouseholdInvitation(invitation, Hash(secret), code, displayName), cancellationToken);
    }

    public async Task<HouseholdReply> ChangeAsync(Guid actor, Guid workspace, Guid epoch,
        MembershipCommand command, CancellationToken cancellationToken)
    {
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var stored = await documents.ReadAsync<WorkspaceState>(Partition(workspace), "state", cancellationToken);
            if (stored is null) return new("FORBIDDEN");
            var state = stored.Value;
            // Do not disclose the current epoch to a removed user or an unknown candidate.
            if (command is not RedeemHouseholdInvitation && !state.Membership.CanRead(actor)) return new("FORBIDDEN");
            if (state.StateEpoch != epoch) return new("EPOCH_CHANGED");
            var revision = checked(state.Revision + 1);
            var decision = MembershipPolicy.Apply(state.Membership, actor, command, clock.GetUtcNow(), revision);
            if (decision.State == state.Membership) return new(decision.Code, View(state, actor));
            var next = state with { Revision = revision, Membership = decision.State,
                Changes = state.Changes.Add(new(revision, [])) };
            if (!HouseholdDocumentLimits.Admits(next, command)) return new("STORAGE_FULL", View(state, actor));
            if (await documents.WriteAsync(Partition(workspace), "state", stored.Version, next, cancellationToken))
                return new(decision.Code, View(next, actor));
        }
        return new("BUSY");
    }

    private async Task<bool> RememberAsync(Guid actor, Guid? workspace, bool redemption, CancellationToken cancellationToken)
    {
        var partition = HouseholdIdentity.Partition(actor);
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var stored = await documents.ReadAsync<HouseholdDirectory>(partition, "directory", cancellationToken);
            var state = stored?.Value ?? HouseholdDirectory.Empty;
            var now = clock.GetUtcNow();
            var recent = state.Attempts.Where(t => t > now.AddHours(-1)).ToImmutableArray();
            if (redemption && recent.Length >= 10) return false;
            var next = state with { Workspaces = workspace is { } id ? state.Workspaces.Add(id) : state.Workspaces,
                Attempts = redemption ? recent.Add(now) : recent };
            if (await documents.WriteAsync(partition, "directory", stored?.Version, next, cancellationToken)) return true;
        }
        throw new InvalidOperationException("Household discovery contention exceeded retry bound.");
    }

    private HouseholdView? View(WorkspaceState state, Guid actor)
    {
        var membership = state.Membership;
        if (membership.DeletedAt is not null)
            return actor == membership.OwnerId
                ? new(state.WorkspaceId, state.StateEpoch, state.Revision, membership.Version,
                    actor, true, false, [], [], state.Name, membership.DeletedAt)
                : null;
        var former = membership.Members.TryGetValue(actor, out var member) && !member.Active;
        var active = membership.CanRead(actor);
        var owner = active && membership.OwnerId == actor;
        var invitations = membership.Invitations.Values
            .Where(x => owner || x.CandidateId == actor && (!former || x.Phase == InvitationPhase.Pending))
            .Select(x => new InvitationView(x.Id, x.Version,
                x.ExpiresAt <= clock.GetUtcNow() && x.Phase is InvitationPhase.Issued or InvitationPhase.Pending
                    ? "Expired" : x.Phase.ToString(), x.ExpiresAt, x.CandidateId, x.ConfirmationCode, x.CandidateName)).ToArray();
        if (!active && invitations.Length == 0) return null;
        return new(state.WorkspaceId, state.StateEpoch, state.Revision, membership.Version, actor, owner, active,
            active ? membership.Members.Values.Where(x => x.Active)
                .Select(x => new MemberView(x.Id, x.Version, x.Id == membership.OwnerId, x.DisplayName)).ToArray() : [], invitations,
            active ? state.Name : "", null);
    }

    private static string Hash(string secret) => Convert.ToHexString(SHA256.HashData(Encoding.ASCII.GetBytes(secret)));
}
