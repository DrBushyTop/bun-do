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

Sign-in uses MSAL Android and Microsoft-hosted authentication for personal
Microsoft accounts. The existing `huuhka.net` tenant owns the registrations. The owner's
[identity decision](../adr/0003-use-existing-workforce-tenant-for-sign-in.md)
replaces the original customer-tenant OTP choice. See
[development identity setup](../identity-development.md) for registrations.

Use Kotlin, Compose, Room and WorkManager on Android. Start with one app module organized by feature and pure domain packages; separate modules when a real dependency/test seam requires it. Use .NET 10 isolated Functions with one domain project and one Function host, plus tests. Do not create pass-through application/contracts/infrastructure projects solely because the original proposal sketched them.

Use Aspire for local backend composition and diagnostics. Its local profiles must select emulators or explicit substitutes and must not provision Azure during startup. Bicep remains the cloud deployment source. Android emulators connect as clients. See [local development](../local-development.md) and its separate build slice.

Bicep modules own an application responsibility, such as the workspace store,
backend hosting or observability. Group the resources and policy that implement
that responsibility. Do not create generic wrappers around single Azure
resources. Put access grants beside the consuming identity to keep module
dependencies one-way.

Keep semantic commands and deterministic reducers testable without UI, network or Azure. The Android local workspace module owns atomic local intent, projection and sync. Its production adapter uses Room; two-device model tests use independent in-memory stores. The server workspace module owns authorization-to-commit orchestration behind one command interface; Cosmos and fake-store adapters run the same behavioral contract tests. Speech has a local runtime adapter. AI has a provider adapter but no authority to bypass domain commands.

The owner reaffirmed Cosmos after evaluating Convex, while requesting a practical
route to switch later. [The storage decision](../adr/0001-cosmos-with-replaceable-storage.md)
defines the adapter's responsibilities and the behavior any replacement must
preserve. It does not introduce a second database or generic repository framework.

Bicep owns the dedicated development and production resource configuration. Keep deployment values in `infra/`, not this contract. The owner authorizes new resource deployment and supplies Foundry model availability as a planning assumption. Do not mutate unrelated resources or log their credentials. Infrastructure permission is not proof of a successful deployment. Use [Azure development](../azure-development.md) for the safe plan/deploy procedure and live gates.

## Known assumptions, not open architecture questions

- Local Parakeet is assumed feasible on vivo X300 Ultra and OnePlus 13. The owner skipped the separate phone benchmark. Do not reopen it as a mandatory gate under another name.
- Agents use two ARM64 Android emulator profiles on this Mac. Install the missing SDK/JDK/emulator tooling during implementation. Emulator functional evidence does not establish those phones' latency, memory use or recognition quality.
- Azure deployment, Entra sign-in/API access, strict schemas on the real model endpoint, Cosmos concurrency and restore drills are early implementation/release gates with failure handling. Check the owning slice for current evidence; the original map did not verify them.
- DESIGN.md is an architect-selected direction, not a rendering or human approval. Implement the original rabbit mark and inspect native screens through Impeccable.
- The owner skipped all Azure budget alerts on September 12, 2026, then deferred product AI length/rate quotas until after v1. Technical safety bounds remain required. No production data exists to migrate.

If a live gate disproves an assumption, stop dependent work, record the evidence and change the owning decision. Do not silently swap cloud speech for offline speech, remove required features or claim a failed gate passed.

The owner subsequently chose [MAI online transcription with Parakeet fallback](../adr/0002-cloud-first-speech-with-offline-fallback.md).
This is an explicit change to the local-only audio boundary. The authenticated
cloud path is a separate v1 slice, not part of the offline capture completion.

## Release scope

Include typed and local voice capture, original text, safe cloud enrichment, manual/AI split and clarify, nested tasks, shared ordering/claims/completion, due and snooze, notes, areas, dependencies, recurrence, delete/restore, activity, Together metrics, reminders, account-safe recovery, Finnish and English. Terra remains configurable and disabled until its live contract passes. Shared weekly streak is included without rankings or punishment.

Tags, effort scores, attachments, public sharing, large-team roles, arbitrary RRULEs and non-Android clients are not required v1 capabilities. Do not confuse optional items in the original proposal with cuts to the owner-confirmed scope.
