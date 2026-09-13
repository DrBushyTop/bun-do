# Azure development foundation

The current infrastructure creates the dedicated development resource group,
Cosmos workspace store, private Blob storage for transient sync snapshots, and
a .NET 10 Flex health endpoint with managed identity, and a dedicated Luna model
deployment. Full completion tracing is described in the
[observability contract](../infra/observability.md). All budget alerts are skipped by the owner's
September 12, 2026 decision. The
[development-stack slice](https://github.com/DrBushyTop/bun-do/issues/17)
passed its deployment, identity, schema and restoration checks on that date.
Downstream sync, authentication and durable AI behavior are not part of this
foundation evidence.

## Source and constraints

`infra/main.bicep` creates `rg-bun-do-dev-swc` in Sweden Central. The account name
is deterministic for that group and the selected subscription.
`infra/modules/workspace-store.bicep` owns the shared workspace store:

- One NoSQL account, one region and Strong consistency.
- Database `bun-do`, with 400 RU/s of manual shared throughput.
- One `workspace-items` container partitioned by `/workspaceId`.
- Free tier requested at account creation, with total throughput capped at
  1,000 RU/s. No automatic paid-throughput fallback.
- Key authentication disabled, TLS 1.2 and no trusted-service network bypass.
- Continuous 30-day backups. TTL is absent because revisioned maintenance owns
  retention, acknowledgements and snapshot pins.

Free tier does not mean every resource is free. Azure bills 30-day continuous
backup separately, and storage above the allowance can incur charges.
The owner's sponsorship/free-billing expectation is not a service-side spending
cap. Do not shorten recovery retention to reduce cost without an explicit
decision.

The public Cosmos endpoint requires identity authentication. Private endpoints
are not part of this increment. The backend managed identity has container-scoped data access. The workspace-store module outputs the
container scope and data contributor role definition. Backend hosting uses
those to grant its identity access, including the metadata reads the SDK needs.
This keeps dependencies one-way instead of passing the backend identity back
into the datastore module that backend configuration depends on.

Modules represent application responsibilities. `workspace-store` groups the
account, database, container and retention policy. `snapshot-artifacts` owns the
separate transient Blob account, container and orphan cleanup policy.
`observability` groups
telemetry resources and policy; `backend-hosting` groups the Function host, its
runtime storage, identity and required grants. Do not add one-resource wrappers
such as `storageaccount.bicep`, or generic optional-feature modules.

## Snapshot artifacts

`infra/modules/snapshot-artifacts.bicep` uses Standard LRS, Hot tier, in Sweden
Central. It disables anonymous reads and Shared Key authorization, requires TLS
1.2 and exports the container scope for the backend identity grant.
Its network endpoint is public; its data is not. Blob capacity and operations
have costs separate from Cosmos free tier.

These are disposable recovery copies, not backups. Versioning, soft delete,
point-in-time restore and change feed are disabled. A lifecycle rule targets
only `sync-snapshots/` and makes orphaned copies eligible for deletion after one
day. That asynchronous fallback is not the protocol's 30-minute expiry. The API
must enforce expiry, membership and epoch on each read and explicitly delete
expired candidates. It must not issue SAS URLs. See the
[module's access and disposal contract](../infra/snapshot-artifacts.md).

## Plan and deploy

Prerequisites are authenticated Azure CLI access to the intended subscription,
Python 3 and Bicep 0.43.8. The scripts do not change the CLI's selected
subscription. Check it before planning.

```sh
az account show --query '{subscription:name,state:state}'
bicep build infra/main.bicep --outfile /tmp/bun-do-foundation.json
python3 -m unittest discover -s tools -p 'test_azure_foundation.py' -v
python3 tools/azure-foundation.py plan
```

Read `.azure/foundation/what-if.json`, including every resource's proposed
properties. These private local records are ignored by Git. A valid plan must
contain only resources in the dedicated group, with no deletion or uninspected
changes. The script rejects `Ignore` because ARM can use it when it could not
finish expanding a nested deployment.

After review:

```sh
python3 tools/azure-foundation.py deploy
```

Deploy requires the same subscription, compiled template, parameter bytes and
saved plan, less than an hour old. It consumes the plan stamp before deployment.
A failed deployment needs a new plan and review before retry. The script
refuses an existing group whose application/environment tags or region do not
match. Resource settings, including the free-tier request, belong to Bicep.

Repeat plan after a successful deployment and inspect drift before redeploying.
No script deletes the group or any data. Aspire startup still runs only the
local Functions/Azurite profile and does not call these scripts.

## Verification and remaining gates

Owner decision, September 13, 2026: Bicep is the Azure configuration source.
Compiled-template assertions, the storage-policy readback tool and temporary
Function gates have been removed. Do not recreate checks that repeat SKUs, RU/s,
retention, names, grants or other Bicep settings. Follow the
[invariant test policy](agents/invariants.md#adding-or-changing-steering-checks).

CI compiles Bicep and tests the deployment command's scope, deletion and plan
integrity safeguards. It does not verify the deployed environment. Use ordinary
Azure commands for targeted diagnosis when needed, not another policy checklist.
No live integration/e2e suite is being added at this stage.

### Historical foundation evidence

On September 12, 2026, the Cosmos foundation deployed successfully and passed
management-plane policy reads. A controlled redeployment after the application
module rename preserved both database and container RIDs, all checked policies,
and manual 400 RU/s throughput. The full what-if was not empty: provider defaults
produced modifications that needed review and follow-up reads.

The snapshot storage increment also deployed on September 12, 2026. Live reads
matched its declared SKU, region, transport/authentication controls, encryption,
empty CORS rules, disabled recovery retention and scoped lifecycle policy.
Cosmos identities, checked policies and 400 RU/s were unchanged. An anonymous
container-list request returned HTTP 401, `NoAuthenticationInformation`.
Its controlled redeployment also passed. The storage account creation time,
resource IDs and checked provider defaults were unchanged, including the
disabled static website and account encryption scope.
This negative probe does not prove backend managed-identity access, Shared Key
rejection with a valid key, runtime expiry or cleanup.

The Infrastructure workflow installs the pinned Bicep binary with its SHA-256,
compiles the subscription template and tests deployment-operation safeguards.
CI does not authenticate to Azure or provision resources.

Live provisioning results belong in the implementation ticket; compilation or
what-if is not deployment.
The later backend and AI increments below prove identity access and complete
schemas. Two-instance transaction tests, real replay and backup restore remain
separate downstream live gates.

The [storage decision](adr/0001-cosmos-with-replaceable-storage.md) keeps Cosmos
mechanics inside its adapter. A replacement database must prove the same
transaction and sync behavior; the interface is not a promise of automatic
data migration.

## Backend hosting evidence

The Function health endpoint deployed and passed a controlled redeployment on
September 12, 2026. Live configuration, identity/grant checks and storage
preservation comparisons passed. Both health probes returned HTTP 200. The
initial package upload had a reset-workers 503 before the successful probe.
The [backend module](../infra/backend-hosting.md) owns runtime storage and grants.

## Exception observability evidence

Exception-focused monitoring deployed and passed a controlled redeployment on
September 12, 2026. A synthetic result-execution failure proved managed-identity
ingestion and redaction through the real Function middleware. The temporary
function-key probe was removed afterward; health returned 200 and Azure listed
only `Health`. Both emitted tables have 30-day total retention, and successful
requests/health counts are not exported.

The exception-only design above is historical. The owner subsequently chose
full completion traces. The final data-plane and negative-AI checks also passed
on September 12, 2026.
Refusal/throttling handling was fixture-tested, not forced against the provider.
These gates do not prove the durable AI worker or downstream sync.

## Future integration and end-to-end coverage

Add real-environment tests when the relevant application slices need them.
Exercise production endpoints, adapters and worker paths under their deployed
identities. Useful assertions cover authorization failures, transaction/replay
behavior, expiry, cleanup and validated AI outcomes. Do not replace the running
application with a temporary test Function or maintain separate REST clients
just for environment checks.

Keep any writes synthetic, isolated and cleaned up. Paid calls need explicit
authorization and bounded usage; never retry an ambiguous paid call to get a
passing test. Record results and review conclusions in the owning GitHub issue.
The historical experiments above do not substitute for that future coverage.
