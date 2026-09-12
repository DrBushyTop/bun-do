# Repository invariant hooks adversarial review

Review date: 2026-09-12.

## Scope and evidence

Reviewed the staged-index hook, Codex Stop hook, CI workflow, explicit Git baseline support, checker fixtures and hook fixtures. Also source-checked the fixes from the Android shell review. No production or parent-workspace index changes were made.

Executed:

- `python3 -m unittest discover -s tools -p 'test_*.py' -v`, all 21 tests passed.
- `python3 tools/check_invariants.py`, passed.
- A temporary-repository `git commit --only` fixture. Committing a README change succeeded while an unrelated invalid domain-project change remained staged. The hook preserved that staged change.
- A temporary-repository new-branch baseline fixture, described below.

Fetched the official Codex hooks documentation directly and checked the Stop event's JSON output and continuation behavior against the implementation. This was documentation validation, not a live trusted-hook invocation inside Codex.

## P2: New-branch pushes can miss an earlier schema rewrite

File: `.github/workflows/invariants.yml`, zero or missing `BASE_REV` fallback.

A new branch push has no previous branch revision. The workflow falls back to `HEAD^`, which only checks the final commit.

Reproduction:

1. Start from a commit containing schema `1.json`.
2. Create a branch and rewrite that existing schema without changing its version.
3. Make an ordinary README commit after the schema rewrite.
4. Evaluate the new-branch fallback against `HEAD^`.

The checker returns no findings because the previous commit already contains the rewritten schema. Comparing with the original branch point returns `DATA002`.

Pull-request CI will catch the rewrite when compared with an unchanged target branch, but the push run incorrectly reports success. For a new branch, compare with the merge base of the fetched default branch. Treat a genuinely first repository history separately. Add a fixture with a violating commit followed by a harmless commit.

Fixed on final recheck. `tools/ci_schema_base.py` now preserves explicit push and PR baselines, selects the default-branch merge base for a new feature branch, and uses the root commit for a first default-branch push. Missing required history fails rather than falling back to the latest parent. The regression commits a schema mutation followed by an unrelated change and verifies `DATA002`.

## Checks that held

- The pre-commit hook checks the active index through a temporary `checkout-index` snapshot, including Git's temporary index for a partial commit.
- It executes the staged checker. An unstaged checker fix cannot mask a staged violation.
- Schema comparisons read baseline history from the original repository rather than the temporary snapshot.
- A nonempty invalid baseline fails rather than disabling historical checks.
- An initial local commit can run without a nonexistent baseline.
- The hook does not stash, rewrite the index or checkout over working files.
- The Stop hook returns JSON, asks for one continuation on failure, and reports a persistent failure without requesting another continuation.
- The CI job has read-only repository permissions, pins checkout to a commit and does not interpolate event data into shell source.

These hooks are editable repository code, not a security boundary against a contributor deliberately disabling checks. The documented local bypass and separate CI gate are appropriate. The checker also remains a narrow structural check, not a compiler or full dependency analysis.

## Android fix recheck

Source inspection found the intended changes:

- Autosave failure no longer releases a pending Save or Back operation's busy state.
- Starting a close operation clears stale write errors, and draft retries reject while busy.
- A single combined read job replaces previous collectors.
- Failed actions retain their retry operation instead of always restarting queue reads.
- Queue scroll state now lives outside the conditional screen branches.
- The build declares a serialization BOM aligned to the Room migration reader.

The reviewer did not rerun Android instrumentation or verify the BOM's resolved runtime dependency graph. Runtime regression results belong to the implementation agent.

The remaining editor-load error-display issue is fixed. `TaskDetail` now renders `ErrorNotice` with localized `open_failed` text and the failed-action retry callback. The queue uses the same accurate error message.

## Final bounded verification

On 2026-09-12 the reviewer ran all 25 Python tests and the repository checker successfully. This includes four CI-baseline tests. Shell syntax checks passed for `tools/android-setup.sh` and `tools/android-check-env.sh`.

The reviewer inspected both generated Android instrumentation XML files. Each reports 10 tests, zero failures, zero errors and zero skips at `2026-09-12T15:01:47`. The implementation agent ran those device tests and reports that build, unit tests and lint also passed. The reviewer did not rerun Gradle, boot devices or perform a rendered review. Exact Android finding dispositions are in [the shell review](android-shell-adversarial.md).

The final review also covered the Android setup scripts and `.github/workflows/android.yml`. No material setup issue was found. The scripts reject conflicting SDK paths and invalid JDKs, verify the downloaded command-line-tools archive, do not auto-accept licenses, and do not replace existing AVDs. The environment check reports tool presence and revision differences without claiming a successful build. Android CI builds, lints, runs unit tests and compiles instrumentation tests; it does not execute emulator tests.

The reviewer confirmed the workflow's exact Temurin `17.0.20.1+1` Linux x64 JDK through the Adoptium API. Installation and the hosted GitHub workflow were not executed during this review. Local setup evidence is not evidence that hosted CI has run.

No finding from this review remains deferred.
