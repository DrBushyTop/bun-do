# Speech comparison

This is an opt-in experiment, not a release quality gate. It compares the real
Android native on-device recognizer, the app's pinned Parakeet runtime, and
Azure transcription. No recognizer receives the reference transcript.

The owner approved uploading the supplied recordings to Azure on September 12,
2026, and approved European resource regions when Sweden Central is unavailable.
That permits this comparison. It does not mean the Android app now uploads audio.
The app still uses local Parakeet; cloud-first capture needs a separate product
implementation.

## Corpus and private files

All audio, references, individual transcripts and raw reports stay under ignored
`.cache/speech/`. Never add personal audio to APK assets, Git or issue comments.
Azure calls upload files inline, not through public Blob URLs.

The initial corpus has 32 distinct clips and six repeated runs:

- 24 eSpeak NG clips, six task scripts in Finnish and English at two speaking rates.
- Five supplied Finnish human recordings.
- One public Finnish FLEURS clip and one English synthetic fixture already used
  by the instrumentation tests.
- Three seconds of silence.
- Two further runs each of one Finnish synthetic, one English synthetic and one
  human clip.

The corpus covers quantities, negation, corrections, dates, actions and pauses.
It does not cover a representative set of speakers, microphones, room noise or
accents. eSpeak performance must not substitute for human speech performance.
Human references initially use the requested reading scripts, not independent
word-by-word annotation of the recordings.

The owner then supplied 36 new WAV clips with `manifest.jsonl`. Together with
the five originals, the separate human corpus contains 41 clips, 190.8 seconds
of speech and 213 reference words. Keep this dataset separate from the synthetic
baseline. The importer accepts `id`, `file`, `language`, `reference`, and optional
`speaker` and `tags`. It preserves source hashes and never edits originals.

```sh
python3 tools/speech-compare.py --corpus .cache/speech/comparison/human41 \
  import-manifest .cache/speech/human-fi/manifest.jsonl --human-dir .cache/speech/human-fi
```

For later manifests, choose a new corpus directory. Omit `--human-dir` unless
the five earlier recordings should also be included.

Prepare the initial corpus once:

```sh
python3 tools/speech-compare.py prepare --human-dir .cache/speech/human-fi
```

This requires local `ffmpeg` and `espeak-ng`. It converts copies to mono 16 kHz
PCM16 and records each waveform's SHA-256, duration and reference. It refuses to
overwrite an existing corpus. Synthetic source versions are recorded in
`provenance.json`.

## Local Android runs

Build with JDK 17 and the configured Android SDK:

```sh
cd src/BunDo.Android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

Install both APKs directly with `adb -s SERIAL install -r`. Do not use Gradle's
`connectedDebugAndroidTest` here. It uninstalls the application after testing,
which removes installed models and local data.

```sh
adb -s emulator-5556 install -r src/BunDo.Android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r src/BunDo.Android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/speech-compare.py device emulator-5556 native
python3 tools/speech-compare.py device emulator-5556 parakeet
```

For the human corpus, put `--corpus .cache/speech/comparison/human41` before
`device`. The cloud script also accepts `--corpus` for the same directory.

Run on each dedicated `bun-do-a` and `bun-do-b` profile. The runner disables
Wi-Fi and mobile data, enables airplane mode, disables host-microphone input and
leaves networking disabled afterward. It copies PCM into the app's private cache.
The instrumentation also rejects any active Android network. A `finally` block
removes device input files and the private report after retrieval.

Native tests use `createOnDeviceSpeechRecognizer`, never a cloud-capable
recognizer with an offline preference. They inspect installed/supported languages,
then exercise prerecorded input through `EXTRA_AUDIO_SOURCE`. An unsupported
language produces a recorded error, not a passing transcript. Providers can
ignore prerecorded-input extras; the host microphone stays disconnected, and
the resulting transcript still needs comparison with the supplied clip.

Parakeet requires the pinned model to be installed first. Model downloads are
not part of this experiment. The actual production `LocalSpeech.transcribe`
method processes each clip and creates a new recognizer each time.

Default instrumentation runs skip this opt-in comparison. `OK (1 test)` for the
experiment means the runner completed, not that every recognizer succeeded.
Inspect the report statuses and completeness.

## Azure setup and runs

Primary-source API and region research is in
[speech engine research](research/speech-engine-comparison.md).
`infra/experiments/speech-comparison.bicep` is separate from `main.bicep`. It adds:

- Two pinned, capacity-10 Global Standard GPT speech test deployments to the
  existing development OpenAI account.
- One key-disabled `AIServices` S0 account in North Europe for MAI-Transcribe-2.
- Account-scoped inference roles for the test operator.

The owner approved European resource locations. Global Standard GPT deployments
do not guarantee EU-only inference. Neither a Sweden account name nor these
tests prove processing residency.

Compile this template and inspect a resource-group incremental what-if before
deploying it. Scope every command to the selected subscription and
`rg-bun-do-dev-swc`. Never deploy the whole foundation merely to run a speech
experiment. The scoped template leaves unrelated resources outside its desired
state. Inspect any modifications; never use Complete mode.

```sh
bicep build infra/experiments/speech-comparison.bicep --outfile .azure/speech-comparison/template.json
az deployment group what-if --subscription SUBSCRIPTION_UUID \
  --resource-group rg-bun-do-dev-swc --template-file .azure/speech-comparison/template.json \
  --parameters openAiAccountName=OPENAI_ACCOUNT speechAccountName=SPEECH_ACCOUNT testerPrincipalId=USER_OBJECT_ID
```

Read back deployments, account authentication settings and inference roles after
provisioning. The runner does not provision anything and retrieves an Entra token
into memory. It never retrieves keys, writes credentials or follows redirects.

The following are paid calls. `uv` installs the script's pinned WebSocket
dependency into its cache.

```sh
uv run tools/speech-cloud-compare.py gpt --subscription SUBSCRIPTION_UUID \
  --account OPENAI_ACCOUNT --deployment bun-do-speech-file-test \
  --allow-private-audio --execute-paid-test
uv run tools/speech-cloud-compare.py live --subscription SUBSCRIPTION_UUID \
  --account OPENAI_ACCOUNT --deployment bun-do-speech-live-test \
  --allow-private-audio --execute-paid-test
uv run tools/speech-cloud-compare.py mai --subscription SUBSCRIPTION_UUID \
  --account SPEECH_ACCOUNT --allow-private-audio --execute-paid-test
```

Use `--case fi-quantity-140` for a single synthetic smoke test first. The runner
requires an additional flag for any private audio. It stops on the first failed
request and does not automatically retry an ambiguous paid call. Partial
results remain in a timestamped private report. Preserve them when diagnosing
failure.

All engines receive the same 16 kHz waveform. Realtime requires a local 24 kHz
resampling of that waveform. GPT file input and MAI get WAV containers; native
and Parakeet get raw PCM. Only language hints are sent. MAI requests verbatim
transcription, not cleanup. No later LLM rewrites run in this comparison.

## Accuracy and latency

```sh
python3 tools/speech-compare.py score .cache/speech/comparison/REPORT.json
```

Pass the matching `--corpus` before `score`. To collect several matching runs
into one private, per-clip JSON report:

```sh
python3 tools/speech-compare.py --corpus .cache/speech/comparison/human41 \
  report REPORT_A.json REPORT_B.json \
  --output .cache/speech/comparison/human41/comparison.json
```

This output contains references and transcripts. Keep it local; only aggregate
results and non-identifying observations belong in committed review notes.

Scoring verifies corpus and waveform hashes before matching results by ID.
Missing/unsupported results remain visible, with no invented zero-error score.
Word error rate ignores punctuation and case but counts number formatting
differences such as `two` versus `2`. Inspect quantities, negation, dates, actions
and self-corrections manually. A low WER does not establish task correctness.
Silence hallucination is a separate result.

Latency has different meanings by engine:

| Measurement | Included |
| --- | --- |
| Parakeet `decode-including-model-load` | Local file read, recognizer/model creation, decode and release. Each call reloads the recognizer, as the app currently does. |
| Native `file-input-start-to-final` | Recognizer creation, binding, supplied-file recognition and final callback. The platform service can stay warm between calls. |
| File cloud `request-to-final` | WAV/multipart assembly, connection, upload, server processing and complete response download. |
| Streaming `audio-commit-to-final` | Wait after the last audio chunk is committed until final text. It excludes the preceding audio duration and connection. |

Streaming also records connection/ready time, first partial text measured from
audio start, paced audio duration, and total session time. It sends 100 ms chunks
at real-time speed. Comparing its finalization delay with a full file request
without these labels would be misleading.

All runs record audio duration. Repeated clips expose variation, but three
repetitions do not establish a reliable p95. Report medians and observed ranges.
Emulator CPU timings on this Mac are not measurements of a target phone.
These runs also exclude UI rendering, microphone capture, authentication refresh,
application backend hops, production queuing and any later LLM cleanup.

## Checks

```sh
python3 tools/check_invariants.py
python3 -m unittest discover -s tools -p 'test_*.py'
```

The comparison unit tests protect consent, immutable audio provenance, missing
results, incompatible latency definitions, redirect rejection and scoring of
negation/quantity changes. They make no cloud calls. Fresh adversarial review is
required before declaring this work complete.
