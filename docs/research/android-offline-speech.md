# Android offline speech for Bun Do

Research date: 2026-09-12. Resolves [Verify Finnish and English offline speech on Android](https://github.com/DrBushyTop/bun-do/issues/3). No phone measurements were performed. The owner identified the target phones as vivo X300 Ultra and OnePlus 13.

Owner decision after research: skip the separate device experiment and proceed assuming satisfactory operation on both phones. The experiment below is retained as the original recommendation, not a planning prerequisite. Normal implementation testing remains required. [Skipped experiment](https://github.com/DrBushyTop/bun-do/issues/11).

Keep Finnish and English offline dictation in the full first-release scope. Parakeet v3 has a credible Android implementation path, and the owner subsequently accepted that feasibility assumption without the separate device experiment. Runtime selection affects latency, memory, installation and recording UX, so it cannot remain an unspecified wrapper detail until phase 3.

## What exists

NVIDIA's 600-million-parameter `parakeet-tdt-0.6b-v3` explicitly supports Finnish and English among 25 languages, detects language automatically, and produces punctuation and capitalization. Its long-recording claims include an A100 80 GB configuration. Those claims do not establish phone performance or accuracy on household names and Finnish task instructions. [NVIDIA model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3).

The sherpa-onnx maintainers publish an int8 ONNX conversion, export scripts and decoding instructions. Required files are `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx` and `tokens.txt`. Their listing totals about 640 MiB, with a 622 MiB encoder. Configure it as `nemo_transducer`. This is a non-streaming model; the Android simulated-streaming demo segments audio rather than proving native incremental transcription. [Model instructions and conversion scripts](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html).

The publisher lists a v3 `arm64-v8a` Android demo APK, currently version 1.13.7, and supplies Android native libraries with JNI and ONNX Runtime. This establishes available artifacts and an integration route. It does not certify Bun Do's two phones. [APK catalog](https://k2-fsa.github.io/sherpa/onnx/android/apk-simulate-streaming-asr.html), [Android build instructions](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html).

The GitHub release API reports **487,170,055 bytes**, approximately 465 MiB, for `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2`, with SHA-256 `5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf`. This research checked metadata, not the downloaded bytes. Pin and verify the artifact during model installation implementation. [Release metadata](https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models), [download](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2).

## Memory and license boundaries

Disk size is not a peak-memory estimate. ONNX Runtime requires the model to fit both storage and memory and calls for measuring application latency and power. [Mobile deployment guidance](https://onnxruntime.ai/docs/tutorials/mobile/).

One firsthand upstream report says two-minute Parakeet v3 clips worked on Android 13 with a Snapdragon 680 and 2 GB **free** RAM. It supplies no measured latency, peak memory or Finnish results. Another reports 1.23 GB RAM after loading v3 int8 on iOS 16. These reports support feasibility investigation and caution against equating model bytes with memory. Neither establishes a minimum Android RAM requirement. [Android report](https://github.com/k2-fsa/sherpa-onnx/issues/3913), [iOS report](https://github.com/k2-fsa/sherpa-onnx/issues/2626).

NVIDIA identifies CC BY 4.0 as the model license. Distribution must preserve required attribution, license information and modification notices. Record NVIDIA provenance and the ONNX/int8 conversion in the model manifest and notices. Sherpa's Apache 2.0 code license is separate; retain applicable runtime and dependency notices. [Model terms](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [CC BY 4.0 terms](https://creativecommons.org/licenses/by/4.0/legalcode.en), [sherpa license](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE).

## Historical experiment recommendation, skipped

Create an Android prototype before committing to the voice UX. These are proposed Bun Do acceptance criteria, not measured results or vendor promises.

1. Record both actual phone models, OS versions, RAM and available storage. Pin runtime version, model digest, ABI, CPU provider and thread count. Start with CPU inference behind `SpeechRecognizer`; add acceleration only after measuring a need.
2. Use each person's Finnish and English recordings at 5, 15, 30, 60 and 120 seconds. Include names, amounts, dates, pauses, self-corrections, background noise, silence and mixed-language task wording. Compare transcripts against human references and inspect meaning-changing errors separately from punctuation. Every result must be editable before use.
3. Measure cold model load, warm stop-to-transcript latency, transcription time divided by recording duration, and peak whole-process memory including native allocations. Use Android profiling and `adb shell dumpsys meminfo`; do not report JVM heap alone. Repeat short clips at least 20 times and record median and p95 latency. [Android memory tooling](https://developer.android.com/tools/dumpsys#meminfo).
4. Propose warm p95 stop-to-transcript at most 3 seconds for recordings up to 15 seconds, cold load at most 5 seconds, no crashes or lost recordings, and no meaning-changing error in at least 90% of representative short clips for each person and language. Report actual memory first, then set the supported-device budget with headroom. Run ten minutes of repeated transcription to observe thermal status, throttling and battery consumption. Failure requires revising the local implementation, not silently dropping offline voice or uploading audio.
5. Test airplane mode after installation, interrupted downloads, corruption, insufficient storage, cancellation, process death and model replacement. Stage and verify downloads before atomic activation, retain the old working model until replacement succeeds, and budget temporary storage for both copies. Provide typed capture during installation or failure. Define bounded local audio retention and retry/delete actions so failed recognition does not lose a task or retain audio indefinitely.

The research issue can close once this evidence and the prototype follow-up are recorded. The Android offline-voice release criterion uses agent-run emulator functional tests. Actual-phone performance remains an accepted unmeasured assumption, not an unassigned release blocker.
