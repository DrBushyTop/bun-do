# Implementation plan

The [build backlog](https://github.com/DrBushyTop/bun-do/issues/14) contains all v1 slices. Native GitHub dependencies determine what is ready. This file indexes those tickets; it does not duplicate their status.

Run a fresh adversarial subagent review at low or medium reasoning only after
the whole slice is implemented and its planned verification has run, not during
partial implementation. Follow the [completion-review workflow](agents/issue-tracker.md#completion-review).
Fix material issues before continuing. Add the review conclusion and test
results to the slice issue. File concrete deferred bugs with reasons, not vague reminders.

| Slice | Blocked by |
| --- | --- |
| [Accept and retry typed task commands in an executable sync model](https://github.com/DrBushyTop/bun-do/issues/15) | None |
| [Capture and edit typed tasks offline in the native Android shell](https://github.com/DrBushyTop/bun-do/issues/16) | None |
| [Deploy the isolated Bun Do development stack with Bicep](https://github.com/DrBushyTop/bun-do/issues/17) | None |
| [Run the backend locally with Aspire and explicit emulator profiles](https://github.com/DrBushyTop/bun-do/issues/36) | None |
| [Sign in and keep local work isolated between accounts](https://github.com/DrBushyTop/bun-do/issues/18) | [Capture and edit typed tasks offline in the native Android shell](https://github.com/DrBushyTop/bun-do/issues/16), [Deploy the isolated Bun Do development stack with Bicep](https://github.com/DrBushyTop/bun-do/issues/17) |
| [Join a household with approved invitations and ownership controls](https://github.com/DrBushyTop/bun-do/issues/19) | [Sign in and keep local work isolated between accounts](https://github.com/DrBushyTop/bun-do/issues/18) |
| [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20) | [Accept and retry typed task commands in an executable sync model](https://github.com/DrBushyTop/bun-do/issues/15), [Capture and edit typed tasks offline in the native Android shell](https://github.com/DrBushyTop/bun-do/issues/16), [Join a household with approved invitations and ownership controls](https://github.com/DrBushyTop/bun-do/issues/19), [Run the backend locally with Aspire and explicit emulator profiles](https://github.com/DrBushyTop/bun-do/issues/36) |
| [Recover stale clients without losing or resurrecting work](https://github.com/DrBushyTop/bun-do/issues/21) | [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20) |
| [Claim, finish and reorder shared tasks without conflicting state](https://github.com/DrBushyTop/bun-do/issues/22) | [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20) |
| [Split tasks manually and enforce combined hierarchy/dependency rules](https://github.com/DrBushyTop/bun-do/issues/23) | [Claim, finish and reorder shared tasks without conflicting state](https://github.com/DrBushyTop/bun-do/issues/22) |
| [Edit dates, snooze, notes and areas offline](https://github.com/DrBushyTop/bun-do/issues/24) | [Claim, finish and reorder shared tasks without conflicting state](https://github.com/DrBushyTop/bun-do/issues/22) |
| [Delete and restore task groups with bounded content purge](https://github.com/DrBushyTop/bun-do/issues/25) | [Split tasks manually and enforce combined hierarchy/dependency rules](https://github.com/DrBushyTop/bun-do/issues/23), [Edit dates, snooze, notes and areas offline](https://github.com/DrBushyTop/bun-do/issues/24), [Recover stale clients without losing or resurrecting work](https://github.com/DrBushyTop/bun-do/issues/21) |
| [Capture local speech with recoverable Parakeet installation](https://github.com/DrBushyTop/bun-do/issues/26) | [Capture and edit typed tasks offline in the native Android shell](https://github.com/DrBushyTop/bun-do/issues/16) |
| [Prefer MAI online transcription with recoverable offline fallback](https://github.com/DrBushyTop/bun-do/issues/38) | [Sign in and keep local work isolated between accounts](https://github.com/DrBushyTop/bun-do/issues/18), [Capture local speech with recoverable Parakeet installation](https://github.com/DrBushyTop/bun-do/issues/26) |
| [Enrich captured tasks with durable and stale-safe AI jobs](https://github.com/DrBushyTop/bun-do/issues/27) | [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20), [Edit dates, snooze, notes and areas offline](https://github.com/DrBushyTop/bun-do/issues/24), [Deploy the isolated Bun Do development stack with Bicep](https://github.com/DrBushyTop/bun-do/issues/17) |
| [Preview and commit AI task splits atomically](https://github.com/DrBushyTop/bun-do/issues/28) | [Enrich captured tasks with durable and stale-safe AI jobs](https://github.com/DrBushyTop/bun-do/issues/27), [Split tasks manually and enforce combined hierarchy/dependency rules](https://github.com/DrBushyTop/bun-do/issues/23), [Capture local speech with recoverable Parakeet installation](https://github.com/DrBushyTop/bun-do/issues/26) |
| [Clarify tasks through non-destructive bilingual suggestions](https://github.com/DrBushyTop/bun-do/issues/29) | [Enrich captured tasks with durable and stale-safe AI jobs](https://github.com/DrBushyTop/bun-do/issues/27) |
| [Generate recurring tasks with deterministic offline predictions](https://github.com/DrBushyTop/bun-do/issues/30) | [Split tasks manually and enforce combined hierarchy/dependency rules](https://github.com/DrBushyTop/bun-do/issues/23), [Edit dates, snooze, notes and areas offline](https://github.com/DrBushyTop/bun-do/issues/24), [Recover stale clients without losing or resurrecting work](https://github.com/DrBushyTop/bun-do/issues/21) |
| [Deliver approximate reminders and independent FCM sync hints](https://github.com/DrBushyTop/bun-do/issues/31) | [Edit dates, snooze, notes and areas offline](https://github.com/DrBushyTop/bun-do/issues/24), [Generate recurring tasks with deterministic offline predictions](https://github.com/DrBushyTop/bun-do/issues/30), [Synchronize typed work through Cosmos with lossless incremental replay](https://github.com/DrBushyTop/bun-do/issues/20) |
| [Show shared activity, clearance and lifetime milestones](https://github.com/DrBushyTop/bun-do/issues/32) | [Claim, finish and reorder shared tasks without conflicting state](https://github.com/DrBushyTop/bun-do/issues/22), [Split tasks manually and enforce combined hierarchy/dependency rules](https://github.com/DrBushyTop/bun-do/issues/23), [Generate recurring tasks with deterministic offline predictions](https://github.com/DrBushyTop/bun-do/issues/30) |
| [Restore workspaces and upgrade clients without replaying old commands](https://github.com/DrBushyTop/bun-do/issues/33) | [Delete and restore task groups with bounded content purge](https://github.com/DrBushyTop/bun-do/issues/25), [Join a household with approved invitations and ownership controls](https://github.com/DrBushyTop/bun-do/issues/19), [Show shared activity, clearance and lifetime milestones](https://github.com/DrBushyTop/bun-do/issues/32) |
| [Finish the King Bun-inspired native interface and accessibility](https://github.com/DrBushyTop/bun-do/issues/34) | [Preview and commit AI task splits atomically](https://github.com/DrBushyTop/bun-do/issues/28), [Clarify tasks through non-destructive bilingual suggestions](https://github.com/DrBushyTop/bun-do/issues/29), [Deliver approximate reminders and independent FCM sync hints](https://github.com/DrBushyTop/bun-do/issues/31), [Show shared activity, clearance and lifetime milestones](https://github.com/DrBushyTop/bun-do/issues/32), [Restore workspaces and upgrade clients without replaying old commands](https://github.com/DrBushyTop/bun-do/issues/33) |
| [Verify the complete v1 release across two offline emulator profiles](https://github.com/DrBushyTop/bun-do/issues/35) | [Finish the King Bun-inspired native interface and accessibility](https://github.com/DrBushyTop/bun-do/issues/34), [Prefer MAI online transcription with recoverable offline fallback](https://github.com/DrBushyTop/bun-do/issues/38) |

## Local orchestration addition

The owner requested Aspire after map closure. [Run the backend locally with Aspire and explicit emulator profiles](https://github.com/DrBushyTop/bun-do/issues/36) is an independent build slice and a native prerequisite for cloud sync integration. The CLI and six workflow skills are installed. The Functions health endpoint, Azurite, loopback-only listeners and request telemetry have been verified through Podman. Bicep still owns Azure deployment.

## Speech decision and post-v1 policy

The owner selected [MAI online transcription with offline fallback](https://github.com/DrBushyTop/bun-do/issues/38)
after the local and Azure comparisons. The [decision](adr/0002-cloud-first-speech-with-offline-fallback.md)
explicitly replaces the earlier local-only audio boundary. This brings v1 to
23 slices. The offline speech slice establishes the fallback and recovery
behavior; it does not claim the authenticated cloud path is implemented.

[AI length and usage-rate limits](https://github.com/DrBushyTop/bun-do/issues/37)
wait until after the v1 parent is complete. They are not a v1 blocker. Preserve
technical bounds, authentication and recovery while deferring product quotas.

## Scope coverage

- Queue, capture, local persistence and collaboration: typed shell, command model, sync, task work and recovery.
- Nested subtasks, dependencies, notes, areas, due, snooze and deletion: graph, details and deletion slices.
- Offline speech and original text: voice slice; model installation and recovery are part of it.
- Cloud cleanup, split, clarify and configurable Terra: AI job, split and clarify slices.
- Recurrence and shared progress: recurrence and progress slices, including lifetime credits versus clearance.
- Identity, invitations, local isolation, reminders, FCM, migrations and cloud restore: identity, membership, reminders and operations slices.
- Complete Finnish/English, native rabbit identity and accessibility: present from the shell and finalized by the UI/release slices.

The first independent work is the executable command model, Android typed shell, and isolated Bicep deployment. Cloud-specific dependent work waits for actual live gates. None of the stages authorizes feature removal.

See [implementation contracts](architecture/README.md) and [environment evidence](research/implementation-environment.md). No physical-phone benchmark is hidden in this backlog.
