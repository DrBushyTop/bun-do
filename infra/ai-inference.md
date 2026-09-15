# AI inference deployment

`modules/ai-inference.bicep` owns Bun Do's dedicated Azure OpenAI account,
model deployments and the separate Speech account. `backend-hosting.bicep` grants its own user-assigned identity
the account-scoped Cognitive Services OpenAI User role. That role includes
Responses inference, not model deployment or key management.

Development uses Sweden Central with `DataZoneStandard`, capacity 10, for
`bun-do-luna`. The catalog model is `gpt-5.6-luna`, version `2026-07-09`.
Model, version, SKU and capacity are explicit inputs in `dev.parameters.json`.
Terra has separate inputs but is disabled. Neither provisioned capacity nor an
automatic spillover deployment is configured. Capacity is a rate quota, not
a spending cap.

The account has an identity-authenticated public endpoint. Local key
authentication is disabled. The model version uses `NoAutoUpgrade`; a version
change requires a reviewed deployment. Default provider
content filtering remains enabled. `store: false` prevents Responses history
storage, but does not claim an exemption from Azure's abuse-monitoring policy.

The backend receives the v1 base URL and deployment names, never an account key.
`AI__Enabled` enables the authenticated cleanup worker. Foreground and periodic
sync process one durable request from the authenticated requester at a time. An
expired inference lease offers Retry without automatically repeating the paid call.
Product quotas and token budgets are not enablement gates; their review waits until after V2.

The backend uses the Speech account for MAI transcription through managed
identity and an account-scoped Speech User grant. The existing account keeps
its resource identity despite its historical `speech-test` name. The experiment
template no longer owns its configuration. Disabling `Speech__Enabled` stops
new online transcription calls; Android retains failed input and can use an
installed local model. Audio stays in backend request memory, not Blob or Cosmos.
This does not assert that Azure's own processing has no retention obligations.

## Verification

Follow `docs/azure-development.md` for compilation, full what-if review and
guarded deployment. Bicep owns the model configuration. Do not duplicate it in
template assertions or a policy-readback tool.

When the production AI adapter is implemented or its model is changed, integration
tests should exercise that adapter through the deployed identity, with synthetic
text and the schemas selected for the active v1 cleanup/split paths. Unused
clarify, recurrence or area fields in older schemas do not expand release scope. Keep strict output validation,
`store: false` and no tools. Record only status, latency, deployment/model version,
usage and result classification. Do not log prompts or output, and do not retry
an ambiguous paid call merely to get a passing result. This is future application
coverage, not a reason to recreate temporary Function gates now.

Useful primary references, checked September 12, 2026:

- [Account Bicep reference](https://learn.microsoft.com/en-us/azure/templates/microsoft.cognitiveservices/2025-06-01/accounts)
- [Deployment Bicep reference](https://learn.microsoft.com/en-us/azure/templates/microsoft.cognitiveservices/2025-06-01/accounts/deployments)
- [Responses API and identity authentication](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/responses)
- [Azure OpenAI roles](https://learn.microsoft.com/en-us/azure/ai-foundry/openai/how-to/role-based-access-control)
