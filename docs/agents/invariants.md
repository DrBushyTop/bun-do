# Enforced invariants

Run from any directory with `python3 /path/to/bun-do/tools/check_invariants.py`.
The checker uses Python's standard library. It installs nothing, makes no network
requests, and does not format or edit source files.

| Rule | Boundary | Repair |
| --- | --- | --- |
| `ARCH001` | The .NET domain has no external dependencies. | Keep package and adapter references outside `BunDo.Domain`. |
| `ARCH002` | Android screens do not access Room or its DAO directly. | Use `InboxRepository`. |
| `ARCH003` | Android persistence does not depend on UI frameworks or screens. | Return values and flows from `data/`. |
| `ARCH004` | Cosmos SDK references stay in the backend storage adapter. | Keep provider code in `BunDo.Functions/Storage/Cosmos`; call the workspace interface elsewhere. |
| `DATA001` | Room must not discard local work or run queries on the main thread. | Add explicit migrations and use asynchronous database access. |
| `DATA002` | Committed Room schemas are immutable and versions match filenames. | Restore old snapshots, bump the version, generate a new schema, and test migration. |
| `PRIV001` | Android backup and device transfer exclude local data. | Preserve the manifest's backup policies and full data exclusions. |
| `I18N001` | Finnish and English resources have matching names and format arguments. | Add the missing translation or fix argument positions and types. |

These are narrow structural checks, not a compiler, security scanner, or complete
architecture proof. Keep regression tests for real behavior. Do not grow the checker
into a second Kotlin parser or add style rules that need human judgment.

## Verification and Git hook

```sh
python3 tools/check_invariants.py
python3 -m unittest discover -s tools -p 'test_*.py' -v
git config --local core.hooksPath .githooks
```

Inspect `git config --get core.hooksPath` before setup. Do not replace an existing
custom hooks directory without deciding how to preserve its hooks.

The pre-commit hook checks a temporary copy of the active Git index. It executes
the staged checker, so an unstaged fix cannot hide a bad commit. It compares staged
Room schemas with `HEAD`. An initial commit has no older schema to compare.
It never stashes files, rewrites the index, or changes the working tree. The hook
does not build Android or .NET and does not replace their tests.

Git hooks are local and can be bypassed. CI repeats the checks and runs their
tests. For pull requests, schema comparison uses the target branch commit.
For pushes, it uses the pre-push commit. A new feature branch has no pre-push
commit, so CI compares with its merge-base against the fetched default branch.
This catches schema changes hidden behind later unrelated commits. Missing
default-branch history or disconnected histories fail the check.

The first push of the default branch has no earlier remote history. CI uses its
root commit, which is also `HEAD` for a one-commit repository. Multiple root
commits require a deliberate baseline instead of silently choosing one.

## Codex hook

`.codex/hooks.json` preserves the Impeccable checks and adds a `Stop` check of the
working tree. Paths resolve from the Git root, including sessions started in a
subdirectory. Failures request one automatic continuation. If the next stop still
fails, the hook reports the failure without requesting another continuation.
Report that blocker to the user; the Git and CI gates still reject violations.

Codex requires trust review for new or changed project hooks. Use `/hooks` in a
Codex CLI session to review this hook. This repository does not change trust
settings or bypass them.

The configuration follows the official Codex hooks documentation, checked on
September 12, 2026: `https://developers.openai.com/codex/hooks`.

## Changing the rules

Add a small regression fixture for both the rejected case and a valid neighboring
case. Keep diagnostics tied to a rule, path, and concrete repair. For one-off
comparison against an earlier commit, run:

```sh
python3 tools/check_invariants.py --git-base BASE_COMMIT
```

`--git-root` supplies the history repository when `--root` is a staged snapshot.
An explicit empty `--git-base ''` skips only the historical comparison. Ordinary
local runs default to `HEAD`.
