# Repository tools

Choose by the evidence needed, not by the number of checks available.

| Location or command | Purpose |
| --- | --- |
| `invariants/` | Application source boundary checks and tests of the checker and its hooks. Read the [test policy](../docs/agents/invariants.md#adding-or-changing-steering-checks) before adding rules. |
| `check_invariants.py` | Stable, offline entrypoint for `invariants/check.py`. Used by agents, Git and CI. |
| `run_invariant_hook.py`, `ci_schema_base.py` | Git/Codex hook integration and schema-history baseline selection. |
| `azure-foundation.py` | Guarded plan and deployment. Mutates Azure only with `deploy`. |
| `test_azure_foundation.py` | Offline tests of deployment-operation safeguards, not Azure resource settings. |
| `android-*` | Android setup and device smoke checks. |
| `speech-compare.py` | Prepare private audio corpora, run offline emulator comparisons and score matching reports. See [speech comparison](../docs/android-speech-comparison.md). |
| `speech-cloud-compare.py` | Explicit paid Azure audio experiments with private-audio consent, scoped account checks and no automatic retries. Never run from tests or hooks. |

Run the source checker and the complete offline tooling suite from the repo root:

```sh
python3 tools/check_invariants.py
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

Discovery includes the `invariants` package. Infrastructure CI installs Bicep,
compiles `infra/main.bicep` and tests deployment-operation safeguards. The
Repository invariants job runs the complete offline tooling suite and the source
checker against the selected schema-history baseline. Neither job logs into
Azure or invokes live verification. An offline pass never means Azure was verified.

Bicep is the source of truth for Azure configuration. Do not recreate compiled
template assertions, policy-readback checklists or temporary Function gates.
Real-environment integration/e2e tests should later exercise production
application paths when their slices need them, not duplicate resource settings.

The current folder split keeps steering checks separate from operational tools.
Do not add a folder per cloud service or wrap ordinary CLI commands without a
repeated safety or verification need.
