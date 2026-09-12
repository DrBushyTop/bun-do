# Hosted and Android speech comparison

Research date: September 12, 2026. This note records available APIs and a proposed
comparison, not measured recognition quality. The owner has approved sending the
five supplied recordings to Azure and considers cloud-first transcription
acceptable. The owner subsequently authorized other European regions when
Sweden does not support a requested model. This research performed
management-plane reads only, with no model
deployments, inference, key retrieval or audio access.

## Findings

The screenshot names real models. `gpt-transcribe` accepts completed recordings;
`gpt-live-transcribe` produces incremental text from an audio stream.
MAI-Transcribe-2 accepts recordings through the Azure Speech enhanced-mode API
and explicitly supports Finnish and English. None is an Android offline model.
In this context, "offline/file transcription" means processing an already
recorded file on a hosted service, not recognition without a network connection.
[OpenAI file transcription][file], [OpenAI realtime transcription][realtime],
[MAI API][mai], [MAI languages][languages].

For Bun Do's current record-then-stop flow, test `gpt-transcribe` and
MAI-Transcribe-2 before adding streaming transport to production. Include
`gpt-live-transcribe` in the experiment to measure whether partial text would
justify that extra implementation. Keep Parakeet and Android's actual on-device
recognizer as offline candidates. This is a proposed comparison order, not a
quality ranking.

## Live account discovery

Read-only discovery used the CLI's already-selected subscription and only
`rg-bun-do-dev-swc`. The group has one Cognitive Services account, an `OpenAI`
resource in Sweden Central. At discovery time it had only the existing
`bun-do-luna` deployment.

The account's model catalog returned:

| Model | Exact Azure version | Offered deployment type | Already deployed |
| --- | --- | --- | --- |
| `gpt-transcribe` | `2026-07-28` | `GlobalStandard` | No |
| `gpt-live-transcribe` | `2026-07-28` | `GlobalStandard` | No |
| `MAI-Transcribe-2` | Not returned by this OpenAI account | Separate Speech API | No suitable resource found in the allowed group |

The screenshot's July 29, 2026 label does not match the version returned by this
account. Use July 28, 2026 when pinning these Azure deployments. Catalog presence
does not prove quota, capacity, deployment success or successful inference.

Reproduce discovery without credentials or data-plane calls:

```sh
az cognitiveservices account list -g rg-bun-do-dev-swc \
  --query '[].{name:name,kind:kind,location:location}'
az cognitiveservices account deployment list \
  -g rg-bun-do-dev-swc -n ACCOUNT_NAME
az cognitiveservices account list-models \
  -g rg-bun-do-dev-swc -n ACCOUNT_NAME \
  --query "[?name=='gpt-transcribe' || name=='gpt-live-transcribe']"
```

The ignored local discovery record is
`.cache/speech/cloud-docs/azure-models.json`. The account disables key
authentication. Use Microsoft Entra bearer authentication, not account keys.

Global Standard can process requests outside the resource's geography.
Sweden Central resource placement therefore does not promise EU-only inference.
Data Zone would provide a different processing boundary, but the account did
not offer it for these two models. [Azure data processing policy][privacy].

MAI-Transcribe currently requires a Speech-enabled Foundry resource in
`centralindia`, `eastus`, `northeurope`, `southeastasia`, `westus` or `westus2`.
Sweden Central is absent from the LLM Speech region table. The owner has now
approved other European regions for this comparison, making North Europe an
allowed candidate. Keep the resource in the existing dedicated group.
Do not try to select MAI by passing its
name to the existing OpenAI endpoint. [MAI prerequisites][mai],
[LLM Speech regions][regions].

## API requests

These are request shapes for implementing the comparison. They are not records
of successful Azure calls. Use bounded deadlines and sequential requests; keep
bearer tokens in memory and never print them in commands, reports or telemetry.
Do not send reference transcripts as prompts or keyword hints.

### Completed recording with GPT-Transcribe

The Azure v1 route is
`https://ACCOUNT.openai.azure.com/openai/v1/audio/transcriptions`.
Send a multipart POST with an Entra `Authorization: Bearer …` header:

```text
model = AZURE_DEPLOYMENT_NAME
file = recording.wav
languages[] = fi
```

Use `en` for English clips. The new model uses plural `languages`; do not copy
the singular `language` field from older transcription examples or send both.
The OpenAI API documents a 25 MB file limit and accepts WAV, M4A and several
other formats. Its response contains `text` and detected `languages`.
Azure deployment acceptance and response compatibility still need a live smoke
test. [OpenAI file request][file], [Azure v1 endpoint guidance][endpoints],
[Azure transcription overview][azure-transcription].

#### File endpoint failure observed during implementation

The implementation agent reports two synthetic requests returning HTTP 404
`DeploymentNotFound` from the v1 file route after ARM reported the
`bun-do-speech-file-test` deployment as `Succeeded`. A separate read-only
management-plane check confirmed its exact name, `gpt-transcribe` model,
`2026-07-28` version and Global Standard capacity 10.

Microsoft explicitly documents both the deployment-style route and the v1
route for file transcription. No documented model restriction found in this
check explains the 404. Its troubleshooting section only advises matching the
case-sensitive deployment name. [Azure route variants][azure-file-routes],
[Azure file quickstart][azure-file-quickstart].

A local MIME-parser check of the comparison runner's multipart builder recovered
the exact deployment value from the `model` field, followed by `languages[]` and
the WAV file part. This rules out an obvious malformed multipart body, not an
Azure gateway defect. The OpenAI docs require plural `languages` for the new
model, while Azure's generic file examples still show singular `language` and
older API versions. Do not infer a field fix from this documentation mismatch.
[Current model fields][file], [Azure examples][azure-file-routes].

The next bounded diagnostic is one synthetic request to the documented
deployment-style route, with the deployment in the URL and no optional
language hint. Record its response without silently replacing the model,
recreating the deployment or repeatedly sending private clips. Until inference
succeeds, report the GPT file engine as unavailable in this experiment, not as
a recognition-quality failure. The service-side cause is unresolved.

### Streaming with GPT-Live-Transcribe

Connect using Entra bearer authentication to:

```text
wss://ACCOUNT.openai.azure.com/openai/v1/realtime?intent=transcription
```

Send:

```json
{
  "type": "session.update",
  "session": {
    "type": "transcription",
    "audio": {
      "input": {
        "format": {"type": "audio/pcm", "rate": 24000},
        "transcription": {
          "model": "AZURE_DEPLOYMENT_NAME",
          "languages": ["fi"],
          "delay": "medium"
        },
        "turn_detection": null
      }
    }
  }
}
```

Resample each source clip locally to mono 24 kHz PCM16. Send base64 PCM in
`input_audio_buffer.append` events, followed by `input_audio_buffer.commit`.
Collect `conversation.item.input_audio_transcription.delta` for first-text
latency, but grade only
`conversation.item.input_audio_transcription.completed`, matched by `item_id`.
Create a fresh session per clip to avoid previous transcripts supplying context.
For latency comparisons, pace chunks at the audio's actual duration rather than
uploading an entire recording instantly. [Azure WebSocket connection and
transcription example][azure-websocket], [OpenAI session and events][realtime].

Microsoft's WebSocket page still demonstrates older transcription models and
singular language hints. The new model's plural `languages` shape above comes
from current OpenAI documentation and requires Azure smoke-test verification.
Do not treat the example as proven Azure compatibility. [Azure example][azure-websocket],
[new model fields][realtime].

### MAI-Transcribe-2

Once a suitable Speech resource exists, its documented multipart route is:

```text
POST https://RESOURCE.cognitiveservices.azure.com/speechtotext/transcriptions:transcribe?api-version=2025-10-15
audio = recording.wav
definition = the JSON below
```

```json
{
  "locales": ["fi"],
  "enhancedMode": {
    "enabled": true,
    "model": "MAI-Transcribe-2",
    "modelOptions": {
      "transcribeStyle": "verbatim",
      "timestamps": "none"
    }
  }
}
```

The MAI page shows key authentication, but the shared LLM Speech REST quickstart
explicitly supports `Authorization: Bearer …` instead. Use a token for
`https://cognitiveservices.azure.com/.default`, the custom-subdomain endpoint,
and the account-scoped Cognitive Services Speech User role,
`f2dc8367-1007-4938-bd23-fe263f013447`. The `aad#…` token wrapper used in some
Speech SDK examples is not the REST bearer header. [LLM Speech REST
authentication][llm-rest], [Speech Entra setup][speech-entra],
[Speech User role][speech-role].

The Foundry resource quickstart specifies `kind: 'AIServices'` and
`sku: { name: 'S0' }`. A North Europe account can use the
`Microsoft.CognitiveServices/accounts@2025-06-01` Bicep resource type with
`customSubDomainName` and `disableLocalAuth: true`. No separate OpenAI model
deployment is described for MAI's enhanced-mode selector. Verify the provisioned
account and a synthetic request before uploading private clips; documentation
alone does not prove account enablement. [Foundry resource quickstart][resource],
[Bicep account properties][account], [MAI API][mai].

WAV, MP3 and FLAC are documented.
The language hint is strong, accepts only one language, and can be omitted for
autodetection. Start with verbatim output so the recognizer does not hide
self-corrections before grading. Test clean output separately. [MAI API][mai].

## Cost and data handling

OpenAI's own API lists GPT-Transcribe at USD 0.0045 per audio minute and
GPT-Live-Transcribe at USD 0.017 per minute. Those are not Azure price quotes.
The Azure pricing page lists both Global models but returned price placeholders
during this check, so an exact Azure charge is not established here.
[GPT-Transcribe pricing][gpt], [GPT-Live-Transcribe pricing][live],
[Azure pricing][azure-pricing].

Microsoft advertises MAI-Transcribe-2 at USD 0.10 per audio hour as a
limited-time offer through the end of 2026. Confirm the actual subscription,
region and meter price before provisioning. The API is public preview; do not
infer production guarantees from a model catalog listing.
[Microsoft announcement][mai-news], [preview notice][mai].

Azure's general model policy says inputs are not used to train the base models,
but abuse monitoring can retain flagged content for human review. This account
did not expose the documented `ContentLogging=false` exemption. Do not claim
zero retention for its speech calls. The policy also warns that previews may
have different privacy practices. [Azure policy][privacy].

The Speech privacy page says fast transcription does not retain customer data.
That page does not spell out MAI-Transcribe-2 enhanced-mode preview retention
separately. Keep that distinction explicit rather than promising preview
behavior from a general service statement. [Speech privacy][speech-privacy],
[MAI preview][mai].

Private recordings, derived audio and raw reports remain Git-ignored. Cloud
permission does not authorize putting them in APK assets, source control,
issue comments or telemetry. Production upload authorization, retention,
deletion and fallback UX still need their own implementation and review.

## Fair comparison and remaining gates

Use one fixed corpus for all engines: the five supplied human clips, the
licensed public fixture, synthetic Finnish and English task commands, and
synthetic variants with pauses, noise and silence. Preserve source clip IDs and
digests across resampling. Never replace a failing clip to improve a score.

Record word error rate separately from task meaning. Explicitly score action,
negation, quantity, date and self-correction preservation. Silence producing an
invented task is a failure even if average word error rate looks good. Report
unsupported language, missing model, timeout, rejected input and unavailable
provider as distinct outcomes, not empty transcripts graded as successes.

Measure raw recognition first. Run any later LLM cleanup as a separate stage
and compare semantic errors before and after it. Cleanup is not evidence that
a recognizer preserved the user's meaning. Label synthetic and human results
separately; synthetic success does not establish natural-speech quality.

Still required:

- Approve and provision the two pinned Global Standard deployments, then prove
  identity-authenticated requests with synthetic audio before private clips.
- Provision and validate the now-authorized North Europe Speech-enabled
  resource for MAI-Transcribe-2.
- Complete the Android on-device availability and prerecorded-input probe
  offline, without falling back to a network recognizer.
- Run and review the corpus. This research alone cannot select a winning model.

[file]: https://developers.openai.com/api/docs/guides/speech-to-text
[realtime]: https://developers.openai.com/api/docs/guides/realtime-transcription
[gpt]: https://developers.openai.com/api/docs/models/gpt-transcribe
[live]: https://developers.openai.com/api/docs/models/gpt-live-transcribe
[mai]: https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe
[languages]: https://github.com/MicrosoftDocs/azure-ai-docs/blob/main/articles/ai-services/speech-service/includes/language-support/mai-transcribe.md
[regions]: https://learn.microsoft.com/en-us/azure/ai-services/speech-service/regions?tabs=llmspeech
[privacy]: https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/openai/data-privacy
[speech-privacy]: https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/speech-service/speech-to-text/data-privacy-security
[azure-websocket]: https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/realtime-audio-websockets
[azure-transcription]: https://learn.microsoft.com/en-us/azure/foundry/openai/concepts/gpt-realtime-whisper
[endpoints]: https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/switching-endpoints
[azure-pricing]: https://azure.microsoft.com/en-us/pricing/details/azure-openai/
[mai-news]: https://microsoft.ai/news/mai-transcribe-2-is-the-fastest-most-accurate-and-cheapest-speech-recognition-model-in-the-world/
[llm-rest]: https://github.com/MicrosoftDocs/azure-ai-docs/blob/main/articles/ai-services/speech-service/includes/common/llm-speech-rest-api.md
[speech-entra]: https://learn.microsoft.com/en-us/azure/ai-services/speech-service/how-to-configure-azure-ad-auth
[speech-role]: https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/ai-machine-learning#cognitive-services-speech-user
[resource]: https://learn.microsoft.com/en-us/azure/ai-services/multi-service-resource?pivots=azcli
[account]: https://learn.microsoft.com/en-us/azure/templates/microsoft.cognitiveservices/2025-06-01/accounts
[azure-file-routes]: https://learn.microsoft.com/en-us/azure/ai-services/speech-service/transcribe-overview
[azure-file-quickstart]: https://learn.microsoft.com/en-us/azure/foundry/openai/whisper-quickstart
