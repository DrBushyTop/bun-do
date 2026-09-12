targetScope = 'subscription'

@allowed([
  'swedencentral'
])
param location string = 'swedencentral'

@allowed([
  'rg-bun-do-dev-swc'
])
param resourceGroupName string = 'rg-bun-do-dev-swc'

resource development 'Microsoft.Resources/resourceGroups@2025-04-01' = {
  name: resourceGroupName
  location: location
  tags: {
    application: 'bun-do'
    environment: 'development'
  }
}

module workspaceStore 'modules/workspace-store.bicep' = {
  name: 'bun-do-workspace-store'
  scope: development
  params: {
    accountName: 'cos-bun-do-dev-${uniqueString(subscription().subscriptionId, resourceGroupName)}'
    location: location
  }
}

module snapshotArtifacts 'modules/snapshot-artifacts.bicep' = {
  name: 'bun-do-snapshot-artifacts'
  scope: development
  params: {
    accountName: 'stbundosnap${uniqueString(subscription().subscriptionId, resourceGroupName)}'
    location: location
  }
}

output resourceGroupName string = development.name
output cosmosAccountName string = workspaceStore.outputs.accountName
output cosmosEndpoint string = workspaceStore.outputs.endpoint
output databaseName string = workspaceStore.outputs.databaseName
output containerName string = workspaceStore.outputs.containerName
output snapshotAccountName string = snapshotArtifacts.outputs.accountName
output snapshotBlobEndpoint string = snapshotArtifacts.outputs.blobEndpoint
output snapshotContainerName string = snapshotArtifacts.outputs.containerName
