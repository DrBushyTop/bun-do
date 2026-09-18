# Bun Do

Bun Do is a shared household task queue. Members capture work and make progress together, including while offline.

## Language

**Bun Do**:
The app's name, meaning "the way of the bun." A bun is a bunny, with a martial-arts character direction inspired by the owner's King Bun reference.
_Avoid_: Shared Task Manager as the product name, bread or bakery branding.

**Workspace**:
A collection of tasks with one access audience. A household workspace belongs to its active members; a personal workspace belongs to one account.

**Private task**:
A task visible only to its creator, kept independently of their household membership.

**Task audience**:
The people allowed to see a task, either one account or a household. A checklist and its items have one audience.

**Claim**:
A member's intention to work on an available task. A claim made offline is provisional until the shared queue accepts it.

**Root task**:
A task with no parent. Root tasks are the unit for shared completion milestones.

**Container task**:
A root task split into direct checklist items whose progress comes from those items.

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

**Weekly streak**:
Consecutive weeks with at least one shared first-completion credit. The current unfinished week gives the household time to continue the run.

**Adventure**:
A shared household outcome or work session that groups main tasks, with progress based on their current completion states. Its Finnish name is Seikkailu; a main task is a root task, including a checklist container.
_Avoid_: Trip or retki as the feature name.

**Adventure suggestion**:
An unaccepted proposed adventure, distinct from the household's accepted adventure. Suggestions can expire without ending accepted work.

**Bun journey**:
The household's persistent trip through authored locations, advanced by new first-completion credits. It is separate from an adventure's reversible task progress and Bun's current activity.
