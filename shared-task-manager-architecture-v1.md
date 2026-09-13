# Bun Do product scope

The owner revised the release boundary on September 13, 2026. This replaces the earlier complete-feature v1 proposal. Its old wire examples and broad operational guarantees are not implementation requirements. Git history and the original decision issues retain that history.

## Purpose

Two people can speak or type a household task, turn it into actionable work, share it, and finish it. Everyday task actions work offline and synchronize later without silently losing user text. Finnish and English work throughout; task content stays in its original language.

## V1

- Shared queue with any available task selectable, claim/unclaim, complete/reopen/cancel, and accessible reordering. New tasks normally join the end. Explicitly urgent or soon-due captures can start near the front, with placement explained before saving. Existing tasks never silently reshuffle.
- Typed capture and editable title/description. Preserve original captured text separately.
- Task detail shows creator, creation time, last modifier and modification time. Keep creation attribution through edits and distinguish pending local changes from accepted shared changes.
- MAI transcription online through the authenticated backend, installed Parakeet offline, and typing when either speech path is unavailable. Preserve unsuccessful recordings for retry/export/delete under the existing local storage policy.
- Cloud cleanup using one configured model. Preserve human corrections, interpret relative dates at capture time and ask before applying ambiguous dates.
- Manual and AI splitting into a root with direct checklist items. Preview AI output and commit accepted children atomically.
- Due dates, snooze, basic approximate Android reminders, and simple daily/weekly repeats with one outstanding occurrence per repeat. Server generation may wait for connectivity; cached occurrences remain usable offline.
- Delete with Undo and recoverable deleted tasks. Ordinary edits never resurrect deleted work.
- Existing Microsoft sign-in, household joining and ownership controls, account isolation, offline persistence and typed sync.
- The full [stale-client recovery slice](https://github.com/DrBushyTop/bun-do/issues/21), including snapshot artifacts, pruning coordination, device expiry and explicit old-epoch recovery. The owner retained this in-progress work in v1.
- Recent shared activity, weekly/monthly first-completion counts, lifetime milestones and a simple weekly streak. Use server acceptance dates, without member rankings, penalties or historical clearance reconstruction.
- Native Android UI, Finnish/English copy, accessibility and the approved rabbit identity. The illustrated release is light-only; dark-mode adaptation is deferred without deleting existing theme code. Adopt the owner's selected [household visual reference](docs/design/household-visual-reference.md), including compact navigation, illustrated claimants, quiet action animation variants and shared progress charts. Remove technical clutter while building each flow.
- Safe migrations for data actually in use, existing backups, a documented manual recovery procedure and focused two-device release verification.

## V2

The [V2 backlog](https://github.com/DrBushyTop/bun-do/issues/42) owns deeper task trees and dependencies, separate notes/areas, AI clarify, advanced recurrence, recurrence extraction and offline predictions, historical clearance/queue trends, FCM sync hints, explicit AI queue placement and optional escalation/worker sophistication, and expanded workspace recovery/compatibility operations. Reassess the proposed mechanisms against usage before implementing them.

Completed foundations and the in-progress recovery slice remain in v1. This scope change does not authorize deleting working code or discarding retained user data.

## After V2

[Review AI usage policy](https://github.com/DrBushyTop/bun-do/issues/37) only after V2. Start without product recording-length quotas, daily/monthly request or token budgets, per-user fairness limits or app-imposed usage caps. Observe actual usage first; keeping no product limits is a valid decision. Provider constraints and technical request, memory, output and timeout bounds still apply. Those bounds prevent failures, not ration household usage.

## Delivery and authority

[Architecture contracts](docs/architecture/README.md) define behavior. [The implementation plan](docs/implementation-plan.md) links the native GitHub work graph. Code, schemas, deployment files and tests own implementation details and current bounds; issue comments own test and review evidence.

Kotlin, Compose, Room and WorkManager remain the Android stack. Azure, the .NET backend, Cosmos, Aspire local orchestration and Bicep remain selected. The scope reduction does not start a platform rewrite.

The initial audience uses vivo X300 Ultra and OnePlus 13. Agents verify functional behavior on two Android emulator profiles. The separate phone benchmark was skipped; no physical-device performance is claimed. The approved original rabbit artwork lives in `assets/brand/`.
