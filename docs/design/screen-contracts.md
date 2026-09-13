# V1 screen contracts

These requirements follow the September 13, 2026 [scope revision](../../shared-task-manager-architecture-v1.md). Use Operate mode, native Material behavior and [DESIGN.md](../../DESIGN.md). The approved rabbit artwork remains unchanged. This document specifies behavior; owning issues contain actual rendered evidence.

## Navigation and queue

Use Queue, Activity and Together destinations with Finnish/English resources. Settings opens from the top bar. Adapt navigation to available width, preserve scroll and drafts, honor system Back and display/IME insets. Keep the queue and task content dominant.

Queue rows show title, claimant and relevant due/snooze state. Checklist roots show direct items and textual progress. V1 has one child level, no blocker badges, area filters or deeper breadcrumbs. Allow active, snoozed and completed views. Deleted tasks have a recovery view.

Voice is the primary capture action and Type is adjacent. Reorder by long-press drag or accessible move actions; never require dragging. Reconciliation must not steal focus or move a row while the user is acting on it.

## Capture and task work

Recording shows elapsed time, recording state, Stop, Cancel and Type. Request microphone permission when needed. Prefer MAI online and installed Parakeet offline; explain a failure without exposing provider mechanics. Keep retry/export/delete for retained unsuccessful recordings. Commit transcript/task before cleaning up audio. Later cleanup never blocks editing or completion.

Typed capture starts with one text field. Description and due editing remain available without requiring a metadata form. Save commits locally and Back preserves a draft. Task detail puts title, claim/completion, description and checklist first, followed by scheduling and collapsed original text/attribution.

An actionable task can be completed without a claim. Roots containing items derive completion from those items. Keep Edit, Split, Snooze, Cancel, Delete and applicable Reopen/Restore actions discoverable. No separate Clarify, dependency, area or notes workflow is required in v1.

Manual and AI split preview editable direct checklist items. Stale acceptance retains the draft and explains that the task changed. AI cleanup preserves human corrections and original text; ambiguous dates require an explicit choice.

Due editing distinguishes date-only and timed values, with saved zone where relevant. Snooze has useful presets and a custom value. Simple repeat setup offers daily or one weekday, with the next due date and Stop repeating. Schedule edits require connectivity and affect future tasks, not the current task. Explain that the next occurrence appears after sync. No schedule-impact preview, skip-range editor or missed-slot catch-up UI is required.

## Access and recovery

Keep existing sign-in, household creation/join, matching-code invitation approval, owner transfer and member removal flows. Typing works before sign-in; importing anonymous drafts into a household is explicit. Model installation remains optional.

Normal account screens show account/household identity and useful actions. Hide installation IDs, token validation, encryption internals and resolved warnings behind troubleshooting details. Settings includes language, appearance, speech storage, reminders, membership, task recovery and sign-out.

Keep the full recovery experience from [Issue 21](https://github.com/DrBushyTop/bun-do/issues/21): current shared state beside retained intent, explicit reapply/copy/dismiss, same-account export/import and interrupted/low-storage snapshot recovery. Pending text survives refresh. Claims, completion and deletion are never bulk-replayed across an expired identity or epoch. Technical state appears only when it explains a needed action.

Delete offers Undo and later Restore while retained. A purged task cannot be silently revived. Show actual recovery limitations without adding self-service workspace delete/undelete countdowns, which belong to V2. Account-switch callbacks cannot expose the previous account's content.

## Activity and Together

Activity is a dated actor/action list with task links when available. Together shows shared weekly/monthly first-completion counts, lifetime total and milestones, and the current weekly streak. Show zero counts as a normal empty state. No clearance percentage, exact midnight trend, rankings or historical finalization status.

Metric details explain that a root counts once, checklist items do not inflate the total, and offline completions count when accepted by the server. The current unfinished week does not break a streak carried from last week. [Dates and progress](../architecture/dates-recurrence-progress.md) defines boundaries. Present numbers and any charts with accessible text equivalents; avoid guilt or loss notifications.

## Verification and copy

Check each flow during implementation, then walk the complete reduced v1 before final release verification. Remove routine technical success notices and stale warnings as screens are built. Finnish must read naturally; task content keeps its own language.

Use actual emulator views in light/dark themes and large text, with TalkBack labels, adequate touch targets, system Back, insets and reduced motion. Cover empty state, permission denial, interrupted recording, account change, simultaneous claims, AI finishing after an edit, delete/undo, snapshot recovery, a weekly boundary and a late synced completion. V2 screens are not release fixtures.
