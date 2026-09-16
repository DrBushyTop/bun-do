# Implementation plan

[Build the Bun Do v1 household release](https://github.com/DrBushyTop/bun-do/issues/14) owns the first household release. [Bun Do V2: deferred capabilities after the first useful release](https://github.com/DrBushyTop/bun-do/issues/42) holds deferred capabilities. Native GitHub sub-issues and blockers own status and readiness; this file indexes the graph.

## V1 boundary

The owner reduced v1 on September 13, 2026. Keep shared capture/claim/completion/order, MAI online speech with Parakeet fallback, cleanup and direct checklist split, description/due/snooze, delete/restore, simple daily/weekly repeats, basic reminders and Finnish/English accessibility. Statistics retain recent activity, weekly/monthly first-completion counts, lifetime milestones and a weekly streak, using server acceptance dates.

The owner explicitly retained the full in-progress [Recover stale clients without losing or resurrecting work](https://github.com/DrBushyTop/bun-do/issues/21) in v1. Snapshot artifacts, pruning coordination, expiry and epoch recovery remain required there. Do not interrupt that work or defer its mechanisms.

Completed slices remain completed. Their issue evidence is historical; revised contracts own future behavior. Preserve useful implementation rather than rewriting the selected Azure/Cosmos/Aspire stack.

## V1 slices

The September 13 visual walkthrough is part of v1, not a separate web release.
[The selected visual reference](design/household-visual-reference.md) specifies
the native handoff. [Native interface and accessibility](https://github.com/DrBushyTop/bun-do/issues/34)
owns its adoption. [Task details](https://github.com/DrBushyTop/bun-do/issues/24)
also owns persisted creator/change attribution, explicit urgency and explained
initial placement. Do not reopen completed claim/order foundations for this work.

AI cleanup and AI checklist split are explicitly retained, through
[cleanup](https://github.com/DrBushyTop/bun-do/issues/27) and
[editable AI split previews](https://github.com/DrBushyTop/bun-do/issues/28).
The browser's canned suggestions are not evidence that either integration ships.

| Slice | Blocked by |
| --- | --- |
| [Simplify voice capture with editable task analysis and opt-in audio history](https://github.com/DrBushyTop/bun-do/issues/55) | None |
| [Accept and retry typed task commands in an executable sync model](https://github.com/DrBushyTop/bun-do/issues/15) | None |
| [Capture and edit typed tasks offline in the native Android shell](https://github.com/DrBushyTop/bun-do/issues/16) | None |
| [Deploy the isolated Bun Do development stack with Bicep](https://github.com/DrBushyTop/bun-do/issues/17) | None |
| [Run the backend locally with Aspire and explicit emulator profiles](https://github.com/DrBushyTop/bun-do/issues/36) | None |
| [Sign in and keep local work isolated between accounts](https://github.com/DrBushyTop/bun-do/issues/18) | [Capture and edit typed tasks offline in the native Android shell](https://github.com/DrBushyTop/bun-do/issues/16), [Deploy the isolated Bun Do development stack with Bicep](https://github.com/DrBushyTop/bun-do/issues/17) |
| [Join a household with approved invitations and ownership controls](https://github.com/DrBushyTop/bun-do/issues/19) | [Sign in and keep local work isolated between accounts](https://github.com/DrBushyTop/bun-do/issues/18) |
| [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20) | [Accept and retry typed task commands in an executable sync model](https://github.com/DrBushyTop/bun-do/issues/15), [Capture and edit typed tasks offline in the native Android shell](https://github.com/DrBushyTop/bun-do/issues/16), [Join a household with approved invitations and ownership controls](https://github.com/DrBushyTop/bun-do/issues/19), [Run the backend locally with Aspire and explicit emulator profiles](https://github.com/DrBushyTop/bun-do/issues/36) |
| [Recover stale clients without losing or resurrecting work](https://github.com/DrBushyTop/bun-do/issues/21) | [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20) |
| [Claim, finish and reorder shared tasks](https://github.com/DrBushyTop/bun-do/issues/22) | [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20) |
| [Delete tasks with Undo and retained restoration](https://github.com/DrBushyTop/bun-do/issues/25) | [Claim, finish and reorder shared tasks](https://github.com/DrBushyTop/bun-do/issues/22) |
| [Split tasks manually into direct checklist items](https://github.com/DrBushyTop/bun-do/issues/23) | [Claim, finish and reorder shared tasks](https://github.com/DrBushyTop/bun-do/issues/22), [Delete tasks with Undo and retained restoration](https://github.com/DrBushyTop/bun-do/issues/25) |
| [Clean up captured tasks with durable, stale-safe AI requests](https://github.com/DrBushyTop/bun-do/issues/27) | [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20), [Deploy the isolated Bun Do development stack with Bicep](https://github.com/DrBushyTop/bun-do/issues/17) |
| [Edit descriptions, due dates and snooze offline](https://github.com/DrBushyTop/bun-do/issues/24) | [Claim, finish and reorder shared tasks](https://github.com/DrBushyTop/bun-do/issues/22), [Clean up captured tasks with durable, stale-safe AI requests](https://github.com/DrBushyTop/bun-do/issues/27) |
| [Capture local speech with recoverable Parakeet installation](https://github.com/DrBushyTop/bun-do/issues/26) | [Capture and edit typed tasks offline in the native Android shell](https://github.com/DrBushyTop/bun-do/issues/16) |
| [Prefer MAI online transcription with recoverable offline fallback](https://github.com/DrBushyTop/bun-do/issues/38) | [Sign in and keep local work isolated between accounts](https://github.com/DrBushyTop/bun-do/issues/18), [Capture local speech with recoverable Parakeet installation](https://github.com/DrBushyTop/bun-do/issues/26) |
| [Preview and accept AI checklist splits](https://github.com/DrBushyTop/bun-do/issues/28) | [Clean up captured tasks with durable, stale-safe AI requests](https://github.com/DrBushyTop/bun-do/issues/27), [Split tasks manually into direct checklist items](https://github.com/DrBushyTop/bun-do/issues/23), [Capture local speech with recoverable Parakeet installation](https://github.com/DrBushyTop/bun-do/issues/26) |
| [Generate simple daily and weekly household repeats](https://github.com/DrBushyTop/bun-do/issues/30) | [Edit descriptions, due dates and snooze offline](https://github.com/DrBushyTop/bun-do/issues/24), [Claim, finish and reorder shared tasks](https://github.com/DrBushyTop/bun-do/issues/22), [Delete tasks with Undo and retained restoration](https://github.com/DrBushyTop/bun-do/issues/25) |
| [Deliver basic approximate Android reminders](https://github.com/DrBushyTop/bun-do/issues/31) | [Edit descriptions, due dates and snooze offline](https://github.com/DrBushyTop/bun-do/issues/24), [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20) |
| [Show shared activity, completion counts, milestones and a weekly streak](https://github.com/DrBushyTop/bun-do/issues/32) | [Claim, finish and reorder shared tasks](https://github.com/DrBushyTop/bun-do/issues/22) |
| [Verify safe v1 upgrades, backups and manual recovery](https://github.com/DrBushyTop/bun-do/issues/50) | [Recover stale clients without losing or resurrecting work](https://github.com/DrBushyTop/bun-do/issues/21), [Join a household with approved invitations and ownership controls](https://github.com/DrBushyTop/bun-do/issues/19) |
| [Restore recovery controls for pre-identity anonymous recordings](https://github.com/DrBushyTop/bun-do/issues/41) | None |
| [Finish the reduced v1 native interface and accessibility](https://github.com/DrBushyTop/bun-do/issues/34) | [Preview and accept AI checklist splits](https://github.com/DrBushyTop/bun-do/issues/28), [Deliver basic approximate Android reminders](https://github.com/DrBushyTop/bun-do/issues/31), [Show shared activity, completion counts, milestones and a weekly streak](https://github.com/DrBushyTop/bun-do/issues/32), [Recover stale clients without losing or resurrecting work](https://github.com/DrBushyTop/bun-do/issues/21), [Generate simple daily and weekly household repeats](https://github.com/DrBushyTop/bun-do/issues/30), [Prefer MAI online transcription with recoverable offline fallback](https://github.com/DrBushyTop/bun-do/issues/38) |
| [Walk v1 flows and remove technical UI clutter before release verification](https://github.com/DrBushyTop/bun-do/issues/40) | [Finish the reduced v1 native interface and accessibility](https://github.com/DrBushyTop/bun-do/issues/34) |
| [Revise v1 requirements and move deferred capabilities to V2](https://github.com/DrBushyTop/bun-do/issues/51) | None |
| [Verify the reduced v1 release across two Android profiles](https://github.com/DrBushyTop/bun-do/issues/35) | [Restore recovery controls for pre-identity anonymous recordings](https://github.com/DrBushyTop/bun-do/issues/41), [Walk v1 flows and remove technical UI clutter before release verification](https://github.com/DrBushyTop/bun-do/issues/40), [Verify safe v1 upgrades, backups and manual recovery](https://github.com/DrBushyTop/bun-do/issues/50), [Revise v1 requirements and move deferred capabilities to V2](https://github.com/DrBushyTop/bun-do/issues/51) |

## Build order

Task completion, leaf deletion and text cleanup can progress from existing foundations. The details slice adds dates/snooze and connects explicit date extraction after cleanup. Checklist work extends the leaf commands. Basic reminders do not wait for recurrence or FCM; statistics do not wait for advanced scheduling or graph work.

Polish each flow as it lands. The final native finish and plain-language walkthrough precede release verification. The small operations slice verifies backups and safe upgrades and documents manual recovery; the full cloud restore drill is V2.

## V2 deferrals

These are deferred discovery/implementation tickets. Reassess concrete needs and acceptance criteria after v1 before marking them ready. The earlier machinery is not a requirement to reproduce it unchanged.

| Deferred capability | Ticket |
| --- | --- |
| Deeper nesting and dependencies | [Add deeper subtasks and task dependencies after household usage validates them](https://github.com/DrBushyTop/bun-do/issues/43) |
| Advanced schedules, recurrence extraction and offline predictions | [Extend simple repeats with calendar schedules and offline occurrence generation](https://github.com/DrBushyTop/bun-do/issues/44) |
| Historical clearance and exact queue trends | [Evaluate historical clearance and queue trends beyond simple shared statistics](https://github.com/DrBushyTop/bun-do/issues/45) |
| Separate notes and areas | [Add shared notes and areas when descriptions are no longer enough](https://github.com/DrBushyTop/bun-do/issues/47) |
| AI clarify | [Clarify tasks through non-destructive bilingual suggestions](https://github.com/DrBushyTop/bun-do/issues/29) |
| FCM sync hints | [Add FCM sync hints if foreground and periodic sync feel too slow](https://github.com/DrBushyTop/bun-do/issues/48) |
| Explicit AI placement, optional escalation and worker recovery | [Evaluate optional AI placement, escalation and worker recovery](https://github.com/DrBushyTop/bun-do/issues/49) |
| Workspace undelete, restore drills and extended compatibility | [Add workspace recovery and extended upgrade operations in V2](https://github.com/DrBushyTop/bun-do/issues/33) |

## Post-core/V3 exploration

[Shared Bun world and optional household quests](https://github.com/DrBushyTop/bun-do/issues/54)
records the owner's later-stage visual exploration. Follow the
[design and implementation sketch](design/bun-world-and-quests.md). It does not
block V1 or commit to XP, autonomous agents or additional AI integrations.

## AI usage policy after V2

[Decide whether AI usage limits are needed after V2](https://github.com/DrBushyTop/bun-do/issues/37) is blocked by V2 completion and belongs to neither release parent. Start without product usage quotas, daily/monthly budgets or per-user fairness allowances. Observe actual usage before deciding whether limits are needed. Technical I/O, memory, output and timeout bounds, authentication and provider constraints remain. No token-limit implementation gates AI enablement.

## Verification and review

Follow [the completion-review workflow](agents/issue-tracker.md#completion-review): implement the whole slice, run its relevant checks, then request one fresh low/medium adversarial review. Fix material findings and record evidence in the owning issue. Do not commit standalone review reports.

Release verification covers the revised v1, including retained recovery and relevant live integrations. It does not require the old full-feature acceptance list or V2 screens. Physical-phone performance remains unmeasured; agents use two Android emulator profiles.

See [product scope](../shared-task-manager-architecture-v1.md), [architecture contracts](architecture/README.md), [local development](local-development.md), [Azure development](azure-development.md), [identity decision](adr/0003-use-existing-workforce-tenant-for-sign-in.md) and [speech decision](adr/0002-cloud-first-speech-with-offline-fallback.md).
