# Repository tools

Choose by the evidence needed, not by the number of checks available.

| Location or command | Purpose |
| --- | --- |
| `invariants/` | Source boundary checks, compiled Bicep policy tests, and tests of the checker and its hooks. Read the [test policy](../docs/agents/invariants.md#adding-or-changing-steering-checks) before adding rules. |
| `check_invariants.py` | Stable, offline entrypoint for `invariants/check.py`. Used by agents, Git and CI. |
| `run_invariant_hook.py`, `ci_schema_base.py` | Git/Codex hook integration and schema-history baseline selection. |
| `azure-foundation.py` | Guarded plan and deployment. Mutates Azure only with `deploy`. |
| `azure-verify-storage.py` | Read-only deployed storage policy checks. No keys, data-plane requests, writes or paid model calls. |
| `test_azure_*.py` | Offline tests of cloud tooling's safety and recovery behavior. |
| `android-*` | Android setup and device smoke checks. |

Run the source checker and the complete offline tooling suite from the repo root:

```sh
python3 tools/check_invariants.py
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

Discovery includes the `invariants` package. Bicep tests visibly skip when Bicep
is unavailable; the Infrastructure CI job installs it and compiles first.
That job explicitly discovers template tests under `tools/invariants/` and runs
`test_azure_*.py` for offline deployment/readback safety tests. The Repository
invariants job runs the complete offline tooling suite and the source checker
against the selected schema-history baseline. Neither job logs into Azure or
invokes live verification. An offline pass never means Azure was verified.

The current folder split keeps steering checks separate from operational tools.
Do not add a folder per cloud service or wrap ordinary CLI commands without a
repeated safety or verification need.
