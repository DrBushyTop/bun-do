# Tooling steering review

September 12, 2026. The owner asked for checks that prevent costly mistakes,
rather than tests of naming conventions, and a separate home for steering checks.

## Changes and limits

`tools/invariants/` now contains the source checker and structural tests.
The existing `tools/check_invariants.py` command, Git hook and CI entrypoints
still work. Operational Azure commands remain outside that package.
The [invariant test policy](../agents/invariants.md#adding-or-changing-steering-checks)
now requires a concrete failure, an owning contract or incident, a rejected
fixture, a valid neighboring case and an explicit limit on what the check proves.

Template tests no longer freeze the root module set, deployment labels, resource
totals, snapshot output-key set or every resource's API version. Persisted storage
account identities remain checked because changing them can strand existing data.
Scope, destructive deployment mode, auth, retention and cost checks remain.

`tools/azure-verify-storage.py` reads deployed storage policy without deploying,
fetching credentials, accessing data or calling a model. It reports expected and
observed properties, repair instructions and PASS, FAIL or INCOMPLETE.
This first command covers 26 storage policy checks. It does not prove backend
access, resource preservation across deployments, health, telemetry delivery,
actual deletion or application behavior.

The commissioning runner's restore path no longer requires the disposable gate
ZIP. The docs now distinguish publishing its normal checkout artifact from
rolling back to a previously deployed build. It remains an opt-in commissioning
tool, not the default verification workflow.

## Review and verification

A fresh adversarial reviewer found two material issues. Removing the storage
account identity assertions weakened data-preservation protection. Malformed
ARM group metadata could escape the JSON failure report. Both were fixed with
regression tests. The reviewer rechecked the fixes and reported no remaining
material findings in this scope.

- Repository invariants passed.
- All 71 offline tooling tests passed, including staged-index and schema-history
  checks after the folder move.
- The final read-only Azure run at 19:21:48 UTC passed all 26 listed policies.
  Its full report is in ignored
  `.azure/foundation/storage-policy-2026-09-12T192148.321566_0000.json`.
- No Azure mutations or paid provider requests were made by this change.

Existing application and infrastructure work in the checkout was outside this
review. An offline tooling pass does not validate those unrelated changes.

## Commit and CI verification

The standalone tooling commit excludes the uncommitted backend modules and
their AI/observability tests, plus the commissioning runner and its recovery fix.
Those changes remain in the working tree with their prerequisites.

The exact staged snapshot passed 58 offline tooling tests and the history-aware
invariant check. The Infrastructure job's commands compiled the committed Bicep,
passed 17 template tests under `tools/invariants/`, and passed 14 offline Azure
tool tests. Workflow YAML parsed successfully. A reviewer checked the staged
snapshot's CI discovery, dependencies and documentation links with no material
findings.

Infrastructure CI now explicitly selects `tools/invariants/` for template
checks and `test_azure_*.py` for tooling safety tests. The Repository invariants
job retains full tooling discovery and historical schema comparison. Neither
workflow runs live Azure checks. These are local checks of CI commands, not
evidence of a GitHub Actions run.
