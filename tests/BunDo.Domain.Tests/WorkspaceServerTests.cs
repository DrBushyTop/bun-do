using BunDo.Domain;

namespace BunDo.Domain.Tests;

public sealed class WorkspaceServerTests
{
    private static readonly Guid Workspace = Guid.Parse("ed395328-4442-44b0-a781-cd4b73d659cd");
    private static readonly Guid Epoch = Guid.Parse("e76224ee-4bf4-45ac-92a5-29b98b0a3d35");
    private static readonly Guid DeviceA = Guid.Parse("550e8400-e29b-41d4-a716-446655440000");
    private static readonly Guid MemberA = Guid.Parse("c3c9a9e9-955a-4ae2-ac15-36f331ba1847");
    private static readonly Guid DeviceB = Guid.Parse("20000000-0000-4000-8000-000000000002");
    private static readonly Guid MemberB = Guid.Parse("30000000-0000-4000-8000-000000000003");

    [Fact]
    public void A_typed_create_is_accepted_and_delivered_as_one_complete_revision()
    {
        var server = Server();
        var operation = Create(1, "Järjestä varasto");

        var result = server.Handle(MemberA, operation);

        Assert.Equal("ACCEPTED", result.Code);
        Assert.Equal(1UL, result.Receipt!.EffectRevision);
        var page = server.Pull(0);
        Assert.Equal(1UL, page.ThroughRevision);
        var task = Assert.Single(Assert.Single(page.Groups).Tasks);
        Assert.Equal("bb91e5a1-1364-5966-8b14-fd2f32d0c1a8", task.Id);
        Assert.Equal("Järjestä varasto", task.Title);
        Assert.Equal(new FieldVersion(1, 1), task.TitleVersion);
    }

    [Theory]
    [InlineData(false, "ACCEPTED")]
    [InlineData(true, "OPERATION_ID_REUSED")]
    public void Retrying_after_response_loss_never_repeats_the_effect(bool changePayload, string code)
    {
        var store = Store();
        var first = new WorkspaceServer(store);
        var committed = first.Handle(MemberA, Create(1, "Buy coffee"));
        // A second Functions instance shares only the store, not a receipt cache.
        var retry = new WorkspaceServer(store).Handle(MemberA,
            Create(1, changePayload ? "Buy tea" : "Buy coffee"));

        Assert.Equal(code, retry.Code);
        if (!changePayload) Assert.Equal(committed.Receipt, retry.Receipt);
        var page = first.Pull(0);
        Assert.Equal(1UL, page.HeadRevision);
        Assert.Equal("Buy coffee", Assert.Single(Assert.Single(page.Groups).Tasks).Title);
    }

    [Theory]
    [InlineData(0UL, "INVALID_SEQUENCE")]
    [InlineData(2UL, "SEQUENCE_GAP")]
    public void An_invalid_sequence_does_not_consume_the_next_sequence(ulong sequence, string code)
    {
        var server = Server();
        var outOfOrder = new FrozenOperation(Workspace, Epoch, DeviceA, sequence,
            new CreateTask("bb91e5a1-1364-5966-8b14-fd2f32d0c1a8", "Wrong order"));

        Assert.Equal(code, server.Handle(MemberA, outOfOrder).Code);
        Assert.Empty(server.Pull(0).Groups);
        Assert.Equal("ACCEPTED", server.Handle(MemberA, Create(1, "Correct order")).Code);
    }

    [Theory]
    [InlineData("member", "FORBIDDEN")]
    [InlineData("workspace", "WRONG_WORKSPACE")]
    [InlineData("epoch", "EPOCH_CHANGED")]
    [InlineData("device", "DEVICE_UNKNOWN")]
    public void Identity_checks_precede_receipt_lookup(string mismatch, string code)
    {
        var server = Server();
        server.Handle(MemberA, Create(1, "Private household task"));
        var request = new FrozenOperation(
            mismatch == "workspace" ? Guid.Empty : Workspace,
            mismatch == "epoch" ? Guid.Empty : Epoch,
            mismatch == "device" ? Guid.Empty : DeviceA,
            1, new CreateTask(TaskIdentity.ForCreate(DeviceA, 1), "Private household task"));

        var result = server.Handle(mismatch == "member" ? Guid.Empty : MemberA, request);

        Assert.Equal(code, result.Code);
        Assert.Null(result.Receipt);
        Assert.Equal(1UL, server.Pull(0).HeadRevision);
    }

    [Fact]
    public void An_edit_changes_only_its_requested_field_and_returns_output_versions()
    {
        var server = Server();
        var created = server.Handle(MemberA, Create(1, "Buy coffee")).Receipt!.Task!;

        var result = server.Handle(MemberA, new FrozenOperation(Workspace, Epoch, DeviceA, 2,
            new EditTask(created.Id, Description: new("Decaf", created.DescriptionVersion.Human))));

        Assert.Equal("ACCEPTED", result.Code);
        Assert.Equal("Buy coffee", result.Receipt!.Task!.Title);
        Assert.Equal("Decaf", result.Receipt.Task.Description);
        Assert.Equal(new FieldVersion(1, 1), result.Receipt.Task.TitleVersion);
        Assert.Equal(new FieldVersion(2, 2), result.Receipt.Task.DescriptionVersion);
        Assert.Equal(result.Receipt.Task, Assert.Single(Assert.Single(server.Pull(1).Groups).Tasks));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Concurrent_human_edits_merge_only_when_their_fields_differ(bool sameField)
    {
        var server = Server();
        var id = server.Handle(MemberA, Create(1, "Original")).Receipt!.Task!.Id;
        server.Handle(MemberA, new FrozenOperation(Workspace, Epoch, DeviceA, 2,
            new EditTask(id, Title: new("A's title", 1))));
        var edit = sameField
            ? new EditTask(id, Title: new("B's title", 1), Description: new("Must stay atomic", 1))
            : new EditTask(id, Description: new("B's description", 1));

        var result = server.Handle(MemberB, new FrozenOperation(Workspace, Epoch, DeviceB, 1, edit));

        Assert.Equal(sameField ? "FIELD_CONFLICT" : "ACCEPTED", result.Code);
        Assert.Equal("A's title", result.Receipt!.Task!.Title);
        Assert.Equal(sameField ? null : "B's description", result.Receipt.Task.Description);
        Assert.Equal(3UL, result.Receipt.EffectRevision);
        if (sameField) Assert.Empty(Assert.Single(server.Pull(2).Groups).Tasks);
        // A terminal rejection consumes B's sequence, so independent later work can proceed.
        Assert.Equal("ACCEPTED", server.Handle(MemberB, new FrozenOperation(Workspace, Epoch, DeviceB, 2,
            new CreateTask(TaskIdentity.ForCreate(DeviceB, 2), "Independent task"))).Code);
    }

    [Theory]
    [InlineData("id", "INVALID_TASK_ID")]
    [InlineData("blank", "INVALID_TITLE")]
    [InlineData("title", "INVALID_TITLE")]
    [InlineData("description", "INVALID_DESCRIPTION")]
    [InlineData("bytes", "TASK_TOO_LARGE")]
    public void A_rejected_create_has_a_retryable_receipt_but_no_task_effect(string invalid, string expected)
    {
        var server = Server();
        var operation = new FrozenOperation(Workspace, Epoch, DeviceA, 1, new CreateTask(
            invalid == "id" ? "some-reused-id" : TaskIdentity.ForCreate(DeviceA, 1),
            invalid == "blank" ? "  " : invalid == "title" ? new string('x', 161) : "Valid title",
            invalid == "description" ? new string('x', 4001) : invalid == "bytes" ? new string('ä', 4000) : null));

        var result = server.Handle(MemberA, operation);

        Assert.Equal(expected, result.Code);
        Assert.False(result.Receipt!.Accepted);
        Assert.Null(result.Receipt.Task);
        Assert.Empty(Assert.Single(server.Pull(0).Groups).Tasks);
        Assert.Equal(result, server.Handle(MemberA, operation));
        Assert.Equal("ACCEPTED", server.Handle(MemberA, Create(2, "Next task")).Code);
    }

    [Theory]
    [InlineData("missing", "ENTITY_MISSING")]
    [InlineData("empty", "EMPTY_EDIT")]
    [InlineData("invalid", "INVALID_TITLE")]
    [InlineData("noop", "ACCEPTED")]
    public void Invalid_or_unchanged_edits_never_mutate_canonical_text(string mode, string code)
    {
        var server = Server();
        var original = server.Handle(MemberA, Create(1, "Original")).Receipt!.Task!;
        var operation = new FrozenOperation(Workspace, Epoch, DeviceA, 2, new EditTask(
            mode == "missing" ? "missing-task" : original.Id,
            mode == "empty" ? null : new(mode == "invalid" ? "" : "Original", 1)));

        var result = server.Handle(MemberA, operation);

        Assert.Equal(code, result.Code);
        Assert.Empty(Assert.Single(server.Pull(1).Groups).Tasks);
        Assert.Equal(mode == "missing" ? null : original, result.Receipt!.Task);
        Assert.Equal(result, server.Handle(MemberA, operation));
    }

    [Fact]
    public void A_store_collision_revalidates_and_commits_without_partial_effects()
    {
        var fault = new CollidingStore(Store(), failures: 1);
        var server = new WorkspaceServer(fault);

        Assert.Equal("ACCEPTED", server.Handle(MemberA, Create(1, "Retry after CAS")).Code);
        Assert.Equal(1UL, server.Pull(0).HeadRevision);
        Assert.Single(Assert.Single(server.Pull(0).Groups).Tasks);
    }

    [Fact]
    public void Persistent_contention_is_bounded_and_does_not_consume_the_sequence()
    {
        var fault = new CollidingStore(Store(), failures: 5);
        var server = new WorkspaceServer(fault);
        var operation = Create(1, "Keep this intent");

        Assert.Equal("BUSY", server.Handle(MemberA, operation).Code);
        Assert.Empty(server.Pull(0).Groups);
        Assert.Equal("ACCEPTED", server.Handle(MemberA, operation).Code);
    }

    [Fact]
    public void Pagination_never_skips_a_revision_including_terminal_rejections()
    {
        var server = Server();
        server.Handle(MemberA, Create(1, "First"));
        server.Handle(MemberA, Create(2, ""));
        server.Handle(MemberA, Create(3, "Last"));

        var first = server.Pull(0, 1);
        var second = server.Pull(first.ThroughRevision, 1);
        var third = server.Pull(second.ThroughRevision, 1);

        Assert.Equal(1UL, first.ThroughRevision);
        Assert.Equal(2UL, second.ThroughRevision);
        Assert.Empty(Assert.Single(second.Groups).Tasks);
        Assert.Equal(3UL, third.ThroughRevision);
        Assert.Equal(3UL, first.HeadRevision);
        Assert.Throws<ArgumentOutOfRangeException>(() => server.Pull(4));
        Assert.Throws<ArgumentOutOfRangeException>(() => server.Pull(0, 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => server.Pull(0, 101));
    }

    [Theory]
    [InlineData(1UL, "bb91e5a1-1364-5966-8b14-fd2f32d0c1a8")]
    [InlineData(2UL, "70257c16-b4c8-51aa-85b2-dcafd12827de")]
    [InlineData(ulong.MaxValue, "b0477066-b98d-5e5f-8549-2ced9f5d3c02")]
    public void Task_ids_match_independent_uuid_v5_fixtures(ulong sequence, string expected) =>
        Assert.Equal(expected, TaskIdentity.ForCreate(DeviceA, sequence));

    [Theory]
    [InlineData(2, 1, "UNSUPPORTED_PROTOCOL")]
    [InlineData(1, 2, "UNSUPPORTED_COMMAND_VERSION")]
    public void Unknown_versions_do_not_reinterpret_or_consume_an_operation(int protocol, int command, string code)
    {
        var server = Server();
        var ordinary = Create(1, "Versioned intent");
        var unsupported = new FrozenOperation(Workspace, Epoch, DeviceA, 1, ordinary.Command, protocol, command);

        Assert.NotEqual(ordinary.Fingerprint, unsupported.Fingerprint);
        Assert.Equal(code, server.Handle(MemberA, unsupported).Code);
        Assert.Empty(server.Pull(0).Groups);
        Assert.Equal("ACCEPTED", server.Handle(MemberA, ordinary).Code);
        Assert.Equal(code, server.Handle(MemberA, unsupported).Code);
    }

    private sealed class CollidingStore(IWorkspaceStore inner, int failures) : IWorkspaceStore
    {
        private int remaining = failures;
        public WorkspaceState Read() => inner.Read();
        public bool TryCommit(ulong expectedRevision, WorkspaceState next) =>
            remaining-- > 0 ? false : inner.TryCommit(expectedRevision, next);
    }

    private static WorkspaceServer Server() => new(Store());

    private static InMemoryWorkspaceStore Store() => new(
        Workspace, Epoch, [new DeviceRegistration(DeviceA, MemberA), new DeviceRegistration(DeviceB, MemberB)]);

    private static FrozenOperation Create(ulong sequence, string title) => new(
        Workspace, Epoch, DeviceA, sequence, new CreateTask(TaskIdentity.ForCreate(DeviceA, sequence), title));
}
