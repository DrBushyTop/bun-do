# Snapshot foundation adversarial review

Reviewed September 12, 2026. This review covers the transient snapshot storage
module, its root integration, compiled-template tests, deployment guard and
infrastructure workflow. It does not cover Android or claim that the snapshot
API exists.

## Verdict

No material finding in this bounded infrastructure increment. No deferred bug
ticket is needed from this review. Repository invariants and all 46 Python tests
passed.

`snapshot-artifacts.bicep` groups resources by a real application responsibility.
It owns disposable recovery copies and their retention policy, separate from
durable workspace data and Functions runtime storage. The container-scoped grant
belongs to the backend module that owns the identity. No generic storage wrapper
or circular module dependency was introduced.

The template disables anonymous Blob access and Shared Key authorization,
requires HTTPS and TLS 1.2, and declares a private container. The public network
endpoint does not imply public data. There is no backend role assignment yet.
Soft delete, versioning, point-in-time restore and change feed are explicitly
disabled for these temporary copies.

The lifecycle policy includes base blobs, versions and native snapshots under
`sync-snapshots/`. Including versions and snapshots matters because Azure will
not delete a current blob while those copies remain. One day is an eligibility
threshold, not a deletion deadline. This policy cannot implement the protocol's
30-minute expiry. The module note correctly assigns expiry checks, pin expiry
and explicit candidate cleanup to the future application.

Disabling Shared Key does not block user delegation SAS. The documentation
correctly prohibits issuing SAS URLs and requires a container-scoped backend
grant. Generating a user delegation key requires permission at account scope or
above. Broader inherited grants must also be absent for that restriction to hold.
This needs live verification when the backend identity is created.

## Reviewed deployment plan

The actual provider what-if succeeded with eight resources, all inside
`rg-bun-do-dev-swc`:

- The existing resource group has no change.
- The existing Cosmos account, database and container have three modifications.
  Their deltas match the provider-default differences recorded in the preceding
  Cosmos review.
- The snapshot account, Blob service, private container and lifecycle policy
  are four creates.

The saved plan digest and compiled template/parameter digest match the stamp.
The new account is `stbundosnapqrquvcgmhocc6`, Standard LRS and Hot tier, in Sweden
Central. The plan shows the expected authentication, transport and retention
settings. It contains no deletion, unsupported change or out-of-group effect.
The deployment guard rejects those cases and does not deploy from CI.

The provider's what-if payload omits some explicit empty/default values,
including Blob encryption service settings, empty network rule arrays and empty
CORS rules. Compilation verifies those requested values, not their final live
state. Read them back after deployment along with the account, container and
lifecycle configuration.

Proceeding with this controlled deployment is reasonable. Repeat the existing
Cosmos database/container RID, policy and manual 400 RU/s checks afterward.
The three Cosmos modifications are not proof of a zero-drift template and must
not be described as universally harmless read-only fields.

## Controlled redeployment review

The post-deployment what-if again inspected eight resources in the dedicated
group. The group, snapshot account and lifecycle policy have no change. The
three Cosmos deltas match the previous review. Two snapshot resources report
modifications to omitted provider defaults:

- The Blob service omits `deleteRetentionPolicy.allowPermanentDelete: false`.
  This controls permanent deletion of soft-deleted versions and snapshots.
  Blob soft delete remains explicitly disabled. Omitting this flag does not
  request a new retention period.
- The Blob service omits `staticWebsite: {enabled: false}`. This property is
  absent from the pinned ARM Blob service template schema. Read its live state
  after redeployment rather than claiming what-if proves preservation.
- The container omits `defaultEncryptionScope: $account-encryption-key` and
  `denyEncryptionScopeOverride: false`. These are writable container properties,
  not universally harmless read-only fields. No custom encryption scope exists
  in this module, and account Blob encryption remains enabled.

No material blocker remains for a controlled redeployment with the requested
read-back. Compare these four values and the account creation time with the
first-deployment record. Repeat the core storage policy checks and Cosmos
identity, policy and throughput checks.

Static website state deserves an explicit check. Azure's account-level anonymous
Blob restriction does not disable a configured static website endpoint, whose
content comes from `$web`. This module creates only the separate
`sync-snapshots` container, and the current static website setting is disabled.
Do not infer that setting from `allowBlobPublicAccess: false` alone.

The saved plan and source digests match. Repository invariants and all 46 Python
tests passed again. The reviewer performed no cloud mutation.

## Remaining live gates

The reviewer performed no Azure mutation. The implementer deployed the reviewed
plan on September 12, 2026. Live management-plane reads matched all declared
storage controls, including the encryption service, empty network rules and
empty CORS values omitted from what-if. Cosmos database/container RIDs,
selected account/container policy objects and manual 400 RU/s remained unchanged.
An anonymous container-list request returned HTTP 401 with
`NoAuthenticationInformation`.

Private evidence is saved under `.azure/foundation/` in
`snapshot-deployment-readback.json`, `snapshot-deployment-verification.json`
and `snapshot-anonymous-list.json`. The first-deployment records are also kept
under `history/snapshot-first-deploy/`.

The controlled redeployment subsequently succeeded on the same date. Follow-up
reads preserved the storage account creation time, account/container resource
IDs and all checked policies. The omitted defaults were unchanged:
`allowPermanentDelete=false`, `staticWebsite.enabled=false`,
`defaultEncryptionScope=$account-encryption-key` and
`denyEncryptionScopeOverride=false`. Cosmos RIDs, selected policies and manual
400 RU/s were unchanged again. This does not prove preservation of real snapshot
content; no application identity or content was created for this increment.

Before serving real snapshots, prove that anonymous and Shared Key reads fail,
that the managed identity can access only the intended container, and that the
API denies expired artifacts and removed members. Prove explicit deletion of
abandoned candidates without retained copies. The 4 MiB chunk bound, 1 GiB
workspace bound, two-candidate limit, revision fencing and 30-minute pin expiry
remain runtime work in the sync/recovery slices.

Do not close the full infrastructure slice on this increment. Functions,
identity grants, observability, budget alerts and model checks remain separate
work. Blob capacity and operations also have costs outside Cosmos free tier.

## Primary-source checks

Microsoft documentation checked during this review:

- [Storage account schema, API 2025-06-01](https://learn.microsoft.com/en-us/azure/templates/microsoft.storage/2025-06-01/storageaccounts)
- [Lifecycle policy schema, API 2025-06-01](https://learn.microsoft.com/en-us/azure/templates/microsoft.storage/2025-06-01/storageaccounts/managementpolicies)
- [Lifecycle policy execution and billing](https://learn.microsoft.com/en-us/azure/storage/blobs/lifecycle-management-overview)
- [Lifecycle filter and version deletion rules](https://learn.microsoft.com/en-us/azure/storage/blobs/lifecycle-management-policy-structure)
- [Shared Key denial and the user delegation SAS exception](https://learn.microsoft.com/en-us/azure/storage/common/shared-key-authorization-prevent)
- [User delegation key authorization scope](https://learn.microsoft.com/en-us/rest/api/storageservices/create-user-delegation-sas)
- [Anonymous Blob access prevention](https://learn.microsoft.com/en-us/azure/storage/blobs/anonymous-read-access-prevent)
- [Previously soft-deleted data survives disabling soft delete](https://learn.microsoft.com/en-us/azure/storage/blobs/soft-delete-blob-overview)
- [Blob service schema, API 2025-06-01](https://learn.microsoft.com/en-us/azure/templates/microsoft.storage/2025-06-01/storageaccounts/blobservices)
- [Container encryption properties, API 2025-06-01](https://learn.microsoft.com/en-us/azure/templates/microsoft.storage/2025-06-01/storageaccounts/blobservices/containers)
- [Container encryption scope behavior](https://learn.microsoft.com/en-us/azure/storage/blobs/encryption-scope-overview)
- [Static website anonymous-access behavior](https://learn.microsoft.com/en-us/azure/storage/blobs/storage-blob-static-website)
