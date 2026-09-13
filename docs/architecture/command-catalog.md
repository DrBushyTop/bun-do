# Commands and version groups

The revised v1 retains the existing protocol, operation identities, receipts and field/human version rules. This catalog states domain preconditions; implementation types and serialization live in code. New entities initialize version groups at creation. A valid same-value edit is an accepted no-op with normal receipt bookkeeping.

## Task groups

| Group | Values and rule |
| --- | --- |
| title, description, contentLanguage | Human edits compare human version; automatic AI patches compare exact field version. |
| due | Date-only or timed values with saved zone; atomic edit, ambiguity requires confirmation. |
| lifecycle | OPEN, COMPLETED or CANCELLED with actor/time; exact observation. Checklist roots derive state from items. |
| claim | Claimant and eligibility; exact claim/lifecycle, claimant must be the actor. |
| snooze | Nullable until-instant; exact lifecycle/snooze. Root snooze also covers checklist availability. |
| hierarchy | Root/direct-item relationship and child membership; parent/root immutable in v1. |
| deletion | Deletion group and retained state; explicit restore only. |
| orderIntent | Last intentional placement, separate from list ranks; exact version for a move of the same task. |
| recurrence | Optional simple-repeat/current-occurrence reference; explicit schedule actions or server generation only. |

First-completion credit is immutable metadata outside editable groups. Derived checklist state advances lifecycle only when it changes. Deeper graph dependencies, note/area entities and offline recurrence predictions have no required v1 commands.

## Preconditions

| Command | Required behavior |
| --- | --- |
| CreateTask | Valid device-derived new ID, membership and technical bounds; never upsert a deleted ID. |
| EditTask | Requested human versions and deletion guard; cannot change lifecycle or hierarchy indirectly. |
| MoveTask | Own order version, expected parent and deletion guard; deterministic live-anchor resolution. |
| ClaimTask / UnclaimTask | Current eligibility and claim/deletion guards; only claimant or owner may release. |
| CompleteTask / ReopenTask | Exact lifecycle/hierarchy/claim as applicable and deletion; confirm another person's claim. Recompute checklist root and preserve first credit. |
| CancelTask | Exact lifecycle/hierarchy/deletion; guard and atomically update any affected checklist items. |
| SetSnooze / ClearSnooze | Exact lifecycle/snooze/deletion; clear affected claims atomically. |
| SplitTask / AddChildren | Open parent and observed hierarchy/lifecycle/deletion; bounded direct items with command-derived IDs. AI acceptance also guards input text versions. |
| DeleteTask | Exact deletion/lifecycle/hierarchy; root cascades guard observed subtree version. |
| RestoreTask | Retained unpurged deletion group and live parent; preserve independent deletion groups and no claims. |

A root subtree version advances for mutations to direct items. Guard destructive cascades and confirmed previews with it; an independent text edit still compares only requested human fields. Root and item effects must fit one supported batch. Repeat-linked reopen/restore also checks that it cannot produce a second OPEN occurrence.

## Other commands

Membership, invitations, owner transfer and workspace defaults keep their existing authorization and exact-version checks. All external commands carry the current epoch and registration; authorization and receipt handling happen before domain validation.

AI requests use normal stable operation identities. Results require current request ownership, membership and target versions; accepted split uses the manual split operation. The [AI contract](ai-processing.md) owns retry/application behavior.

Simple repeat creation/edit/stop requires online confirmation and an observed schedule version. The server persists a stable identity for each generated occurrence and advances the current occurrence conditionally. Completion, cancellation or deletion records the repeat transition atomically with its task effect, or durable generation intent in that same boundary. See [dates and progress](dates-recurrence-progress.md). No client MaterializePredictedOccurrence, skip-range command or schedule-impact journal is required.

Conflict responses identify the affected task/group and current state while retaining local intent. A new user correction creates a new command. Never change frozen submission bytes or silently advance a stale precondition to make a command succeed.
