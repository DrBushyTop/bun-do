using System.Text.Json;
using System.Text.Json.Nodes;
using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class TaskLifecycleTests
{
    private readonly Guid workspace = Guid.NewGuid();
    private readonly Guid epoch = Guid.NewGuid();
    private readonly Guid alice = Guid.NewGuid();
    private readonly Guid bob = Guid.NewGuid();
    private readonly Guid deviceA = Guid.NewGuid();
    private readonly Guid deviceB = Guid.NewGuid();

    [Fact]
    public void A_member_can_claim_a_task_through_the_typed_wire_command()
    {
        var store = Store();
        var server = new WorkspaceServer(store);
        var id = TaskIdentity.ForCreate(deviceA, 1);
        server.Handle(alice, new(workspace, epoch, deviceA, 1, new CreateTask(id, "Wash the dishes")));

        var result = server.Handle(alice, Action(deviceA, 2, "ClaimTask", id, 1, 1));

        Assert.Equal("ACCEPTED", result.Code);
        Assert.Equal(alice, JsonSerializer.SerializeToElement(result.Receipt!.Task).GetProperty("ClaimantId").GetGuid());
    }

    [Fact]
    public void Racing_claims_and_confirmed_completion_keep_exact_observations_and_first_credit()
    {
        var store = Store();
        var clock = new TestClock();
        var server = new WorkspaceServer(store, clock);
        var id = TaskIdentity.ForCreate(deviceA, 1);
        server.Handle(alice, new(workspace, epoch, deviceA, 1, new CreateTask(id, "Wash the dishes")));
        Assert.Equal("ACCEPTED", server.Handle(alice, Action(deviceA, 2, "ClaimTask", id, 1, 1)).Code);
        Assert.Equal("CLAIM_CONFLICT", server.Handle(bob, Action(deviceB, 1, "ClaimTask", id, 1, 1)).Code);
        Assert.Equal("ALREADY_CLAIMED", server.Handle(bob, Action(deviceB, 2, "ClaimTask", id, 1, 2)).Code);
        Assert.Equal("CLAIM_CONFIRMATION_REQUIRED", server.Handle(bob, Action(deviceB, 3, "CompleteTask", id, 1, 2)).Code);
        var completed = server.Handle(bob, Action(deviceB, 4, "CompleteTask", id, 1, 2, alice));
        Assert.Equal("ACCEPTED", completed.Code);
        Assert.Equal("COMPLETED", completed.Receipt!.Task!.Lifecycle);
        var first = JsonSerializer.SerializeToElement(completed.Receipt.Task).GetProperty("FirstCompletion");
        Assert.Equal(id, first.GetProperty("RootId").GetString());
        Assert.Equal(bob, first.GetProperty("MemberId").GetGuid());
        Assert.Equal(clock.Now, first.GetProperty("AcceptedAt").GetDateTimeOffset());
        Assert.Equal("LIFECYCLE_CONFLICT", server.Handle(alice, Action(deviceA, 3, "CompleteTask", id, 1, 2, alice)).Code);
        Assert.Equal("ACCEPTED", server.Handle(alice, Action(deviceA, 4, "ReopenTask", id, 6, 6)).Code);
        Assert.Equal("LIFECYCLE_CONFLICT", server.Handle(bob, Action(deviceB, 5, "CompleteTask", id, 6, 6)).Code);
        clock.Now = clock.Now.AddDays(8);
        var again = server.Handle(bob, Action(deviceB, 6, "CompleteTask", id, 8, 6));
        Assert.Equal("ACCEPTED", again.Code);
        Assert.Equal(first.GetRawText(), JsonSerializer.SerializeToElement(again.Receipt!.Task).GetProperty("FirstCompletion").GetRawText());
    }

    [Fact]
    public void Completion_needs_no_claim_and_same_state_commands_do_not_repeat_effects()
    {
        var store = Store();
        var server = new WorkspaceServer(store);
        var id = TaskIdentity.ForCreate(deviceA, 1);
        server.Handle(alice, new(workspace, epoch, deviceA, 1, new CreateTask(id, "Take out the bins")));
        var operation = Action(deviceB, 1, "CompleteTask", id, 1, 1);
        var first = server.Handle(bob, operation);
        Assert.Equal("ACCEPTED", first.Code);
        Assert.Equal(first, new WorkspaceServer(store).Handle(bob, operation));
        var repeat = server.Handle(alice, Action(deviceA, 2, "CompleteTask", id, 2, 1));
        Assert.Equal(first.Receipt!.Task, repeat.Receipt!.Task);
        Assert.Empty(store.Read().Changes.Last().Tasks);
        Assert.Equal("ACCEPTED", server.Handle(alice, Action(deviceA, 3, "CancelTask", id, 2, 1)).Code);
        Assert.Equal("CANCELLED", store.Read().Tasks[id].Lifecycle);
        Assert.Equal("ACCEPTED", server.Handle(alice, Action(deviceA, 4, "ReopenTask", id, 4, 1)).Code);
        Assert.Equal("OPEN", store.Read().Tasks[id].Lifecycle);
    }

    [Fact]
    public void Only_the_claimant_or_owner_can_release_an_active_claim()
    {
        var store = Store();
        var server = new WorkspaceServer(store);
        var id = TaskIdentity.ForCreate(deviceA, 1);
        server.Handle(alice, new(workspace, epoch, deviceA, 1, new CreateTask(id, "Cook dinner")));
        server.Handle(alice, Action(deviceA, 2, "ClaimTask", id, 1, 1));
        Assert.Equal("CLAIM_NOT_YOURS", server.Handle(bob, Action(deviceB, 1, "UnclaimTask", id, 1, 2)).Code);
        Assert.Equal("ACCEPTED", server.Handle(alice, Action(deviceA, 3, "UnclaimTask", id, 1, 2)).Code);
        Assert.Equal("ACCEPTED", server.Handle(bob, Action(deviceB, 2, "ClaimTask", id, 1, 4)).Code);
        Assert.Equal("ACCEPTED", server.Handle(alice, Action(deviceA, 4, "UnclaimTask", id, 1, 5)).Code);
        Assert.Null(store.Read().Tasks[id].ClaimantId);
    }

    [Fact]
    public void Removed_members_claims_are_available_without_cascading_task_writes()
    {
        var store = Store();
        var server = new WorkspaceServer(store);
        var id = TaskIdentity.ForCreate(deviceA, 1);
        server.Handle(alice, new(workspace, epoch, deviceA, 1, new CreateTask(id, "Water the plants")));
        server.Handle(bob, Action(deviceB, 1, "ClaimTask", id, 1, 1));
        Assert.Equal("ACCEPTED", server.ChangeMembership(alice, epoch, new RemoveHouseholdMember(bob, 0)).Code);
        Assert.Equal(bob, store.Read().Tasks[id].ClaimantId);
        Assert.Equal("ACCEPTED", server.Handle(alice, Action(deviceA, 2, "ClaimTask", id, 1, 2)).Code);
        Assert.Equal(alice, store.Read().Tasks[id].ClaimantId);
        Assert.Equal("FORBIDDEN", server.Handle(bob, Action(deviceB, 2, "CompleteTask", id, 1, 4, alice)).Code);
    }

    [Fact]
    public void Root_moves_resolve_live_anchors_in_acceptance_order_but_reject_stale_moves_of_the_same_task()
    {
        var store = Store();
        var server = new WorkspaceServer(store);
        var ids = Enumerable.Range(1, 3).Select(n => TaskIdentity.ForCreate(deviceA, (ulong)n)).ToArray();
        for (var n = 0; n < 3; n++)
            server.Handle(alice, new(workspace, epoch, deviceA, (ulong)n + 1, new CreateTask(ids[n], $"Task {n}")));
        Assert.Equal("ACCEPTED", server.Handle(alice, Move(deviceA, 4, ids[2], 3, 3, ids[0], ids[1])).Code);
        Assert.Equal(new[] { ids[0], ids[2], ids[1] }, RootOrder(store));
        Assert.Equal("ACCEPTED", server.Handle(bob, Move(deviceB, 1, ids[1], 2, 2, ids[0], ids[2])).Code);
        Assert.Equal(ids, RootOrder(store));
        Assert.Equal("ORDER_CONFLICT", server.Handle(alice, Move(deviceA, 5, ids[2], 3, 3, null, ids[0])).Code);
        Assert.Equal(ids, RootOrder(store));
        Assert.Equal("ACCEPTED", server.Handle(bob, Move(deviceB, 2, ids[0], 1, 1, Guid.NewGuid().ToString(), Guid.NewGuid().ToString())).Code);
        Assert.Equal(new[] { ids[1], ids[2], ids[0] }, RootOrder(store));
        Assert.Equal("ACCEPTED", server.Handle(alice, Move(deviceA, 6, ids[2], 4, 3, ids[0], ids[1])).Code);
        Assert.Equal(new[] { ids[1], ids[0], ids[2] }, RootOrder(store));
    }

    [Fact]
    public void Completion_removes_a_root_from_active_order_and_reopen_appends_it()
    {
        var store = Store();
        var server = new WorkspaceServer(store);
        var id = TaskIdentity.ForCreate(deviceA, 1);
        var other = TaskIdentity.ForCreate(deviceA, 2);
        server.Handle(alice, new(workspace, epoch, deviceA, 1, new CreateTask(id, "First")));
        server.Handle(alice, new(workspace, epoch, deviceA, 2, new CreateTask(other, "Second")));
        Assert.Equal("ACCEPTED", server.Handle(alice, Action(deviceA, 3, "CompleteTask", id, 1, 1)).Code);
        Assert.Equal(new[] { other }, RootOrder(store));
        Assert.Equal("TASK_NOT_OPEN", server.Handle(bob, Move(deviceB, 1, id, 1, 1, other, null)).Code);
        Assert.Equal("ACCEPTED", server.Handle(alice, Action(deviceA, 4, "ReopenTask", id, 3, 1)).Code);
        Assert.Equal(new[] { other, id }, RootOrder(store));
    }

    private static string[] RootOrder(InMemoryWorkspaceStore store) =>
        JsonSerializer.SerializeToElement(store.Read()).GetProperty("RootOrder").EnumerateArray().Select(x => x.GetString()!).ToArray();

    private sealed class TestClock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = DateTimeOffset.Parse("2026-09-13T12:00:00Z");
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private InMemoryWorkspaceStore Store()
    {
        var members = HouseholdMembership.Create(alice);
        members = members with { Members = members.Members.Add(bob, new(bob, 0, DisplayName: "Bob")) };
        return new(workspace, epoch, members, [new(deviceA, alice), new(deviceB, bob)]);
    }

    private FrozenOperation Action(Guid device, ulong sequence, string kind, string id,
        ulong lifecycle, ulong claim, Guid? confirmed = null)
    {
        var payload = new JsonObject { ["taskId"] = id };
        if (kind == "CompleteTask") payload["confirmedClaimantId"] = confirmed?.ToString("D");
        var observed = new JsonObject();
        foreach (var (group, version) in new[] { ("lifecycle", lifecycle), ("claim", claim), ("hierarchy", 1UL), ("deletion", 1UL) })
            observed[group] = new JsonObject { ["fieldVersion"] = version.ToString() };
        return Wire(device, sequence, kind, payload, observed);
    }

    private FrozenOperation Move(Guid device, ulong sequence, string id, ulong order, ulong deletion, string? after, string? before)
    {
        var payload = new JsonObject { ["taskId"] = id, ["expectedParentId"] = null, ["afterTaskId"] = after, ["beforeTaskId"] = before };
        var observed = new JsonObject {
            ["orderIntent"] = new JsonObject { ["fieldVersion"] = order.ToString() },
            ["deletion"] = new JsonObject { ["fieldVersion"] = deletion.ToString() },
        };
        return Wire(device, sequence, "MoveTask", payload, observed);
    }

    private FrozenOperation Wire(Guid device, ulong sequence, string kind, JsonObject payload, JsonObject observed)
    {
        var body = new JsonObject {
            ["protocolVersion"] = 1, ["commandVersion"] = 1, ["workspaceId"] = workspace.ToString("D"),
            ["stateEpoch"] = epoch.ToString("D"), ["deviceId"] = device.ToString("D"), ["sequence"] = sequence.ToString(),
            ["command"] = kind, ["payload"] = payload, ["dependencies"] = new JsonArray(), ["observedVersions"] = observed,
            ["occurredAtContext"] = JsonNode.Parse("""
                {"capturedInstant":"2020-01-01T00:00:00Z","capturedLocal":"2020-01-01T00:00:00.000",
                 "captureZoneId":"UTC","captureOffsetSeconds":0,"zoneSource":"DEVICE","locale":"en","clockConfidence":"UNKNOWN"}
                """)
        };
        return OperationEnvelope.Parse(JsonSerializer.SerializeToUtf8Bytes(body));
    }
}
