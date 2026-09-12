# Cosmos foundation adversarial review

Reviewed on September 12, 2026. This is a review of the Cosmos-only foundation,
not completion of the full Azure deployment slice.

## Material finding, fixed

P2: `tools/azure-foundation.py`, `validate_changes`, accepts `Ignore` changes.
Azure what-if uses this value both for untouched resources and for resources it
could not expand before reaching a limit. The documented expansion limits
include five minutes. A skipped nested Cosmos deployment can therefore pass the
guard and receive a deployment stamp without inspected child changes.

The same validator accepts `{"status": "Succeeded"}` with no `changes` member.
An absent change set should not count as a reviewed plan.

Both cases reproduced by calling the validator directly. The fix rejects
`Ignore` and requires a nonempty, list-valued change set. All five deployment
guard tests pass, including regression cases for absent, null, empty and
wrong-type change sets. The existing-resource-group check also now requires
both Bun Do application and development environment tags.

If future templates need ignored resources, distinguish known untouched
resources from skipped inspection explicitly.

## Provider what-if follow-up

The initial provider what-if reported `Succeeded` and exactly four `Create`
changes, all inside `rg-bun-do-dev-swc`. They create the resource group, Cosmos
account, database and workspace container. There are no modifications,
deletions, diagnostics, errors or potential changes.

The inspected account properties match the intended Strong consistency, single
Sweden Central region, required free tier, 1,000 RU/s account limit, disabled
local authentication and continuous 30-day backup. Database shared throughput
is 400 RU/s. The container uses `/workspaceId`, partition-key version 2 and no
TTL. The plan has no data-role assignment, consistent with the omitted backend
identity in this partial foundation.

The saved plan digest and compiled template/parameter digest match the plan
stamp. The corrected validator accepts this actual provider response, and
repository invariants pass. No material review finding remains before this
foundation deployment.

## Application module and redeployment review

After the first successful deployment, the module was renamed
`workspace-store.bicep` to describe its application responsibility. The account,
database and container resource names did not change. Removing the unused
optional identity parameter does not revoke a live role assignment because no
assignment was created. A future backend-hosting module should consume the
container scope and contributor-role outputs and grant its own identity access.
That avoids feeding the backend identity back into the store module while the
backend also depends on store outputs.

The next what-if reports one `NoChange` and three `Modify` resources, all in
the same resource group. Each delta was inspected:

- `sqlEndpoint` and the container's returned `backupPolicy` are absent from
  the API version's writable template schema. The account backup remains
  explicitly `Continuous30Days`; the returned container field is not a request
  to change that retention.
- `defaultIdentity` controls Cosmos access to Key Vault for customer-managed
  keys. It is not the application's authentication mode. No customer-managed
  key is configured, and both authentication-disable settings remain true.
- Analytical schema configuration has no effect while analytical storage is
  disabled. The per-region/per-partition autoscale flag does not select
  autoscale throughput for this manually provisioned database. These are
  writable settings, not universally harmless read-only fields.
- The database's requested throughput options still specify the same 400 RU/s
  previously read back from its throughput resource. The database GET payload
  used by what-if omits those creation/update options.
- The omitted indexing policy defaults to automatic indexing of document paths.
  The returned policy contains the default all-path include and `_etag`
  exclusion. The returned `/_ts` conflict-resolution path is the default for
  last-writer-wins; this remains a single-write-region account.

No material configuration change is intended or established by these deltas.
This is enough to proceed with a controlled redeployment, not proof that every
provider-returned default survives it. Compare database and container `_rid`
values afterward. Also read back indexing and conflict-resolution policies,
account backup and authentication settings, partition key, absent TTL and
manual throughput. A change in those checks needs investigation before calling
the module refactor verified.

The reviewer performed no cloud mutation. Repository invariants and all 38
Python tests passed during this follow-up.

## Implementer redeployment evidence

The controlled module-rename redeployment succeeded on September 12, 2026.
Management-plane reads afterward found unchanged database and container RIDs.
The original container's `_self` path provided the pre-deployment database RID;
the original container's `rid` provided its own identity.

The complete selected account and container policy objects matched the saved
pre-deployment values. This includes indexing, conflict resolution, partition
key, absent TTL, backup, authentication, free tier, throughput cap, region and
the provider defaults discussed above. A separate throughput read still
returned manual 400 RU/s with no autoscale settings.

Private evidence is in `.azure/foundation/redeployment-verification.json`,
`before-redeploy-policy.json`, `after-redeploy-policy.json`,
`after-redeploy-resources.json` and the original `readback.json`. These are
control-plane observations, not data-plane transaction or restore tests.

## Checks that passed

- `python3 tools/check_invariants.py` passed.
- All 36 Python tests passed, including compiled Cosmos policy tests.
- The template uses one Strong-consistency region, the `/workspaceId` partition,
  no TTL and continuous 30-day backup. Manual shared throughput is 400 RU/s,
  free tier is required, and the account throughput limit is 1,000 RU/s.
- Local/key authentication is disabled. The future backend data role should be
  container-scoped. Microsoft's metadata permission documentation explicitly
  permits `readMetadata` at container scope, so SDK initialization does not
  justify granting account-wide data access. Runtime code must use the known
  database/container, not enumerate or provision databases.
- The storage ADR keeps SDK types and database mechanics inside one adapter
  without imposing a generic repository or second production database.
  ARCH004 checks ordinary SDK namespace references outside that directory.
- The infrastructure workflow compiles and tests without an Azure login or
  deployment action.

## Scope and remaining evidence

No Azure mutation ran during this review. The implementing agent completed the
initial deployment and policy read-back. The module-rename redeployment passed
the follow-up checks recorded above. Neither deployment proves runtime
data-plane access or backup restoration.

This foundation does not provision Functions, a backend identity, private Blob
storage, monitoring budgets or Foundry. It does not prove Cosmos concurrency,
unknown-outcome retries or backup restoration. Keep the full deployment slice
open. The owner-requested provider seam is documented, but the production
storage adapter and its shared contract tests remain future work.

Continuous 30-day backup has separate storage charges. A free-tier account and a
throughput cap do not make backup, restoration or excess data storage free.

## Source checks

Microsoft documentation was read directly during the review:

- Azure Resource Manager, *What-if deployment operation*, sections on change
  types and nested-template expansion limits.
- Azure Cosmos DB, *Data plane security reference*, sections on the built-in
  contributor role and required metadata.
- Azure Cosmos DB, *Lifetime free tier*.
- Azure Cosmos DB, *Continuous backup and point-in-time restore*, pricing and
  retention sections.
- The `2025-04-15` template schemas for database accounts and SQL containers,
  the container indexing-policy documentation and conflict-resolution policies.

The sources support the permission and billing distinctions above. They do not
replace live validation of this subscription.
