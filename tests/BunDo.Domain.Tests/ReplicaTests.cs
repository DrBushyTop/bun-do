using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class ReplicaTests
{
    private static readonly Guid Workspace = Guid.Parse("ed395328-4442-44b0-a781-cd4b73d659cd");
    private static readonly Guid Epoch = Guid.Parse("e76224ee-4bf4-45ac-92a5-29b98b0a3d35");
    private static readonly Guid DeviceA = Guid.Parse("550e8400-e29b-41d4-a716-446655440000");
    private static readonly Guid DeviceB = Guid.Parse("20000000-0000-4000-8000-000000000002");
    private static readonly Guid Member = Guid.Parse("c3c9a9e9-955a-4ae2-ac15-36f331ba1847");

    [Fact]
    public void An_edit_made_during_create_response_loss_remains_visible_until_both_clients_converge()
    {
        var server = Server();
        var a = new LocalReplica(Workspace, Epoch, DeviceA);
        var b = new LocalReplica(Workspace, Epoch, DeviceB);
        var id = a.Capture("Original");
        var create = a.NextSubmission()!;
        var firstReceipt = server.Handle(Member, create).Receipt!;
        a.EditTitle(id, "Edited while the response was lost");

        Assert.Equal("Edited while the response was lost", a.Find(id)!.Title);
        a.Receive(server.Handle(Member, create).Receipt!);
        Assert.Equal("Edited while the response was lost", a.Find(id)!.Title);
        a.Apply(server.Pull(a.Cursor));
        Assert.Equal("Edited while the response was lost", a.Find(id)!.Title);
        var edit = a.NextSubmission()!;
        Assert.Equal(2UL, edit.Sequence);
        Assert.Equal(1UL, ((EditTask)edit.Command).Title!.ExpectedHumanVersion);
        a.Receive(server.Handle(Member, edit).Receipt!);
        a.Apply(server.Pull(a.Cursor));
        b.Apply(server.Pull(b.Cursor));

        Assert.Equal(a.Find(id), b.Find(id));
        Assert.Equal("Edited while the response was lost", b.Find(id)!.Title);
        Assert.Null(a.NextSubmission());
        Assert.Equal(1UL, firstReceipt.EffectRevision);
        Assert.Equal(2UL, a.Cursor);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Both_reconnect_orders_preserve_the_losing_text_and_converge(bool bFirst)
    {
        var server = Server();
        var a = new LocalReplica(Workspace, Epoch, DeviceA);
        var b = new LocalReplica(Workspace, Epoch, DeviceB);
        var id = a.Capture("Original");
        Send(server, a);
        b.Apply(server.Pull(0));
        a.EditTitle(id, "A's offline title");
        b.EditTitle(id, "B's offline title");

        Send(server, bFirst ? b : a);
        Send(server, bFirst ? a : b);
        a.Apply(server.Pull(a.Cursor));
        b.Apply(server.Pull(b.Cursor));

        Assert.Equal(a.Find(id), b.Find(id));
        Assert.Equal(bFirst ? "B's offline title" : "A's offline title", a.Find(id)!.Title);
        var losing = bFirst ? a : b;
        var variant = Assert.Single(losing.Recovery);
        Assert.Equal(bFirst ? "A's offline title" : "B's offline title", variant.Title);
        Assert.Equal("FIELD_CONFLICT", variant.Code);
        Assert.Null(losing.NextSubmission());
    }

    [Fact]
    public void A_rejected_create_and_its_dependent_edit_do_not_strand_independent_work()
    {
        var server = Server();
        var a = new LocalReplica(Workspace, Epoch, DeviceA);
        var rejectedId = a.Capture("");
        a.EditTitle(rejectedId, "Retain this correction");
        var independentId = a.Capture("Independent task");
        Send(server, a);

        Assert.NotNull(a.NextSubmission());
        Send(server, a);
        Send(server, a);

        Assert.Equal(2, a.Recovery.Count);
        Assert.Contains(a.Recovery, x => x.Title == "Retain this correction" && x.Code == "BLOCKED_DEPENDENCY");
        Assert.Equal("Independent task", a.Find(independentId)!.Title);
        Assert.Null(a.Find(rejectedId));
        Assert.Null(a.NextSubmission());
        Assert.Equal(3UL, a.Cursor);
    }

    [Theory]
    [InlineData("gap")]
    [InlineData("through")]
    [InlineData("wrong-base")]
    [InlineData("head")]
    public void A_malformed_page_cannot_partially_change_the_base_or_cursor(string defect)
    {
        var server = Server();
        var a = new LocalReplica(Workspace, Epoch, DeviceA);
        var id = a.Capture("Keep local text");
        server.Handle(Member, a.NextSubmission()!);
        var valid = server.Pull(0);
        var invalid = defect switch
        {
            "gap" => valid with { Groups = [valid.Groups[0], new(3, [])], ThroughRevision = 3, HeadRevision = 3 },
            "through" => valid with { ThroughRevision = 2, HeadRevision = 2 },
            "wrong-base" => valid with { AfterRevision = 1 },
            _ => valid with { HeadRevision = 0 }
        };

        Assert.Throws<ArgumentException>(() => a.Apply(invalid));
        Assert.Equal(0UL, a.Cursor);
        Assert.Equal(0UL, a.Find(id)!.TitleVersion.Field);
        a.Apply(valid);
        Assert.Equal(1UL, a.Cursor);
    }

    [Fact]
    public void A_receipt_must_match_the_frozen_intent_before_it_can_acknowledge_it()
    {
        var server = Server();
        var a = new LocalReplica(Workspace, Epoch, DeviceA);
        a.Capture("Original");
        var operation = a.NextSubmission()!;
        var receipt = server.Handle(Member, operation).Receipt!;

        Assert.Throws<ArgumentException>(() => a.Receive(receipt with { Fingerprint = "altered" }));
        Assert.Equal(operation, a.NextSubmission());
        a.Receive(receipt);
        Assert.Throws<ArgumentException>(() => a.Receive(receipt with { Code = "FIELD_CONFLICT" }));
    }

    private static void Send(WorkspaceServer server, LocalReplica replica)
    {
        replica.Receive(server.Handle(Member, replica.NextSubmission()!).Receipt!);
        replica.Apply(server.Pull(replica.Cursor));
    }

    private static WorkspaceServer Server() => new(new InMemoryWorkspaceStore(
        Workspace, Epoch, [new(DeviceA, Member), new(DeviceB, Member)]));
}
