namespace BunDo.Domain;

public sealed record RecoveryVariant(string TaskId, string Title, string? Description, string Code);

/// <summary>Single-threaded, in-memory reference client. Room replaces storage in the Android slice.</summary>
public sealed class LocalReplica(Guid workspaceId, Guid epoch, Guid deviceId)
{
    private readonly Dictionary<string, TaskSnapshot> canonical = [];
    private readonly List<Intent> journal = [];
    private ulong nextSequence = 1;
    public ulong Cursor { get; private set; }
    public IReadOnlyList<RecoveryVariant> Recovery => journal
        .Where(x => x.Receipt is { Accepted: false })
        .Select(x => new RecoveryVariant(x.TaskId, x.Title, x.Description, x.Receipt!.Code)).ToArray();

    public string Capture(string title, string? description = null)
    {
        var sequence = nextSequence++;
        var id = TaskIdentity.ForCreate(deviceId, sequence);
        journal.Add(new(sequence, id, title, IsCreate: true, ExpectedHumanVersion: 0, AfterSequence: null,
            Description: description, ChangesDescription: true));
        return id;
    }

    public void EditTitle(string taskId, string title)
    {
        var visible = Find(taskId) ?? throw new ArgumentException("Task must exist locally.", nameof(taskId));
        var preceding = journal.LastOrDefault(x => x.TaskId == taskId && x.ChangesTitle && !IsSettled(x));
        journal.Add(new(nextSequence++, taskId, title, false, visible.TitleVersion.Human, preceding?.Sequence));
    }

    public void EditDescription(string taskId, string? description)
    {
        var visible = Find(taskId) ?? throw new ArgumentException("Task must exist locally.", nameof(taskId));
        var preceding = journal.LastOrDefault(x => x.TaskId == taskId && x.ChangesDescription && !IsSettled(x));
        journal.Add(new(nextSequence++, taskId, visible.Title, false, visible.DescriptionVersion.Human,
            preceding?.Sequence, description, ChangesDescription: true, ChangesTitle: false));
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
            expected = intent.ChangesTitle ? receipt.Task!.TitleVersion.Human : receipt.Task!.DescriptionVersion.Human;
        }
        TaskCommand command = intent.IsCreate
            ? new CreateTask(intent.TaskId, intent.Title, intent.Description)
            : new EditTask(intent.TaskId,
                Title: intent.ChangesTitle ? new(intent.Title, expected) : null,
                Description: intent.ChangesDescription ? new(intent.Description, expected) : null);
        intent.Frozen = new(workspaceId, epoch, deviceId, intent.Sequence, command);
        return intent.Frozen;
    }

    public void Receive(OperationReceipt receipt)
    {
        var intent = journal.SingleOrDefault(x => x.Frozen?.OperationId == receipt.OperationId);
        if (intent is null || intent.Frozen!.Fingerprint != receipt.Fingerprint ||
            intent.Receipt is { } known && known != receipt)
            throw new ArgumentException("Receipt does not match the frozen operation and its known outcome.", nameof(receipt));
        if (receipt.EffectRevision == 0)
            throw new ArgumentException("A terminal receipt needs a committed revision.", nameof(receipt));
        if (receipt.Accepted)
        {
            if (receipt.Task is not { } effect || effect.Id != intent.TaskId ||
                intent.ChangesTitle && effect.Title != intent.Title ||
                intent.ChangesDescription && effect.Description != intent.Description ||
                effect.TitleVersion.Server == 0 ||
                effect.DescriptionVersion.Server == 0 ||
                effect.TitleVersion.Human > effect.TitleVersion.Server ||
                effect.DescriptionVersion.Human > effect.DescriptionVersion.Server ||
                effect.TitleVersion.Server > receipt.EffectRevision ||
                effect.DescriptionVersion.Server > receipt.EffectRevision)
                throw new ArgumentException("Accepted receipt does not describe the requested task effect.", nameof(receipt));
            if (receipt.EffectRevision <= Cursor && !ContainsEffect(canonical, effect))
                throw new ArgumentException("The canonical base does not contain the accepted effect.", nameof(receipt));
        }
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
            foreach (var task in group.Tasks)
                if (!ValidVersion(task.TitleVersion, group.Revision) ||
                    !ValidVersion(task.DescriptionVersion, group.Revision))
                    throw new ArgumentException("Snapshot versions exceed their containing revision.", nameof(page));
            expected = group.Revision;
        }
        if (expected != page.ThroughRevision)
            throw new ArgumentException("Page cursor exceeds the supplied effects.", nameof(page));
        var staged = new Dictionary<string, TaskSnapshot>(canonical);
        foreach (var group in page.Groups)
            foreach (var task in group.Tasks)
                staged[task.Id] = task;
        foreach (var receipt in journal.Select(x => x.Receipt))
            if (receipt is { Accepted: true } && receipt.EffectRevision <= page.ThroughRevision &&
                !ContainsEffect(staged, receipt.Task!))
                throw new ArgumentException("Page skipped an acknowledged effect.", nameof(page));
        canonical.Clear();
        foreach (var pair in staged) canonical.Add(pair.Key, pair.Value);
        Cursor = page.ThroughRevision;
    }

    public TaskSnapshot? Find(string id)
    {
        canonical.TryGetValue(id, out var visible);
        foreach (var intent in journal.Where(x => x.TaskId == id && !IsSettled(x)))
        {
            if (intent.IsCreate)
                visible ??= new(id, intent.Title, intent.Description, new(0, 0), new(0, 0));
            else if (visible is not null)
                visible = visible with
                {
                    Title = intent.ChangesTitle ? intent.Title : visible.Title,
                    Description = intent.ChangesDescription ? intent.Description : visible.Description
                };
        }
        return visible;
    }

    private bool IsSettled(Intent intent) =>
        intent.Receipt is { } receipt && (!receipt.Accepted || receipt.EffectRevision <= Cursor);

    private static bool ContainsEffect(IReadOnlyDictionary<string, TaskSnapshot> state, TaskSnapshot effect) =>
        state.TryGetValue(effect.Id, out var current) &&
        current.TitleVersion.Server >= effect.TitleVersion.Server &&
        current.DescriptionVersion.Server >= effect.DescriptionVersion.Server &&
        (current.TitleVersion.Server != effect.TitleVersion.Server || current.Title == effect.Title) &&
        (current.DescriptionVersion.Server != effect.DescriptionVersion.Server || current.Description == effect.Description);

    private static bool ValidVersion(FieldVersion version, ulong revision) =>
        version.Server > 0 && version.Human > 0 && version.Human <= version.Server && version.Server <= revision;

    private sealed record Intent(
        ulong Sequence, string TaskId, string Title, bool IsCreate, ulong ExpectedHumanVersion, ulong? AfterSequence,
        string? Description = null, bool ChangesDescription = false, bool ChangesTitle = true)
    {
        public FrozenOperation? Frozen { get; set; }
        public OperationReceipt? Receipt { get; set; }
    }
}
