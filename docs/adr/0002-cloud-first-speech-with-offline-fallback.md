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
not hide transcription failures or overwrite human corrections.

Signed-in capture uses the authenticated backend when connected. Without a
connection, it uses an installed Parakeet model. An online failure can fall back
to the installed model, but rejected authentication remains visible with an
explicit local retry. Without a model, the recording stays available for retry,
export or deletion. Anonymous capture stays local.

There is no automatic upload retry or server audio job. Android commits text
before removing local audio and fences results with the active account lease
and cancellation. An uncertain upload may have incurred a provider charge;
explicit retry can incur another. Cancelling an upload does not promise that
Azure stopped processing it.

On September 13, 2026, the owner moved product AI length and usage-policy review until after V2. Start without product quotas and observe household usage before deciding whether limits are needed. Authentication, provider constraints, technical payload/memory/output bounds, timeouts and recoverable input remain required. The [usage-policy issue](https://github.com/DrBushyTop/bun-do/issues/37) is outside both release parents; earlier numeric admission proposals are superseded.

## September 16 capture revision

The owner replaced the setup-heavy voice sheet with tap-to-record, stop, editable
review and Save. Settings owns speech selection, Parakeet installation and audio
history. Local-only speech never uploads audio. The separate optional capture
analysis may send household transcript text online, including locally transcribed
text; explain that distinction in Settings.

New audio is temporary by default. Retain it only when the user opts in before
recording. Failed/canceled temporary audio is deleted, not offered for recovery;
successful transcription is persisted as a review draft before audio deletion.
After a crash, delete unretained incomplete audio. Existing retained recordings
keep their expiry on upgrade. This replaces this ADR's earlier mandatory local
audio recovery for new recordings. It does not permit audio in logs or automatic
sharing of retained developer/test recordings.
