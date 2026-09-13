# Transient sync snapshots

`modules/snapshot-artifacts.bicep` owns the Blob account, private
`sync-snapshots` container and orphan cleanup policy for the
[sync recovery contract](../docs/architecture/sync-protocol.md).
It does not hold canonical workspace data, Functions host state or deployment
packages. Those resources have different lifetimes.

The module exports `accountName`, `blobEndpoint`, `containerName` and `containerId`.
Backend hosting must assign its managed identity Blob data access at
`containerId`, not at account or resource-group scope. The module creates no role
assignment and returns no credentials. Pinning grants to the consumer avoids a
dependency cycle between its identity and its storage.

## Access and expiry

The account disables anonymous Blob access, Shared Key authorization, NFS and
SFTP. It requires HTTPS and TLS 1.2. Its network endpoint remains public with
identity authorization, not a private endpoint. The container has no public
read/list permission and the Blob service has no CORS rules. CORS is not an
authorization control.

The application must serve all chunks through authenticated API reads. Before
every read, it must validate current membership, device, epoch and the artifact's
30-minute expiry. It must expire pruning pins and delete expired or abandoned
candidate blobs itself. No client may get a Blob URL with a SAS token, even when
that token would expire in 30 minutes. That would bypass membership revocation.

Disabling Shared Key blocks account and service SAS but **does not block user
delegation SAS**. A Blob data role scoped only to this container does not grant
the account-level permission needed to generate a user delegation key. Do not
grant the backend that permission through an account, resource group or
subscription role. Operators with broader rights remain an administrative trust
boundary.

The one-day lifecycle rule only limits abandoned data left behind by failed
application cleanup. It covers block blobs under `sync-snapshots/`, including
accidental native Blob snapshots or old versions. One day is its eligibility
threshold, not a guaranteed physical-deletion deadline. Azure evaluates the
policy asynchronously. The module alone does not implement 30-minute expiry,
authorization, workspace capacity limits or the two-candidate concurrency limit.

Blob and container soft delete, versioning, point-in-time restore and change feed
are explicitly off. These artifacts are disposable copies of Cosmos data.
Enabling automatic retention would preserve household content after application
deletion. The application must not create native Blob snapshots. If an existing
account previously enabled soft delete, disabling it does not erase already
retained objects. Inspect and resolve that history before reusing the account.

## Cost and verification

The account uses Standard LRS, Hot tier, in Sweden Central. A lost artifact can be
rebuilt from Cosmos; it does not need geo-replication or a backup policy. Blob
capacity and operations have separate costs from Cosmos free tier. There is no
paid-tier fallback, reservation or fixed capacity provisioned by this module.

Compile `infra/main.bicep`; Bicep owns the resource settings. Do not add a second
set of configuration assertions or a policy-readback checklist.

Before serving snapshots, verify that anonymous and Shared Key reads fail, that
the backend can read/write/delete only its container using managed identity,
and that expired artifacts and removed members cannot read through the API.
Exercise failed candidate cleanup and confirm that deletion leaves no retained
version or soft-deleted copy. These future integration/e2e checks belong to the
sync/recovery slices and must exercise their production adapters, not test-only
clients. They are not a requirement to rebuild the removed infrastructure gates now.

## Microsoft references

Checked September 12, 2026:

- [Storage account API, pinned to 2025-06-01](https://learn.microsoft.com/en-us/azure/templates/microsoft.storage/2025-06-01/storageaccounts)
- [Blob service retention settings](https://learn.microsoft.com/en-us/azure/templates/microsoft.storage/2025-06-01/storageaccounts/blobservices)
- [Lifecycle policy API](https://learn.microsoft.com/en-us/azure/templates/microsoft.storage/2025-06-01/storageaccounts/managementpolicies)
- [Lifecycle execution delays and billing](https://learn.microsoft.com/en-us/azure/storage/blobs/lifecycle-management-overview)
- [Shared Key controls and user delegation SAS exception](https://learn.microsoft.com/en-us/azure/storage/common/shared-key-authorization-prevent)
- [User delegation key authorization scopes](https://learn.microsoft.com/en-us/rest/api/storageservices/create-user-delegation-sas)
- [Existing soft-deleted objects survive disabling soft delete](https://learn.microsoft.com/en-us/azure/storage/blobs/soft-delete-blob-overview)
