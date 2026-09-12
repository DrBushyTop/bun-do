# Commands and version groups

This catalog is normative for wire protocol 1. Every mutable group has `fieldVersion` and `humanVersion`. Versions are workspace revision stamps, encoded as decimal strings. `expected` below means the exact field version unless the row explicitly allows human-only comparison. Derived and AI changes advance field version without inventing a human action.

New entities initialize every group at their creation revision. Optional values still have a version when null. A command that sets the same value and has valid preconditions is an accepted no-op receipt, without changing that field's version. Receipt/revision bookkeeping still commits.

## Task groups

| Group | Values | Writer and rule |
| --- | --- | --- |
| `title`, `description`, `contentLanguage` | Each value separately | Human edit compares human version; AI compares field version. |
| `due` | Kind, nominal local values, pinned zone, resolved instant and resolution | Atomic human edit or validated AI patch; uncertain AI is proposal-only. |
| `area` | Nullable area ID | Human or AI; area must be active when assigning. |
| `recurrence` | Template/occurrence reference | Explicit user confirmation or canonical materialization; AI only proposes. |
| `lifecycle` | OPEN, COMPLETED, CANCELLED, actor/time and cancellation group | Exact expected version; container values derive from descendants. |
| `claim` | Claimant and claim eligibility/version | Exact expected claim and lifecycle; inactive member's claim is ineligible. |
| `snooze` | Nullable until-instant | Human; exact lifecycle and snooze. |
| `dependencies` | Up to eight explicit prerequisite IDs | Human; exact dependencies, hierarchy and deletion, then combined graph validation. |
| `hierarchy` | Kind, parent/root references and child membership | Parent/root immutable. Split/add changes parent and ancestor progress atomically. |
| `deletion` | Deletion group, former leaf state, deleted time and purge state | Human delete/restore or maintenance; exact group versions. |
| `orderIntent` | Last intentional placement request | Human or explicit AI placement; task version is separate from list rank data. |

Container recomputation advances lifecycle when the derived value changes. It does not advance title or human lifecycle version. First-completion credit is immutable after assignment, outside editable groups. Completion events and current completer presentation are not the same as lifetime credit.

## Task command preconditions

| Command | Required observation and outcome |
| --- | --- |
| CreateTask | Device-derived new ID, active workspace, active area if supplied, all limits; no entity upsert. |
| EditTask | Requested group's human versions plus exact deletion; cannot change lifecycle or hierarchy indirectly. |
| MoveTask | Exact own order intent, expected parent and deletion; resolve live anchors without requiring list version. A remote move of another task does not reject it. |
| ClaimTask | Exact claim/lifecycle/deletion, current availability; claimant must be actor. |
| UnclaimTask | Exact claim/deletion, actor owns claim or is workspace owner. |
| CompleteTask | Exact lifecycle/hierarchy/claim/deletion, current prerequisites and snooze eligibility. Completing another member's claim requires explicit `confirmOtherClaim`. |
| ReopenTask | Exact lifecycle/hierarchy/deletion; recompute ancestors, never clear lifetime credit. |
| CancelTask | Exact lifecycle/hierarchy/deletion; bounded descendants are evaluated canonically. |
| SetSnooze/ClearSnooze | Exact lifecycle/snooze/deletion; clear current claim atomically. |
| AddDependency/RemoveDependency | Exact dependencies/hierarchy/deletion; recompute validation from canonical graph. |
| SplitTask/AddChildren | Exact parent hierarchy/lifecycle/deletion, bounded canonical root; new child IDs derive from this command. AI proposal additionally guards input text versions. |
| DeleteTask | Exact selected deletion/hierarchy/lifecycle plus `subtreeVersion`, which advances on any descendant task mutation; no stale cascade over newly edited children. |
| RestoreTask | Exact deletion group version, unpurged group and live ancestors; revalidate graph and root admission. |
| AddNote | Task not deleted, expected deletion, device-derived new note ID. |
| EditNote/DeleteNote/RestoreNote | Note text human version or exact deletion version as appropriate, task deletion guard, current membership. |

`subtreeVersion` lives on the root and advances on every descendant task mutation, including notes. Cascade delete/cancel/split previews carry it where the preview covers descendants. It is a conflict guard, not a field that a client can edit. Count that root write and snapshot in every batch. A leaf-only text edit still compares only its requested human groups.

Human MoveTask arriving after another move of that same task rejects on own `orderIntent`; moves of different tasks resolve by accepted server order. An AI move compares both order field and human versions. The command's anchors are intent, not authorization or evidence of a historic neighbor list.

## Other entities

An area has independent name human version and deletion version. A note has text human version and deletion version. Templates have `scheduleVersion` and `generation` for user schedule/blueprint edits, plus `materializationVersion` for the generator cursor and skips. Predictions guard schedule version/generation, not unrelated worker cursor advances. A skip also guards the impacted range and materialization version. Template edits with an impact preview guard both versions; stale offline edits stay recoverable.

Membership, workspace defaults, invitations and owner transfer use their exact canonical entity versions and the workspace revision CAS. Removed membership invalidates all command types. Changing defaults does not rewrite previously pinned task dates or statistics zone.

AI requests use stable device operation identities. Job lease fence, status and proposal version follow the AI contract. User cancellation requires current job version; server invalidation does not wait for a client. An internal effect key and expected phase prevent duplicate transition effects.

All commands include the current state epoch and installation registration. Sync performs authorization, receipts and sequence checks before these domain rules. Conflict errors return a stable code, affected group, expected/current version and the client's recoverable intent reference.

## Recurrence creation and prediction

`CreateRecurrenceTemplate` derives its UUIDv5 ID from device namespace and ASCII `template/{sequence}/0`. It carries the closed rule, anchor, zone, blueprint and optional `seedTaskId` with the task's expected recurrence/deletion versions. A seed remains an ordinary task and is not a canonical occurrence. Its date is excluded by choosing the first template slot strictly after that date. Ordinary standalone templates have no seed. The creation receipt returns schedule/materialization versions for later `AfterOperation` references, so a phone can calculate identities before reconnecting without submitting arbitrary template IDs.

`MaterializePredictedOccurrence` carries the exact canonical key/ID, schedule version/generation and the rule-derived blueprint hash. It creates one eligible missing root, or finds the existing matching key; it never changes a found task. The result receipt returns its canonical version groups. A following edit/claim/complete depends on that receipt with `AfterOperation`, uses those output versions and undergoes the normal current-state checks. Store the user's action separately until materialization succeeds. This is intentionally two bounded commands, not an undefined nested action. If the occurrence was deleted/purged, skipped or superseded, preserve the dependent action as recovery rather than creating another root. The server's generation cursor and lifetime compact materialization ranges identify already-used slots even after a task is purged. Retain generation summaries until template purge; old-generation predictions never create after a schedule edit.
