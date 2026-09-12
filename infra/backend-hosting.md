# Backend hosting

`modules/backend-hosting.bicep` owns the .NET 10 isolated Flex app, its
user-assigned identity, runtime storage and access grants. The identity exists
before the app and grants, so storage modules never depend on their consumer.
The root passes known database/container names and connection endpoints.

The Linux FC1 plan has no always-ready instances, 512 MiB per instance and a
maximum of two instances per function scaling group. Azure's Sweden Central runtime discovery advertised
this configuration on September 12, 2026. These are development limits, not a
measured production capacity target.

Runtime storage uses a separate Standard LRS account with Shared Key and public
Blob access disabled. The host identity has Storage Blob Data Owner on that
account to create host containers, manage locks and read deployment packages.
It has no account-management role. HTTP and timer hosting need no queue/table
role; future bindings must justify additional grants.

The same identity has Cosmos Data Contributor only at
`/dbs/bun-do/colls/workspace-items` and Blob Data Contributor only on the
`sync-snapshots` container. The snapshot grant does not permit obtaining an
account-level user delegation key. No operator role is granted. Live role reads
must also check inherited access before claiming isolation.

Backend hosting also grants Monitoring Metrics Publisher on the dedicated
Application Insights component and Cognitive Services OpenAI User on the
dedicated AI account. These are consumer-owned, resource-scoped grants, not
subscription roles. AI deployment and telemetry retention belong to their
application modules.

The app requires HTTPS and TLS 1.2. FTP and basic publishing authentication are
disabled. Deploy compiled packages using Azure CLI's Entra-authenticated Flex
OneDeploy path. Infrastructure deployment alone does not install executable
code. `AI__Enabled=false` records the intended initial setting; the AI worker
and enforcement of its kill switch remain unimplemented.

The existing anonymous `/api/health` endpoint returns only `{"status":"ok"}`.
It proves host execution, not Entra API authentication, Cosmos access, snapshot
expiry or synchronization. Do not add application data routes without the
identity contract. The WorkspaceStore and Snapshots settings are reserved
connection configuration for the future adapters.

## Verification

Compile `infra/main.bicep` and run
`python3 -m unittest discover -s tools -p 'test_backend_template.py'` with the
other infrastructure tests. Use the guarded plan/review/deploy process in
[Azure development](../docs/azure-development.md). Read back runtime, scale,
identity, app settings, publishing restrictions, storage controls and role
scopes. Repeat the Cosmos RID/policy/400 RU/s and snapshot identity/policy
comparisons. Publish the package, check health, then review another what-if and
verify a controlled redeployment.

## Microsoft references

- [Functions infrastructure configuration](https://learn.microsoft.com/en-us/azure/azure-functions/functions-infrastructure-as-code)
- [Identity-based host storage](https://learn.microsoft.com/en-us/azure/azure-functions/functions-identity-based-connections-tutorial)
- [Flex deployment and runtime configuration](https://learn.microsoft.com/en-us/azure/azure-functions/flex-consumption-how-to)
