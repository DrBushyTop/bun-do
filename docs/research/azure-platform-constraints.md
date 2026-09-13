# Azure platform constraints

This note keeps the external facts that shaped Bun Do's contracts. Bicep owns
Azure resource configuration, and the implementation contracts own product
behavior. Neither document proves that the selected subscription has capacity or
that a production request succeeds.

## AI and structured output

Azure publishes the Luna/Terra family in its model catalog, but catalog presence does not guarantee availability in Bun Do's subscription or region. Keep the selected deployment name and version in Bicep, then record the live result in the owning slice.

Use the Azure OpenAI v1 Responses API with `text.format` for strict structured
output and `reasoning.effort` for reasoning controls. Set `store: false` for
independent task extraction. Strict schemas must require every property, make
optional values nullable, and close objects with `additionalProperties: false`.
Server validation still owns text limits, semantic dates, recurrence, and domain
rules. Treat refusal, incomplete output, invalid schema, throttling, timeout,
and a possible post-send completion as distinct outcomes.

## Cosmos

A transactional batch is one logical partition and has documented operation,
payload, and time limits. That is why workspace data, receipts, changes, and
revision metadata share the workspace partition. The sync contract adds the
application rules for conditional revision writes, retries, ordered changes, and
recovery. Provider limits do not prove those rules in a live account.

## Live gates

Catalog visibility and public documentation do not establish model capacity,
quota, deployment, Entra authorization, actual API acceptance, or Cosmos
concurrency. The owning implementation slice must record those checks against
the deployed system.

Sources: [Azure model catalog](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure),
[Azure Responses](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/responses),
[Azure structured outputs](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/structured-outputs#json-schema-support-and-limitations),
[Azure reasoning](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/reasoning),
[Cosmos transactional batches](https://learn.microsoft.com/en-us/azure/cosmos-db/transactional-batch),
and [Cosmos request limits](https://learn.microsoft.com/en-us/azure/cosmos-db/concepts-limits#per-request-limits).
