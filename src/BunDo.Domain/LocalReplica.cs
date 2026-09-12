namespace BunDo.Domain;

public sealed record RecoveryVariant(string TaskId, string Title, string Code);

/// <summary>Single-threaded, in-memory reference client. Room replaces storage in the Android slice.</summary>
public sealed class LocalReplica(Guid workspaceId, Guid epoch, Guid deviceId)
{
    private readonly Dictionary<string, TaskSnapshot> canonical = [];
    private readonly List<Intent> journal = [];
    private ulong nextSequence = 1;
    public ulong Cursor { get; private set; }
    public IReadOnlyList<RecoveryVariant> Recovery => journal
        .Where(x => x.Receipt is { Accepted: false })
        .Select(x => new RecoveryVariant(x.TaskId, x.Title, x.Receipt!.Code)).ToArray();

    public string Capture(string title)
    {
        var sequence = nextSequence++;
        var id = TaskIdentity.ForCreate(deviceId, sequence);
        journal.Add(new(sequence, id, title, IsCreate: true, ExpectedHumanVersion: 0, AfterSequence: null));
        return id;
    }

    public void EditTitle(string taskId, string title)
    {
        var visible = Find(taskId) ?? throw new ArgumentException("Task must exist locally.", nameof(taskId));
        var preceding = journal.LastOrDefault(x => x.TaskId == taskId && !IsSettled(x));
        journal.Add(new(nextSequence++, taskId, title, false, visible.TitleVersion.Human, preceding?.Sequence));
    }

    public FrozenOperation? NextSubmission()
    {
        var intent = journal.FirstOrDefault(x => x.Receipt is null);
        if (intent is null) return null;
        if (intent.Frozen is not null) return intent.Frozen;
        var expected = intent.ExpectedHumanVersion;
        if (intent.AfterSequence is { } previous)
        {
            var receipt = journal.Single(x => x.Sequence == previous).Receipt;
            if (receipt is null) return null;
            if (!receipt.Accepted)
            {
                intent.Frozen = new(workspaceId, epoch, deviceId, intent.Sequence, new DiscardBlockedIntent(previous));
                return intent.Frozen;
            }
            expected = receipt.Task!.TitleVersion.Human;
        }
        TaskCommand command = intent.IsCreate
            ? new CreateTask(intent.TaskId, intent.Title)
            : new EditTask(intent.TaskId, Title: new(intent.Title, expected));
        intent.Frozen = new(workspaceId, epoch, deviceId, intent.Sequence, command);
        return intent.Frozen;
    }

    public void Receive(OperationReceipt receipt)
    {
        var intent = journal.SingleOrDefault(x => x.Frozen?.OperationId == receipt.OperationId);
        if (intent is null || intent.Frozen!.Fingerprint != receipt.Fingerprint ||
            intent.Receipt is { } known && known != receipt)
            throw new ArgumentException("Receipt does not match the frozen operation and its known outcome.", nameof(receipt));
        intent.Receipt = receipt;
    }

    public void Apply(ChangePage page)
    {
        if (page.AfterRevision != Cursor || page.ThroughRevision > page.HeadRevision || page.Groups.IsDefault)
            throw new ArgumentException("Page does not continue this replica's cursor.", nameof(page));
        var expected = Cursor;
        foreach (var group in page.Groups)
        {
            if (expected == ulong.MaxValue || group.Revision != expected + 1 || group.Tasks.IsDefault ||
                group.Tasks.Select(x => x.Id).Distinct(StringComparer.Ordinal).Count() != group.Tasks.Length)
                throw new ArgumentException("Page contains an incomplete or noncontiguous revision.", nameof(page));
            expected = group.Revision;
        }
        if (expected != page.ThroughRevision)
            throw new ArgumentException("Page cursor exceeds the supplied effects.", nameof(page));
        foreach (var group in page.Groups)
            foreach (var task in group.Tasks)
                canonical[task.Id] = task;
        Cursor = page.ThroughRevision;
    }

    public TaskSnapshot? Find(string id)
    {
        canonical.TryGetValue(id, out var visible);
        foreach (var intent in journal.Where(x => x.TaskId == id && !IsSettled(x)))
        {
            if (intent.IsCreate)
                visible ??= new(id, intent.Title, null, new(0, 0), new(0, 0));
            else if (visible is not null)
                visible = visible with { Title = intent.Title };
        }
        return visible;
    }

    private bool IsSettled(Intent intent) =>
        intent.Receipt is { } receipt && (!receipt.Accepted || receipt.EffectRevision <= Cursor);

    private sealed record Intent(
        ulong Sequence, string TaskId, string Title, bool IsCreate, ulong ExpectedHumanVersion, ulong? AfterSequence)
    {
        public FrozenOperation? Frozen { get; set; }
        public OperationReceipt? Receipt { get; set; }
    }
}
