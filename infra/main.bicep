targetScope = 'subscription'

@allowed([
  'swedencentral'
])
param location string = 'swedencentral'

@allowed([
  'rg-bun-do-dev-swc'
])
param resourceGroupName string = 'rg-bun-do-dev-swc'

param lunaModel string
param lunaVersion string
@allowed(['DataZoneStandard', 'GlobalStandard'])
param lunaSku string
@minValue(1)
param lunaCapacity int
param terraEnabled bool = false
param terraModel string
param terraVersion string
@allowed(['DataZoneStandard', 'GlobalStandard'])
param terraSku string
@minValue(1)
param terraCapacity int

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

module observability 'modules/observability.bicep' = {
  name: 'bun-do-observability'
  scope: development
  params: {
    name: 'appi-bun-do-dev'
    location: location
  }
}

module aiInference 'modules/ai-inference.bicep' = {
  name: 'bun-do-ai-inference'
  scope: development
  params: {
    accountName: 'ai-bun-do-dev-${uniqueString(subscription().subscriptionId, resourceGroupName)}'
    location: location
    lunaModel: lunaModel
    lunaVersion: lunaVersion
    lunaSku: lunaSku
    lunaCapacity: lunaCapacity
    terraEnabled: terraEnabled
    terraModel: terraModel
    terraVersion: terraVersion
    terraSku: terraSku
    terraCapacity: terraCapacity
  }
}

module backendHosting 'modules/backend-hosting.bicep' = {
  name: 'bun-do-backend-hosting'
  scope: development
  params: {
    appName: 'func-bun-do-dev-${uniqueString(subscription().subscriptionId, resourceGroupName)}'
    insightsName: observability.outputs.insightsName
    telemetryConnectionString: observability.outputs.connectionString
    runtimeStorageName: 'stbundohost${uniqueString(subscription().subscriptionId, resourceGroupName)}'
    location: location
    cosmosAccountName: workspaceStore.outputs.accountName
    cosmosEndpoint: workspaceStore.outputs.endpoint
    databaseName: workspaceStore.outputs.databaseName
    containerName: workspaceStore.outputs.containerName
    cosmosContainerScope: workspaceStore.outputs.containerScope
    cosmosDataContributorRoleDefinitionId: workspaceStore.outputs.dataContributorRoleDefinitionId
    snapshotAccountName: snapshotArtifacts.outputs.accountName
    snapshotContainerName: snapshotArtifacts.outputs.containerName
    snapshotBlobEndpoint: snapshotArtifacts.outputs.blobEndpoint
    aiAccountName: aiInference.outputs.accountName
    aiEndpoint: aiInference.outputs.endpoint
    lunaDeployment: aiInference.outputs.lunaDeployment
    terraDeployment: aiInference.outputs.terraDeployment
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
output functionAppName string = backendHosting.outputs.appName
output functionHostname string = backendHosting.outputs.hostname
output backendPrincipalId string = backendHosting.outputs.principalId
output runtimeStorageName string = backendHosting.outputs.runtimeStorageName
output aiAccountName string = aiInference.outputs.accountName
output aiEndpoint string = aiInference.outputs.endpoint
