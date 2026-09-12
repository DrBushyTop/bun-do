# AI inference foundation

September 12, 2026. Continued the development-stack slice while the OTel
implementation subagent worked. The dedicated Azure OpenAI account is in Sweden
Central. Luna uses deployment `bun-do-luna`, catalog model `gpt-5.6-luna`, version
`2026-07-09`, DataZoneStandard capacity 10. Terra remains disabled.

The module takes model, version, SKU and capacity inputs. Key authentication is
disabled; backend hosting grants its existing identity account-scoped Cognitive
Services OpenAI User. `AI__Enabled=false` remains unchanged. No application AI
worker or admission policy is implied by this foundation.

## Review and deployment

A fresh adversarial reviewer inspected source, tests and every what-if delta.
The first successful plan had three creates, 18 modifications and eight unchanged
resources. The reviewer independently verified the source/parameter/plan hashes
and fresh stamp. All resources were inside `rg-bun-do-dev-swc`.

The first attempt had stopped on an unmodeled Azure-created anomaly alert. The
revised observability module explicitly disables that owned rule and removes
its unrelated action-group reference. The guarded script was not relaxed.

The initial deployment succeeded. Live reads confirmed the exact model/version,
SKU/capacity, keyless endpoint, disabled auto-upgrade and default content filter.
The new account's own catalog lists the selected model. Backend identity, scoped
inference permission and disabled application AI matched the template.

The reviewed controlled-redeployment plan had 20 modifications and nine unchanged
resources, no creation or deletion. Its new Foundry deltas omitted provider
values `allowProjectManagement:false` and `currentCapacity:10`; follow-up reads
explicitly verified both instead of assuming the omissions harmless.
Redeployment succeeded. Account/deployment IDs and creation times, internal ID,
capacity, rate limits and pinned model policy were preserved. Existing backend,
telemetry schemas, runtime/snapshot storage and Cosmos policy/RID/400 RU/s checks
passed too.

## Model gate

A temporary function-key-protected probe embeds each complete schema from
`contracts/ai/`. It uses synthetic text, v1 Responses, `store:false`, no tools and
the contract's output-token ceilings. The reviewer found a classification defect
for non-JSON error bodies; the main agent fixed it before probe approval. Known HTTP errors are
classified before parsing; observed status/usage survive malformed response
handling. The probe has one provider send, no retry loop.

Each complete strict schema passed once under the Function managed identity.
All three calls returned HTTP 200 with schema-valid JSON, `store:false` and no
tools. The provider's response `model` field was the deployment alias; the
management-plane read verified its pinned catalog model/version separately.

| Schema | Input tokens | Output tokens | Gate elapsed |
| --- | ---: | ---: | ---: |
| extraction | 299 | 96 | 3,288 ms |
| clarify | 283 | 134 | 1,758 ms |
| split | 139 | 115 | 1,596 ms |

Elapsed time includes credential acquisition, the request and validation.
There were no automatic or manual repeat calls. A dispatch marker prevents
accidental reruns of the private script. Telemetry retained schema, deployment,
provider status and usage without prompt or output text.

The normal package was restored. Live health returned 200, the probe route
returned 404 and Azure listed only Health. Application AI remains disabled.

This proves the three successful G2 schema paths, not the future durable worker.

## Negative gates

The later opt-in `tools/azure-gates.py` run completed two additional live calls
under the same Function identity, without retries:

- An intentionally invalid strict schema returned HTTP 400,
  `invalid_json_schema` and `text.format.schema`. The gate classified it as
  `FAILED_CONFIGURATION`, without returning the provider's message.
- A benign request with a 16-token ceiling returned HTTP 200 and
  `status=incomplete`, reason `max_output_tokens`. Usage was 26 input and 16
  output tokens; gate elapsed time was 546 ms.

The invalid-schema call took 1,036 ms. These times exclude credential acquisition.
Both requests used synthetic text, `store:false` and no tools.

Refusal and throttling classifications passed deterministic response fixtures,
including malformed-body/status handling. They were not forced against the
live provider. We did not send unsafe prompts or flood quota to manufacture those
responses. The future AI adapter still needs its own durable admission, retry
and failure-state tests.

Normal-package restoration passed: Health 200, gate 404, only Health listed.
Full telemetry contains gate/result/status and observed usage, with none of
the tested input/output or raw-error canaries. Application AI remains disabled.
Private evidence is under `.azure/foundation-gates/`. Data-plane Cosmos/Blob
identity checks passed in the same run; see the [backend review](backend-foundation.md).
G3 concurrency/sync and restore remain downstream gates.
