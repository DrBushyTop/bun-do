using System.Text.Json;
using BunDo.Domain;

namespace BunDo.Functions.Households;

/// <summary>Content growth cannot spend the capacity needed to revoke access.</summary>
public static class HouseholdDocumentLimits
{
    public const int GrowthBytes = 1024 * 1024;
    // Below Cosmos's item ceiling, including its envelope. Only bounded cleanup can enter this reserve.
    public const int WorkspaceDocumentBytes = GrowthBytes + 256 * 1024;

    public static bool Admits(WorkspaceState next, MembershipCommand command) =>
        command is CancelHouseholdInvitation or RemoveHouseholdMember or LeaveHousehold or DeleteHousehold ||
        JsonSerializer.SerializeToUtf8Bytes(next).Length <= GrowthBytes;

    public static void CheckEncodedSize<T>(T value, int bytes)
    {
        var maximum = value is WorkspaceState ? WorkspaceDocumentBytes : GrowthBytes;
        if (bytes > maximum) throw new HouseholdStorageFullException();
    }
}
