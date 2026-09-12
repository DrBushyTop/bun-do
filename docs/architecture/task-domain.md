# Task graph and shared queue

Architecture decision, 2026-09-12. Resolves [Bound task hierarchy, dependencies, and ordering](https://github.com/DrBushyTop/bun-do/issues/6). These are the architect's v1 policies. Full v1 scope remains included. The sync protocol owns authorization, operation receipts, revision CAS, retention and local replay. The [command catalog](command-catalog.md) owns exact version guards.

## Representation and limits

A root has depth 1. Task IDs are opaque strings, ordinary UUIDs or recurrence IDs. Every task has immutable `rootId` and `parentId`; v1 supports adding and splitting children, without moving existing tasks between parents. Manual and AI splits use the same command. A root contains at most 32 tasks, including deleted descendants until their retention expires, and depth cannot exceed 3. A split creates 1 to 8 children. The parent must be an OPEN actionable leaf; split clears its claim. Further child additions target an OPEN container. Reopening precedes additions to completed or cancelled containers.

Each task has at most eight explicit prerequisites. Relationships stay inside the workspace and may cross roots. Each task document is at most 16 KiB of serialized UTF-8 JSON including metadata. Title limit is 160 Unicode scalar values, description 4,000, note body 4,000 and note document 20 KiB. Aggregate byte limits still apply. Capture text lives separately, up to 64 KiB. A root keeps at most 128 notes, including retained deleted notes. Notes are separate documents and never copied into task snapshots. Create/split input is at most 256 KiB. Limit errors preserve the local draft and identify the exceeded limit.

A workspace retains at most 32,768 task documents, including completed and deleted history. New-task admission rejects at that cap without discarding local text. At most 1,024 roots may be OPEN and not deleted in one workspace. Snoozed and blocked roots count. A single root-order document, capped at 128 KiB, holds their IDs and ranks. Completed and cancelled roots leave this index; reactivation appends them. These limits bound atomic changes without dropping nested tasks, dependencies or recovery.

All domain commands use the workspace revision CAS described in [sync protocol](sync-protocol.md). Validate against canonical state, then condition the commit on the same revision; retry validation after a collision. Graph reads occur before the transaction. Never trust a client graph or dependency availability flag. The batch planner counts every write, including task snapshots, activity, receipt, order index and revision metadata. Reject without partial effects above 90 operations or 1.75 MiB serialized payload. A 32-task delete plus 32 task change snapshots leaves room for metadata. If the sync implementation packs changes differently, it must still prove this budget with maximal fixtures.

## Lifecycle, claims and availability

Persist lifecycle as OPEN, COMPLETED or CANCELLED. `IN_PROGRESS` is a display value for an OPEN actionable task with a claim. A claim stores member and claim version; only one member can hold it. Claiming requires an OPEN, available, non-deleted actionable leaf. Claim repeats by its owner are idempotent. Another member's claim rejects with the canonical claimant.

Any current member may edit, complete, cancel, delete and restore shared tasks. Completing another member's claim is allowed and records the actor and prior claimant in activity. Unclaim requires the current claimant, except an owner may release another member's claim explicitly. A claim whose member is no longer active is ineligible and does not prevent another claim, as defined by the identity protocol. Claim, lifecycle, dependency and hierarchy commands carry the corresponding observed versions. Stale commands reject instead of guessing whether an old intent supersedes new work.

Complete requires OPEN, unsnoozed and unblocked; it clears claim and records the completer. Cancel clears claim and snooze. Reopen changes a completed or cancelled leaf to OPEN, clears completion presentation fields and claim, and retains history. A stale complete cannot undo a later reopen. Snoozing clears a claim and makes work unavailable until the stored instant; an explicit clear-snooze permits immediate work.

Containers have no direct claim or completion command. Their actionable descendant leaves determine progress, excluding deleted and cancelled leaves. With a nonempty denominator, all completed means COMPLETED; otherwise OPEN. An empty denominator means derived CANCELLED with reason `NO_ACTIONABLE_LEAVES`, including all-deleted children. Derived cancellation automatically reverses when a leaf returns. Explicit container cancellation cancels every non-deleted actionable descendant atomically. Reopening an explicitly cancelled container reopens leaves carrying that cancellation operation ID; independently cancelled leaves stay cancelled. Recompute affected containers bottom-up. A child reopen or restore can reopen any completed or derived-cancelled ancestor.

## Completion graph

Define a directed edge `A -> B` to mean A requires B to finish. The graph contains every parent-to-direct-child edge and every explicit dependency edge. A task also inherits prerequisites from each ancestor, represented during validation as task-to-ancestor-prerequisite edges. Parent edges and inherited edges are derived during validation, never duplicated in storage. Check the combined graph with iterative depth-first traversal and a recursion-stack set. Scan at most 32,768 stored tasks and their bounded edges; a 20-second validation deadline returns retryable `GRAPH_BUSY` without consuming an operation. Deletion, cancellation and removal of dependencies cannot introduce cycles and need no full cycle scan. This catches a child depending on its parent and cross-root deadlocks involving containers. Scan the whole affected reachable graph, not only explicit dependencies. Accept graph edits only when acyclic. Include retained deleted and cancelled tasks in cycle checking, so restoration cannot expose a dormant cycle. Reject new dependencies on deleted tasks. A purged dependency ID stays a nonblocking historical reference and cannot be reused.

Availability uses the same inherited prerequisite set. A prerequisite blocks while OPEN and not deleted; COMPLETED, CANCELLED and deleted prerequisites do not block. Dependency completion does not auto-complete dependents. Reopening a prerequisite can block currently OPEN dependents, but does not revoke existing completion credit or reopen completed dependents. Claims on newly blocked tasks remain visible as intentions; the task shows the blocker and completion rejects until it clears. This avoids an unbounded cross-root claim cascade. Containers cannot become claimable.

## Deletion and restoration

Delete marks the selected task and its currently non-deleted descendants with one `deletionGroupId`, clears claims, and recomputes ancestors. It leaves already deleted descendants in their original deletion groups. Notes become hidden through their task's deletion state without individual writes. Dependency references remain stored but stop blocking. Ordinary edits never restore deleted tasks.

Restore requires the deletion group's root and every ancestor outside that group to exist and be non-deleted. It restores only tasks tagged by that group, preserving their previous leaf lifecycle and snooze, with no claims, then recomputes containers. A separately deleted child stays deleted. Restoring an OPEN root must fit the active-root cap. The command revalidates the graph and byte budget and either commits entirely or returns a recovery conflict. A child restore under a deleted parent rejects with `RESTORE_ANCESTOR_FIRST`. After retention expires, recovery creates new IDs from any saved local text, never resurrects purged IDs.

## Ordering algorithm

Root order and sibling order are separate. Each container embeds a bounded child-order list. Neither claim nor snooze changes order. Root membership includes all OPEN roots; UI filtering does not change the underlying sequence. A child list retains completed, cancelled and deleted entries until purge, so recovery preserves sibling placement. Rank is an unsigned 64-bit integer encoded as 16 lowercase hexadecimal digits. Compare rank first, then unsigned UTF-8 bytes of the opaque task ID for defensive tie handling.

`MoveTask` sends task ID, expected parent, `afterTaskId` and `beforeTaskId`. No historical-neighbor fallback exists. Remove the moving item before resolving anchors. Only surviving anchors in the same current order list qualify. If both qualify in their expected order, insert immediately after `afterTaskId`, even when other items are between them. If inverted, `afterTaskId` wins. With only after, insert after it; with only before, insert before it; with neither, append. Null anchors use these same rules. Self-anchors are invalid. Deleted or non-OPEN roots cannot move. Concurrent moves of different tasks resolve in canonical server order. A stale move of the same task rejects on its order-intent version and stays recoverable.

Choose the midpoint between immediate neighboring ranks, using 0 and `2^64 - 1` as sentinels. If no free integer exists, rebalance the whole bounded list with rank `floor(i * (2^64 - 1) / (n + 1))`, for i starting at 1, then insert. Root rebalance writes one order document; sibling rebalance writes one parent. Task documents do not duplicate root ranks. Create appends by default. Explicit AI placement uses this command and the observed order-intent version, so late AI cannot replace a manual move.

## Acceptance scenarios

- A child depending on its parent rejects. Two cross-root edges that create a cycle only through parent completion also reject in either reconnect order.
- Split versus complete accepts the first command; the stale second command preserves its draft and reports the changed state.
- Delete a parent after one child was separately deleted. Restoring the parent restores its group only, with no claims and correct ancestor progress.
- Cancel every leaf, reopen one and then complete it. Ancestors transition CANCELLED, OPEN, COMPLETED; credit rules remain root-based.
- Reopen a prerequisite after a dependent completed. The completed task stays completed; an OPEN claimed sibling becomes blocked without losing its claim.
- Remove both move anchors, reverse them, exhaust a rank gap and reopen a root. Each path produces one deterministic bounded index update.
- Maximum-size 32-task cascade fits the batch planner; an oversized command writes nothing and retains local intent.

## Areas, notes and optional fields

An area has an immutable ID and editable name, at most 80 Unicode scalar values. A workspace keeps at most 64 active and 128 retained areas. Deleting an area makes references display as unassigned without rewriting every task; retained tasks keep the ID for history. Restoration restores that mapping. All members may manage areas. AI may choose only active IDs supplied with its input.

Notes are appendable offline. Each has author, text, created time and its own text/deletion versions. Any member may edit or delete a shared note with actor history. Concurrent edits use the same human-version conflict policy as task text. A deleted note remains recoverable for 120 days and counts against its root's note cap until purge. Adding a note to a deleted task rejects and preserves its text.

Tags and effort estimates were optional in the original proposal, not part of the required release. V1 has areas, no separate tags or effort-score model. Weekly shared streak is included. Description, capture provenance, due, snooze, notes, areas and every required task action remain in scope.
