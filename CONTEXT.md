# Bun Do

Bun Do is a shared household task queue. Members capture work and make progress together, including while offline.

## Language

**Bun Do**:
The app's name, meaning "the way of the bun." A bun is a bunny, with a martial-arts character direction inspired by the owner's King Bun reference.
_Avoid_: Shared Task Manager as the product name, bread or bakery branding.

**Workspace**:
The shared collection of tasks and members who work on them.

**Claim**:
A member's intention to work on an available task. A claim made offline is provisional until the shared queue accepts it.

**Root task**:
A task with no parent. Root tasks are the unit for shared completion milestones.

**Container task**:
A task split into subtasks whose progress comes from its actionable descendants.

**Occurrence**:
One scheduled task created from a recurrence template. Splitting an occurrence does not change the next occurrence's decomposition by default.

**Deadline**:
When the task's work should be done. A time mentioned for a related event is not automatically a deadline.

**Recurrence template**:
The schedule and starting task content used to create separate occurrences. Editing one occurrence does not edit the template.

**Recovery variant**:
Locally authored work retained when it cannot safely join the shared state. The member may compare, copy or explicitly reapply it.

**Lifetime completion credit**:
The first completed state of a root task. Reopening and completing it again does not award another credit.

**Period clearance**:
The share of roots open at a period's start that are completed at its end, or now for the current period. This differs from lifetime completion credit.
