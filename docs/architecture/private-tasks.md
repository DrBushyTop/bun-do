# Private tasks

[Creator-owned private tasks](https://github.com/DrBushyTop/bun-do/issues/80) adds
account-private task tracking. Household tasks remain the default. Existing
household tasks stay shared and local-only tasks are never uploaded automatically.

## Audience and ownership

A signed-in account has one personal workspace, independent of household
membership. Only that account may read or write it. It cannot invite members,
transfer ownership, leave, or be deleted through household controls. Another
installation of the same account can synchronize its personal tasks.

The Tasks view and new-task editor use the existing menu controls to choose a
household or Only me. Switching an unfinished capture preserves its draft without
overwriting another unfinished capture. Account settings also opens Only me,
including for someone who has no household. First setup needs a connection;
subsequent capture and ordinary task actions work offline.

Only a task's creator may change its audience. A checklist moves as a whole;
its children must also belong to that creator. Unknown creator attribution does
not grant permission. A child cannot change audience independently. Generated
repeat occurrences retain their schedule creator's attribution.

Household Lists, Together and Activity remain household destinations. Adventures
and Bun progress have no personal counterpart. Private tasks never enter
household activity, adventure suggestions, shared completion counts or Bun's
journey. Making a task private does not erase credit already earned by the
household. Sharing it back does not earn that credit again.

## Changing audience

Audience changes require connectivity and a confirmation preview of the current
content. Save unfinished edits and synchronize first. If the source changes,
open a fresh preview rather than silently moving a newer version.

Sharing publishes current title, notes, dates, lifecycle and live checklist
items. Original capture, recordings, deleted checklist items, AI requests and
private edit history are not published. The new shared task gets fresh creation
attribution. Recordings remain in their existing account-private storage.

A task in the active adventure must first be removed from that adventure.
Completed and cancelled tasks can otherwise change audience. Claims are cleared.
The current recurrence schedule moves with the task and stops in the source
workspace. An older occurrence cannot move an active schedule away from its
current occurrence. Previously shared occurrences remain household history.

Making private cannot erase another person's memory, exports or offline cache.
The old shared record becomes a non-restorable tombstone. Its previously shared
history remains readable under the existing retention contract; no later private
edits enter that history. Clients hide moved tombstones from ordinary deleted
items and reject stale edits rather than restoring the old shared task.

## Transfer durability

Personal and household data use separate workspace partitions and replication
streams. A per-task visibility filter inside the household stream is not a
privacy boundary because receipts, history and snapshots also contain task data.

The owner's private journal records the approved content and request identity
before the source transaction retires the task and stops its recurrence. A
second transaction imports the approved content under new task IDs. Each
workspace records a durable transfer receipt in the same transaction as its
effects. Retries check those receipts and never overwrite an already imported
task. Completed private journals discard their temporary content.

This is not an atomic cross-partition transaction. A connection failure can leave
a pending move between the two commits. The private journal preserves the
approved content and permits retry after restart, reinstall, or removal from the
source household. A changed source fails before retirement. Lost destination
access or exhausted storage leaves the request pending instead of publishing to
a different audience. The client must not report completion until import is
confirmed.

Only me exposes pending changes with their saved content and a retry action. For
an unfinished share, Keep private first fences the destination against import,
then restores the private content if source retirement already happened. If the
share already completed, cancellation reports that result instead of pretending
to revoke household access. Pending recovery responses are bounded; finishing
one batch makes later pending requests available.
