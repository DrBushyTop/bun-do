# Android offline shell adversarial review

Review date: 2026-09-12.

## Scope and evidence

Fresh review of the anonymous typed-capture slice and repository invariant checker. Read the task-domain, identity, screen and implementation contracts, plus the Grug development and agent-harness guidance.

Reviewed Room transactions and migration fixtures, original-text preservation, drafts, view-model scheduling, navigation state, backup exclusions, localization checks and schema checks. Authentication, shared sync, voice and task lifecycle actions belong to later slices and were not assessed as missing shell features.

The reviewer ran all 15 initial Python invariant tests successfully and the repository checker passed. A temporary Git repository reproduced the committed-schema gap below. Android findings came from source and control-flow inspection. The reviewer did not run instrumentation, capture screenshots or perform a rendered UI review. The already-known Room migration serializer failure was excluded because the implementation agent was fixing it.

Findings below describe the initial code. All five findings are fixed. The final recheck and evidence are recorded after the findings.

## Findings

### P2: A late retry can recreate an already-committed draft

Files: `src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxViewModel.kt`, `src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxApp.kt`.

`closeEditor` sets `working` without clearing an earlier `writeFailed`. The Retry action stays visible and `retryDraft` does not check `working`.

Reproduction:

1. Cause Save to fail, leaving the editor and its error visible.
2. Restore writes and press Save again, with that commit delayed.
3. Press Retry before the commit finishes.
4. The serialized worker commits the task and deletes its draft, then executes the queued retry and saves the same draft again.

For a new task, the queue now offers Resume draft for content that was already committed. Saving it creates a second task. An edit can similarly leave an obsolete draft that later overwrites newer task content.

There is a second route into the same race. The worker's generic catch resets `working` after any autosave failure, even when a queued Save or Back operation owns that lock. That permits more edits or another Save before the pending operation finishes.

Reject draft retries while a close operation is in progress, clear old error state when starting that operation, and release `working` only from the operation that acquired it. Add delayed-write tests for both sequences. The repository transaction itself is atomic; this bug is in the view-model queue around it.

### P2: Read retries leave duplicate collectors and can hide a failed draft stream

File: `src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxViewModel.kt`, `load` and `retryRead`.

Each retry starts two collectors without cancelling the previous ones. Both report errors through one `readFailed` flag, but every successful task emission clears that flag whether or not the draft collector is healthy.

A failing draft query followed by a successful task emission removes the error while draft markers stop updating. Pressing Retry repeatedly also accumulates healthy task collectors.

Use one restartable job that combines both streams, cancels the previous job on retry, and clears the error only after both have supplied a valid state. Exercise a failed draft stream while tasks continue emitting, then retry and verify one active subscription to each source.

### P2: Editor-open failures have a Retry button that retries the wrong operation

Files: `src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxViewModel.kt`, `src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxApp.kt`.

`openEditor` runs a read inside the write worker. If loading a draft fails, the generic catch sets `writeFailed` and leaves the editor closed. The queue displays Try again, but that action calls `retryRead`, which neither retries `openEditor` nor clears `writeFailed`.

Reproduce with a transient failure loading the new-task draft after the task list has loaded. Restore the database and press the advertised Try again button. The editor still does not open and the write error remains.

Keep the failed editor key and retry that action, or report an editor-load error with its own retry operation. Do not label a failed read as a failed save. Also make an editor Save error's retry semantics explicit; saving the draft is not the same as retrying the failed task commit.

### P2: Ordinary navigation discards queue scroll position

File: `src/BunDo.Android/app/src/main/java/fi/bundo/ui/InboxApp.kt`, conditional screen selection and `Queue`.

The queue's `LazyColumn` owns its default remembered list state. Opening Settings, the editor or compact-width task detail removes the queue from composition. Back creates a new list state at the top.

Seed enough tasks to scroll, open a task well below the first viewport, then return. The queue does not retain the selected row's position. This contradicts the screen contract and makes repeated task editing frustrating.

Hoist `rememberLazyListState` into `InboxApp` outside the conditional screen branch and pass it to the list. Test Settings, detail and editor round trips.

### P2: Committed Room schema changes pass the CI check

File: `tools/check_invariants.py`, `check_schemas`.

The initial checker compared files only against current `HEAD`. That detects uncommitted mutations but cannot detect a rewritten or deleted historical schema once the bad change has been committed.

The reviewer created a temporary repository, committed `1.json`, committed a changed identity hash without changing version 1, and ran `check_schemas`. It returned no findings. Committing a deletion of that file also returned no findings.

Accept an explicit baseline revision. CI should compare historical snapshots against the target branch's merge base or the preceding push revision, while local checks can retain `HEAD` as their default. Add fixtures that commit the violating changes before checking them.

## Final fix disposition

Rechecked on 2026-09-12.

| Finding | Disposition and evidence |
| --- | --- |
| Late retry recreates a committed draft | Fixed. Autosave errors no longer release another operation's busy state, closing clears stale errors, and retries reject while busy. Real-Room tests cover Retry during commit and failed autosave before commit. |
| Duplicate read collectors and masked failures | Fixed in source. One combined read job cancels its predecessor and only publishes successful state after both streams emit. There is no fault-injected read-stream regression in the current suite. |
| Editor-open Retry uses the wrong operation | Fixed. The failed action retains its own retry callback. A real-Room test checks reopening after a failed lookup. Queue and task detail display the localized `open_failed` error and Retry action. |
| Navigation discards queue scroll | Fixed. `InboxApp` owns list state outside the conditional branches. The UI regression checks the Settings round trip. Detail and editor paths share that same retained state; separate runtime tests for those paths were not added. |
| Committed schema changes pass CI | Fixed. The checker accepts separate source/history roots and an explicit baseline. CI chooses the previous push or PR target revision, or the default-branch merge base for a new feature branch. Tests cover committed mutation, deletion and a mutation followed by an unrelated commit. |

The reviewer reran all 25 Python tests and the repository checker successfully. The reviewer also inspected the generated instrumentation XML. Both `bun-do-a` and `bun-do-b` report 10 tests, zero failures, zero errors and zero skips, with timestamp `2026-09-12T15:01:47`. Those device runs were performed by the implementation agent, not the reviewer. They include the Room migration, storage, view-model and UI regressions.

The implementation agent reports that Android build, lint and unit checks passed after aligning the serialization BOM. The reviewer did not rerun Gradle or perform a rendered UI review. No finding from this review remains deferred.

## Checks that held during inspection

- Capture and edit place the task change, local intent and draft deletion in one Room transaction.
- Edit does not update `originalTitle` or `originalDescription`.
- Save validates text before entering the transaction, while invalid input remains recoverable as a draft.
- The anonymous database name is fixed independently of any future account selection.
- The app has no network or microphone permission in this slice.
- Backup exclusions cover cloud backup and device transfer, including device-protected storage in the extraction policy.
- The local code does not log task text or SQL exceptions.

These checks do not establish account isolation after authentication is added, actual backup behavior on all devices, successful process-death recovery or finished accessibility. Those require their own runtime evidence.
