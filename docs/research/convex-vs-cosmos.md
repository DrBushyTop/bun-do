# Convex versus Cosmos DB

Reviewed September 12, 2026. This is retained research, not a replacement for the
[architecture contracts](../architecture/README.md). The owner has created a
Convex account and project. This research did not access that account, deploy
anything, or inspect its billing settings.

## Owner decision, September 12, 2026

Stay with Cosmos DB. The owner reports that Convex's free plan does not cover
European deployments and that Cosmos DB is available to them without cost.
Those are owner-supplied billing inputs, not billing entitlements verified by
this research. Do not assume the general free-tier comparison below applies to
a European Convex deployment.

The owner cancelled the local Convex proof before implementation. No proof files,
containers, volumes or cloud resources were created. Keep this comparison for
future reference; the Azure and Cosmos architecture and implementation backlog
remain in force.

## Earlier recommendation, not adopted

Convex is promising enough to justify a small local migration proof now, before
implementing the Cosmos adapter. If that proof passes, I would favor hosted
Convex for shared state and local Convex containers for development. It removes
real backend work, especially transaction coordination, online subscriptions
and job dispatch. Those benefits matter more than a possible zero-dollar bill.
They follow from the provider capabilities described below.

There are two conditions. Keep the durable Android offline model, and explicitly
resolve the backup policy before declaring production readiness. Do not sell
this as replacing Room with a live query. Nor should the current 15-minute
recovery-point target force this household to keep Cosmos without discussion.
That is a chosen requirement, not an immutable product feature.

The [implementation plan](../implementation-plan.md) places Cosmos integration
after the existing typed-command model and Android shell. The current
[local backend](../local-development.md) only exposes health. There is no
production data migration to perform according to the architecture record.
This is a relatively cheap time to change the backend. Keep the working model
and its tests until a replacement proves the same behavior.

## What gets simpler

Convex mutations provide a consistent transactional read view and atomic writes.
Its optimistic concurrency implementation gives serializability and retries
conflicting deterministic executions. Bun Do could put authorization, command
validation, effects and receipts in one mutation instead of hand-building the
Cosmos metadata ETag loop. A workspace revision can remain for the application
protocol without being the database's concurrency mechanism.
[Mutation transactions](https://docs.convex.dev/functions/mutation-functions#transactions),
[concurrency implementation](https://docs.convex.dev/database/advanced/occ).

An official Kotlin client exposes query subscriptions as `Flow`, calls mutations
and actions, and maintains the underlying WebSocket connection through the Rust
client. It fits the current Compose application without switching to React
Native. A narrow repository adapter can feed accepted remote state into Room;
screens should continue reading Room projections.
[Android client](https://docs.convex.dev/client/android/overview).

Scheduling a function inside a mutation is atomic with that mutation, and the
schedule survives server restarts. This can replace a separate queue wake-up
path and much of the dispatch plumbing for AI, recurrence and maintenance.
Scheduled mutations retry internal failures, while scheduled actions run at
most once and do not automatically retry transient failures. Authorization
does not automatically carry into scheduled work.
[Scheduled functions](https://docs.convex.dev/scheduling/scheduled-functions).

The likely final shape is:

- Kotlin, Compose, Room and WorkManager, unchanged in responsibility.
- TypeScript command handlers, queries and scheduled work in Convex.
- Azure Foundry as an external AI provider.
- A small Azure AI relay only if preserving Azure managed identity makes it
  worthwhile. That is a separate choice, not a mandatory second backend.

This is an architectural proposal. Calling an external provider from a Convex
action is supported, but does not make that external call transactional.
[Actions](https://docs.convex.dev/functions/actions).

## What Convex does not remove

The [sync contract](../architecture/sync-protocol.md) promises more than current
query results. Offline edits survive process death, retries do not repeat
effects, human conflicts remain recoverable, deleted tasks stay deleted, and
restoring a server never blindly replays old commands.

The inspected Rust request manager keeps outstanding requests in an in-memory
map and replays them on connection restart. That is useful connection recovery,
not evidence of a persistent Android outbox. The official Android guide does
not document a process-death-safe local database and journal. Keep Room until a
specific replacement proves those guarantees.
[Request manager source](https://github.com/get-convex/convex-rs/blob/main/src/base_client/request_manager.rs),
[Android client](https://docs.convex.dev/client/android/overview).

These application rules remain:

- Commit local intent and projection together before showing local success.
- Preserve immutable operation identities, command dependencies, rejection
  variants and accepted-sequence high-water records.
- Check human-versus-AI field versions, lifecycle and deletion preconditions.
  Database serializability cannot decide which person's text to preserve.
- Guard account switching, logout, membership removal and late callbacks.
- Keep deletion retention, expired-device recovery and restore epochs.
- Preserve task graphs, claims, recurrence identity, calendar rules and credit
  accounting from the existing domain contracts.
- Keep AI admission, stale-result checks and paid-call uncertainty. A durable
  scheduled action cannot prove whether a timed-out model request was billed.
- Retain Android reminder scheduling and background catch-up. A foreground
  subscription is not a replacement for the existing background/reminder
  contract.

Do not remove the immutable change journal during the first migration slice.
A subscription to workspace head can wake the existing bounded pull protocol.
Later, a separately reviewed design could replace some transport machinery
with versioned bounded snapshot queries. That would still need receipts,
atomic local application, deletion information and stale-client recovery.
Independent task-list subscriptions do not by themselves establish the
contract's complete revision-group application rule.

## Native Android, identity and Azure

Convex's Kotlin number mapping needs care. JavaScript `number` cannot preserve
all Kotlin `Long` values, and the SDK documents explicit numeric serializers.
Keep the contract's unsigned 64-bit sequence as a decimal string on the wire;
do not convert it to a JavaScript floating-point number.
[Kotlin type conversion](https://docs.convex.dev/client/android/data-types).

The client supports custom `AuthProvider` implementations. Convex also accepts
custom JWT issuers with a configured audience, issuer, signing keys and
algorithm. That makes Entra integration plausible, not already verified.
Require the exact audience and issuer, inspect the delegated scope in the
function, and check current membership inside every protected query or mutation.
Test token refresh, expiration, wrong scope and removal while subscribed.
[Android authentication](https://docs.convex.dev/client/android/overview#authentication),
[custom JWT configuration](https://docs.convex.dev/auth/advanced/custom-jwt).

Do not assume Convex Auth's email OTP offering supplies native Kotlin
authentication. Its documented client targets are React web and React Native,
and it is beta. Keeping Entra initially avoids combining an identity migration
with the database migration.
[Convex Auth](https://docs.convex.dev/auth/convex-auth).

Hosted region choices currently include Northern Virginia and Ireland, not
Azure Sweden Central. Existing deployment regions cannot change in place.
Select Ireland deliberately if European hosting is desired. The current
pricing page still labels bring-your-own-cloud as forthcoming.
[Regions](https://docs.convex.dev/production/regions),
[plan comparison](https://www.convex.dev/pricing).

A hosted Convex action is outside the planned Azure Function identity boundary.
Direct Foundry calls need a deliberately chosen server credential or a tested
federation arrangement. A narrow Azure relay can retain managed identity but
adds another hop and deployment. Do not assume Azure sponsorship pays Convex
charges. These are consequences of changing the hosting boundary, not verified
account capabilities.

## Local containers and Aspire

The official self-hosted guide provides backend and dashboard containers. The
backend defaults to SQLite with a persistent volume. The example exposes the
backend on 3210, HTTP actions on 3211 and dashboard on 6791. It supports the
hosted product's free-tier features. Convex says the self-hosted backend uses the
same current code as its cloud service.
[Self-hosting guide](https://github.com/get-convex/convex-backend/blob/main/self-hosted/README.md),
[self-hosting overview](https://docs.convex.dev/self-hosting).

For this repository, use an explicit Aspire local profile around pinned
backend and dashboard images. Keep the existing Podman VM. Preserve loopback
host bindings, persistent local data, readiness checks and explicit reset
commands. The upstream Compose file uses unqualified host port mappings, so
copying it unchanged would violate this repo's loopback policy. Its
`/version` health check and `/convex/data` volume give concrete starting points.
[Upstream container configuration](https://github.com/get-convex/convex-backend/blob/main/self-hosted/docker/docker-compose.yml),
[repo lifecycle policy](../local-development.md).

Expose only the backend development URL to Android, using the existing emulator
route through `10.0.2.2`. Keep the self-hosted admin key out of the APK, source,
logs and screenshots. Backend/dashboard containers do not need a browser
frontend for this native application. Pin and verify ARM64 image support and
Podman compatibility before treating this as a working profile.

There is also a different CLI-local option. It runs the backend as a subprocess,
stores state in `.convex`, supports an existing project, and does not consume
cloud function or database quotas. It is beta and is not intended for
production. Choose the container profile for this owner's requested workflow,
rather than maintaining both paths at first.
[Local deployments](https://docs.convex.dev/cli/local-deployments).

Local tests can exercise real Convex transaction and subscription behavior.
They cannot prove hosted quotas, regional latency, account auth configuration,
multi-zone durability or cloud restore operations. Self-hosting production is
an escape option, but owning backups, upgrades and availability would give back
much of the operational simplicity gained here.

## Price for a household

Prices below were read on September 12, 2026. Convex distinguishes hard-capped
Free from Starter, which has a $0 base price and bills additional usage.
Professional lists $25 per developer per month. Developers are team seats,
not the two people using the Android app.
[Pricing](https://www.convex.dev/pricing).

| Resource | Free included amount | Starter overage, US rate |
| --- | --- | --- |
| Database storage | 0.5 GB | $0.22 per GB-month |
| Database I/O | 1 GB per month | $0.22 per GB |
| Function calls | 1,000,000 per month | $2.20 per million |
| Action compute | 20 GB-hours per month | $0.33 per GB-hour |
| File storage | 1 GB | $0.033 per GB-month |
| Data egress | 1 GB per month | $0.132 per GB |
| Search storage | 0.5 GB | $0.55 per GB-month |
| Search queries | 3,000 query-GB per month | $0.11 per 1,000 query-GB |

The limits apply per team unless stated otherwise. Database storage includes
indexes, and each index is priced as another copy of its table. Subscription
updates, scheduled executions and file accesses count as function calls.
EU resource rates are 1.3 times the listed US rates. Free limits can cause new
mutations that insert or update data to fail; it is not automatic paid overflow.
[Resource limits and billing definitions](https://docs.convex.dev/production/state/limits).

For two people entering modest amounts of text, Free is plausible. It is not a
capacity promise. An illustrative 100 commands per household per day produces
3,000 commands per month. At an assumed 10 KB of retained records per command,
120 days of history alone occupies about 120 MB before indexes, canonical
tasks, activity and backups. Neither activity nor bytes per command has been
measured. The current 1 GiB per-workspace admission allowance is already larger
than Convex's Free database quota.

Broad reactive reads can consume more I/O than command counts suggest. Reading
a 100 KB workspace on 6,000 reexecutions is roughly 600 MB before writes, other
reads and backups. Measure bounded indexed queries rather than assuming a
task-list subscription is free. Dev and production usage must both fit the
team budget; local development avoids that particular cloud usage.

Backups are another cost. A hypothetical 10 MB full export every 15 minutes is
about 28.8 GB read per 30-day month, before file handling. A strict recovery
target can exceed Free allowances even when two users barely edit tasks.
These are sizing examples, not a quoted bill or measured Convex workload.

Cosmos also has an indefinite free tier: 1,000 RU/s and 25 GB on eligible
provisioned-throughput accounts, with one free-tier account per subscription.
It is not available for serverless accounts and must be chosen at creation.
The planned 30-day continuous backup has storage charges; restores are charged.
[Cosmos free tier](https://learn.microsoft.com/en-us/azure/cosmos-db/free-tier),
[continuous backup pricing](https://learn.microsoft.com/en-us/azure/cosmos-db/continuous-backup-restore-introduction).

Compare complete systems. Cosmos needs the Function host, host storage,
deployment, telemetry, authorization and sync implementation already planned.
Convex includes several of those backend responsibilities, but Foundry,
identity, possible Azure relay, model-file distribution and backup retention
remain. Azure subscription eligibility, sponsorship and actual regional
charges have not been inspected. There is no defensible exact monthly savings
claim yet.

## Backup and recovery is the decision to make

Convex reports encrypted storage, multi-availability-zone replication and
provider-operated backups. That describes platform durability, not a
self-service promise to undo an application bug at any chosen timestamp.
[Durability statement](https://docs.convex.dev/production/state).

The documented customer backup mechanism is a consistent data snapshot.
Free/Starter allows two stored backups per deployment. Manual backups last
seven days. Professional adds daily backups retained seven days or weekly
backups retained fourteen days. Backups exclude code, environment variables
and scheduled functions. Exported ZIPs contain JSONL table data and optional
files.
[Backup and restore](https://docs.convex.dev/database/backup-restore).

Those documented facilities do not establish the existing contract's
15-minute RPO and 30-day restore window. Buying Professional for daily backups
does not close that gap. The owner can choose one of these paths:

1. Retain those targets and prove a supported recovery design, including
   export cadence, external retention, monitoring, quota impact and restore
   timing. Do not promise it from documentation alone.
2. Adopt a smaller explicit household recovery policy, for example daily
   exports with 30 days of externally retained copies, accepting up to a day
   of acknowledged work lost after destructive corruption. Automatic
   execution still needs implementation and failure monitoring.
3. Keep Cosmos if point-in-time recovery at the current target matters more
   than the backend simplification.

All paths retain external restore epochs, membership review, invitation
revocation and quarantine of old pending commands. Recovering tables cannot
alone make restored credentials or stale device commands safe. Reconstruct
scheduled work from durable job records because exported data does not include
pending schedules.

Exports and a self-hostable backend reduce exit risk. They do not make an exit
free. Convex queries, mutations, scheduling, generated APIs, document IDs and
the reactive client protocol would need replacement. Use domain task IDs as
stable application keys rather than making Convex storage IDs the offline
identity scheme.
[Data export](https://docs.convex.dev/database/import-export/export),
[self-hosting](https://docs.convex.dev/self-hosting).

## Smallest proof before migration

Make one throwaway local profile and one durable typed-task path. Do not port
AI, recurrence and the entire graph before learning whether this works.

1. Pin the container image and CLI/SDK versions. Start backend and optional
   dashboard through Aspire on existing Podman. Verify ARM64 execution,
   loopback listeners, emulator connectivity and data surviving restart.
2. Port create/edit acceptance and receipts to one TypeScript mutation.
   Keep the existing .NET model as a behavioral reference, not a remote
   service called during Convex transactions.
3. Connect two emulator profiles through the Android data layer. Create and
   edit offline, kill and restart the process, reconnect, then retry after
   losing an acknowledgement. Verify one effect and preserved text.
4. Race two human edits, an AI-style stale patch and membership removal.
   Test changed-envelope retries and an expired state epoch.
5. Export, reset an isolated local deployment and restore it. Rebuild
   scheduled work from records and demonstrate stale clients entering
   recovery. This is functional evidence, not a hosted recovery-time result.
6. Reuse the owner's existing cloud project only after explicitly selecting
   its development deployment. Prove Entra token validation and subscription
   revocation there, inspect usage counters, and record the selected backup
   policy. Never infer the project or production target from a convenient
   CLI default.

After that passes, update the owning architecture decision, ready-slice
dependencies and mechanical boundaries together. Port the pure server rules to
TypeScript with the existing protocol fixtures. Retire the .NET implementation
only after parity, and do not keep two canonical rule engines indefinitely.
The Android work, deterministic identities, AI JSON schemas and adversarial
test cases remain useful regardless of backend choice.
