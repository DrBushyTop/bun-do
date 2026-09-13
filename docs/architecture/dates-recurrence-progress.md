# Dates, simple repeats and shared progress

The owner retained simple statistics and explicitly requested a weekly streak in the September 13, 2026 scope revision. This contract replaces the earlier scheduling and historical accounting requirements.

## Capture and due dates

Preserve the existing capture context, including original local date/time and zone. Relative language such as "tomorrow" uses capture time, not later processing time. A task deadline and a time mentioned in its text are different facts. An ambiguous date remains a suggestion for human confirmation. V1 does not require a new authenticated-clock confidence system; existing metadata can remain without making it an automatic-date admission gate.

Date-only values stay dates. Timed values keep their local time and IANA zone. Pin the reminder zone when saving a date-only deadline. Later changes to device or workspace defaults do not rewrite saved dates. In a DST overlap choose the earlier instant; in a gap shift by the gap duration. Preserve the nominal value and explain any adjustment where relevant. Snooze is a separate until-instant.

## Simple repeats

A repeat creates one root at a time, daily or weekly on one selected weekday, in a saved household zone. V1 repeats have date-only due dates and a title/description blueprint. They do not copy a checklist tree, claims, snooze or completed state. Users may split an individual occurrence.

The server is the only generator. It creates the first eligible occurrence when a repeat starts and keeps at most one OPEN occurrence. Completing or cancelling it advances to the next scheduled date strictly after the server acceptance date in the saved zone. Missing dates do not produce a backlog of missed chores. A late completion creates the next future chore, not every missed slot.

Persist advancement and a stable occurrence identity so retrying or running two workers cannot produce duplicates. A repeat created from an existing open root uses that task as its current occurrence; it must not create a second current task. First-completion credit belongs to each occurrence independently.

Existing cached occurrences remain editable and completable offline. Creation of the next occurrence waits for the server. V1 has no predicted occurrence IDs on phones, future batches, skip-range journals or catch-up previews. The repeat screen explains that the next task appears after synchronization.

Creating, changing or stopping the repeat schedule requires connectivity in v1. A schedule edit changes the blueprint/rule for the next occurrence and leaves the current task untouched. A stale edit rejects visibly. Stopping a repeat leaves its current task intact and prevents further generation. Deleting the current occurrence stops that repeat in the same command; Undo restores the task but does not silently restart the repeat. Reopening an older occurrence never creates or replaces the current occurrence. Repeat-linked reopen/restore must reject if it would create two OPEN occurrences, preserving the user's intent for explicit comparison.

[Advanced schedules and offline generation](https://github.com/DrBushyTop/bun-do/issues/44) are V2 work. They must not block reminders or shared statistics.

## Activity and completion counts

Activity records actor, action and server acceptance time with links to retained tasks. Keep household content out of diagnostic telemetry. Removed members display as former members; no member rankings are shown.

The counting unit is a root task. When a root first becomes COMPLETED, save an immutable first-completion timestamp and root identity in the same transaction. Checklist children never receive separate household completion credit. Reopening, cancelling, deleting or restoring a previously completed root does not remove or grant another credit. Each recurring occurrence is a new root and can earn its own first credit.

Use server acceptance time and the household's fixed statistics zone for weekly and monthly counts. Weeks start Monday. A Monday offline completion accepted on Thursday counts on Thursday; one accepted the following week counts in that following week. The UI explains this in metric details. Do not build clock attestation, retroactive client-time correction or period-boundary reconstruction.

Derive counts from first-completion records or maintain small aggregates with the same atomic/idempotent guarantees. Retain content-free credit metadata if task content is purged so totals and milestones remain stable. No root-state event reconstruction, midnight snapshots, denominator sets, finalization workers or 365-day checkpoint pipeline is required.

## Weekly streak and milestones

A qualifying week contains at least one first-completion credit. If this week qualifies, count consecutive qualifying weeks ending this week. Otherwise display the run ending last week while this week is still in progress. If neither this week nor last week qualifies, the current streak is zero. A completed empty week breaks the run. There is no neutral-week exemption or punishment message.

For example, qualifying weeks 1 and 2 give a streak of two during week 3 until it ends. A first credit during week 3 extends it to three. If week 3 ends empty, week 4 starts at zero. Additional credits for an already qualifying week do not extend the streak.

Keep shared lifetime milestones at simple code-defined thresholds. Derive the reached milestone from the total and persist any celebration acknowledgement needed to avoid repeated celebrations on sync retries. Milestones do not compare members.

Historical clearance, exact queue trends and alternate late-sync/streak semantics are [V2 discovery](https://github.com/DrBushyTop/bun-do/issues/45).

## Required proof

Verify capture-relative dates, date-only and Helsinki DST behavior, repeated worker delivery, offline completion followed by reconnection, schedule edit/stop races, delete/undo and older-occurrence reopen. Verify that checklist completion and reopen/recomplete grant one credit, purged task content does not erase totals, week/month boundaries use acceptance time, and an unfinished current week does not prematurely erase the streak. Tests should exercise these behaviors rather than reproduce old scheduling or reconstruction machinery.
