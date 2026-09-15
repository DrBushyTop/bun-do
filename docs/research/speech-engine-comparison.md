# Hosted and Android speech research

Research date: September 12, 2026. This note records why the comparison exists
and the provider constraints that affect it. The comparison procedure and its
commands live in [speech comparison](../android-speech-comparison.md); the
runners are the source for exact request shapes.

## Decision context

Bun Do records first and transcribes after stop. The owner approved a comparison
of hosted transcription with local Android recognition, then selected
MAI-Transcribe-2 as the primary online path with Parakeet retained for offline
fallback. The decision alone did not implement cloud transcription. The
[speech decision and runtime contract](../adr/0002-cloud-first-speech-with-offline-fallback.md)
now owns its authenticated upload, retention, cancellation and recovery behavior.

Test completed-file transcription before adding streaming. Streaming is worthwhile
only if measured partial results justify its transport and lifecycle complexity.
Keep raw recognition separate from later AI cleanup. A cleanup model cannot prove
that a recognizer preserved meaning.

## Provider constraints

`gpt-transcribe` and `gpt-live-transcribe` were available in the development
account's catalog during research. MAI-Transcribe-2 needs a Speech-enabled
Foundry resource and, at that time, was available in North Europe but not Sweden
Central. Catalog visibility does not prove capacity, deployment, authentication,
or inference success.

Use Entra bearer authentication. Do not use account keys, expose Foundry
credentials to Android, put audio in telemetry or public storage, or assume
Sweden Central placement guarantees EU-only processing for a Global Standard
model. Preview services may have different retention and support terms. Confirm
the actual region, account, model version, cost, authentication, and data
handling before a live experiment.

A documented GPT file-transcription route returned `DeploymentNotFound` during
a synthetic implementation probe. Treat it as unavailable until a bounded
follow-up identifies a working, documented route. Do not replace the model,
recreate deployments, or repeatedly upload private audio to force a result.

## Fair comparison

Use the same preserved waveform and reference set for every engine. Score missing,
unsupported, timeout, rejected, and unavailable results separately from
recognition errors. Report word error rate alongside semantic checks for action,
negation, quantity, date, and self-correction. Silence hallucination is a
separate failure. Keep synthetic and human results separate.

Sources: [OpenAI speech-to-text](https://developers.openai.com/api/docs/guides/speech-to-text),
[OpenAI realtime transcription](https://developers.openai.com/api/docs/guides/realtime-transcription),
[MAI Transcribe](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe),
[LLM Speech regions](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/regions?tabs=llmspeech),
and [Azure data privacy](https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/openai/data-privacy).
