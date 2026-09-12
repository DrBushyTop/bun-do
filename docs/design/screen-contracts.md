# V1 screen contracts

Status: architect-selected plan. Scope is the whole v1 in the architecture. All screens use Operate mode and [DESIGN.md](../../DESIGN.md). Code-first implementation is recorded in `.impeccable/config.json`; no comp or rendered approval exists.

The first implemented shell covers anonymous local capture/edit, original-text
detail and language/appearance settings. Until their slices exist, voice, shared
destinations, task lifecycle and sync controls are absent rather than disabled
placeholders. The shell uses one filled Type action below its scrolling queue.
These omissions do not change the full v1 contract below.

## Direction contract

THESIS: Capture locally, choose any available work, and see shared progress without ranking members.

OWN-WORLD: Pale paper, dark ink, evergreen Material controls, flat task rows, and one small martial-arts bunny.

STORY: Open the queue, capture or select a task, act immediately, resolve shared changes when necessary.

FIRST VIEWPORT: Native top bar, compact status strip when needed, full-width queue rows, reachable voice FAB and adjacent Type action, then three labeled navigation destinations. Task content gets the largest area.

FORM: Native shared queue selected by delegated architect judgment. No concept-seed or owner selection is claimed. This planning document substitutes for a direction interview under the owner's autonomous instruction.

FINISH: implementation ends with rendered emulator evidence, Impeccable finish review, reconciled DESIGN.md and token sidecar, and provenance for every shipping raster. None of these rendering checks has run during planning.

## Navigation and queue

Compact width uses localized destinations Jono / Queue, Tapahtumat / Activity, Yhdessä / Together. These pairs name Finnish and English resources, never hard-coded bilingual labels. Settings opens from the top bar. Preserve scroll, filters, and drafts across navigation. Use system and predictive Back, display/IME insets, and native sheets. At 600 dp use a navigation rail; at 840 dp use queue/detail panes, with a 360 dp minimum queue. Editors stay within 640 dp.

Queue rows contain title, claimant name, then relevant due/area/blocker metadata. Containers have a disclosure control and textual progress. Indent children 16 dp through two visible levels; deeper navigation opens detail with a parent breadcrumb. Order remains shared priority. Filter sheets expose available, all active, snoozed, completed, and area views. Deleted tasks live in Settings recovery.

The only FAB starts voice. Kirjoita / Type is a neighboring text button, with independent touch space. Reorder through long-press drag or accessible Move before/after actions. Never require dragging. Preserve row position while interacting; explain any canonical reorder after sync without stealing focus.

## Capture and task work

Voice opens a sheet with explicit recording label, elapsed time, level indicator, Stop, Cancel, and Type. Ask microphone permission on first use. Denial keeps typing available. Stopping shows local transcription progress, then the committed task. Failures preserve recoverable input and offer Retry or Type. Interrupted recording explains what was retained. Cloud processing is a separate task status; it never gates editing or completion.

Typed creation uses an autofocus multiline field, optional description, and expandable due, recurrence, and area controls. Save commits locally; Back retains a draft. Reopening restores it.

Task detail orders title, lifecycle/claim, description, subtasks/blockers, scheduling/area, notes, and collapsed provenance. Provenance contains transcript, creator, timestamps, and completers. Actionable tasks expose Complete and Claim/Unclaim. Completion needs no prior claim. Containers derive completion from descendants. Overflow exposes Edit, Split, Clarify, Snooze, Cancel, Delete, and relevant Reopen/Restore actions.

Manual subtasks use the same editor. Split previews editable children before acceptance. Clarify presents questions and editable suggestions. Keep original text available. Stale AI suggestions show current versus suggested fields and require explicit application. Rejected splits retain the proposal for recovery.

Due editing separates date-only from timed deadlines and displays timezone for timed values. Snooze offers later today, tomorrow, next week, custom, and unsnooze. Dependencies use searchable task selection and explain blockers or rejected cycles. Recurrence offers daily, weekly, selected weekdays, monthly day, every N periods, and optional end date. Show the next occurrence and distinguish this occurrence from future schedule edits. Notes have author/time and pending status. Areas have shared editable labels. V1 uses areas, not separate tags or effort scores.

## Recovery, access, and progress

Sync and AI states remain independent. Show pending count in the top strip, item-specific conflict beside the task, and AI status in detail. Offline pending is neutral. Failed writes retain entered text and offer retry; no success animation precedes a local commit.

Recovery lists affected tasks and explains the accepted shared state beside this phone's retained change. Offer domain-permitted reapply, copy, or dismiss actions. Claims show the accepted claimant. Deleted/conflicting tasks never silently return. Delete offers Undo; recovery offers Restore. Access removal locks shared actions and preserves recoverable local work without implying continued membership.

Welcome contains the original mark, one sentence about shared tasks, sign-in, and create/join workspace. Invitation previews the workspace before acceptance. Model setup shows download/storage progress and typing access. Settings covers UI language, appearance, voice model/storage, reminders, workspace members/invites, areas, sync recovery, deleted tasks, and sign-out. Explain retained drafts before sign-out. Network failures expose retry without wiping local content.

Activity is a dated list with actor, action, and task link. Together shows period selection, root completions, start-of-period clearance, queue trend, shared streak, and milestones. Provide chart text equivalents, metric explanations, and last-sync status. A zero denominator reads "No tasks at period start." No member comparison. Empty queue invites capture; empty activity explains when events appear; filtered emptiness offers Clear filters.

## Review fixtures

Use synthetic household tasks, including "Järjestä varasto", an English multiline task, deep subtasks, multiple blockers, long member/area names, ambiguous dates, and simultaneous offline claims. Cover empty/loading/error/retry, process recreation, expired sign-in, removed membership, queued AI, stale suggestions, delete/restore, and recurrence boundaries. All copy, plurals, accessibility labels, notifications, and errors need Finnish and English resources. UI language changes never translate task content. Review actual rendered screens before declaring UI implementation complete.

## Administration and recovery screens

Add pending invitation approval with the matching code, invitation expiry/cancel, owner transfer, and workspace deletion countdown with owner-only Restore. Explain unavailable owner-account recovery without suggesting another member can take ownership. Import and export show text previews, count, destination warning, progress, failure without deletion, and new-ID semantics.

Expired-device and restored-server recovery show old local text beside the current shared state. Copy/edit is explicit. Claims, completion, split, delete and membership actions require a new action; never provide bulk automatic replay. A purging deletion group explains that restore is no longer available. A response arriving after account switch cannot reveal the previous account's content.

Recurrence editing shows the activation date and retained future occurrences. Offline edits keep an impact preview and reject visibly if the shared schedule changed. Catch-up shows its oldest pending slot and explicit skips. Together distinguishes lifetime completion credit from period clearance and shows calculating states until boundary snapshots are ready.
