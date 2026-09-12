# Dates, recurrence and shared progress

Architecture decision, 2026-09-12. Resolves [Define offline date capture, recurrence, and progress accounting](https://github.com/DrBushyTop/bun-do/issues/8). This contract is part of v1. It uses the task limits in [task-domain.md](task-domain.md) and the revisioned outbox protocol. A recurrence worker is a normal workspace writer: it reads canonical state and commits with the workspace revision CAS.

## Terms and stored time

A *deadline* is when the work should be done. An *event reference* is a time mentioned in the task text, such as a dinner reservation. They are different facts. An *occurrence* is the root task produced for one nominal slot of a recurrence template. A *slot* is a local calendar date, or local date and time, before conversion to an instant.

Persist these values. Do not derive them later from the device's current zone or locale.

```text
CaptureContext
  capturedInstant: RFC 3339 UTC instant supplied by the device
  capturedLocal: yyyy-MM-dd'T'HH:mm:ss.SSS
  captureZoneId: IANA zone, or workspace zone when unavailable
  captureOffsetSeconds: signed offset at capture
  zoneSource: DEVICE | WORKSPACE_FALLBACK
  locale: BCP 47 language tag
  clockConfidence: HIGH | UNKNOWN | SUSPECT
  serverAnchorInstant: optional authenticated server instant
  anchorElapsedRealtimeMs: optional monotonic time at anchor
  capturedElapsedRealtimeMs: optional monotonic time at capture
  receivedAt: server instant, filled only after upload

Due
  kind: NONE | DATE_ONLY | DATE_TIME
  localDate: yyyy-MM-dd
  localTime: HH:mm, required only for DATE_TIME
  zoneId: IANA zone, required only for DATE_TIME
  dateOnlyReminderZoneId: IANA zone, required only for DATE_ONLY
```

The client records `HIGH` only when the stored anchor fields prove an authenticated server-time anchor from the same boot is no more than seven days old, and the difference between expected and observed `elapsedRealtime` drift is at most five minutes. Automatic Android time or zone settings are not proof. Otherwise it records `UNKNOWN`. The server changes it to `SUSPECT` when `capturedInstant` is over 15 minutes after `receivedAt`, or more than 365 days before it. Offline delay alone never makes a capture suspect. The client preserves the original values even when the user later changes device time or zone.

AI extraction receives the whole `CaptureContext`, workspace zone, source text, and a separate server `processedAt`. Relative language such as "tomorrow" resolves against `capturedLocal` in `captureZoneId`, never `processedAt`. If confidence is `SUSPECT`, if zone source is `WORKSPACE_FALLBACK`, or if the phrase does not identify one calendar result, AI returns an ambiguity and leaves `due` unset. It may still propose text. `UNKNOWN` permits a proposed result, marked for review.

AI sets `due` only for language that says when the work itself is due, for example "pay this by Tuesday". A mentioned appointment, delivery window, birthday, or restaurant time goes in the title or description and is reported as an ambiguity when it changes the action's deadline. It never becomes `due` by inference. The user may set a deadline explicitly, which records an ordinary task edit rather than an AI interpretation.

A date-only deadline stays a date. It has no instant, no offset, and no DST conversion. When the user sets one, copy the then-current workspace default into `dateOnlyReminderZoneId`; reminders resolve the member's chosen date-only reminder time in that pinned zone. Timed deadlines retain their local time and IANA zone. Resolve either reminder instant only when scheduling, using the DST policy below. A workspace zone change changes only future input defaults. It never rewrites a saved due value, capture context, template zone, reminder zone, or statistics period.

## Recurrence template

A template is a separate, non-task document. It holds a root-task blueprint, not a mutable task. Occurrences are ordinary root tasks and obey the root maximum of 32 tasks, depth 3, and the workspace's 1,024 open-root limit.

```text
RecurrenceTemplate
  templateId: lowercase RFC 4122 UUID
  state: ACTIVE | DELETED
  generation: positive integer
  zoneId: IANA zone, copied into each generation
  timeKind: DATE_ONLY | DATE_TIME
  anchorLocal: yyyy-MM-dd or yyyy-MM-dd'T'HH:mm
  rule: see below
  endLocalDate: optional yyyy-MM-dd, inclusive
  nextUnmaterializedSlot: nominal slot or null
  catchupState: CURRENT | BLOCKED_BY_LIMIT
```

`anchorLocal` is the first eligible slot. A schedule never produces a slot before it. Timed templates use minute precision; seconds and fractional seconds are forbidden. Date-only templates produce date-only due values. Timed templates copy their nominal local date and time plus `zoneId` into the occurrence due value.

V1 rules are closed, not RRULE text:

```text
DAILY(interval 1..366)
WEEKLY(interval 1..52, weekdays nonempty subset of MON..SUN)
MONTHLY(interval 1..24, dayOfMonth 1..31)
```

`WEEKLY` with one weekday is weekly. Selected weekdays use `WEEKLY`; every N weeks uses its interval; every N days or months uses `DAILY` or `MONTHLY`. Weekly weeks are seven-day blocks anchored by the ISO Monday containing `anchorLocal`. A selected weekday before the anchor in its first block is skipped. `MONTHLY` visits months `interval` apart from the anchor month. A requested day absent from a month is skipped, not moved to month end. `endLocalDate` compares to the nominal local date and excludes later slots.

Template creation validates the first slot and writes no occurrence until generation needs it. The UI may show the computed next slot. V1 does not expose a due-offset field; each occurrence is due at its slot. This keeps schedule and deadline identical instead of introducing a second calendar calculation.

## Canonical occurrence identity

The canonical key is an ASCII byte sequence, UTF-8 encoded exactly as shown; each `\n` below is one LF byte (`0x0A`), not a backslash and `n`. `templateId` is lowercase hyphenated UUID. `generation` is base-10 with no leading zero. `slot` is `yyyy-MM-dd` for date-only and `yyyy-MM-dd'T'HH:mm` for timed templates. `zoneId` is the exact IANA name stored in the generation, ASCII case preserved.

```text
bun-do-occurrence-key-v1\n{templateId}\n{generation}\n{slot}\n{zoneId}
```

For a date-only template, `zoneId` remains present even though the occurrence has no timed due instant. This makes identity stable if display defaults change. The occurrence ID is `occ1_` followed by RFC 4648 URL-safe base64 of the full 32-byte SHA-256 digest, with `=` padding removed. Base64url case is significant and must be preserved. The stored document includes both key fields and ID. Creation uses the ID as the Cosmos document ID and `Create`, not upsert. Compact used-slot ranges and the generator cursor prevent materializing a purged historical occurrence again. Bound current-generation used-slot ranges to 256, coalescing adjacent slot ordinals; admission rejects another disjoint range rather than forgetting one. Old generations are never eligible to materialize after a schedule edit, so their per-slot ranges can compact to a closed-generation marker. A duplicate-create conflict means the occurrence already exists and is a successful idempotent result only after key fields match byte-for-byte.

Fixture vectors use the same escaped-LF notation:

```text
key text:
bun-do-occurrence-key-v1\n550e8400-e29b-41d4-a716-446655440000\n1\n2026-10-25T03:30\nEurope/Helsinki
sha256 hex: db1066c6e72af1428e3ff1589a8fb880eb2ddfe91d41220e76039015252e7d3e
occurrenceId: occ1_2xBmxucq8UKOP_FYmo-4gOst3-kdQSIOdgOQFSUufT4

key text:
bun-do-occurrence-key-v1\n550e8400-e29b-41d4-a716-446655440000\n12\n2026-02-28\nEurope/Helsinki
sha256 hex: d8698bc058c14d79b8ca879f072ab4d0fd414fdd0d394ba3e6b3f520a49eac8f
occurrenceId: occ1_2GmLwFjBTXm4yoefByq00P1BT90NOUuj5rP1IKSerI8
```

The Kotlin and .NET test suites must share these literal key-text vectors and assert UTF-8 bytes, SHA-256 digest bytes, and final ID against a checked-in generated fixture. The fixture generator runs in one language only; the other implementation treats its result as test data. Do not use platform UUID byte order, locale formatting, `DateTime.ToString()`, Java default zone, or a JSON serializer in this algorithm. An occurrence ID is an opaque task ID, not a UUID. Task ordering must tie-break opaque task IDs by unsigned UTF-8 byte order, not UUID bytes.

## Generation, edits and deletion

The server generator materializes slots from `nextUnmaterializedSlot` through 30 local days ahead. One transaction creates at most 16 occurrences and advances the cursor only across slots represented by a created occurrence, an explicit skip record, or an ineligible end date. It also writes the normal receipt, changes, activity and revision metadata, and must pass the 90-operation and 1.75 MiB batch plan. A continuation runs later.

It never silently jumps a slot. If 16 materialized open occurrences for one template are already outstanding, or an open-root/storage limit blocks creation, it leaves the cursor unchanged, sets `BLOCKED_BY_LIMIT`, and emits one visible activity/change state. The UI shows the oldest pending slot and offers explicit catch-up.

`SkipSlotsThrough(localDate)` first lists every canonical occurrence in the range. It rejects with `RECURRENCE_SKIP_HAS_OCCURRENCES` and advances nothing until the user deletes or cancels each listed occurrence. The client also lists its local predictions in the range and requires materialize or discard before it enqueues the skip. A prediction from another offline device is rejected on replay as `SKIPPED_RECURRENCE_SLOT`, with its local command retained as a conflict variant. Only then does the command record one inclusive compressed skip range, advance through that range, and write activity. It requires confirmation. A template stores at most 128 skip ranges; merge adjacent ranges, otherwise reject and require the user to delete the template or resolve existing history. Completing a predicted past slot is allowed and advances no unrelated cursor.

A phone may predict a missing slot for display and may queue a command against it offline. Its predicted root uses the canonical ID and key, carries the template generation and observed schedule version, and is marked `PREDICTED`. It is not a second schedule source. On replay the server verifies that the template is active, generation and rule admit the slot, the slot is not skipped, and all task limits hold. The explicit `MaterializePredictedOccurrence` command from the command catalog creates or finds the canonical occurrence without changing found tasks. The separately journaled user action depends on its receipt and then uses normal preconditions. A stale generation/schedule version returns `STALE_RECURRENCE_TEMPLATE`; the client retains the local text as a conflict variant and removes the prediction from the shared projection. It does not retarget it to a new schedule.

Editing a template requires its observed schedule and materialization versions and an explicit impact confirmation. Offline editing builds that preview from the local canonical base and queues it; a changed server version rejects without silently changing the confirmed impact. The server preserves created occurrences as their own tasks. Creating a template from an existing task keeps that task as an ordinary `seedTaskId` and starts future canonical slots after its date, as specified in the command catalog. Its default activation date is the later of the capture-local edit date and the first local date after the greatest date of any materialized old-generation occurrence. When none exists, use the capture-local edit date. The new generation may not create a slot on a date that still has an OPEN or COMPLETED old-generation occurrence. To activate sooner, the user explicitly cancels or deletes every affected materialized occurrence first; the bounded list is part of the same confirmed plan. Those cancellation/deletion commands complete first; the final schedule edit has no hidden multi-root cascade.

Before commit, the server shows the replacement range: first old unmaterialized slot, last slot or `NO_END`, and count when finite. It separately names the materialized occurrences retained or explicitly cancelled. The confirmation, range, count, and first/last slot are written to activity. The server then increments `generation`, closes the old cursor at the activation boundary, and starts a fresh cursor for the new rule. Past due slots before the boundary are materialized or explicitly skipped first; none are silently abandoned. Unbounded future slots are recorded as `NO_END`, not fabricated as a finite count. It does not alter completed, open, deleted, split, or manually edited occurrences. Thus an offline old-generation prediction cannot become an unintended occurrence after a schedule edit. A split changes only that occurrence; later occurrences start unsplit.

Deleting a template is a versioned `ACTIVE -> DELETED` transition. It stops future generation and leaves existing occurrences untouched. It never performs an unbounded cascade. Delete an existing occurrence with the normal task deletion command. To suppress one unmaterialized future slot, use `SkipOccurrence`, which creates a one-slot skip record and counts toward the 128-range bound. Restoring an occurrence restores only that task; it does not restore a deleted template or reopen skipped slots.

## Template bounds and commands

A workspace may have at most 128 active templates and 256 retained template documents, including deleted templates until tombstone pruning. A template document, including its blueprint, cursor, and skip ranges, is limited to 16 KiB UTF-8 JSON. Creation, edit, deletion, skip, prediction acceptance, and generator continuation all carry the observed schedule version and workspace revision. A conflict changes nothing and leaves an offline command or local draft recoverable.

The blueprint may create only one root per slot. Its resulting root must pass the normal title, description, dependency, active-root, root-size, and batch limits at materialization time. A recurrence template cannot contain a child tree, prerequisite ID, claim, snooze, completion state, or copied notes. Users add those to an individual occurrence. This avoids a future schedule trying to recreate a stale dependency or a deleted note.

The generator runs after template writes, after sync, and from a bounded server job. It uses canonical state only. It does not use an Android prediction, client clock, or an unacknowledged outbox entry to move the cursor. A duplicate worker invocation sees the create conflict and continues safely. Template edits and deletion race generation through the same CAS, so one has a definite order.

## Calendar resolution

Generation enumerates nominal slots in calendar arithmetic, then resolves a timed slot to an instant. There is no conversion for date-only slots.

* With one valid offset, use it.
* In a DST overlap, choose the larger UTC offset, which gives the earlier instant.
* In a DST gap, shift the local wall time forward by the exact gap duration. A nominal `02:30` in a one-hour gap resolves to `03:30`.

The occurrence key and due local fields retain the nominal slot. Store `resolvedDueInstant` and `resolution: EXACT | OVERLAP_EARLIER | GAP_SHIFTED` for timed occurrences. Reminders use the resolved instant and show the adjusted time where relevant. Kotlin and .NET must test the same IANA tzdb fixture version for `Europe/Helsinki` gap and overlap cases; a tzdb upgrade is a wire/schema migration decision, not a silent mobile/server divergence.

## Progress accounting and statistics

The workspace has an immutable `statisticsZoneId`, selected at creation. It defines all statistics periods in v1; an owner cannot change it. Template and due zones may differ. The unit of lifetime shared credit is the root. Each root has immutable `firstRootCompletedAt`, `firstRootCompletionSource`, and `completionCreditId`. When the root first becomes COMPLETED through a leaf or direct leaf completion, the same transaction assigns them once. Reopening does not clear them. Re-completing later records activity and current lifecycle but grants no second lifetime credit. Cancellation, deletion, restore, or a reopened prerequisite never revoke lifetime credit.

For a first credit, use `occurredAtClient` only when its capture/event context is `HIGH`, it is no more than 365 days before receipt, and it is no more than 15 minutes after receipt. Otherwise use `receivedAtServer` and mark the activity `TIMING_FALLBACK`. This timestamp selects the historical week and month completion aggregate. It never decides command order or conflict winners.

The server retains immutable root-state revision events, with before/after lifecycle and root membership, for 365 days. A weekly or monthly `PeriodSnapshot` has `status: FINALIZING | FINAL`, statistics zone, local period start and end, source revision range, and the compressed sorted root IDs that were OPEN at its start. The worker reconstructs that denominator from server-acceptance timeline events, then marks it `FINAL`. Until then the UI says "Calculating period start" and shows no clearance percentage. It never substitutes the first later write for the boundary state. Snapshot IDs are capped at 1,024 and 64 KiB, following the open-root limit.

Clearance is separate from lifetime credit. For an in-progress period, count denominator roots that are COMPLETED now. For a closed period, count denominator roots that were COMPLETED at the local period end, reconstructed from the same immutable event log. A root completed during the period and reopened before its end is not clear. A zero denominator reads "No tasks at period start." Late offline first-credit events update their credited historical aggregates, but because their server acceptance happened later, they do not rewrite a final period boundary or clearance result. The UI labels such aggregate changes "includes late sync."

The exact queue trend is a `DailyQueueSnapshot` of the number of OPEN, non-deleted roots at each statistics-zone local midnight. It uses the same reconstruction and `FINALIZING | FINAL` state. Retain snapshots, aggregates, and events for 365 days, then retain workspace-only aggregate counts. No statistic ranks members.

Lifetime milestones use total root lifetime credits and fire once at 10, 25, 50, 100, 250, 500, 1,000, then each additional 1,000. They are shared milestones, not a clearance or member score. A weekly streak means consecutive non-neutral weeks with at least one lifetime credit attributed to that week. Late credits recompute the recent displayed streak from retained weekly aggregates. A week with no tasks in its start denominator and no credits is neutral and does not break it. A week with work but no credit ends the displayed streak. There are no penalties, loss notifications, points deductions, or member comparisons.

## Required acceptance cases

- A Thursday upload of a Monday Finnish capture saying "huomenna" proposes Tuesday. Automatic-time settings alone do not make it trusted; without stored fresh same-boot server-anchor evidence it is a review-only proposal.
- "Varaa pöytä lauantaille noin seitsemäksi" preserves the reservation time as text and leaves `due` empty unless the speaker also states a booking deadline.
- Kotlin and .NET derive the same ID from every checked-in vector, including generation 12 and a date-only slot.
- Two devices predict the same slot, one server worker creates it, and all paths yield one root and one completion credit.
- An edit with pre-created future occurrences activates after their latest local date, unless the confirmed plan cancels them. It names the discarded old range and an old offline prediction rejects as stale rather than appearing under the new schedule.
- A monthly day-31 template skips April; selected weekdays respect the anchored ISO week; an inclusive end date permits its final slot.
- Helsinki gap and overlap fixtures retain the nominal key and resolve to the specified instant; a date-only value never moves date or changes its pinned reminder zone after a workspace-zone edit.
- A long-offline template stops at a visible catch-up limit. Skip rejects while canonical or local predicted work exists, and it loses no slot silently.
- Deleting a template stops only future generation. Deleting or restoring an occurrence does not mutate its template.
- A root completes, reopens, and completes in a later week. It has two activity events and one lifetime credit. It does not count as cleared for a prior period if it was reopened at that period end. A late trusted Monday completion updates Monday's aggregate without changing Monday's final clearance snapshot. A missing-boundary snapshot remains FINALIZING until reconstruction succeeds.
