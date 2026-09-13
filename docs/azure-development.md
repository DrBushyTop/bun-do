# Azure development foundation

Bicep under `infra/` is the sole source of Azure configuration. It defines the
dedicated Bun Do development resource group and the resources, names, regions,
capacity, retention, authentication, and grants within it. Do not restate those
values in documentation, tests, or a second policy checker.

The modules are grouped by application responsibility. Keep that shape: a
workspace store owns its database policy, backend hosting owns its identity and
runtime storage, snapshot artifacts own transient recovery copies, and
observability owns telemetry policy.

## Plan and deploy

Prerequisites are authenticated Azure CLI access to the intended subscription,
Python 3, and the Bicep version required by the repository tooling. The scripts do not change the CLI's selected
subscription. Check it before planning.

```sh
az account show --query '{subscription:name,state:state}'
bicep build infra/main.bicep --outfile /tmp/bun-do-foundation.json
python3 -m unittest discover -s tools -p 'test_azure_foundation.py' -v
python3 tools/azure-foundation.py plan
```

Read `.azure/foundation/what-if.json`, including every proposed property. These
private local records are ignored by Git. A valid plan must contain resources
only in the dedicated group, with no deletion or unreviewed change. The script
rejects `Ignore` by default because ARM can use it when it cannot expand a nested
deployment. If an owner-reviewed resource is intentionally outside the template,
pass its exact resource ID with `--allow-ignored-resource` to both `plan` and
`deploy`. That exception never permits deletion, changes outside the group, or
evaluation failures.

After review:

```sh
python3 tools/azure-foundation.py deploy
```

Deployment requires the same subscription, compiled template, parameter bytes,
and reviewed plan less than an hour old. A failed deployment needs a new plan
and review before retry. No script deletes the group or data. Aspire startup is
local only and does not call these scripts.

## Evidence and future checks

CI compiles Bicep and tests the deployment command's scope, deletion, and plan
integrity safeguards. It does not verify a deployed environment. Use ordinary
Azure commands to diagnose a specific problem, not a second configuration
checklist.

Run real-environment tests only when their application slice needs them. Exercise
the deployed adapter or worker under its managed identity. Scope synthetic writes
and paid calls, clean them up, and never retry an ambiguous paid request merely
to obtain a passing result. Record live evidence and review conclusions in the
owning GitHub issue.

The [storage decision](adr/0001-cosmos-with-replaceable-storage.md) defines the
behavior a future database replacement must preserve. The interface does not
make data migration automatic.
