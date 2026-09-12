# Bun Do: product and implementation specification

**Status:** v1 architecture draft, under adversarial review  
**App name:** Bun Do, "the way of the bun"  
**Primary platform:** Android  
**Target phones:** vivo X300 Ultra and OnePlus 13  
**Primary language:** Finnish  
**Secondary language:** English  
**Cloud:** Microsoft Azure  
**Primary interaction:** local voice dictation  
**Core product principle:** a shared queue that works fully offline and synchronizes safely later

The owner confirmed the entire v1 scope on 2026-09-12. Implementation may proceed in stages, but those stages do not remove recurrence, dependencies, nested subtasks, AI split/clarify, statistics, or other included features from the release.

The [Bun Do wayfinder map](https://github.com/DrBushyTop/bun-do/issues/1) tracks unresolved architecture decisions. Read the [adversarial review](docs/reviews/architecture-adversarial-review.md) before implementation. Statements below that name a technology or describe a desired outcome do not establish device feasibility, tenant availability, or a complete sync protocol. Section 38 records the original design choices; the linked review qualifies them.

---

## 1. Product summary

Bun Do is a small collaborative task manager intended primarily for a couple or similarly small shared household/team. The primary experience is intentionally simpler than a conventional project-management tool:

1. Open the app.
2. Tap a large voice button.
3. Dictate a task naturally in Finnish or English.
4. Local speech recognition immediately produces text.
5. The task exists locally right away.
6. When online, a Microsoft Foundry model cleans up the transcript and converts it into a structured task.
7. The shared task queue can be scrolled freely; users may choose any available task, not only the first task.
8. Tasks can be claimed, completed, reprioritized, snoozed, edited, clarified, split into subtasks, and linked by dependencies.
9. Everything important continues working offline.
10. When a device reconnects, local mutations synchronize with the canonical backend and then propagate to the other client.

The product should emphasize **shared progress**, not competition between users. Attribution is stored because it is operationally useful ("who created this?", "who completed this?"), but the default statistics and gamification surfaces should not compare users against each other.

### 1.1 Name and visual identity

The name is **Bun Do**, "the way of the bun." Bun means bunny. The owner's reference is the tiny King Bun in [xkcd's Bun comic](https://xkcd.com/1682/), supplied during the architecture review.

The visual direction is a martial-arts bunny. Develop an original, recognizable rabbit mark with a composed bearing and a little deadpan humor. Carry the identity into the launcher icon, onboarding, voice capture, empty states, and shared completion feedback. Task rows and conflict messages must remain easy to read. The theme should express patient practice and working together; it must not introduce competitive member ranks or punish unfinished tasks.

All UI design, implementation, and review must use **Impeccable**, adapted to native Android and Compose. [PRODUCT.md](PRODUCT.md) records the product brief. The [visual brief](docs/design/bun-do-visual-brief.md) records the current direction and open choices. Palette, typography, final logo artwork, and motion are not yet selected.

---

## 2. Product principles

### 2.1 Voice-first, not voice-only

Voice is the primary task-creation path. The most prominent action on the queue screen is a large microphone button.

Typing must still be first-class:

- create task by typing;
- edit title and description;
- add notes;
- give AI split instructions by typing;
- give AI split instructions by voice;
- use all non-AI task functions without a microphone.

A user must never be blocked because voice recognition is unavailable.

### 2.2 Local-first and offline-first

The local database is the application's source of truth for the UI.

The UI must never wait for Azure before showing a local task mutation.

Every important action is first committed transactionally to Room and, when appropriate, to a durable local mutation/outbox table. Azure is the synchronization rendezvous and canonical multi-device authority, not the immediate UI database.

Offline support includes at minimum:

- reading the entire locally cached queue;
- creating typed tasks;
- creating voice tasks with local ASR;
- editing tasks;
- queue reordering;
- claiming/unclaiming provisionally;
- completing/reopening tasks;
- snoozing/unsnoozing;
- adding notes;
- adding/removing manual subtasks;
- changing due dates;
- changing areas/tags;
- changing recurrence settings;
- adding/removing dependencies where locally resolvable;
- deleting/restoring tasks;
- queueing AI enrichment, AI clarify, and AI split work for later.

AI processing that requires Foundry may remain pending until connectivity returns.

### 2.3 AI enhances tasks; it does not own them

A task is created before cloud AI succeeds.

AI is used for:

- cleaning up dictation;
- extracting a concise title;
- extracting a description;
- identifying explicit due dates/times;
- identifying explicit recurrence;
- assigning an existing area when appropriate;
- clarification suggestions;
- task splitting;
- optionally estimating rough effort.

AI must not:

- choose creator/completer identities;
- authorize actions;
- silently change queue priority unless the user explicitly dictated a placement/priority request;
- invent deadlines;
- invent recurrence;
- create arbitrary permissions;
- overwrite user-edited fields after a stale AI request completes;
- directly mutate the database without server-side validation.

### 2.4 Finnish-first, bilingual throughout

Every application feature must work in both Finnish and English.

This includes:

- Android UI;
- onboarding;
- authentication UI around the app-owned parts of sign-in;
- voice dictation;
- typed task creation;
- AI cleanup;
- AI split;
- AI clarify;
- due-date interpretation;
- recurrence interpretation;
- validation errors;
- conflict messages;
- notifications;
- activity feed;
- statistics;
- settings;
- accessibility labels.

The application defaults to Finnish unless the device/user has selected English.

Task content stays in the language in which the user created it. AI should not translate task titles/descriptions unless explicitly requested.

Code-switching should be tolerated. A Finnish UI user may create an English task and vice versa.

---

# 3. V1 user experience

## 3.1 Main queue

The queue is the default screen.

Example:

```text
┌──────────────────────────────────────┐
│ Meidän tehtävät                 ⚙   │
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ Siivoa keittiö             Pasi │ │
│ │ Tänään                      ●    │ │
│ └──────────────────────────────────┘ │
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ Varaa eläinlääkäri             │ │
│ └──────────────────────────────────┘ │
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ Järjestä varasto           2/4  │ │
│ │ ▰▰▱▱                          ▾ │ │
│ └──────────────────────────────────┘ │
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ Osta uusi lamppu                │ │
│ └──────────────────────────────────┘ │
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ Siivoa parveke            Jenny │ │
│ └──────────────────────────────────┘ │
│                                      │
│                 ⋮                    │
│                                      │
│                 🔴                   │
│            Lisää puhumalla           │
│                                      │
│             ＋ Kirjoita              │
└──────────────────────────────────────┘
```

English equivalent:

```text
Our Tasks

[ ...scrollable queue... ]

        🔴
   Add by voice

     + Type
```

Requirements:

- normal long scroll;
- no artificial "top two tasks only" limitation;
- any available actionable task may be opened/claimed;
- order expresses shared priority, not mandatory execution order;
- task rows show only useful operational information;
- no points/leaderboard on the home screen;
- completed and snoozed tasks do not clutter the active queue;
- containers show progress;
- claimed/in-progress tasks visibly show who is doing them;
- pending sync/AI state is subtle but visible when relevant;
- drag-and-drop or long-press reordering;
- microphone action remains easily reachable while scrolling, ideally as a floating action button.

Suggested bottom navigation:

```text
Jono / Queue
Tapahtumat / Activity
Yhdessä / Together
```

Settings may be top-right or a fourth destination if it grows.

## 3.2 Task detail

Task detail should show:

- title;
- description;
- original dictation transcript when source was voice;
- creator;
- creation time;
- current claimant;
- completer(s);
- completion time;
- due date/time;
- recurrence;
- area;
- notes;
- parent/subtask relationship;
- dependency/blocking state;
- AI state if pending/failed/needs review;
- sync/conflict state if relevant;
- actions.

Primary actions:

```text
Claim / Ota tehtävä
Complete / Valmis
Unclaim / Vapauta
Split / Pilko
Clarify / Selkeytä
Snooze / Siirrä myöhemmäksi
Edit / Muokkaa
```

"Complete" should work without an explicit claim. Completing an unclaimed task may implicitly mark the actor as the completer without forcing an extra tap.

## 3.3 Voice creation UX

Desired path:

```text
Tap microphone

Recording…
00:07   ▂▆▃▇▅▂▆

Stop

Transcribing locally…
"Laita muistutus että pitää…"

Local task appears immediately

Preparing task… ✨

Final structured task replaces/enriches the draft
```

At every stage the user must know something happened.

Do not show a dead spinner without context.

Suggested states:

1. `RECORDING`
2. `LOCAL_TRANSCRIBING`
3. `LOCAL_DRAFT_CREATED`
4. `AI_PENDING` or `AI_PROCESSING`
5. `AI_READY`
6. `AI_NEEDS_REVIEW`
7. `AI_FAILED`

Offline:

```text
Local transcript created
↓
Task exists normally
↓
"AI cleanup pending — will continue when online"
```

The task can be edited/claimed/completed before cloud enrichment runs.

## 3.4 Typed creation

Typed creation should be one tap away from voice creation.

A lightweight editor is enough:

- title/free text field;
- optional description;
- optional due date;
- optional recurrence;
- optional area.

There are two reasonable modes:

**Fast typed capture:** one text box can be sent through the same Luna extraction pipeline.

**Structured manual editor:** user directly edits fields.

V1 should support both by treating a single text box as the quick path and exposing structured fields when expanded.

---

# 4. Voice architecture

## 4.1 Primary ASR: local NVIDIA Parakeet

Proceed with local Parakeet on vivo X300 Ultra and OnePlus 13. The owner explicitly chose to skip the separate feasibility experiment and accept satisfactory operation as a planning assumption. No device measurements support that assumption yet. Normal implementation tests still cover offline capture, transcription and failure recovery. [Speech research](docs/research/android-offline-speech.md) records the available artifacts; [the skipped experiment](https://github.com/DrBushyTop/bun-do/issues/11) records the owner's decision.

Selected model family:

`nvidia/parakeet-tdt-0.6b-v3`

Why:

- local/offline;
- Finnish support;
- English support;
- multilingual auto-detection;
- punctuation/capitalization;
- no need to upload household audio by default.

NVIDIA's model card currently lists 25 European languages, including Finnish and English.

Implementation options may use an Android-compatible inference runtime such as sherpa-onnx. Verify the exact model export, quantization, decoder, runtime version, and supported device profile together. Keep the selected runtime behind an interface:

```kotlin
interface SpeechRecognizer {
    suspend fun transcribe(audio: LocalAudio): TranscriptionResult
}
```

Model installation should be separable from the APK if model size makes bundling undesirable.

Suggested first-run UX:

```text
Offline speech recognition

Download voice model (~model-dependent size)

✓ Works without internet
✓ Finnish and English
✓ Audio stays on this phone

[Download]
```

Once downloaded, local speech is available offline.

## 4.2 Audio lifecycle and privacy

Default:

```text
Microphone
→ temporary local audio file
→ Parakeet
→ transcript persisted on task
→ audio deleted after successful transcription
```

Do not upload audio to Azure during the normal path.

Possible future fallback:

- optional Foundry/Azure speech transcription if local model is absent or fails;
- feature-flagged;
- not required for v1.

Avoid retaining raw audio indefinitely.

If transcription fails, keep the audio only long enough to allow retry/recovery and make its state visible.

## 4.3 Audio capture limits

Set practical bounds to prevent accidental huge recordings:

- soft warning around 60 seconds;
- hard maximum configurable, e.g. 2 minutes;
- user can stop anytime;
- show duration;
- avoid processing empty/near-empty recordings.

Tasks should normally be short commands, not meeting transcripts.

---

# 5. Foundry AI architecture

## 5.1 Default model strategy

Public Azure documentation supports the proposed Luna/Terra model family, Responses, structured outputs, and the reasoning controls below. This is not a verified deployment in Bun Do's subscription. Record the exact deployed model/version, region, API, schema acceptance, quota, and managed-identity access before integration. [Azure platform research](docs/research/azure-platform-constraints.md) records the evidence and a version discrepancy between public tables.

Default cloud intelligence model:

`gpt-5.6-luna`

Use the Microsoft Foundry Responses API and strict structured output.

Recommended defaults:

### Task extraction / cleanup

- model: GPT-5.6 Luna
- reasoning effort: `none`
- low verbosity
- strict JSON schema

### Clarify

- model: GPT-5.6 Luna
- reasoning effort: `none` or `low`
- strict JSON schema

### Split

- model: GPT-5.6 Luna
- reasoning effort: `low`
- strict JSON schema

### Optional fallback/escalation

GPT-5.6 Terra should be configuration-driven, not hard-coded.

Potential policy:

1. Luna first.
2. If structured-output validation fails, retry once.
3. If semantic ambiguity is high or a user explicitly asks for a better split, optionally escalate to Terra.
4. Do not automatically pay a Terra latency penalty for every trivial task.

Because the user has Azure sponsorship, model cost is not the primary optimization target. **Perceived latency is more important.**

All model deployments must be configured by deployment name/environment variable so models can be swapped without changing domain code.

Example:

```text
AI_TASK_EXTRACTION_DEPLOYMENT=gpt-5.6-luna
AI_SPLIT_DEPLOYMENT=gpt-5.6-luna
AI_FALLBACK_DEPLOYMENT=gpt-5.6-terra
```

## 5.2 Task extraction input

Server supplies:

```json
{
  "sourceText": "Muista varata pöytä lauantaille johonkin vegaaniseen paikkaan noin seitsemäksi",
  "sourceLanguageHint": "fi",
  "uiLanguage": "fi",
  "workspaceTimeZone": "Europe/Helsinki",
  "capturedLocalDateTime": "2026-09-12T14:30:00+03:00",
  "captureTimeZone": "Europe/Helsinki",
  "processedAt": "2026-09-12T11:30:02Z",
  "existingAreas": [
    { "id": "area-home", "fi": "Koti", "en": "Home" }
  ]
}
```

Do not let the model infer identity fields.

## 5.3 Example extraction output

```json
{
  "language": "fi",
  "title": "Varaa vegaaninen ravintola lauantaille",
  "description": "Varaa pöytä lauantaille noin seitsemäksi.",
  "due": null,
  "recurrence": null,
  "areaId": null,
  "explicitPlacement": null,
  "ambiguities": [
    "Tarkoittaako lauantai tätä vai seuraavaa lauantaita?",
    "Tarkoittaako seitsemän klo 7 vai klo 19?"
  ],
  "needsReview": true
}
```

The schema should distinguish date-only and date-time values.

Do **not** flatten every date into UTC midnight.

## 5.4 Date semantics

Store due information semantically:

```text
NONE
DATE_ONLY
DATE_TIME
```

Example fields:

```text
dueKind
dueLocalDate
dueLocalTime
dueTimeZone
```

Reasons:

- "tomorrow" often means a day, not exactly midnight;
- Finnish and English natural-language dates need workspace-local interpretation;
- DST should not move a date-only deadline;
- notifications may need a user-defined default reminder time for date-only tasks.

The workspace has an IANA time zone, initially `Europe/Helsinki`, configurable later.

When AI interprets relative phrases, use the original capture date/time, timezone, and language, preserved with the source text. Pass processing time separately. A task captured on Monday as "tomorrow" still refers to Tuesday when uploaded on Thursday. Flag uncertain capture time when the device clock is suspect.

Distinguish the task deadline from a time mentioned in its subject. A restaurant reservation for Saturday does not establish when the booking task is due. The example in section 5.3 therefore keeps `due` null and asks for clarification of the reservation time.

If the phrase is ambiguous, return an ambiguity instead of inventing precision.

## 5.5 Language behavior

Rules:

- preserve source language;
- do not translate automatically;
- Finnish dictation → Finnish title/description;
- English dictation → English title/description;
- mixed-language input may remain mixed if that best preserves intent;
- UI language does not force content language;
- prompts can be written in English internally, but output language is constrained by source intent;
- errors from backend should be machine-readable codes and localized by Android rather than generated prose.

## 5.6 Stale AI result protection

This is critical.

Scenario:

1. User creates offline voice task.
2. Transcript is uploaded.
3. AI processing starts.
4. Before result returns, user manually edits the title.
5. AI returns a different title.

The AI result must **not overwrite the manual edit**.

Every AI request includes:

```text
taskId
baseTaskVersion
sourceTextHash
requestedFields
promptVersion
```

When the AI result arrives:

- apply a field only if that field has not been modified since `baseTaskVersion`;
- otherwise store the AI value as a suggestion or discard it;
- never overwrite newer user input;
- if the task was deleted/cancelled before AI returns, do not resurrect it;
- if a task was completed before enrichment returns, descriptive enrichment may still apply if safe, but status must not change.

## 5.7 AI prompt safety

Task text is untrusted data.

The Foundry calls used here:

- have no tools;
- cannot call arbitrary services;
- return strict structured output;
- receive clearly delimited task content;
- cannot set authorization/identity fields.

A transcript saying "ignore the previous instructions" is task content, not an instruction to the application.

Set input length limits to avoid accidental or malicious oversized requests.

---

# 6. Task model

## 6.1 Core task

Conceptual schema:

```csharp
public sealed class TaskItem
{
    public required string Id { get; init; }              // UUIDv7/ULID-style client generated ID
    public required string WorkspaceId { get; init; }

    public string? ParentTaskId { get; init; }
    public required string RootTaskId { get; init; }

    public TaskKind Kind { get; set; }                     // Actionable | Container
    public TaskLifecycleStatus Status { get; set; }        // Open | InProgress | Completed | Cancelled

    public required string Title { get; set; }
    public string? Description { get; set; }

    public string? ContentLanguage { get; set; }           // fi | en | und
    public TaskSource Source { get; set; }                 // Voice | Typed | AiSplit | ManualSubtask | Recurrence

    public string? OriginalTranscript { get; set; }

    public string QueueRank { get; set; }

    public string CreatedByMemberId { get; init; }
    public DateTimeOffset CreatedAtClient { get; init; }
    public DateTimeOffset? CreatedAtServer { get; set; }

    public string? ClaimedByMemberId { get; set; }
    public DateTimeOffset? ClaimedAt { get; set; }

    public IReadOnlyList<string> CompletedByMemberIds { get; set; }
    public DateTimeOffset? CompletedAtClient { get; set; }
    public DateTimeOffset? CompletedAtServerReceived { get; set; }

    public DueValue? Due { get; set; }

    public DateTimeOffset? SnoozedUntil { get; set; }

    public string? AreaId { get; set; }

    public IReadOnlyList<string> DependencyTaskIds { get; set; }

    public RecurrenceRef? Recurrence { get; set; }

    public int? EffortEstimate { get; set; }                // optional planning estimate, not individual score

    public AiProcessingState AiState { get; set; }

    public long ServerVersion { get; set; }
    public int SchemaVersion { get; set; }

    public bool IsDeleted { get; set; }
    public DateTimeOffset? DeletedAt { get; set; }
}
```

Actual storage models may differ from domain models.

## 6.2 Separate state dimensions

Do not overload one enum with everything.

Use distinct dimensions:

### Lifecycle

```text
OPEN
IN_PROGRESS
COMPLETED
CANCELLED
```

### Kind

```text
ACTIONABLE
CONTAINER
```

### AI processing

```text
NONE
PENDING
PROCESSING
READY
NEEDS_REVIEW
FAILED
```

### Sync state — local-only concern

```text
SYNCED
LOCAL_ONLY
PENDING
CONFLICT
FAILED
```

### Availability — mostly derived

A task may be:

- active;
- snoozed;
- blocked by dependencies;
- hidden because completed/cancelled/deleted.

## 6.3 IDs

Generate task IDs on the client so offline-created tasks can be referenced immediately by later offline operations.

Use UUIDv7 if ecosystem support is convenient, otherwise ULID or normal random UUID.

Requirements:

- globally unique without server allocation;
- stable forever;
- child tasks can refer to local parent before sync;
- operations can refer to local tasks before sync.

---

# 7. Task splitting and hierarchy

## 7.1 Split semantics

Before:

```text
Clean apartment
kind = ACTIONABLE
```

After:

```text
Clean apartment
kind = CONTAINER
│
├── Clean kitchen
├── Clean bathroom
└── Vacuum bedrooms
```

The parent becomes a progress/container task.

Only unfinished actionable leaves are normally claimable.

Container completion is derived from descendants.

## 7.2 Nested splitting

Support nested splitting in the data model.

Recommended v1 UI limit:

- maximum visible hierarchy depth: 3;
- maximum AI-generated children per split: 8;
- AI should prefer 2–6 useful actionable pieces.

Server rejects cycles.

If a user wants more granularity beyond UI depth, consider allowing it only after future UX review rather than creating an infinitely nested task tree.

## 7.3 Progress

For a container:

```text
completed non-cancelled actionable leaves
/
all non-cancelled actionable leaves
```

Cancelled leaves are excluded from the denominator.

If all non-cancelled actionable leaves become completed:

- container automatically becomes completed;
- completion time is based on the last completing descendant;
- an automatic domain event records the transition.

If a completed descendant is reopened:

- affected ancestor containers automatically reopen.

If every child is cancelled and no actionable child remains:

- do not silently call the parent "completed";
- either mark the container cancelled or surface "No active subtasks";
- recommended default: auto-cancel the container only when all children are cancelled.

## 7.4 AI split flow

User:

```text
[Split]
```

Optional instruction:

```text
"Pilko tämä huoneittain."
"Split this so we can work independently."
```

Instruction can be dictated locally with Parakeet.

If online:

1. submit split request;
2. show clear progress state;
3. receive strict structured result;
4. show preview;
5. user may edit/remove suggested children;
6. confirm;
7. server atomically converts parent and creates children.

If offline:

1. store the split instruction locally;
2. mark AI split request pending;
3. manual subtasks remain available;
4. process AI request after reconnection;
5. before applying, verify parent base version;
6. if the parent changed materially, require review rather than blindly applying stale children.

## 7.5 Split conflicts

If two users split the same task independently while disconnected:

- do not merge both generated trees automatically;
- first accepted split changes the parent version/kind;
- second split becomes an explicit conflict;
- show a localized message such as:
  - FI: "Jenny pilkkoi tämän tehtävän sillä aikaa kun olit offline-tilassa."
  - EN: "Jenny split this task while you were offline."
- preserve the losing split proposal as recoverable text/suggestion until user dismisses it.

---

# 8. Queue ordering

## 8.1 Queue is shared priority

Queue order means shared preferred order.

It does **not** mean only item #1 can be selected.

Any available actionable task may be opened or claimed.

## 8.2 Rank storage

Use fractional ranking / LexoRank-like keys rather than integer positions.

Do not renumber the whole queue on each move.

Example:

```text
A   "a"
B   "m"
C   "z"
```

Insert between A and B:

```text
A   "a"
X   "g"
B   "m"
```

Periodic server-side rebalance is allowed.

## 8.3 Offline move operations store intent

Do not put a raw generated rank in the mutation log.

Store intent:

```json
{
  "type": "MOVE_TASK",
  "taskId": "task-x",
  "afterTaskId": "task-a",
  "beforeTaskId": "task-b"
}
```

When the server receives it, it interprets that intent against the newest canonical queue and calculates the rank.

If an anchor disappeared because it completed/deleted while the device was offline:

1. use the surviving anchor if possible;
2. otherwise use nearest surviving neighbor according to the client's previous local queue snapshot;
3. otherwise append deterministically;
4. return canonical rank to the client.

Multiple moves from one offline device must be replayed in that device's local sequence order.

## 8.4 New-task placement

Default new tasks should **not silently leap above existing priorities**.

Recommended default:

- append new tasks to the end of the active unclaimed queue;
- visually highlight newly created task briefly;
- if the user explicitly says "high priority", "ensimmäiseksi", "put this at the top", etc., AI may return an explicit placement intent;
- only explicit priority language may change insertion location automatically.

Avoid a separate "priority" enum unless a future use case requires it; queue order is already the priority system.

---

# 9. Claiming, completion, and collaboration

## 9.1 Claim

A claim means:

> "I intend to work on this."

Only one primary claimant in v1.

Online claim is server-authoritative.

Offline claim is provisional:

- update local UI immediately;
- enqueue claim operation;
- on sync, server accepts only if still claimable;
- if the other member claimed first on the server, reject and reconcile.

The losing client gets an explicit conflict message rather than silently changing state.

## 9.2 Complete without claim

Allow completing an unclaimed task.

On completion:

- actor is recorded as a completer;
- status becomes completed;
- no prior claim is required.

## 9.3 Who completed the task

Store `CompletedByMemberIds`, not only one scalar, even if v1 normally inserts one member.

This allows a future/simple "Done together" action without a data migration.

Default UI can remain one-person completion.

## 9.4 Unclaim

Allow unclaim unless task is already completed/cancelled.

Offline unclaim is queued normally.

---

# 10. Snooze / availability

A snoozed task is removed from the active queue until its availability time.

Data:

```text
snoozedUntil
queueRank remains intact
```

When the time passes:

- local app may make it visible immediately based on local clock;
- backend also treats it as available;
- next sync reconciles state.

Do not require a server job merely to "unsnooze" a task. Availability can be derived from current time.

Date-only snooze options:

- later today;
- tomorrow;
- next week;
- custom.

All localized.

---

# 11. Dependencies / blocking

Tasks may depend on other tasks within the same workspace.

Example:

```text
Paint wall
depends on
Buy paint
```

A task is blocked while any non-cancelled prerequisite is incomplete.

Rules:

- dependency graph must be acyclic;
- server validates cycle creation;
- self-dependency prohibited;
- deleted/cancelled prerequisite should not permanently deadlock dependent tasks;
- recommended default: cancelled prerequisite is treated as no longer blocking, but activity feed records it;
- blocked tasks remain visible in detail but can be hidden/lowered in main actionable queue;
- default: blocked tasks cannot be claimed;
- offline clients may have stale dependency state, so a provisional offline claim may be rejected at sync if the server still sees the task as blocked.

---

# 12. Recurrence

Recurrence is included in v1, but keep semantics deliberately constrained.

## 12.1 Model recurrence as a template

A recurrence definition creates task occurrences.

Do not mutate one eternal task every week.

Conceptually:

```text
RecurrenceTemplate
  ↓
Occurrence 2026-09-19
Occurrence 2026-09-26
Occurrence 2026-10-03
```

Unique occurrence key:

```text
templateId + scheduledLocalOccurrence
```

This prevents duplicate creation.

## 12.2 V1 recurrence feature set

Support:

- daily;
- weekly;
- selected weekdays;
- monthly by day-of-month;
- every N days/weeks/months;
- optional end date;
- workspace timezone.

Store schedule in local-time semantics + IANA timezone.

A full arbitrary RRULE editor is not required in v1.

## 12.3 Split recurrence rule

If one occurrence is split ad hoc:

```text
Vacuum apartment
→ Bedroom
→ Kitchen
→ Living room
```

the next recurrence creates a fresh **unsplit** occurrence by default.

Do not clone ad-hoc child decomposition automatically.

Future enhancement: recurrence templates with predefined subtasks.

## 12.4 Offline recurrence

The phone may locally predict/display an occurrence if needed, but the backend is responsible for canonical occurrence creation and deduplication.

If a device remains offline over a recurrence boundary:

- it can create a provisional occurrence using deterministic occurrence ID/key;
- sync must deduplicate it with a server-created occurrence.

This avoids duplicate weekly tasks.

---

# 13. Areas and tags

Avoid unrestricted AI-generated tag sprawl in two languages.

Recommended v1:

## Areas

User-defined shared areas, e.g.:

```text
Koti / Home
Asiointi / Errands
Matkat / Travel
Talous / Finance
```

Area is a stable ID with localized display labels where useful.

AI may assign an area only from the existing area list unless the user explicitly requests creating a new one.

## Tags

Optional lightweight free-form tags may exist, but should not be central to v1.

If included:

- normalize case;
- preserve original display text;
- do not auto-create arbitrary tags from every AI request;
- user can add manually.

---

# 14. Notes

Tasks may have append-only notes.

Note:

```text
id
taskId
authorMemberId
body
contentLanguage
createdAtClient
createdAtServer
isDeleted
```

Editing a note may be implemented as a new revision or simple edit event.

Notes must work offline.

Description is the canonical "what is this task?" field; notes are timeline-style additions.

---

# 15. Deletion

Use soft deletion.

Fields:

```text
isDeleted
deletedAt
deletedBy
```

Deleted tasks disappear from normal views but leave tombstones long enough for offline devices to learn about the deletion.

Recommended retention:

- tombstones: at least 30–90 days;
- choose a period longer than the expected maximum offline device interval;
- purge later with a scheduled cleanup.

If a phone returns after tombstone retention has expired, a full resync must prevent resurrection of old local tasks.

A client must never treat "not returned by incremental sync" as proof that a local item should be uploaded again.

---

# 16. Shared progress and gamification

## 16.1 Product intent

The statistics screen should answer:

> "What are we getting done together?"

It should **not** answer:

> "Which one of us is doing more?"

The backend still records actors for history, correctness, and possible future private analytics, but the default product has:

- no leaderboard;
- no side-by-side member scores;
- no "Pasi 43 / Jenny 31" view;
- no winner/loser language;
- no completion share pie chart;
- no home-screen point display.

## 16.2 Recommended "Together" dashboard

Suggested Finnish UI:

```text
Yhdessä

Tällä viikolla
12 tehtävää valmiiksi

Viikon alun tehtävistä
68 % valmiina

Aktiiviset tehtävät
21 → 16   (-5)

6 aktiivista viikkoa peräkkäin

🎉 250 tehtävää tehty yhdessä
```

English:

```text
Together

This week
12 tasks completed

Of tasks open at the start of the week
68% completed

Active queue
21 → 16   (-5)

6 active weeks in a row

🎉 250 tasks completed together
```

## 16.3 Metrics

### A. Shared completions

Count **root tasks completed**, not every generated subtask.

Why:

- AI splitting does not inflate the score;
- manual micro-task creation under a root does not inflate the score;
- the metric stays understandable.

Subtask completions can be shown as secondary operational information but should not drive milestones.

### B. Start-of-period clearance rate

At the beginning of a period (week/month), snapshot root tasks that are:

- open/in progress;
- not cancelled/deleted;
- actionable or containers representing active work.

Then:

```text
clearance rate =
number from start-of-period set now completed
/
number in start-of-period set
```

New tasks added during the week do not make the percentage worse.

They can still contribute to "tasks completed this week."

This makes the percentage much more stable and less demotivating than:

```text
completed / current queue
```

where adding legitimate work would reduce the score.

### C. Queue trend

Show:

```text
active root tasks at period start
→
active root tasks now
```

Do not label queue growth as failure; sometimes a week legitimately discovers more work.

Phrase it neutrally.

### D. Shared streak

Prefer weekly rather than daily streaks.

Example definition:

> A week is active if at least one root task was completed.

Daily streaks can create unnecessary pressure.

Make streak display optional.

### E. Shared milestones

Examples:

- 25 root tasks completed together;
- 100;
- 250;
- 500;
- 1,000;
- 10 active weeks;
- 25 active weeks.

Avoid assigning milestones to an individual.

## 16.4 Optional internal effort estimate

Tasks may have an `EffortEstimate`, e.g.:

```text
1 tiny
2 small
3 normal
5 large
8 very large
```

Use it for:

- planning;
- future weighted metrics;
- AI split quality;
- possibly an optional team goal.

Do **not** show individual effort totals.

If a root task is split, the root remains the unit for shared milestone scoring so splitting cannot create more shared reward.

If a future "shared points" view is added, award the root task's team value once when the root completes.

## 16.5 Personal stats

Not in default v1 product surfaces.

If ever added:

- make them private to the current user;
- never default to comparison;
- no notifications saying another user is ahead.

---

# 17. Activity feed

Activity is operational, not competitive.

Examples:

```text
Jenny completed "Call electrician"        14:08
Pasi moved "Clean balcony" higher         13:46
Jenny added "Buy dog food"                12:10
AI split "Organize storage" into 4 parts  11:44
```

Localized action text.

Activity may include names because provenance matters.

Store structured event types rather than pre-rendered English/Finnish sentences. Render localization on device.

---

# 18. Offline data model on Android

Recommended stack:

- Kotlin
- Jetpack Compose
- Room
- WorkManager
- Kotlin Coroutines / Flow
- Kotlinx Serialization
- Retrofit or Ktor client
- MSAL
- Firebase Cloud Messaging

The UI observes Room via `Flow`.

Network responses write into Room.

UI does not directly bind to HTTP responses as its durable state.

## 18.1 Key Room tables

```text
workspace
member
task
task_note
area
recurrence_template
activity_event
pending_mutation
pending_ai_request
sync_cursor
conflict
temporary_audio
```

Some may be combined depending on implementation style.

## 18.2 Atomic local mutation

Every important action must be one Room transaction.

Example: complete task offline.

```text
BEGIN
  update task local state
  insert pending_mutation(operationId, COMPLETE_TASK, ...)
  insert local activity placeholder if useful
COMMIT
```

Never update task first and enqueue mutation later in a separate transaction.

Otherwise an app crash can lose synchronization intent.

---

# 19. Mutation/outbox format

Conceptual:

```json
{
  "operationId": "0199...",
  "workspaceId": "workspace-1",
  "deviceId": "device-a",
  "localSequence": 183,
  "actorMemberId": "derived-locally-but-server-validates-from-auth",
  "type": "COMPLETE_TASK",
  "entityId": "task-x",
  "baseServerVersion": 41,
  "occurredAtClient": "2026-09-12T14:38:14+03:00",
  "payload": {
    "completedByMemberIds": ["member-pasi"]
  },
  "schemaVersion": 1
}
```

Server does **not** trust `actorMemberId` from payload. It derives actor from authenticated principal/workspace membership.

Fields useful for all mutations:

- operation ID;
- device ID;
- monotonically increasing device-local sequence;
- client occurrence time;
- base server version where relevant;
- schema version.

## 19.1 Idempotency

Every operation must be idempotent.

If WorkManager retries the same mutation 10 times, it must be applied once.

Server stores an operation receipt keyed by operation ID.

Duplicate submission returns the previous result rather than performing the action again.

---

# 20. Synchronization protocol

## 20.1 Backend owns canonical multi-device order

Local state is authoritative for immediate UX.

Server state is authoritative for reconciliation between devices.

## 20.2 Recommended sync endpoint

Rather than dozens of chatty calls for offline replay, provide a synchronization endpoint.

Conceptually:

```http
POST /workspaces/{workspaceId}/sync
Authorization: Bearer ...
```

Request:

```json
{
  "deviceId": "device-a",
  "cursor": 812,
  "operations": [
    { "...": "..." }
  ],
  "maxChanges": 200
}
```

Response:

```json
{
  "acceptedOperations": [
    {
      "operationId": "...",
      "result": "APPLIED"
    }
  ],
  "rejectedOperations": [
    {
      "operationId": "...",
      "code": "TASK_ALREADY_CLAIMED",
      "canonicalEntity": { "...": "..." }
    }
  ],
  "changes": [
    {
      "serverRevision": 813,
      "type": "TASK_UPDATED",
      "entity": { "...": "..." }
    }
  ],
  "nextCursor": 813,
  "hasMore": false
}
```

## 20.3 Sync cycle

Recommended client cycle:

```text
1. Load pending mutations from Room in localSequence order.

2. Send a bounded batch plus last server cursor.

3. Server authenticates and verifies workspace membership.

4. Server applies each operation idempotently and in order.

5. Server returns:
   - accepted operations;
   - semantic conflicts/rejections;
   - canonical changed entities;
   - changes since cursor;
   - new cursor.

6. Client applies response to Room in one or a small number of transactions.

7. Preserve still-pending local operations and rebase their local projections if necessary.

8. Continue if server says more changes exist.

9. Mark successful mutations acknowledged.

10. Retry transient failures with exponential backoff.
```

Run sync:

- app startup;
- app foreground/resume;
- after local mutations when online;
- WorkManager connectivity-triggered job;
- after an FCM "workspace changed" signal.

## 20.4 FCM is a hint, never correctness

FCM message:

```text
workspace changed
workspaceId = ...
```

It triggers a sync.

Do not put canonical task state in the push payload.

If FCM is delayed or lost:

- opening/foregrounding the app syncs anyway;
- periodic WorkManager may sync;
- correctness is unaffected.

---

# 21. Server revision/change log

Incremental synchronization needs a stable server-side ordering that does not trust client clocks.

Use a monotonically increasing **workspace revision**.

Every accepted canonical mutation gets a revision.

Example:

```text
811 TaskCreated
812 TaskMoved
813 TaskCompleted
814 TaskSplit
```

Clients store `lastServerRevision`.

Do not use `occurredAtClient` to decide canonical conflict order. Client clocks can be wrong.

The client timestamp is historical metadata only.

## 21.1 Workspace change record

Conceptually:

```json
{
  "id": "change:0000000000000814",
  "workspaceId": "workspace-1",
  "type": "change",
  "serverRevision": 814,
  "operationId": "...",
  "entityType": "task",
  "entityId": "task-x",
  "changeKind": "UPDATED",
  "receivedAtServer": "..."
}
```

A change can contain enough state for incremental replay or reference the current entity.

At this tiny scale, returning the updated entity snapshot is simplest.

---

# 22. Conflict rules

Do not use generic last-write-wins for every domain action.

Use semantic conflict handling.

## 22.1 Non-conflicting examples

Device A offline:

```text
Move task higher
```

Device B:

```text
Complete task
```

Result:

- completion wins operationally;
- queue move becomes irrelevant because completed task is not in active queue;
- no scary user conflict dialog needed.

## 22.2 Claim vs claim

A true conflict.

Server accepts one claim.

Other mutation is rejected:

```text
TASK_ALREADY_CLAIMED
```

Client shows localized conflict and canonical claimant.

## 22.3 Edit vs edit

Field-level strategy:

- if edits touched different fields, merge;
- if both changed the same field from the same base version, server chooses canonical operation order but losing edit must be recoverable;
- show conflict only when user intent could be lost;
- retain losing text in conflict record until resolved/dismissed.

For title/description, silent destructive LWW is undesirable.

## 22.4 Complete vs edit

Usually merge:

- task becomes completed;
- descriptive edit can still apply.

## 22.5 Complete vs delete

Recommended:

- explicit delete after completion can delete;
- offline ordering determined by server acceptance/revision;
- if both actions came from stale clients, prefer not to resurrect;
- deletion is stronger than ordinary edit;
- preserve audit events.

## 22.6 Reopen vs complete

If concurrent and based on same version, treat as conflict because states represent opposing intent.

## 22.7 Dependency conflict

Server validates the resulting graph.

If offline operation would create a cycle against newer server state:

```text
DEPENDENCY_CYCLE
```

Reject and explain.

## 22.8 Recurrence conflict

Template changes use version checks.

Occurrence generation uses deterministic unique keys so retries/devices cannot create duplicates.

---

# 23. Historical timestamps

Events need at least:

```text
occurredAtClient
receivedAtServer
```

Example:

- task completed Monday while offline;
- sync reaches server Wednesday.

Statistics should normally credit completion to Monday, while auditing knows the server received it Wednesday.

Do not use Cosmos/server write timestamp as the only completion timestamp.

Because client clocks can be wrong, very extreme clock skew should be flagged. Do not use client time to determine who won a concurrency conflict.

---

# 24. Azure backend architecture

Recommended:

```text
Android
  │
  │ HTTPS / Entra token
  ▼
Azure Functions (.NET 10 isolated, Flex Consumption)
  │
  ├── Sync/domain API
  ├── AI orchestration
  ├── Recurrence generation
  ├── notification trigger
  │
  ├────────► Microsoft Foundry
  │           GPT-5.6 Luna
  │           optional Terra fallback
  │
  ├────────► Azure Cosmos DB
  │
  └────────► Firebase Cloud Messaging
```

Use Bicep for infrastructure.

Use managed identity for Azure-to-Azure authentication wherever possible.

## 24.1 Why Functions Flex Consumption

For this small workload:

- event-driven;
- can scale to zero;
- no need for always-on infrastructure initially;
- low idle cost;
- easy HTTP + scheduled jobs.

Cold start can happen after idle periods. The architecture intentionally hides much of that cost:

- task exists locally before the backend responds;
- AI cleanup is asynchronous;
- sync is not required for immediate local interaction.

AI split preview is a user-waiting operation; show progress clearly.

If cold start later proves annoying, enable a minimal Always Ready configuration. Do not pay for it preemptively.

---

# 25. Cosmos DB design

## 25.1 Important design choice: one workspace-scoped container

Use one main container for workspace-scoped domain/sync items:

```text
container: workspace-items
partition key: /workspaceId
```

Do **not** put Tasks, Events, OperationReceipts, and WorkspaceSyncState in separate containers if we need atomic cross-item domain transitions.

Reason:

Cosmos transactional batches can atomically group operations only when they share the same partition key in the same container.

This is valuable for:

- split parent + children + event + operation receipt;
- completion + ancestor container updates + event;
- claim + event + operation receipt;
- server revision update + change record + task mutation.

## 25.2 Item discriminator

Examples:

```text
workspace
member
task
note
area
recurrence-template
event
operation-receipt
change
stats-snapshot
```

IDs can be prefixed:

```text
workspace:{id}
member:{id}
task:{id}
note:{id}
event:{id}
op:{operationId}
change:{revision}
recurrence:{id}
```

Every item contains:

```text
workspaceId
type
schemaVersion
```

A separate account/global container is optional for app-wide metadata, but most v1 data can remain workspace-scoped.

## 25.3 Workspace revision transaction

Maintain a workspace sync metadata document:

```json
{
  "id": "sync-state",
  "workspaceId": "workspace-1",
  "type": "workspace-sync-state",
  "serverRevision": 814,
  "_etag": "..."
}
```

When applying a mutation:

1. read current sync state and its server `_etag`, then read dependent state using the chosen consistent-read strategy;
2. calculate next revision;
3. transactional batch:
   - update domain entity/entities;
   - create event(s);
   - create operation receipt;
   - create change record;
   - replace sync-state revision conditionally with `If-Match` on that `_etag`;
4. on optimistic concurrency failure, reread state and recompute the whole command before retrying.

Every writer must participate in this protocol, including recurrence, AI results, membership changes, and repairs. Define session-token propagation or a stronger consistency policy across Functions instances before implementing cursor reads. A revision number is not a Cosmos session token.

Budget each transaction against the documented 100-operation, 2 MB, and five-second limits. Include domain items, events, receipts, change records, and sync metadata. Hierarchy, deletion, and rank-rebalance limits remain open in [Bound task hierarchy, dependencies, and ordering](https://github.com/DrBushyTop/bun-do/issues/6). See the [platform research](docs/research/azure-platform-constraints.md) for primary sources.

Traffic is tiny, so serializing per-workspace mutations at this layer is acceptable.

## 25.4 Free-tier consideration

Cosmos DB lifetime free tier currently provides 1,000 RU/s and 25 GB when enabled on an eligible provisioned-throughput account.

Important:

- free tier must be enabled when the account is created;
- only one free-tier Cosmos account per Azure subscription;
- serverless is not the same free-tier mode.

This application will be tiny relative to 1,000 RU/s.

If the subscription's free-tier account is already consumed, sponsorship/normal Cosmos billing is still unlikely to be significant at this scale.

---

# 26. Authentication and membership

Recommended:

Microsoft Entra External ID + MSAL native authentication.

For two users, email one-time passcode is a good default.

Domain mapping:

```text
Entra subject
→ App user/member
→ Workspace membership
```

Server derives actor from token and membership.

Never accept arbitrary `createdBy`, `completedBy`, `actor`, etc. from the client as authoritative.

## 26.1 Invitation

V1 can be simple:

- owner creates workspace;
- invite email address;
- invited user signs in;
- backend links matching External ID identity to pending invitation.

Avoid public workspace discovery.

## 26.2 Offline authentication

An expired access token must not make the offline app unusable.

While offline:

- user can continue using the last locally authenticated workspace;
- operations accumulate locally;
- sync waits for valid authentication.

If the member was removed from the workspace while their phone was offline:

- server rejects sync after reauthentication;
- do not silently upload rejected data elsewhere;
- preserve local changes temporarily so the user can view/recover them;
- offer an explicit local-data cleanup after acknowledgement.

Security limitation:

No cloud system can remotely revoke data already cached on an offline phone. Treat this as an accepted offline-first trade-off.

---

# 27. Backend API

Normal online API can expose convenient endpoints, but the client synchronization contract is the fundamental offline path.

Suggested endpoints:

```text
POST   /workspaces/{id}/sync

GET    /workspaces/{id}
PATCH  /workspaces/{id}

POST   /tasks
PATCH  /tasks/{id}

POST   /tasks/{id}/claim
POST   /tasks/{id}/unclaim
POST   /tasks/{id}/complete
POST   /tasks/{id}/reopen
POST   /tasks/{id}/cancel
POST   /tasks/{id}/move

POST   /tasks/{id}/split/prepare
POST   /tasks/{id}/split/commit
POST   /tasks/{id}/clarify

POST   /tasks/{id}/snooze
POST   /tasks/{id}/unsnooze

POST   /tasks/{id}/notes

POST   /tasks/{id}/dependencies
DELETE /tasks/{id}/dependencies/{dependencyId}

POST   /tasks/{id}/delete
POST   /tasks/{id}/restore

GET    /stats
GET    /activity

POST   /devices
DELETE /devices/{id}
```

The app may implement most mutations through `/sync` rather than invoking all action endpoints while replaying offline work.

REST action endpoints remain useful for debugging, future clients, and simple immediate online calls.

---

# 28. AI extraction schema guidance

Partial provider-schema sketch. Supply complete `due` and `recurrence` definitions before deployment. Keep the provider schema within Azure's supported strict-output subset, then enforce title length 160, description length 4000, at most five ambiguities, valid dates, and domain limits in server validation. The schema must pass a real deployment check; this sketch is not a complete API request.

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "language",
    "title",
    "description",
    "due",
    "recurrence",
    "areaId",
    "explicitPlacement",
    "ambiguities",
    "needsReview"
  ],
  "properties": {
    "language": {
      "type": "string",
      "enum": ["fi", "en", "und"]
    },
    "title": {
      "type": "string"
    },
    "description": {
      "type": ["string", "null"]
    },
    "due": {
      "anyOf": [
        { "type": "null" },
        { "$ref": "#/$defs/due" }
      ]
    },
    "recurrence": {
      "anyOf": [
        { "type": "null" },
        { "$ref": "#/$defs/recurrence" }
      ]
    },
    "areaId": {
      "type": ["string", "null"]
    },
    "explicitPlacement": {
      "type": ["string", "null"],
      "enum": [null, "TOP", "BOTTOM"]
    },
    "ambiguities": {
      "type": "array",
      "items": { "type": "string" }
    },
    "needsReview": {
      "type": "boolean"
    }
  }
}
```

Exact Foundry structured-output schema syntax should be implemented according to the current SDK/API.

For Azure OpenAI v1 Responses, use `text.format` for the strict schema and `reasoning.effort` for reasoning. Use `store: false` for independent extraction requests. Handle refusal, incomplete generation, and invalid-schema configuration errors separately from retriable outages. A fallback model cannot repair an invalid schema. See [Azure platform research](docs/research/azure-platform-constraints.md).

Prompt rules should state:

- preserve intent;
- preserve language;
- concise title;
- description only from supplied information;
- do not invent due dates;
- do not invent recurrence;
- explicit placement only if user explicitly requested priority/order;
- use only area IDs provided in the allowed list;
- surface ambiguity instead of guessing.

---

# 29. AI split schema guidance

Input:

```json
{
  "task": {
    "title": "Järjestä varasto",
    "description": "..."
  },
  "instruction": "Pilko huoneittain tai tavaratyypeittäin",
  "language": "fi"
}
```

Output:

```json
{
  "language": "fi",
  "children": [
    {
      "title": "...",
      "description": "...",
      "effortEstimate": 2
    }
  ],
  "notes": null,
  "needsReview": false
}
```

Rules:

- 2–6 children preferred;
- hard maximum 8;
- children should be independently actionable when possible;
- no child should merely restate the parent;
- preserve task language;
- no creation until preview is confirmed;
- no automatic recursive split;
- no identity assignment;
- no queue placement except relative child placement beneath parent if UI uses grouping.

---

# 30. Clarify action

Clarify is non-destructive.

Example:

```text
"Hoida vakuutusjuttu"
```

AI suggestion:

```text
"Vertaa nykyisen vakuutuksen uusimishintaa vaihtoehtoihin ja päätä uusitaanko vakuutus."
```

Clarify returns suggested fields.

User previews and accepts.

Do not silently rewrite an existing task.

Clarify works from typed or voice instruction.

Offline clarify request can queue until online.

---

# 31. Notifications

Push is for convenience.

Default notification policy should be conservative.

Possible user-visible notifications:

- due soon;
- overdue;
- a task explicitly assigned to you in a future assignment feature;
- a sync conflict needing attention;
- optional "new task added" notification if user enables it.

Do not notify for every reorder, note, AI cleanup, or completion by default.

Silent FCM push can trigger synchronization without showing a notification.

FCM is currently a no-cost Firebase product.

---

# 32. Localization implementation

## 32.1 Android resources

All UI strings must be resources.

Suggested:

```text
res/values/strings.xml       Finnish default product strings
res/values-en/strings.xml    English
```

Alternatively use conventional English default resources but explicitly set Finnish as the app's initial preferred locale. The critical requirement is complete parity.

Avoid hard-coded strings in Composables.

Use Android plurals:

```text
1 tehtävä
2 tehtävää

1 task
2 tasks
```

Localize:

- dates;
- times;
- number formatting;
- recurrence descriptions;
- accessibility labels;
- error messages;
- notifications;
- activity event rendering.

## 32.2 Per-app language selection

Settings:

```text
Kieli / Language

○ Suomi
○ English
○ Järjestelmän kieli / System default
```

Recommended first-run behavior:

- device Finnish → Finnish;
- device English → English;
- unsupported device language → Finnish fallback.

Persist selection.

## 32.3 Backend error localization

Backend returns:

```json
{
  "code": "TASK_ALREADY_CLAIMED",
  "args": {
    "memberDisplayName": "Jenny"
  }
}
```

Android renders the correct language.

Do not make backend prose the user-facing contract.

## 32.4 Content language

Store task `contentLanguage`.

It controls:

- AI output language;
- potentially voice read-back later;
- language-specific text processing.

It does not need to equal app UI language.

---

# 33. Observability and privacy

## 33.1 Measure useful timings

Collect non-content telemetry:

- local ASR duration;
- audio duration;
- AI extraction duration;
- AI split duration;
- AI success/failure;
- sync duration;
- pending mutation count;
- conflict count by code;
- Functions request latency;
- Cosmos RU/latency;
- retry counts.

This helps optimize perceived speed.

## 33.2 Do not log task content

Avoid sending:

- transcripts;
- task titles;
- descriptions;
- notes;
- raw AI prompts/responses;
- raw audio

into general application telemetry.

Application Insights/OpenTelemetry should use IDs, status codes, timing, model/deployment, token counts, and error classes, not household text.

## 33.3 AI request audit

Store minimal AI metadata:

```text
requestId
taskId
operationType
modelDeployment
promptVersion
schemaVersion
baseTaskVersion
startedAt
completedAt
status
token usage
```

Raw request/response retention should be off by default or short-lived only for explicit debug mode.

---

# 34. Security

- HTTPS only.
- Validate issuer, audience, signature, expiry.
- Server checks workspace membership on every request.
- Managed identity from Functions to Cosmos/Foundry where supported.
- FCM credential stored securely, e.g. Key Vault; never ship server credential to APK.
- Access tokens stored with Android/MSAL-supported secure mechanisms.
- Do not put Foundry keys in Android.
- Do not put Cosmos credentials in Android.
- AI cannot authorize domain actions.
- Validate every AI-produced enum/ID against server-owned allowed values.
- Rate-limit unusually large AI requests.
- Limit transcript and note lengths.
- Validate dependency graph and hierarchy depth server-side.
- Escape/render text safely; no HTML interpretation by default.

---

# 35. Infrastructure as code

Use Bicep.

Resources likely:

```text
Resource group
├── Function App — Flex Consumption
├── Storage account required by Functions
├── Cosmos DB account
│   └── database
│       └── workspace-items container
├── Microsoft Foundry / Azure OpenAI resource + deployments
├── Application Insights / Log Analytics as desired
├── Key Vault (mainly non-Azure external secret such as FCM credential)
└── Entra External ID configuration is partially outside normal ARM/Bicep scope
```

Prefer one environment initially:

```text
dev
prod
```

For a personal app, even a single production environment plus local emulator/development may be enough, but IaC should keep environment parameters clean.

---

# 36. Cost posture

The design intentionally has minimal idle cloud cost.

Current relevant platform characteristics:

- Azure Functions Flex Consumption on-demand has no required minimum instance count and includes a monthly free grant on eligible pay-as-you-go subscriptions.
- Cosmos DB lifetime free tier can provide 1,000 RU/s + 25 GB if enabled on an eligible provisioned-throughput account.
- Firebase Cloud Messaging has no usage cost.
- Parakeet runs locally.
- The main variable cloud intelligence cost is Foundry model usage.

At this scale, cost should be extremely low, particularly with sponsorship credits.

Optimize primarily for:

1. UX latency;
2. correctness;
3. simplicity;
4. privacy;
5. then model price.

---

# 37. Important edge cases and required behavior

This section is intentionally adversarial. These cases should become tests.

## 37.1 Voice task created offline, then completed before AI runs

Expected:

- local task completes normally;
- AI may later enrich untouched descriptive fields;
- AI must not reopen it;
- completion timestamp stays original local occurrence time;
- shared stats count it once.

## 37.2 Voice task edited before AI result

Expected:

- manually edited fields are protected;
- stale AI result cannot overwrite;
- untouched fields may still be enriched;
- AI suggestion may be displayed if useful.

## 37.3 Voice task deleted before AI result

Expected:

- AI result discarded;
- task never resurrected.

## 37.4 Same task claimed by both users offline

Expected:

- both may temporarily see a provisional local claim;
- first accepted canonical claim wins;
- other client receives conflict;
- losing user sees who owns it;
- no silent overwrite.

## 37.5 User completes task while other user moves it

Expected:

- completion persists;
- move becomes irrelevant;
- no unnecessary conflict dialog.

## 37.6 Both users edit title offline

Expected:

- do not silently destroy losing text;
- conflict contains both variants;
- offer choose/merge.

## 37.7 Both users split same task offline

Expected:

- never auto-merge task trees;
- one split accepted;
- second proposal preserved for review.

## 37.8 Parent split while another user completes parent

Expected:

- server semantic rule required.
Recommended:
  - once parent is converted to container, direct parent completion is no longer valid;
  - if COMPLETE_PARENT arrives first, SPLIT must require confirmation/reopen or be rejected as stale;
  - if SPLIT arrives first, COMPLETE_PARENT is rejected because container completion is derived.

## 37.9 Child completed, then split

If a leaf is already completed:

- either reopening is required before splitting;
- simplest v1: reject splitting completed task with `TASK_NOT_SPLITTABLE`;
- user can reopen explicitly.

## 37.10 Child deleted under completed container

Expected:

- ancestor derived completion/progress recalculated;
- deletion should not create impossible 3/2 progress;
- change event recorded.

## 37.11 Last child cancelled

Expected:

- parent should not appear as successfully completed;
- recommended parent auto-cancel.

## 37.12 Dependency task is deleted/cancelled

Expected:

- dependents do not remain permanently blocked;
- cancellation/deletion removes blocking effect;
- history preserved.

## 37.13 Dependency cycle formed by two offline devices

Example:

A offline adds A → B.
B offline adds B → A.

Expected:

- server accepts first;
- rejects second with cycle conflict.

## 37.14 Queue anchors vanish during offline reorder

Expected:

- server reconstructs intent with surviving anchors;
- deterministic fallback;
- no corrupt rank.

## 37.15 Rank density exhausted

Expected:

- server rebalances ranks;
- client does not care about exact string values;
- rank rebalance creates canonical changes but should not spam activity feed.

## 37.16 Client clock wrong

Expected:

- historical `occurredAtClient` may be flagged;
- canonical mutation ordering uses server revision, not client time;
- extreme future/past times can be clamped for stats display or marked uncertain.

## 37.17 Token expired while offline

Expected:

- local app works;
- sync pauses;
- reauthentication requested when online;
- pending mutations remain intact.

## 37.18 User removed from workspace while offline

Expected:

- server rejects mutations;
- local data is not silently deleted immediately;
- user can inspect/export/recover own unsynced text;
- explicit cleanup path.

## 37.19 Device is offline longer than tombstone retention

Expected:

- incremental cursor may be declared too old;
- backend requests full resync;
- client reconciles without resurrecting deleted items.

## 37.20 Duplicate WorkManager retry

Expected:

- same operation ID returns same receipt;
- no duplicate completion/event/subtasks.

## 37.21 AI split request retried

Expected:

- AI request ID/idempotency prevents multiple committed child sets;
- preview may be regenerated only deliberately.

## 37.22 Recurring occurrence generated by phone and backend

Expected:

- deterministic occurrence key deduplicates.

## 37.23 DST transition

Expected:

- date-only tasks stay on the same date;
- recurring 08:00 task remains 08:00 local time;
- timezone conversion happens only when deriving instant/reminder.

## 37.24 User switches UI language

Expected:

- task content does not translate;
- UI/event labels switch immediately;
- future AI actions preserve task language unless instructed otherwise.

## 37.25 Finnish UI + English dictation

Expected:

- English transcript;
- English task title/description;
- Finnish buttons/errors/navigation.

## 37.26 English UI + Finnish dictation

Expected mirror of above.

## 37.27 Ambiguous date

Example:

```text
"do this 10/11"
```

Expected:

- locale/source language may inform parsing;
- if still ambiguous, `needsReview=true`;
- do not silently invent a date.

## 37.28 AI invents an area ID

Expected:

- server rejects area ID not in provided allow-list;
- task remains valid without area.

## 37.29 AI output schema mismatch

Expected:

- retry once/fallback policy;
- local task remains usable;
- mark AI failed if needed;
- offer retry;
- never lose task.

## 37.30 Foundry outage

Expected:

- task app continues;
- AI requests remain pending/retriable;
- manual editing/splitting works;
- no queue loss.

## 37.31 Cosmos/Functions outage

Expected:

- offline-first app continues locally;
- mutations queue;
- clear but non-alarming sync indicator;
- retry later.

## 37.32 FCM outage

Expected:

- nothing correctness-critical breaks;
- next foreground/WorkManager sync catches up.

## 37.33 App killed during local write

Expected:

- Room transaction means task state and outbox mutation either both commit or neither commits.

## 37.34 App killed during sync response apply

Expected:

- response application is transactional/idempotent;
- next sync safely repeats.

## 37.35 Very large queue

Even though expected use is small:

- Compose LazyColumn;
- indexed Room queries;
- incremental server sync;
- do not load unbounded event history onto main screen.

---

# 38. Decisions made after adversarial review

These are explicit corrections/choices and should not be casually undone by the implementation agent.

## Decision 1 — one Cosmos workspace container

Use one workspace-scoped container so domain changes can use transactional batches.

## Decision 2 — semantic sync, not generic row replication

Use operations/mutations and server revisions rather than blindly syncing row timestamps.

## Decision 3 — server revision, not client timestamp, determines canonical order

Client timestamps are history, not concurrency authority.

## Decision 4 — AI results are versioned patches

AI may only apply to fields that have not changed since the request's base version.

## Decision 5 — stats count root tasks for core shared milestones

Splitting must not inflate the "together" score.

## Decision 6 — no individual leaderboard in v1

Attribution remains in data; stats aggregate by workspace.

## Decision 7 — queue rank is the priority system

Do not add an independent generic priority enum unless a real requirement appears.

## Decision 8 — new tasks append by default

AI may only move a new task to the top when priority placement was explicitly requested.

## Decision 9 — recurrence creates occurrences from templates

Do not recycle a single mutable task forever.

## Decision 10 — ad-hoc split does not automatically change recurrence template

Next recurrence is unsplit unless explicitly configured otherwise.

## Decision 11 — local Parakeet is the normal speech path

Cloud receives text, not audio, by default.

## Decision 12 — Functions cold starts are acceptable initially

Local task creation hides most server latency. Add Always Ready only if measured UX justifies it.

---

# 39. V1 scope

## Included

### Queue
- shared scrollable queue;
- drag reorder;
- choose any actionable task;
- claim/unclaim;
- complete/reopen;
- progress containers;
- task detail;
- completed/history views.

### Creation
- voice-first creation;
- local Parakeet;
- typed creation;
- offline creation;
- original transcript retention;
- cloud AI cleanup.

### AI
- GPT-5.6 Luna structured extraction;
- AI split;
- voice or typed split instructions;
- split preview;
- AI clarify;
- Terra configurable fallback/escalation;
- stale-result protection.

### Task features
- description;
- due date/time;
- areas;
- notes;
- snooze;
- dependencies/blocking;
- recurrence;
- manual subtasks;
- nested task model;
- soft deletion.

### Collaboration
- two or more workspace members in data model;
- creator;
- claimant;
- completer(s);
- activity feed;
- FCM-triggered sync.

### Offline
- local source of truth;
- mutation outbox;
- WorkManager;
- semantic conflicts;
- idempotency;
- incremental sync;
- recovery from long offline intervals.

### Shared progress
- root tasks completed together;
- start-of-week/month clearance rate;
- active queue trend;
- shared milestones;
- optional weekly streak;
- no individual comparison.

### Localization
- complete Finnish;
- complete English;
- per-app language selection;
- language-preserving AI behavior.

## Explicitly not necessary for first release

- iOS;
- web client;
- desktop client;
- attachments/photos;
- calendar integration;
- email integration;
- chat;
- public sharing;
- large teams/roles;
- individual leaderboard;
- complex arbitrary RRULE editor;
- automatic recurring subtask templates;
- on-device general-purpose LLM;
- cloud speech as mandatory dependency;
- SignalR/WebSocket realtime layer;
- graph database;
- Azure Service Bus;
- API Management.

---

# 40. Recommended Android architecture

Package-level conceptual structure:

```text
app/
  ui/
    queue/
    taskdetail/
    create/
    activity/
    together/
    settings/
    auth/

  domain/
    task/
    workspace/
    recurrence/
    sync/
    ai/

  data/
    local/
      room/
    remote/
      api/
    repository/
    sync/

  speech/
    parakeet/
    recording/

  auth/
    msal/

  notifications/
    fcm/

  localization/
```

Key principles:

- domain logic not embedded in Composables;
- repositories expose `Flow`;
- Room entities separated from API DTOs where useful;
- sync engine testable without Android UI;
- Parakeet behind interface;
- AI is server-side, never direct from Android;
- state reducers/domain functions should be deterministic for conflict tests.

---

# 41. Recommended backend architecture

Conceptual projects:

```text
src/
  TaskApp.Api/               Azure Functions isolated entrypoints
  TaskApp.Domain/            entities, commands, conflict rules
  TaskApp.Application/       orchestration/use cases
  TaskApp.Infrastructure/    Cosmos, Foundry, FCM, Entra helpers
  TaskApp.Contracts/         API/sync contracts

tests/
  TaskApp.Domain.Tests/
  TaskApp.Sync.Tests/
  TaskApp.AiEval.Tests/
  TaskApp.IntegrationTests/

infra/
  main.bicep
  modules/
```

Domain commands:

```text
CreateTask
EditTask
MoveTask
ClaimTask
UnclaimTask
CompleteTask
ReopenTask
SnoozeTask
CancelTask
DeleteTask
RestoreTask
AddDependency
RemoveDependency
SplitTaskCommit
AddNote
UpdateRecurrence
```

Every accepted command produces:

- domain state mutation;
- domain event(s);
- operation receipt;
- server revision/change record.

---

# 42. Testing strategy

## 42.1 Domain unit tests

High priority:

- container progress;
- reopen propagation;
- cancel propagation;
- dependency cycles;
- queue move fallback;
- recurrence occurrence IDs;
- root task stats;
- stale AI patch behavior;
- split validation.

## 42.2 Sync scenario tests

Simulate two independent device databases.

Example harness:

```text
Server
Device A
Device B
```

Run:

```text
sync both
disconnect both
perform divergent operations
reconnect in A→B order
reset
reconnect in B→A order
verify invariants
```

Test all adversarial cases from section 37.

## 42.3 Property/invariant tests

Useful invariants:

- no task is its own ancestor;
- dependency graph contains no cycles;
- one canonical primary claim per task;
- completed container has no incomplete non-cancelled leaves;
- deleted item cannot be resurrected by stale mutation;
- same operation ID applied N times has same final result as once;
- server revision strictly increases;
- recurrence occurrence key unique;
- queue ranks define deterministic order;
- AI patch never overwrites a post-base-version manual edit.

## 42.4 Bilingual AI evaluation set

Create a checked-in evaluation corpus.

Target at least:

- 50 Finnish task dictations;
- 50 English;
- 20 mixed/code-switched;
- date phrases;
- recurrence phrases;
- ambiguous phrases;
- explicit priority phrases;
- vague tasks;
- split cases.

Examples Finnish:

```text
"Muista ostaa kahvia huomenna."
"Varaa hammaslääkäri ensi viikolle."
"Vie pahvit ja lasit kierrätykseen kun ehdit."
"Siivoa keittiö joka sunnuntai."
"Laita tämä listan kärkeen, pitää hoitaa tänään."
```

Examples English:

```text
"Book the dentist sometime next week."
"Take the cardboard and glass to recycling."
"Clean the kitchen every Sunday."
"Put this at the top, we need to do it today."
```

Evaluate:

- title quality;
- description faithfulness;
- language preservation;
- due date accuracy;
- recurrence accuracy;
- no invented facts;
- explicit priority only;
- schema validity;
- split usefulness;
- latency.

Models are configurable; compare Luna/Terra only if actual quality data justifies it.

---

# 43. Implementation sequence

The stages below build toward the entire v1 release. They are not separate scope commitments. Resolve the linked architecture decisions before treating this sequence as executable tickets.

## Phase 0: prove the risky assumptions

1. Use the accepted Parakeet feasibility assumption for the two target phones. The separate benchmark experiment is skipped; implement and test model installation, capture and recovery with the voice feature.
2. Verify the actual Foundry deployment, schema, identity, and API combination in the target Azure environment.
3. Specify and exercise two-device sync with response loss, pending edits, transaction limits, and stale-cursor recovery.
4. Confirm the Impeccable visual study for the martial-arts bunny identity and the main queue's key states.

These checks belong before substantial UI and cloud implementation. They do not remove features from v1.

## Phase 1 — skeleton and local domain

1. Android project with Compose.
2. Finnish + English localization framework.
3. Room schema.
4. Shared queue UI using local dummy data.
5. Task detail.
6. Typed local task creation.
7. Local task state transitions.
8. Local queue reorder.
9. Local outbox creation for every mutation.

At the end: the app is a useful single-device offline task manager.

## Phase 2 — authentication and cloud synchronization

1. Entra External ID.
2. Workspace/member model.
3. Functions API.
4. Cosmos single-container design.
5. sync-state/server revision.
6. operation receipt/idempotency.
7. `/sync`.
8. two-device synchronization.
9. semantic conflict UI.
10. FCM silent sync hints.

At the end: two Android clients can work offline and converge.

## Phase 3 — local voice

1. audio recording UI;
2. Parakeet integration;
3. model management/download;
4. transcript;
5. create local draft task;
6. audio cleanup;
7. offline voice flow.

At the end: primary voice capture works without Azure.

## Phase 4 — Foundry enrichment

1. Foundry client with managed identity;
2. Luna deployment config;
3. strict task extraction schema;
4. pending AI request model;
5. versioned/stale-safe patch application;
6. bilingual eval corpus;
7. retry/error UX.

At the end: voice/typed drafts become structured tasks.

## Phase 5 — split + clarify

1. split prepare;
2. split preview;
3. transactional split commit;
4. stale split handling;
5. offline queued split;
6. clarify suggestions.

## Phase 6 — additional v1 task features

1. due dates;
2. snooze;
3. areas;
4. notes;
5. dependencies;
6. recurrence;
7. soft delete.

## Phase 7 — Together/activity

1. structured activity feed;
2. weekly/monthly snapshots;
3. root completions;
4. clearance rate;
5. queue trend;
6. milestones;
7. optional weekly streak.

---

# 44. Acceptance criteria for first usable release

The release is ready when all of these are true and the architecture review's unresolved release blockers have been addressed:

1. Two members can sign in to the same workspace.
2. Both can see the same queue after sync.
3. Finnish is complete.
4. English is complete.
5. Either language can create task content regardless of UI language.
6. Voice creation works locally without internet after the Parakeet model is installed.
7. Typed creation works offline.
8. A voice task exists before Foundry responds.
9. Offline task creation survives app restart.
10. Offline reordering survives app restart.
11. Offline completion survives app restart.
12. Offline claim conflict is resolved explicitly.
13. Two devices converge after reconnection.
14. Duplicate sync requests do not duplicate actions.
15. AI enrichment cannot overwrite a newer manual edit.
16. AI split creates linked children atomically.
17. Conflicting splits do not merge silently.
18. Completed child progress updates ancestors.
19. Reopening a child reopens necessary ancestors.
20. Due dates distinguish date-only from date-time.
21. Recurrence does not duplicate occurrences.
22. Dependencies cannot form a cycle.
23. FCM can be disabled and sync still works on foreground.
24. No raw audio is uploaded in the normal voice path.
25. No raw task text is logged to general telemetry.
26. Together dashboard contains no default individual comparison.
27. Splitting a task does not inflate root-task shared milestones.
28. A device returning after a long offline period cannot resurrect deleted tasks.
29. Functions/Foundry outage does not make local task management unusable.
30. All destructive/conflicting states have recoverable user feedback.
31. The app identifies itself as Bun Do and expresses "the way of the bun" through its martial-arts bunny identity.
32. Impeccable guides the UI work, with native Android checks for text scaling, accessibility, Finnish and English content, offline states, and reduced motion.
33. Delayed extraction interprets relative dates against capture time and distinguishes a task deadline from an event mentioned in the task.
34. Sync recovery preserves unsynced user intent after a lost response, expired cursor, or rejected prerequisite operation.

---

# 45. External references used for architectural validation

These are references supplied with the original proposal. Their presence is not proof of the surrounding claims. The research notes and decision tickets record verification performed during review. Re-check service/model availability in the target environment when deploying.

## Android offline-first

Android Developers — Build an offline-first app  
https://developer.android.com/topic/architecture/data-layer/offline-first

The Android guidance explicitly describes local data sources, queued/lazy writes, conflict resolution, and WorkManager-backed synchronization.

## NVIDIA Parakeet TDT 0.6B v3

NVIDIA model card  
https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3

The model card lists Finnish and English among its 25 supported European languages.

## Microsoft Foundry GPT-5.6

Microsoft Learn — Foundry Models sold by Azure  
https://learn.microsoft.com/en-us/azure/ai-foundry/azure-openai-in-ai-foundry

Microsoft Learn — Azure OpenAI reasoning models  
https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/reasoning

The [platform research](docs/research/azure-platform-constraints.md) verified public Luna/Terra, Responses, structured-output and reasoning-control documentation. Bun Do's actual deployment/version, quota and request acceptance still require an integration check.

## Azure Cosmos DB transactional batch

Microsoft Learn  
https://learn.microsoft.com/en-us/rest/api/cosmos-db/transactional-batch

Transactional batches apply to operations sharing the same partition key, motivating the single workspace-scoped container design.

## Cosmos DB free tier

Microsoft Learn  
https://learn.microsoft.com/en-us/azure/cosmos-db/free-tier

Current free-tier documentation describes 1,000 RU/s and 25 GB for eligible accounts.

## Azure Functions Flex Consumption

Azure pricing  
https://azure.microsoft.com/pricing/details/functions/

Microsoft Learn  
https://learn.microsoft.com/azure/azure-functions/flex-consumption-plan

On-demand Flex Consumption can scale to zero; optional Always Ready exists if cold-start latency later warrants it.

## Entra External ID Android native authentication

Microsoft Learn  
https://learn.microsoft.com/en-us/entra/identity-platform/tutorial-native-authentication-android-sign-in-sign-out

Email OTP/password native authentication is supported for Android External ID scenarios.

## Firebase Cloud Messaging

Firebase  
https://firebase.google.com/products/cloud-messaging

FCM is used only as a synchronization hint and is not required for data correctness.

---

# 46. Final architecture summary

The intended system is:

```text
                         ANDROID
┌────────────────────────────────────────────────────────────┐
│                                                            │
│  Jetpack Compose                                           │
│        │                                                   │
│        ▼                                                   │
│  Repository / domain                                       │
│        │                                                   │
│        ▼                                                   │
│  Room = local UI source of truth                           │
│     ├── Tasks                                              │
│     ├── Notes                                              │
│     ├── Recurrence                                         │
│     ├── Events                                             │
│     ├── Pending mutations                                  │
│     ├── Pending AI                                         │
│     └── Sync cursor/conflicts                              │
│                                                            │
│  Microphone → Parakeet → transcript                        │
│                           │                                │
│                           └──── local task immediately      │
│                                                            │
│  WorkManager → sync when connected                         │
└───────────────────────────┬────────────────────────────────┘
                            │
                            │ HTTPS / Entra
                            ▼
                    AZURE FUNCTIONS
┌────────────────────────────────────────────────────────────┐
│  Authentication / membership                              │
│  Domain command validation                                │
│  Semantic conflict resolution                             │
│  Incremental sync                                         │
│  AI orchestration                                         │
│  Recurrence generation                                    │
│  FCM hint publishing                                      │
└──────────────┬────────────────────┬────────────────────────┘
               │                    │
               ▼                    ▼
       COSMOS DB                FOUNDRY
       /workspaceId             GPT-5.6 Luna
       one container            GPT-5.6 Terra optional
               │
               │
               └──────────────► FCM → other Android device
```

The three capability layers are deliberately independent:

```text
LEVEL 1 — ALWAYS AVAILABLE
Room + queue + edit + complete + manual subtasks + local sync log

LEVEL 2 — OFFLINE INTELLIGENCE
Parakeet speech → text

LEVEL 3 — CLOUD INTELLIGENCE
Luna/Terra cleanup + extraction + clarify + split
```

Without internet, installed local speech and local task actions remain available. Shared reconciliation, cloud AI, and server-owned background work wait for connectivity. Offline claims and predicted recurring occurrences remain provisional.

That is the core architecture to preserve during implementation.
