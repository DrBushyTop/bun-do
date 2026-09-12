targetScope = 'resourceGroup'

// Owns transient sync recovery artifacts and their disposal policy.
// Durable workspace data and Functions host/deployment blobs belong elsewhere.
@description('Globally unique name for the dedicated Bun Do snapshot account.')
@minLength(3)
@maxLength(24)
param accountName string

@allowed([
  'swedencentral'
])
param location string = 'swedencentral'

var containerName = 'sync-snapshots'

resource account 'Microsoft.Storage/storageAccounts@2025-06-01' = {
  name: accountName
  location: location
  kind: 'StorageV2'
  sku: {
    // Artifacts are disposable and rebuilt from Cosmos after loss.
    name: 'Standard_LRS'
  }
  properties: {
    accessTier: 'Hot'
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    allowCrossTenantReplication: false
    supportsHttpsTrafficOnly: true
    minimumTlsVersion: 'TLS1_2'
    isHnsEnabled: false
    isNfsV3Enabled: false
    isSftpEnabled: false
    // Private data, not a private endpoint. The backend uses managed identity.
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      bypass: 'None'
      defaultAction: 'Allow'
      ipRules: []
      virtualNetworkRules: []
    }
    encryption: {
      keySource: 'Microsoft.Storage'
      services: {
        blob: {
          enabled: true
          keyType: 'Account'
        }
      }
    }
  }
}

resource blobs 'Microsoft.Storage/storageAccounts/blobServices@2025-06-01' = {
  parent: account
  name: 'default'
  properties: {
    // Recovery of these temporary copies would outlive application expiry.
    isVersioningEnabled: false
    deleteRetentionPolicy: {
      enabled: false
    }
    containerDeleteRetentionPolicy: {
      enabled: false
    }
    restorePolicy: {
      enabled: false
    }
    changeFeed: {
      enabled: false
    }
    cors: {
      corsRules: []
    }
  }
}

resource snapshots 'Microsoft.Storage/storageAccounts/blobServices/containers@2025-06-01' = {
  parent: blobs
  name: containerName
  properties: {
    publicAccess: 'None'
  }
}

resource orphanCleanup 'Microsoft.Storage/storageAccounts/managementPolicies@2025-06-01' = {
  parent: account
  name: 'default'
  properties: {
    policy: {
      rules: [
        {
          name: 'discard-orphaned-sync-artifacts'
          enabled: true
          type: 'Lifecycle'
          definition: {
            filters: {
              blobTypes: [
                'blockBlob'
              ]
              prefixMatch: [
                '${containerName}/'
              ]
            }
            actions: {
              baseBlob: {
                delete: {
                  daysAfterModificationGreaterThan: 1
                }
              }
              // Also clean accidental native Blob snapshots/old versions.
              snapshot: {
                delete: {
                  daysAfterCreationGreaterThan: 1
                }
              }
              version: {
                delete: {
                  daysAfterCreationGreaterThan: 1
                }
              }
            }
          }
        }
      ]
    }
  }
}

// The API must reject expired artifacts and pins after 30 minutes, recheck
// identity/membership/epoch on every read, and explicitly delete expired blobs.
// Delayed lifecycle cleanup is an orphan fallback, not an authorization timer.
// Backend hosting owns its container-scoped identity grant. Never issue SAS URLs.
output accountName string = account.name
output blobEndpoint string = account.properties.primaryEndpoints.blob
output containerName string = snapshots.name
output containerId string = snapshots.id
