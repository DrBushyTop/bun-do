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
python3 -m unittest discover -s tools -p 'test_*template.py' -v
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
match, and refuses a free-tier allocation already used outside this group.

Repeat plan after a successful deployment and inspect drift before redeploying.
No script deletes the group or any data. Aspire startup still runs only the
local Functions/Azurite profile and does not call these scripts.

## Verification and remaining gates

New template assertions and verification tools must follow the
[invariant test policy](agents/invariants.md#adding-or-changing-steering-checks).
Test privacy, data preservation, scope and cost boundaries, not module naming
or an exact copy of the current template.

### Repeatable read-only storage verification

```sh
python3 tools/azure-verify-storage.py --subscription SUBSCRIPTION_UUID
```

Supply the intended subscription explicitly. The command does not change Azure
CLI defaults. It uses only management-plane reads, verifies the dedicated group's
tags and region, and discovers storage resources from deployment outputs.
It needs Azure CLI login and permission to read those resources, not their keys
or application data. Bicep and a prior local plan are not required.

The JSON report contains a timestamp and each checked resource/property with
expected value, observed value and repair guidance. Exit zero means all listed
checks passed. Drift returns `FAIL`; unavailable or malformed evidence returns
`INCOMPLETE`. Both exit nonzero. Redirect stdout to a new ignored evidence file
when retaining a run. Never replace an earlier report to imply it passed.

This first readback command covers Cosmos consistency, keyless access, backup,
database throughput, partition key and TTL, plus snapshot authentication,
native retention and scoped orphan cleanup. It does not verify every Azure
setting, resource identity preservation across deployments, backend grants,
running build identity, health, telemetry ingestion, actual data access or
30-minute snapshot expiry. Use it after relevant changes or to diagnose drift.
Extend it only for a concrete repeated check, with a failing and valid fixture.
Do not redeploy automatically when verification fails.

On September 12, 2026, the Cosmos foundation deployed successfully and passed
management-plane policy reads. A controlled redeployment after the application
module rename preserved both database and container RIDs, all checked policies,
and manual 400 RU/s throughput. The full what-if was not empty: provider defaults
produced modifications that needed review and follow-up reads. See the
[review and dated evidence](reviews/cosmos-foundation.md).

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
See the [snapshot review](reviews/snapshot-foundation.md).

The Infrastructure workflow installs the pinned Bicep binary with its SHA-256,
compiles the subscription template, and tests compiled storage settings and
deployment safeguards. CI does not authenticate to Azure or provision resources.

The [Cosmos foundation review](reviews/cosmos-foundation.md) records the fresh
adversarial review. Live provisioning results belong in the implementation
ticket and a dated evidence note; compilation or what-if is not deployment.
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
initial package upload had a reset-workers 503 before the successful probe; see
the [backend review](reviews/backend-foundation.md) for exact limits and evidence.
The [backend module](../infra/backend-hosting.md) owns runtime storage and grants.

## Exception observability evidence

Exception-focused monitoring deployed and passed a controlled redeployment on
September 12, 2026. A synthetic result-execution failure proved managed-identity
ingestion and redaction through the real Function middleware. The temporary
function-key probe was removed afterward; health returned 200 and Azure listed
only `Health`. Both emitted tables have 30-day total retention, and successful
requests/health counts are not exported. See the
[review and delivery limits](reviews/exception-observability.md).

The exception-only design above is historical. The owner subsequently chose
full completion traces. See [wide-trace evidence](reviews/wide-otel.md) and
[AI inference evidence](reviews/ai-inference.md) for the following increment.
The final data-plane and negative-AI checks also passed on September 12, 2026.
See the backend and AI reviews for exact status codes, cleanup and limits.
Refusal/throttling handling was fixture-tested, not forced against the provider.
These gates do not prove the durable AI worker or downstream sync.

## Opt-in data-plane and negative AI checks

`tools/cloud-gates/` contains the temporary Function and its tested checks.
It is not part of the normal application package. Read its README before use.
These are commissioning experiments, not the default verification command.
Future adapter tests should exercise production code rather than grow a second
storage or AI implementation inside temporary Functions.

```sh
python3 tools/azure-gates.py build
python3 tools/azure-gates.py run
```

The runner validates exact resource endpoints and identity against ARM in the
dedicated group, checks package hashes, creates a no-repeat dispatch marker,
then deploys the temporary package. It verifies synthetic Cosmos/Blob CRUD,
denied Blob account listing, a rejected AI schema and token-limited incomplete
output. The two AI calls incur model usage; neither has a retry loop.

The runner keeps Function keys in memory and attempts to restore the normal package
even when a gate fails. Success requires Health 200, gate 404 and the expected function list.
Here "restore" means publishing the normal artifact built from the checkout;
it is not rollback to the previously deployed build. Do not use this commissioning
workflow when replacing the current dev package is unacceptable.
If restoration fails, use only:

```sh
python3 tools/azure-gates.py restore
```

Do not delete the dispatch marker or rerun paid gates to recover deployment.
Records and ZIPs stay in ignored `.azure/foundation-gates/`. Inspect full
exported telemetry for private content, not just a projection of safe fields.
