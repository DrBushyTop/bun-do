# Backend foundation review and live evidence

September 12, 2026. A fresh adversarial subagent reviewed the backend-hosting
module, root wiring, tests and actual Azure what-if. No material finding or
deferred bug remained. The reviewer confirmed the separate runtime account,
consumer-owned identity/grants, container scopes, .NET 10 isolated Flex settings
and identity-based package storage. Invariants and 49 Python tests passed.

## Deployment and preservation

The first plan contained 11 creates, five modifications and three unchanged
resources. Every resource belonged to `rg-bun-do-dev-swc`. Existing Cosmos and
snapshot deltas matched the provider defaults documented in their preceding
reviews. What-if masked app identity and site configuration, so the reviewer
checked the compiled template and required live reads.

The guarded deployment succeeded. Live reads verified Linux FC1, .NET isolated
10.0, 512 MiB, maximum two instances per scaling group, no always-ready instances,
HTTPS/TLS 1.2, disabled FTP/basic publishing and managed-identity host/package
storage. The identity had only the runtime-account Blob Data Owner grant and
snapshot-container Blob Data Contributor grant in the Azure RBAC listing,
including inherited roles. Cosmos Data Contributor was scoped only to
`/dbs/bun-do/colls/workspace-items`.

The health package uploaded through Azure CLI. OneDeploy reported partial
success because its reset-workers call returned 503. A subsequent independent
request returned HTTP 200 and `{"status":"ok"}` without another upload or
restart. This is evidence of eventual host readiness, not proof of the cause of
the earlier 503. Basic publishing remained disabled.

A second reviewed plan had 12 modifications and seven unchanged resources.
Besides the previous Blob/Cosmos defaults, it contained unresolved references
to the existing identity principal and package endpoint, omitted identity
`isolationScope: None`, and provider site defaults. No resource creation or
removal was proposed. The reviewer's follow-up happened after the guarded
script consumed the stamp, so that reviewer could not independently attest its
hash or age. The script enforced those checks before deployment.

The controlled redeployment succeeded. Follow-up checks preserved identity
principal/client IDs and isolation scope, runtime storage creation time, all
application settings and grants. Runtime Blob static website and retention
remained disabled; package-container encryption defaults matched expected
account encryption. The separate web config's legacy `netFrameworkVersion`
remained `v4.0`; what-if had supplied `v4.6`. The selected execution runtime
remained `functionAppConfig.runtime = dotnet-isolated/10.0`.
Health returned HTTP 200 again.

Both deployments preserved Cosmos database/container RIDs, checked policies,
manual 400 RU/s and absence of autoscale. Snapshot resource IDs, account creation
time, retention, cleanup and checked provider defaults remained unchanged.

## Initial evidence

Private records remain under ignored `.azure/foundation/`, including
`history/backend-first-deploy/`, `backend-readback.json`,
`health-redeploy-response.txt`, `snapshot-deployment-verification.json`, and the
reviewed what-if/deployment records. No credentials were committed.

Local .NET verification before this increment passed 44 domain tests and one
hosting test. Host execution does not prove application identity authorization,
Cosmos data operations, snapshot expiry, sync, backup restore or AI schemas.
Observability and Foundry are the next increments. The owner skipped all budget
alerts on the same date; they are no longer an acceptance requirement.

## Final development-stack gates

On September 12, 2026, the opt-in `tools/azure-gates.py` package proved data-plane
access under the deployed Function identity. Cosmos created one random synthetic
document in its own workspace partition, read and compared its contents, deleted
it and confirmed 404. Blob did the same in `sync-snapshots`. Account-level Blob
listing returned 403, outside the identity's container grant. The gate never
upserts a document or overwrites an existing blob.

Cosmos returned 201/200/204/404 for create/read/delete/final read. Blob returned
201/200/202/404. Cleanup uses the exact generated identifiers even after an
ambiguous create response. The saved report confirms both synthetic artifacts
were removed.

The same package completed the two negative AI checks described in the
[AI review](ai-inference.md). A request without a Function key returned 401.
The runner restored the normal package in its `finally` block. Health returned
200, the removed gate returned 404, and Azure listed only `Health`.

Full exported telemetry contains one completion record per gate, its result and
AI status/usage where applicable. It contains none of the tested synthetic
content, provider body canaries, bearer prefix or local source path. A later
Health completion confirms ingestion from the restored package.

Private evidence is in `.azure/foundation-gates/`: `artifacts.json` holds package
hashes and source digest, `dispatch.json` holds live results and restoration,
and `telemetry.json` holds full exported records. The gate files live outside
the normal Function project and are linked into hosting tests only.

These checks prove infrastructure identity access, not the future Cosmos SDK
adapter, concurrent sync transactions, snapshot expiry or backup restore.
Those remain downstream integration/release gates.

The fresh gate reviewer independently checked the saved live results, both
package hashes, source digest, exact ARM targets and all four telemetry records.
Final acceptance found no material issue. Local verification passed 59 Python,
44 domain and 28 hosting tests, invariants, locked restore and the Release build
with no warnings. No deferred bug remained in this slice.
