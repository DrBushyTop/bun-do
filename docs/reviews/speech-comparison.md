# Speech comparison evidence

Run date: September 12, 2026 UTC. These are commissioning experiments on the
owner's Mac and Azure development resources, not a release quality guarantee.

## Decision

The owner selected MAI-Transcribe-2 for the online path and retained Parakeet
for offline fallback. Authenticated application integration remains a
[separate v1 slice](https://github.com/DrBushyTop/bun-do/issues/38).
[Product usage limits](https://github.com/DrBushyTop/bun-do/issues/37) wait until
after v1. See the [decision](../adr/0002-cloud-first-speech-with-offline-fallback.md).

## Human corpus

The user supplied 36 WAV files with `manifest.jsonl`, added to five earlier
recordings. The 41 clips contain 190.8 seconds of audio and 213 reference words.
Every engine received the same normalized waveform; realtime input needed
24 kHz resampling. Reference answers were not sent to the engines.

| Engine | Results | Literal word error | Median latency | Observed latency range |
| --- | --- | --- | --- | --- |
| MAI-Transcribe-2 | 41/41 | 13/213, 6.1% | 601 ms request-to-final | 519 to 766 ms |
| GPT-Live-Transcribe | 41/41 | 18/213, 8.5% | 853 ms audio-commit-to-final | 772 to 1,757 ms |
| Parakeet, emulator A | 41/41 | 71/213, 33.3% | 1,707 ms including model load | 1,553 to 2,050 ms |
| Parakeet, emulator B | 41/41 | 71/213, 33.3% | 1,542 ms including model load | 1,455 to 1,836 ms |
| Android native | Finnish unsupported | Not scored | Not scored | Not scored |

Streaming time is not comparable to a complete file request without its
definition. The GPT-Live total session median was 5,571 ms, including paced
audio. Its first partial-event median was 1,828 ms after audio started.
The runner now ignores empty delta events before recording first partial text;
the original runs measured the first delta event.

Word error ignores case and punctuation, not number formatting or colloquial
variants. Some cloud differences are harmless; others change an action or a
household object. Parakeet has more omissions, altered verbs and broken words.
No LLM cleanup ran. Do not assume cleanup can reconstruct missing meaning.
No semantic-accuracy percentage is claimed without a reviewed annotation rubric.

The private, per-clip report is
`.cache/speech/comparison/human41/comparison.json`.
It includes reference text, each transcript, word errors, audio hashes, duration
and latency. It is deliberately excluded from Git. The source recordings remain
unchanged. This corpus is not evidence across many speakers, noisy rooms or
target phones.

## Synthetic and platform baseline

The earlier baseline contains 38 runs across 32 distinct clips: 24 eSpeak
samples, the five original human samples, two existing public/synthetic fixtures,
silence, and six repeats. Keep those results separate from the human corpus.

Both API 36 ARM64 emulators reported an on-device recognizer and installed
`en-US`. Neither listed Finnish among installed, pending or supported languages.
Actual Finnish recognition returned Android error 12,
`ERROR_LANGUAGE_NOT_SUPPORTED`. English prerecorded recognition succeeded with
networking disabled. The native comparison never switched to a network engine.

On emulator B, native English synthetic WER was 14.0%, with a 94.5 ms median
file-input-to-final time. Parakeet English synthetic WER was 8.8%; its Finnish
eSpeak result exceeded 100% WER because insertions count too. MAI's Finnish
synthetic WER was 20.8%, versus 6.1% on the later human corpus. Synthetic speech
therefore did not predict the real-recording result well enough to select a
model on its own. MAI, GPT-Live and Parakeet returned empty text for the silence
fixture. Native Finnish silence was unsupported, not a silence pass.

The host is an Apple M1 Max with 32 GiB RAM. Device reports record build
fingerprint, recognizer package versions, model manifest and waveform hashes.
Parakeet uses the app's actual CPU/two-thread JNI path, including recognizer
creation and model loading each call. Native service and cloud warm state are
not controlled. No phone latency or reliable p95 is claimed.

## Azure execution and failures

The experiment used a key-disabled North Europe `AIServices` S0 account for
MAI and two version `2026-07-28`, capacity-10 Global Standard GPT deployments in
the existing Sweden Central OpenAI account. It added only account-scoped
inference grants for the operator. Tokens stayed in memory. Audio was sent
inline, never exposed through public storage, logs or telemetry.

The first incremental Bicep deployment encountered an account write conflict
between the two GPT deployments. Readback showed which deployment succeeded.
The template now sequences them. A fresh inspected what-if and deployment
completed; account authentication, model versions and capacity were read back.
Existing storage policy verification still returned `PASS`.

GPT file transcription's v1 route returned `404 DeploymentNotFound` despite a
successful management-plane state. At the owner's request, bounded synthetic
readiness probes continued at one-minute intervals. No private recording was
sent through that failing route. One explicit deployment-style route probe
returned HTTP 200 in 1,558 ms on the synthetic clip. This shows a route-specific
difference, not a completed model comparison. The owner chose MAI before a full
GPT file run; retries were stopped. Preserve the failed evidence rather than
claiming GPT file quality failed.

Local operational records are in `.azure/speech-comparison/`. Private provider
results are under `.cache/speech/comparison/`. Exact Azure billing was not
established. The test deployments remain for reproducibility; no provider was
added to the Android application. Global Standard is not an EU-only processing
guarantee.

## Review and checks

Fresh adversarial review found and fixed:

- WebSocket redirects could resend bearer credentials to a different host.
  The client now rejects redirects; a local redirect-server test confirmed no
  target connection or credential forwarding.
- Scoring accepted stale audio with matching IDs. Reports now carry corpus and
  waveform hashes, and scoring rejects mismatches. Device experiments were
  rerun rather than adding provenance to old reports.
- Summaries mixed streaming finalization with full request time. They now retain
  latency definitions, separate streaming metrics, and reject mixed meanings.
- The manifest importer incorrectly rejected explicitly supplied originals in
  a sibling directory. It now validates each input against its authorized
  directory, with a regression test.

The comparison tests also cover missing/unsupported results, silence,
negation/quantity errors and explicit private-audio permission. No cloud calls
run in the test suite. See [repeatable commands](../android-speech-comparison.md).
