# Azure platform constraints for Bun Do

Reviewed 2026-09-12 for [Verify Azure model, database, and authentication constraints](https://github.com/DrBushyTop/bun-do/issues/4), against the original architecture sections 5, 24 through 26, 28 and 41. This is public-documentation research. No tenant, deployment, quota, bill, or device integration was inspected or changed. The architecture now incorporates the mechanical corrections below; unresolved protocol and integration decisions remain in the map.

The Azure stack is viable on paper. The extraction schema needs correction before use, and the sync design needs explicit concurrency and read-consistency rules. Public model availability does not establish access in Bun Do's sponsored subscription.

## Foundry models and extraction

Microsoft lists `gpt-5.6-luna` and `gpt-5.6-terra` with Responses API and structured-output support. The model catalog gives version `2026-07-09`; the reasoning support table gives `2026-06-25`. Treat that as a documentation discrepancy and record the actual deployed version during integration. Some subscription quota tiers require a quota request. [Azure model catalog](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure), [reasoning support](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/reasoning).

Azure documents `none` and `low` reasoning for this family and low verbosity. Keep the proposed defaults as configuration to evaluate on Finnish and English task inputs. They do not establish a latency or extraction-quality result. [Reasoning controls](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/reasoning).

Specify the Azure OpenAI v1 Responses endpoint in the adapter, with a deployment name in `model`. Responses uses `text.format` for the schema and `reasoning.effort` for effort. Do not copy the Chat Completions `response_format` wrapper into this request. The generic phrase "Foundry Responses API" leaves room to mix the project and model endpoints. [Azure Responses guide](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/responses), [Foundry Responses reference](https://learn.microsoft.com/rest/api/aifoundry/project/responses).

Section 28 is not a deployable provider schema. It contains `maxLength` and `maxItems`, which Azure documents as unsupported in strict output schemas for both Responses and Chat Completions. It also references `due` and `recurrence` definitions without supplying them. Build a complete provider schema using the supported subset, require every field, make optional values nullable, and set `additionalProperties: false` on every object. Keep text lengths, collection limits, date validity and domain rules in server validation. [Azure schema limitations](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/structured-outputs#json-schema-support-and-limitations).

Set `store: false` for independent task extraction requests. Explicitly handle refusal, incomplete output, validation failure, throttling and timeout. An HTTP success can still carry incomplete generation. A bad schema is a configuration failure, so retrying it or escalating to Terra does not repair it. These are Bun Do implementation requirements inferred from the request and response contracts. [Responses guide](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/responses), [incomplete generation](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/reasoning#manage-the-context-window).

## Cosmos transactions and sync

The single-container `/workspaceId` choice correctly permits atomic domain changes, receipts, change records and revision updates. A transactional batch is limited to one logical partition, 100 operations, 2 MB and five seconds. It rolls back entirely on failure. [Transactional batches](https://learn.microsoft.com/en-us/azure/cosmos-db/transactional-batch), [service limits](https://learn.microsoft.com/en-us/azure/cosmos-db/concepts-limits#per-request-limits).

Bun Do should count the complete transaction before submission, including events, changes, receipts and sync metadata. The proposed eight AI children and three visible levels do not bound manual children, subtree deletion, reorder or repair operations. Define operation and payload bounds or a resumable protocol with explicit partial-progress behavior. Splitting a nominally atomic action into several batches silently would change its semantics. This is an architectural consequence of the documented batch limits.

Section 25.3 must require a conditional replacement of `sync-state` using the server `_etag` through `If-Match`. Read sync-state before computing from dependent documents, use a consistent read session, and recompute the entire command after a conflict. Every writer of revisioned state must participate, including AI jobs, recurrence, membership changes and repairs. An unconditional final revision write would not serialize concurrent commands. Use a single write region for v1. Cosmos documents conditional writes and different conflict behavior for multiple write regions. [Optimistic concurrency](https://learn.microsoft.com/en-us/azure/cosmos-db/database-transactions-optimistic-concurrency).

Session consistency is the default. A Cosmos SDK client tracks its session token, but a later HTTP request can reach a different Functions instance with a different client. Bun Do must choose and test either strong consistency or explicit session-token propagation across instances. With session consistency, carry the read/write session through dependent reads and cursor pagination; do not treat a revision number as a Cosmos session token. Never advance a sync cursor past changes actually returned and applied. These protocol requirements follow from Microsoft's multi-node session example. [Managing session consistency](https://learn.microsoft.com/en-us/azure/cosmos-db/how-to-manage-consistency#utilize-session-tokens).

Create receipts with unique operation IDs in the same transaction as effects. Distinguish a duplicate receipt conflict from a concurrency conflict, inspect the failed batch operation, and return the recorded outcome on a duplicate. The other batch operations can report `424`, while the failing create reports `409`. Define recovery after a timeout where commit success is unknown. [Batch failure handling](https://learn.microsoft.com/en-us/azure/cosmos-db/transactional-batch).

## Entra External ID on Android

Microsoft has an Android native-authentication tutorial for email OTP sign-in and a separate tutorial for obtaining access tokens for custom API scopes. The proposed combination is documented. Use an external customer tenant and its native-authentication setup; the workforce B2B guest OTP feature is a different scenario. [Android email OTP](https://learn.microsoft.com/en-us/entra/identity-platform/tutorial-native-authentication-android-sign-in-sign-out), [Android protected API access](https://learn.microsoft.com/en-us/entra/identity-platform/tutorial-native-authentication-android-sign-in-call-api), [external-tenant native authentication](https://learn.microsoft.com/en-us/entra/identity-platform/concept-native-authentication).

Bun Do still needs an integration proof covering app/API registrations, delegated scope, native-authentication enablement, both users' sign-up and sign-in, token refresh and API rejection paths. Validate issuer, audience, expiry and scope before workspace membership checks. Use the validated issuer and stable subject for identity mapping. Invitation email matching needs a documented verified-identity claim or explicit invitation redemption, rather than trusting client-supplied email. These are recommended application acceptance criteria, not claims that the tenant has been configured.

## Functions runtime

Replace ".NET 8+ isolated" with a pinned supported target. Functions 4.x supports .NET 10 isolated, including Linux Flex Consumption. .NET 10 cannot run on the older Linux Consumption plan. Microsoft's minimum packages for .NET 10 are `Microsoft.Azure.Functions.Worker` 2.50.0 and `Microsoft.Azure.Functions.Worker.Sdk` 2.0.5. Pin compatible released versions and the SDK when creating the project. [Isolated worker support](https://learn.microsoft.com/en-us/azure/azure-functions/dotnet-isolated-process-guide#supported-versions).

## Deployment evidence still required

Public research can close the research ticket. Release readiness requires later evidence:

- Record subscription, region, deployment type, deployed model/version, quota and managed-identity permissions. Verify sponsored billing eligibility separately.
- Send the complete extraction, clarification and split schemas to the actual Luna deployment; test Terra only if enabled. Capture accepted parameters and handling of invalid schemas, refusals, output limits and throttling.
- Run simultaneous mutations through separate Functions instances. Prove revision uniqueness, complete change retrieval, idempotent retry after uncertain commit and rollback at transaction bounds.
- Complete email OTP and custom API access on both Android devices, then test expired tokens, wrong audience/scope and removed membership.
- Deploy a minimal .NET 10 isolated Function on the selected Flex region and record the toolchain and cold-start behavior.

These checks do not reduce the full v1 scope. They determine whether the selected infrastructure can support it before feature implementation depends on assumptions.
