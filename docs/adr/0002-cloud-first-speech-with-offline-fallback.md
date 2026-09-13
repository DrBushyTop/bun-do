# Prefer MAI transcription and keep offline capture

On September 12, 2026, the owner approved sending recordings to Azure and chose
MAI-Transcribe-2 for the primary online transcription path. The 41-clip Finnish
comparison produced lower word error and lower post-recording latency than local
Parakeet. Keep Parakeet as an offline fallback and typing available without a
model. The native recognizer on both test emulators lacks Finnish support.
See the [comparison procedure](../android-speech-comparison.md) and the slice
issue's recorded results.

This explicitly changes the earlier local-only audio boundary. It does not
authorize audio in telemetry, public storage, source control or unrelated
services. Send audio through the authenticated Bun Do backend to a key-disabled
Speech-enabled Foundry account. Android must not receive Foundry credentials.
Use European resource regions, with North Europe available when Sweden Central
does not support the chosen model. Keep server audio transient, preserve local
recovery until text commits, and fence late cloud results after cancellation,
fallback or an account switch.

Transcription converts speech to text. Later AI enrichment is separate and must
not hide transcription failures or overwrite human corrections. Cloud-first
capture ships only after its authentication and recovery integration passes.
The completed offline slice and local comparison scripts do not constitute that
integration.

On September 13, 2026, the owner moved product AI length and usage-policy review until after V2. Start without product quotas and observe household usage before deciding whether limits are needed. Authentication, provider constraints, technical payload/memory/output bounds, timeouts and recoverable input remain required. The [usage-policy issue](https://github.com/DrBushyTop/bun-do/issues/37) is outside both release parents; earlier numeric admission proposals are superseded.
