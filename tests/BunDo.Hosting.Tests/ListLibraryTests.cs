#if DEBUG
using BunDo.Domain;
using BunDo.Functions.Adventures;
using BunDo.Functions.Households;
using BunDo.Functions.Identity.Development;
using BunDo.Functions.Sync;

namespace BunDo.Hosting.Tests;

public sealed class ListLibraryTests
{
    [Fact]
    public async Task Shared_library_survives_task_writes_and_rejects_stale_edits_and_other_members()
    {
        var path = Path.Combine(Path.GetTempPath(), "bundo-lists-" + Guid.NewGuid());
        try
        {
            var documents = new LocalHouseholdDocuments(path);
            var member = Guid.NewGuid(); var workspace = Guid.NewGuid(); var device = Guid.NewGuid();
            var epoch = (await new HouseholdService(documents).CreateAsync(member, workspace, default)).Household!.StateEpoch;
            var service = new ListLibraryService(documents, _ => Task.FromResult(true));
            var value = new SavedList(Guid.NewGuid(), "Packing", null, [new("Keys", "Spare set")]);
            var command = new SaveList(Guid.NewGuid(), 0, value.Id, value);
            var first = await service.SendAsync(member, workspace, epoch, command, default);
            Assert.Equal(1ul, first.Library.Version);
            Assert.Equal(first.Library.Version, (await service.SendAsync(member, workspace, epoch, command, default)).Library.Version);
            await new SyncService(documents).SubmitAsync(member, new(workspace, epoch, device, 1, new CreateTask(TaskIdentity.ForCreate(device, 1), "Separate task")), default);
            Assert.Single((await service.SendAsync(member, workspace, epoch, null, default)).Library.Lists);
            var stale = await Assert.ThrowsAsync<SyncException>(() => service.SendAsync(member, workspace, epoch, command with { OperationId = Guid.NewGuid() }, default));
            Assert.Equal("LIST_CHANGED", stale.Code);
            Assert.Equal("FORBIDDEN", (await Assert.ThrowsAsync<SyncException>(() => service.SendAsync(Guid.NewGuid(), workspace, epoch, null, default))).Code);
            Assert.Equal("EPOCH_CHANGED", (await Assert.ThrowsAsync<SyncException>(() => service.SendAsync(member, workspace, Guid.NewGuid(), null, default))).Code);
            var retired = new ListLibraryService(documents, _ => Task.FromResult(false));
            Assert.Equal("REGISTRATION_RETIRED", (await Assert.ThrowsAsync<SyncException>(() => retired.SendAsync(member, workspace, epoch, null, default))).Code);
            await service.SendAsync(member, workspace, epoch, new(Guid.NewGuid(), 1, value.Id, null), default);
            Assert.Empty((await service.SendAsync(member, workspace, epoch, null, default)).Library.Lists);
        }
        finally { if (Directory.Exists(path)) Directory.Delete(path, true); }
    }
}
#endif
