# Offline speech slice

Verification date: September 12, 2026 UTC. This completes
[local speech capture with recoverable Parakeet installation](https://github.com/DrBushyTop/bun-do/issues/26).
It supplies the offline fallback. The selected
[MAI online path](https://github.com/DrBushyTop/bun-do/issues/38) still needs
authenticated application integration.

## Implemented

- Pinned ARM64 sherpa-onnx 1.13.7 and int8 Parakeet v3, with archive/file digests,
  runtime and model notices, storage preflight, staged extraction and atomic
  activation. Rejected archive paths, links, duplicate entries, unexpected files
  and size/hash mismatches cannot replace a verified active model.
- Native microphone capture to bounded mono 16 kHz PCM16. Recording stops when
  leaving the sheet or application. Typing remains available without microphone
  permission or an installed model.
- Room v3 recording journal and explicit migration. Task, capture intent and
  COMMITTED marker persist before audio deletion. Deterministic capture identity
  prevents duplicate tasks after a cleanup failure.
- Retry, WAV export and deletion for interrupted, canceled or failed captures.
  Access enforces seven-day expiry and the store bounds recordings to 20 and
  256 MiB. A six-hour WorkManager sweep performs best-effort background cleanup;
  Android may defer it. This is not a guarantee of physical deletion at an exact
  wall-clock instant while the app is stopped.
- App-owned work survives rotation. Cancellation fences a late JNI transcript.
  Process death recovers from the persisted recording journal and partial PCM.

## Device evidence

Both dedicated API 36 ARM64 profiles ran offline with host microphone input
disabled. The actual installer previously completed through the app on both
profiles. Force-stop during a model download on B left a partial file, which
startup recovery removed before a successful retry. A later offline install on A
used the pinned local archive through the production installer. Gradle's
connected-test task had removed A's application data during an earlier run;
subsequent tests used direct APK installation and instrumentation instead.

The pinned runtime transcribed Finnish FLEURS and English synthetic fixtures on
both emulators, committed tasks and removed their working audio. The fixture
test checks full-reference WER of at most 20%; it is a functional language check,
not the speech-quality acceptance criterion. Silence created no task.

Manual native UI checks on B exercised:

- Record and stop with emulator silence, followed by the saved-audio recovery
  state.
- Force-stop during recording, reopen, and the interrupted recovery entry.
- Cancel and application-background interruption, retaining recorded audio.
- Actual Android microphone denial and the visible typing alternative.
- Export through the Android document picker. The synthetic recording produced
  a 266,284-byte RIFF/WAVE file, which was inspected and then deleted.
- Deleting saved recordings through the UI and reopening with no recovery rows.
- Replacing a newly created synthetic recovery fixture with the public English
  PCM, then pressing Retry. The real controller and Parakeet runtime created a
  task, opened its detail and removed working audio.

Screenshots and UI trees are local under `.cache/speech/evidence/screens/`.
They cover English light, Finnish dark at 2.0 font scale, landscape, recording,
silence, cancel, permission denial, recovery, export and saved task detail.
No private human transcript appears in this UI evidence.

## Reviews

Adversarial reviews found three data/recovery issues, all fixed:

1. A post-commit audio-deletion failure could overwrite COMMITTED with FAILED.
   Failure updates now apply only to uncommitted recordings, with a regression.
2. Death after renaming the first model generation but before publishing its
   pointer could leak the extracted model. Recovery removes unreferenced
   generations even when no active pointer exists, while preserving the
   referenced generation.
3. Background expiry used a second Room instance and failed to invalidate the
   foreground recovery list. Both instances now use multi-instance invalidation.
   The regression observes through one instance, prunes through another, closes
   the background connection immediately and waits for the removal notification.

The final code review reported no remaining P1/P2 findings. The independent
native finish review accepted the renders but required documentation to stop
claiming there was no voice control. A separate documenter updated PRODUCT.md,
DESIGN.md and the sidecar's filled recording-button preview without changing the
palette or type system. The reviewer scored that documentation fix resolved and
returned `ship`. That verdict covers the named fix; it is not an additional
whole-interface review.

## Final checks

- Invariants and whitespace checks passed.
- 86 offline Python tooling tests passed.
- Android debug and instrumentation APK builds, lint and 11 JVM tests passed.
- Direct full instrumentation on both emulators reported 23 tests: 21 passed,
  with the two explicit private/comparison experiments skipped. The real model
  fixture test was enabled. The private and comparison experiments also ran
  separately with their own reports.
- Locked .NET restore, Release build with no warnings, 44 domain tests and
  28 hosting tests passed for the accompanying workspace checkpoint.

The [41-clip comparison](speech-comparison.md) measured substantial Parakeet
errors and led to the owner's MAI choice. It does not establish semantic
correctness, phone performance, TalkBack behavior or tablet support. Review and
editing remain necessary. No target-phone benchmark was performed or made a
hidden release gate.
