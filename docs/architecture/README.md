# Implementation contract

Decision date: 2026-09-12. The owner delegated the remaining decisions and asked for the wayfinder map to finish before implementation. The full v1 release remains required.

## Read order and authority

The [original proposal](../../shared-task-manager-architecture-v1.md) owns product scope, not the final wire/schema examples. The contracts below supersede conflicting mechanics in that proposal. Do not implement its older receipt, rank, schema, state or API examples directly.

| Contract | What it owns |
| --- | --- |
| [Sync and recovery](sync-protocol.md) | Canonical base, outbox replay, receipts, sequences, Strong reads, revisions, snapshots, retention and epochs. |
| [Commands and versions](command-catalog.md) | Shared version names, command preconditions and conflict rules. |
| [Task graph and queue](task-domain.md) | Limits, lifecycle, claims, hierarchy, dependency cycles, ordering, areas, notes and deletion groups. |
| [Dates, recurrence and progress](dates-recurrence-progress.md) | Capture time, deadlines, calendar rules, occurrence identities and statistics. |
| [AI processing](ai-processing.md) | Durable jobs, leases, paid-call uncertainty, validation, patches, split and clarify. |
| [Identity and operations](identity-and-operations.md) | Joining, permissions, account isolation, reminders, migrations, export and cloud restore. |
| [Design direction](../../DESIGN.md) and [screen contracts](../design/screen-contracts.md) | Native Android interaction and visual requirements, including unfinished rendered verification. |

GitHub decision tickets hold the resolution history. This directory holds the implementation contract those resolutions select. The [implementation plan](../implementation-plan.md) maps required behavior to build tickets.

## Chosen deployment and modules

Use Kotlin, Compose, Room and WorkManager on Android. Start with one app module organized by feature and pure domain packages; separate modules when a real dependency/test seam requires it. Use .NET 10 isolated Functions with one domain project and one Function host, plus tests. Do not create pass-through application/contracts/infrastructure projects solely because the original proposal sketched them.

Keep semantic commands and deterministic reducers testable without UI, network or Azure. The Android local workspace module owns atomic local intent, projection and sync. Its production adapter uses Room; two-device model tests use independent in-memory stores. The server workspace module owns authorization-to-commit orchestration behind one command interface; Cosmos and fake-store adapters run the same behavioral contract tests. Speech has a local runtime adapter. AI has a provider adapter but no authority to bypass domain commands.

New infrastructure belongs to Bun Do's own resource group, `rg-bun-do-dev-swc`, through Bicep. Select Sweden Central first, a single write region, Strong Cosmos consistency, .NET 10 Flex, private Blob storage for snapshots/model artifacts, and managed identity for backend resource access. Model deployment names are `bun-do-luna` and optional `bun-do-terra`. Pin actual package, API and model versions during the first live gate. Production uses a separate resource group and non-secret parameter file.

The owner authorizes new resource deployment and supplies Foundry model availability as a planning assumption. Do not mutate unrelated resources or log their credentials. Infrastructure permission is not proof of a successful deployment. The [environment inventory](../research/implementation-environment.md) gives the tools, setup path and required live checks.

## Known assumptions, not open architecture questions

- Local Parakeet is assumed feasible on vivo X300 Ultra and OnePlus 13. The owner skipped the separate phone benchmark. Do not reopen it as a mandatory gate under another name.
- Agents use two ARM64 Android emulator profiles on this Mac. Install the missing SDK/JDK/emulator tooling during implementation. Emulator functional evidence does not establish those phones' latency, memory use or recognition quality.
- Azure deployment, Entra OTP/API access, strict schemas on the real model endpoint, Cosmos concurrency and restore drills are untested. They are explicit early implementation/release gates with failure handling, not completed wayfinder experiments.
- DESIGN.md is an architect-selected direction, not a rendering or human approval. Implement the original rabbit mark and inspect native screens through Impeccable.
- Azure budgets alert; they are not a provider cost guarantee. AI admission has hard token/job limits. No production data exists to migrate.

If a live gate disproves an assumption, stop dependent work, record the evidence and change the owning decision. Do not silently swap cloud speech for offline speech, remove required features or claim a failed gate passed.

## Release scope

Include typed and local voice capture, original text, safe cloud enrichment, manual/AI split and clarify, nested tasks, shared ordering/claims/completion, due and snooze, notes, areas, dependencies, recurrence, delete/restore, activity, Together metrics, reminders, account-safe recovery, Finnish and English. Terra remains configurable and disabled until its live contract passes. Shared weekly streak is included without rankings or punishment.

Tags, effort scores, attachments, public sharing, large-team roles, arbitrary RRULEs and non-Android clients are not required v1 capabilities. Do not confuse optional items in the original proposal with cuts to the owner-confirmed scope.
