# Tasks and the shared queue

The September 13, 2026 scope revision keeps everyday shared task work and one level of checklist items. Deeper nesting and cross-task prerequisites are [V2 work](https://github.com/DrBushyTop/bun-do/issues/43). Sync authorization, receipts, revisions and recovery follow [the sync contract](sync-protocol.md).

## Shape and limits

A task is either a standalone root, a root containing direct checklist items, or a checklist item. Items cannot contain children or move between roots in v1. Manual and AI splits use the same domain command. Split requires an open, non-deleted leaf and creates an editable, bounded list of children atomically. Adding items to an open checklist uses the same validation.

Technical text, item-count and batch-size bounds live in code and tests. Keep effects within one supported transaction and preserve the draft when an operation is too large. Existing safety bounds remain until deliberately changed; no generic graph engine or large-workspace capacity system is required.

## Lifecycle and claims

Lifecycle is OPEN, COMPLETED or CANCELLED. A claimed open leaf displays as in progress. Claiming is for oneself and requires an open, unsnoozed, non-deleted leaf. A repeated claim by its holder is harmless; another claimant causes a recoverable conflict. Only the claimant or household owner may release a claim. A removed member's claim is ineligible.

Any current member may complete a leaf without first claiming it. Completing another member's claimed work requires confirmation and records actor attribution. Completion clears the claim. Reopen clears current completion presentation and claims but preserves first-completion statistics. Cancel clears claims and snooze. Exact lifecycle/deletion versions prevent a stale completion from undoing a reopen.

Checklist roots cannot be claimed or directly completed. Their non-deleted, non-cancelled items determine completion: with at least one eligible item, all completed means COMPLETED, otherwise OPEN. With none, display CANCELLED with an empty-checklist reason. Restoring or reopening an item recomputes the root. Splitting clears the former root claim.

Explicit checklist cancellation affects eligible items atomically. Reopening that cancellation only reopens items cancelled by that action. Independently cancelled items remain cancelled. A root mutation covering items uses an observed subtree version so stale actions cannot silently cover newly edited items.

Snooze hides a leaf until its stored instant and clears its claim. A snoozed checklist root hides its items and prevents their claiming/completion until unsnoozed. Root snooze clears current child claims in the same bounded command. Already-open item details observe this rule too.

## Ordering

Root and checklist-item orders are separate bounded lists. Keep the established anchor-based MoveTask behavior and exact version guard for moves of the same task. Moving different tasks resolves in server acceptance order. Missing anchors use the established deterministic fallback; accessible move actions and drag produce the same command. No task dependencies or graph traversal are required.

Remove the moving task before resolving live anchors in its list. A surviving after-anchor wins, including when the anchors are inverted. Otherwise use a surviving before-anchor, or append if neither survives. Self-anchors are invalid. Active root order contains OPEN roots; completion/cancellation removes a root and reopening appends it. Keep placement separate from each task's intentional-move version.

## Delete and restore

Delete hides a leaf or a checklist with its currently non-deleted items, using one deletion group. Already-deleted items keep their own groups. Clear claims and recompute checklist completion atomically. Guard a root cascade with its observed subtree version.

Undo and later Restore use the same explicit restoration command. Restore only that group's records, with previous leaf lifecycle and snooze but no claims. Restore an item only when its parent exists and is not deleted. A separately deleted item stays deleted when the root returns. Concurrent edits cannot revive a deletion.

Keep the retention, purge safety and non-resurrection rules implemented by [stale-client recovery](https://github.com/DrBushyTop/bun-do/issues/21). Deletion need not wait for dates, notes, areas or advanced recurrence. Later checklist integration must extend leaf deletion atomically before release.

## Details

V1 has title, description, original capture text, due and snooze. Separate shared notes and areas are [deferred](https://github.com/DrBushyTop/bun-do/issues/47). Tags, effort scores and attachments remain outside v1. [Dates and progress](dates-recurrence-progress.md) define simple repeats and completion counting.
