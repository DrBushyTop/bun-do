# Repository hygiene audit

Audited September 12, 2026 after the local hosting slice, at `e503ecf`.

## Findings

The published `master` history contained no dependency folders or build output. Three local T3 checkpoint snapshots had captured the discarded process-only Azurite installation. Each snapshot contained the same 15,946-file `tools/azurite/node_modules` tree, about 101 MB uncompressed.

A fourth checkpoint contained the downloaded Impeccable executable under its ignored `scripts/bin` directory. The installed skill instructions and launcher are legitimate source files and remain.

## Changes

- Expanded `.gitignore` for nested Node dependencies, package caches, .NET and Android output, Python environments, test reports, logs, local settings and signing credentials.
- Kept package lockfiles, the future Gradle wrapper, Room schemas, example settings and logo assets eligible for source control.
- Renamed the emulator-only Functions settings file to `local.settings.example.json`. Personal `local.settings.json` files are ignored. Aspire still starts the Function through injected settings without that file.
- Removed only the dependency tree and downloaded executable from the four affected checkpoint snapshots. All other paths, file modes, contents and commit metadata were preserved.

The checkpoint ref update used an atomic compare-and-swap. A recovery bundle and old/new commit mapping are stored outside the repository under `~/.local/share/bun-do/git-backups/`. No published commit changed, and no force-push was needed. Unreachable objects were left for normal Git garbage collection rather than aggressively pruned while another agent was working.

The logo agent's `assets/`, design drafts and questionnaire files were not changed or staged by this work.

## Verification

A fresh Luna adversary at low reasoning independently checked the original findings and final cleanup. It confirmed that each rewritten checkpoint differs only by the intended removals.

- Every tree reachable through local refs and reflogs passed the generated-file scan.
- The remote advertised only the clean `master` branch.
- An ignore-rule matrix checked 18 generated/private paths and 14 legitimate source, lockfile and asset paths.
- Aspire readiness and loopback HTTP health passed without `local.settings.json`.
- The earlier settings file contained only emulator defaults, not cloud credentials. This audit does not claim an exhaustive historical secret-content scan.
