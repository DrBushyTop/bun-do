targetScope = 'resourceGroup'

// Owns the Function runtime, its storage, identity and consumer-side grants.
param appName string
param runtimeStorageName string
param insightsName string
param telemetryConnectionString string
@allowed(['swedencentral'])
param location string = 'swedencentral'
param cosmosAccountName string
param cosmosEndpoint string
param databaseName string
param containerName string
param cosmosContainerScope string
param cosmosDataContributorRoleDefinitionId string
param snapshotAccountName string
param snapshotContainerName string
param snapshotBlobEndpoint string
param aiAccountName string
param aiEndpoint string
param lunaDeployment string
param terraDeployment string

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: '${appName}-identity'
  location: location
}

resource runtimeStorage 'Microsoft.Storage/storageAccounts@2025-06-01' = {
  name: runtimeStorageName
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
    networkAcls: {
      bypass: 'None'
      defaultAction: 'Allow'
    }
  }
}

resource runtimeBlobs 'Microsoft.Storage/storageAccounts/blobServices@2025-06-01' = {
  parent: runtimeStorage
  name: 'default'
  properties: {
    isVersioningEnabled: false
    deleteRetentionPolicy: { enabled: false }
    containerDeleteRetentionPolicy: { enabled: false }
    cors: { corsRules: [] }
  }
}

resource packages 'Microsoft.Storage/storageAccounts/blobServices/containers@2025-06-01' = {
  parent: runtimeBlobs
  name: 'function-packages'
  properties: { publicAccess: 'None' }
}

// Host storage must create its own containers and manage host locks/secrets.
// This account contains no workspace or snapshot content. HTTP/timer hosting
// needs Blob Data Owner; add other roles only for a concrete future binding.
resource runtimeAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(runtimeStorage.id, identity.id, 'host-blob-owner')
  scope: runtimeStorage
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'b7e6dc6d-f1e8-4753-8033-0f276bb0955b')
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource snapshots 'Microsoft.Storage/storageAccounts/blobServices/containers@2025-06-01' existing = {
  name: '${snapshotAccountName}/default/${snapshotContainerName}'
}

resource snapshotAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(snapshots.id, identity.id, 'snapshot-blob-contributor')
  scope: snapshots
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'ba92f5b4-2d11-453d-a403-e96b0029c9fe')
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource cosmos 'Microsoft.DocumentDB/databaseAccounts@2025-04-15' existing = {
  name: cosmosAccountName
}

resource workspaceAccess 'Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments@2025-04-15' = {
  parent: cosmos
  name: guid(cosmosContainerScope, identity.id, 'workspace-data-contributor')
  properties: {
    roleDefinitionId: cosmosDataContributorRoleDefinitionId
    principalId: identity.properties.principalId
    scope: cosmosContainerScope
  }
}

resource insights 'Microsoft.Insights/components@2020-02-02' existing = {
  name: insightsName
}

resource telemetryAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(insights.id, identity.id, 'telemetry-publisher')
  scope: insights
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '3913510d-42f4-4e42-8a64-420c390055eb')
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource ai 'Microsoft.CognitiveServices/accounts@2025-06-01' existing = {
  name: aiAccountName
}

resource inferenceAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(ai.id, identity.id, 'openai-user')
  scope: ai
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '5e0bd9bd-7b93-4f28-af87-19fc36ad61bd')
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource plan 'Microsoft.Web/serverfarms@2024-04-01' = {
  name: '${appName}-plan'
  location: location
  sku: {
    name: 'FC1'
    tier: 'FlexConsumption'
  }
  properties: {
    reserved: true
    zoneRedundant: false
  }
}

resource app 'Microsoft.Web/sites@2024-04-01' = {
  name: appName
  location: location
  kind: 'functionapp,linux'
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${identity.id}': {} }
  }
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    publicNetworkAccess: 'Enabled'
    functionAppConfig: {
      runtime: {
        name: 'dotnet-isolated'
        version: '10.0'
      }
      deployment: {
        storage: {
          type: 'blobContainer'
          value: '${runtimeStorage.properties.primaryEndpoints.blob}${packages.name}'
          authentication: {
            type: 'UserAssignedIdentity'
            userAssignedIdentityResourceId: identity.id
          }
        }
      }
      scaleAndConcurrency: {
        maximumInstanceCount: 2
        instanceMemoryMB: 512
        alwaysReady: []
      }
    }
    siteConfig: {
      minTlsVersion: '1.2'
      scmMinTlsVersion: '1.2'
      ftpsState: 'Disabled'
      appSettings: [
        { name: 'AzureWebJobsStorage__credential', value: 'managedidentity' }
        { name: 'AzureWebJobsStorage__clientId', value: identity.properties.clientId }
        { name: 'AzureWebJobsStorage__accountName', value: runtimeStorage.name }
        { name: 'AZURE_CLIENT_ID', value: identity.properties.clientId }
        { name: 'WorkspaceStore__Endpoint', value: cosmosEndpoint }
        { name: 'WorkspaceStore__DatabaseName', value: databaseName }
        { name: 'WorkspaceStore__ContainerName', value: containerName }
        { name: 'Snapshots__BlobEndpoint', value: snapshotBlobEndpoint }
        { name: 'Snapshots__ContainerName', value: snapshotContainerName }
        // Deliberately worker-only. The host must not export raw request logs.
        { name: 'BunDoTelemetry__ConnectionString', value: telemetryConnectionString }
        // Worker failures also travel in host RPC responses. Do not log those
        // raw exceptions through the separate Functions host logger.
        { name: 'AzureFunctionsJobHost__logging__logLevel__default', value: 'None' }
        // The pinned exporter otherwise sends a resource metric with each batch.
        { name: 'OTEL_DOTNET_AZURE_MONITOR_ENABLE_RESOURCE_METRICS', value: 'false' }
        { name: 'APPLICATIONINSIGHTS_STATSBEAT_DISABLED', value: 'true' }
        { name: 'APPLICATIONINSIGHTS_SDKSTATS_DISABLED', value: 'true' }
        { name: 'AI__Enabled', value: 'false' }
        { name: 'AI__Endpoint', value: aiEndpoint }
        { name: 'AI__LunaDeployment', value: lunaDeployment }
        { name: 'AI__TerraDeployment', value: terraDeployment }
      ]
    }
  }
  dependsOn: [runtimeAccess, snapshotAccess, workspaceAccess, telemetryAccess, inferenceAccess]
}

resource scmAuthentication 'Microsoft.Web/sites/basicPublishingCredentialsPolicies@2024-04-01' = {
  parent: app
  name: 'scm'
  properties: { allow: false }
}

resource ftpAuthentication 'Microsoft.Web/sites/basicPublishingCredentialsPolicies@2024-04-01' = {
  parent: app
  name: 'ftp'
  properties: { allow: false }
}

output appName string = app.name
output appId string = app.id
output hostname string = app.properties.defaultHostName
output identityId string = identity.id
output principalId string = identity.properties.principalId
output runtimeStorageName string = runtimeStorage.name
