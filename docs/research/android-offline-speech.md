# Android offline speech research

Research date: September 12, 2026. The owner kept Finnish and English offline
capture in v1, chose Parakeet as the local fallback, and accepted target-phone
performance as an unmeasured assumption. The separate physical-device experiment
is deliberately not a release gate.

Parakeet v3 has Finnish and English support and a published Android integration
path through sherpa-onnx. Those facts justified implementation, not a promise of
accuracy, latency, memory use, battery use, or reliability on the target phones.
The selected runtime and model still need normal emulator and recovery testing.

The model carries CC BY 4.0 obligations. Preserve model provenance, attribution,
license information, and conversion notices. Keep the Apache 2.0 notices for
sherpa-onnx and its dependencies. Do not infer peak memory from model download
size. Keep typed capture available while a model is absent, installing, corrupt,
or unable to transcribe.

Sources: [NVIDIA model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
[sherpa-onnx model instructions](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html),
[ONNX Runtime mobile guidance](https://onnxruntime.ai/docs/tutorials/mobile/),
and [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/legalcode.en).
