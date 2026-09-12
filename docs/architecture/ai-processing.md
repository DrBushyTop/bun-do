# Durable AI work and safe result application

Architecture decision, 2026-09-12. Resolves [Define durable AI work and safe patch application](https://github.com/DrBushyTop/bun-do/issues/7). It extends [task-domain.md](task-domain.md), [dates-recurrence-progress.md](dates-recurrence-progress.md), and the revisioned sync contract. This is the full v1 AI design. It does not create Azure resources or prove a model deployment.

## Boundary and provider

The Android client submits AI intent through the normal authenticated command path. The Function app owns prompts, model calls, validation, and every canonical write. Android never receives a Foundry credential.

Luna is the default deployment. Terra is an optional configuration-driven escalation. The owner assumes all required models will be available in the new dedicated Bun Do resource group. That is a deployment assumption, not a quota, latency, schema, or invocation result.

Use the Azure OpenAI v1 Responses API with `model` set to the deployment name, `text.format` set to one of the schemas in `contracts/ai/`, and `store: false`. Requests have no tools. Responses, strict-output limits, and provider model availability remain subject to the live deployment gate. [Responses API](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/responses), [structured outputs](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/structured-outputs).

## Durable job state

One `ai-job:{id}` document lives in the workspace partition. It contains `kind` (`EXTRACT`, `CLARIFY`, `SPLIT`), requester member ID, target task ID, accepted workspace revision, prompt/schema versions, selected deployment policy, input hash, immutable private input snapshot reference, base versions, token reservation, attempt count, dispatch marker, status, lease fence, and result metadata. Do not retain prompt or model text in telemetry.

A user command that requests AI writes the task's visible `aiState`, immutable job intent, private input snapshot, operation receipt, activity, change records, and workspace revision in **one** conditional transactional batch. Initial status is `ACCEPTED`. The server validates membership, size limits, target state, and job admission before that batch. The private snapshot is readable only inside the workspace partition, never telemetry. Terminal-job input snapshots are scrubbed after seven days. Terminal metadata lasts 30 days, then becomes compact aggregate counters. An accepted command survives a crash even if no worker runs.

```text
ACCEPTED -> READY -> RUNNING -> SUCCEEDED
                     |          -> SUPERSEDED | CANCELLED | FAILED
                     -> RETRY_WAIT -> READY
                     -> UNKNOWN_OUTCOME
```

A best-effort queue message may wake a worker, but it is only a hint. A timer sweep is authoritative: it finds `ACCEPTED`, due `RETRY_WAIT`, and expired `RUNNING` jobs, then advances them under the workspace revision CAS. It may reclaim an expired `RUNNING` job only before its dispatch marker is written; an expired job marked `SENT` becomes `UNKNOWN_OUTCOME`. A job cancellation, task deletion, membership removal, restore epoch change, or explicit newer request sets `CANCELLED` or `SUPERSEDED` in that same boundary.

## Leases, retries, and call cost

A worker claims `READY` with a new random lease ID, incremented integer `leaseFence`, a two-minute `leaseExpiresAt`, and `RUNNING` status in one revisioned write. It renews the same lease every 30 seconds through the workspace CAS. Immediately before the cloud call, it writes `dispatchMarker: SENT` with that lease fence. Only the worker holding that exact lease ID and fence may finish the job. A late callback loses its result rather than overwriting newer state.

The cloud call happens outside Cosmos transactions. Before it starts, the worker records the reserved input/output token ceilings and attempt. On return, it records provider request ID when supplied, usage, latency, and result class without task text. Every status transition and result application creates a workspace change and advances the revision.

Retry only failures known not to have produced a completion, such as admission throttling, a rejected request, or a pre-send transport failure. Use at most three total attempts with bounded backoff. A known configuration 4xx, including a schema rejection, is terminal and never retries or escalates to Terra. Timeout, dropped connection after `SENT`, an expired sent lease, and malformed provider response after a possible completion become `UNKNOWN_OUTCOME`; do not automatically make a duplicate paid call. A member may explicitly retry, creating a new job and a new budget reservation.

Per attempt, cap input at 8,000 tokens. Cap output at 1,200 tokens for extraction/clarify and 1,600 for split. Admit at most three live jobs and 20 new jobs per workspace per day. Reserve the full ceiling for each accepted attempt against a 100,000-token workspace daily budget. Replace the reservation with reported usage when it arrives. An `UNKNOWN_OUTCOME` reservation is not released, because its cost is uncertain. Charge known failed attempts too. A disabled AI kill switch leaves accepted jobs queued and does not affect task sync.

## Safe patches and suggestions

The [command catalog](command-catalog.md) gives independently editable `title`, `description`, `contentLanguage`, and `area` fields plus atomic `due`, `recurrence` reference, `lifecycle`, `claim`, `hierarchy`, and `orderIntent` groups a `fieldVersion` and `humanVersion`. Every accepted server change advances affected `fieldVersion`. Only an explicit human command advances `humanVersion`; AI and derived writes do not.

An extraction patch records the base field and human versions for every requested group plus exact `lifecycleVersion`, `hierarchyVersion`, and `deletionVersion`. Apply a proposed group only when its `fieldVersion` still equals the recorded base and the task's lifecycle, hierarchy, and deletion versions still match. An AI order change also requires the recorded `orderIntent.fieldVersion` and `orderIntent.humanVersion`. The patch never changes claims, lifecycle, membership, dependencies, IDs, or task hierarchy.

A pending local human edit replays in command order against canonical state. If an intervening AI-only patch changed the field but its `humanVersion` still matches the command base, the human edit applies and supersedes that AI value. If a remote human edit changed `humanVersion`, preserve the local edit as a conflict variant. Never silently choose between two human edits.

Explicit AI placement becomes a normal `MoveTask` only when both recorded order-intent versions still match. It cannot replace a manual move. `needsReview: true`, or an ambiguity about due date, recurrence, or placement, blocks automatic application of that group. The server retains it as a suggestion rather than guessing. Recurrence is always a proposal: only an explicit human confirmation creates a template. That confirmation records the current ordinary task as `seedTaskId`, outside the canonical occurrence series, and starts the first slot strictly after its nominal local date. The task keeps its UUID; future occurrences use canonical occurrence IDs. Neither generator nor prediction recreates that seed date. The server verifies dates, workspace area IDs, task limits, and all domain rules after schema validation.

Clarify is always non-destructive. Its result is a versioned suggestion document. The user may copy or edit its fields through an ordinary human edit command. Superseded or partially unapplied AI values remain as recoverable suggestions for 30 days; they do not disappear just because one patch group lost its CAS race.

## Split preview and commit

A split job records the exact parent `hierarchyVersion`, `lifecycleVersion`, `deletionVersion`, parent-leaf precondition, and `fieldVersion` for title, description, and content language. A valid result creates `ai-split-proposal:{id}` in the same revisioned batch as `SUCCEEDED`. The proposal has `proposalVersion: 1`, immutable generated children, a 24-hour `expiresAt`, and no task mutation.

The preview is editable. `ACCEPT_SPLIT_PROPOSAL` carries proposal ID, proposal version, and the user's final child list. Before committing, the server requires that the proposal is unexpired, the parent still has the recorded hierarchy/lifecycle/deletion and title/description/content field versions, and the final list meets the normal manual split rules. It then atomically converts the parent and creates the edited children. The model's list is never committed by itself. A stale, expired, cancelled, or already accepted proposal fails without a partial split.

## Validation and failure handling

The JSON schemas only constrain shape. The server rejects empty or overlong text, invalid semantic dates/recurrence, unknown area IDs, duplicate or near-duplicate split children, a split child count outside 1–8, unsafe placement, disallowed language, and task/domain limits. It records `FAILED_SCHEMA`, `FAILED_VALIDATION`, `FAILED_CONFIGURATION`, `FAILED_PROVIDER`, `THROTTLED`, `UNKNOWN_OUTCOME`, or `SUPERSEDED` as machine-readable status. A schema configuration error is not retried or escalated to Terra.

Refusal and incomplete Responses output are terminal job results with a localized client code. Preserve the original task and show retry or manual editing. Log correlation IDs, deployment, model version, schema/prompt version, token counts, latency, and status only. Never log household text, raw prompts/responses, audio, JWTs, or provider credentials.

## Provider schemas

The three files below use the strict-output subset: every object has `additionalProperties: false`; every property is required; nullable values carry `null` in their type. `mixed` preserves code-switched Finnish/English content. They intentionally omit `maxLength`, `maxItems`, `minItems`, `pattern`, and unsupported size constraints. The request wrapper supplies a stable schema name and `strict: true`; server validation enforces the real limits.
