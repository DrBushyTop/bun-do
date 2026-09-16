targetScope = 'resourceGroup'

// Reusable generic illustrations are durable library assets, not expiring sync snapshots.
param accountName string
param location string
resource account 'Microsoft.Storage/storageAccounts@2025-06-01' = {
  name: accountName
  location: location
  kind: 'StorageV2'
  sku: { name: 'Standard_LRS' }
  properties: {
    accessTier: 'Hot'
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    allowCrossTenantReplication: false
    supportsHttpsTrafficOnly: true
    minimumTlsVersion: 'TLS1_2'
    publicNetworkAccess: 'Enabled'
    networkAcls: { bypass: 'None', defaultAction: 'Allow', ipRules: [], virtualNetworkRules: [] }
  }
}
resource blobs 'Microsoft.Storage/storageAccounts/blobServices@2025-06-01' = {
  parent: account
  name: 'default'
  properties: {
    isVersioningEnabled: false
    deleteRetentionPolicy: { enabled: true, days: 7 }
    containerDeleteRetentionPolicy: { enabled: true, days: 7 }
    cors: { corsRules: [] }
  }
}
resource images 'Microsoft.Storage/storageAccounts/blobServices/containers@2025-06-01' = {
  parent: blobs
  name: 'artwork'
  properties: { publicAccess: 'None' }
}
resource catalog 'Microsoft.Storage/storageAccounts/blobServices/containers@2025-06-01' = {
  parent: blobs
  name: 'catalog'
  properties: { publicAccess: 'None' }
}
output accountName string = account.name
output endpoint string = account.properties.primaryEndpoints.blob
output imagesContainer string = images.name
output catalogContainer string = catalog.name
