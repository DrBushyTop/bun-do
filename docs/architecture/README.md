# Implementation contract

The owner revised v1 on September 13, 2026 to prioritize daily household use. [Product scope](../../shared-task-manager-architecture-v1.md) replaces the earlier full-feature proposal. [V2](https://github.com/DrBushyTop/bun-do/issues/42) holds deferred capabilities. The owner explicitly retained the full in-progress stale-client recovery slice in v1 and requested a simple weekly streak alongside counts and milestones.

## Read order and authority

| Contract | What it owns |
| --- | --- |
| [Sync and recovery](sync-protocol.md) | Local intent, receipts, revisions, retained snapshots/retention/expiry and epoch recovery. |
| [Commands and versions](command-catalog.md) | V1 command preconditions and conflict rules. |
| [Task queue](task-domain.md) | Task lifecycle, claims, direct checklist items, ordering and deletion. |
| [Dates, repeats and progress](dates-recurrence-progress.md) | Capture-relative dates, simple server-generated repeats, completion counts, milestones and weekly streak. |
| [AI processing](ai-processing.md) | Durable cleanup/split requests and safe result application, without product quotas. |
| [Identity and operations](identity-and-operations.md) | Existing joining/account boundaries, reminders, safe upgrades and manual recovery. |
| [Design direction](../../DESIGN.md) and [screen contracts](../design/screen-contracts.md) | Native Android behavior, accessibility and the approved rabbit identity. |

GitHub owns work status and evidence. The [implementation plan](../implementation-plan.md) indexes the revised native dependency graph. Completed decision tickets retain historical context; their earlier full-v1 scope does not override this revision. Code, schemas, Bicep and tests own implementation details and pinned values. Existing schemas or methods for deferred features do not make those features release requirements.

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
- Azure deployment, Entra sign-in/API access, strict schemas on the configured model endpoint and Cosmos concurrency remain relevant live gates with failure handling. The complete cloud restore drill moves to V2; v1 verifies backup configuration and documents safe manual recovery. Check the owning slice for current evidence; the original map did not verify them.
- DESIGN.md includes the owner's selected household visual direction for native v1. The browser study is not evidence of Android implementation or accessibility. Preserve the owner-approved rabbit mark in assets/brand/ and inspect native screens through Impeccable.
- The owner skipped all Azure budget alerts on September 12, 2026, then moved product AI usage-policy review until after V2 on September 13, 2026. Start without product quotas and use measured usage to decide whether any are needed. Technical safety bounds remain required. No production data exists to migrate.

If a live gate disproves an assumption, stop dependent work, record the evidence and change the owning decision. Do not silently swap cloud speech for offline speech, remove required features or claim a failed gate passed.

The owner subsequently chose [MAI online transcription with Parakeet fallback](../adr/0002-cloud-first-speech-with-offline-fallback.md).
This is an explicit change to the local-only audio boundary. The authenticated
cloud path is a separate v1 slice, not part of the offline capture completion.

## Release scope

V1 includes typed capture, MAI online transcription with Parakeet fallback, original text, cleanup and manual/AI checklist split, shared claims/completion/order, description, due/snooze, delete/restore, simple daily/weekly repeats, reminders, activity, first-completion counts, lifetime milestones and a weekly streak. Keep Finnish/English, accessibility, account-safe persistence and the full retained recovery slice.

V2 owns deeper subtasks/dependencies, notes/areas, AI clarify, advanced scheduling and offline predictions, exact historical metrics, FCM, explicit AI queue placement/recurrence extraction, optional model escalation and expanded workspace operations. No V2 work blocks v1. AI product-limit policy is outside both releases and begins only after V2. Technical bounds and authentication remain.

[Reusable household lists](reusable-lists.md) defines saved starting content,
standing lists, fresh copies and permissive adventure finishing.
