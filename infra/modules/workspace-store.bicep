targetScope = 'resourceGroup'

// Owns the durable shared workspace store, not a generic database account wrapper.
@description('Globally unique name for the dedicated Bun Do Cosmos DB account.')
@minLength(3)
@maxLength(44)
param accountName string

@description('The single region used by the development stack.')
param location string = 'swedencentral'

var databaseName = 'bun-do'
var containerName = 'workspace-items'
var dataContributorRoleId = '00000000-0000-0000-0000-000000000002'

resource account 'Microsoft.DocumentDB/databaseAccounts@2025-04-15' = {
  name: accountName
  location: location
  kind: 'GlobalDocumentDB'
  properties: {
    databaseAccountOfferType: 'Standard'
    // Fail rather than silently creating a paid-throughput account when unavailable.
    enableFreeTier: true
    capacity: {
      totalThroughputLimit: 1000
    }
    consistencyPolicy: {
      defaultConsistencyLevel: 'Strong'
    }
    locations: [
      {
        locationName: location
        failoverPriority: 0
        isZoneRedundant: false
      }
    ]
    enableAutomaticFailover: false
    enableMultipleWriteLocations: false
    disableLocalAuth: true
    disableKeyBasedMetadataWriteAccess: true
    minimalTlsVersion: 'Tls12'
    // Identity protects the public endpoint; a private endpoint is not provisioned.
    publicNetworkAccess: 'Enabled'
    networkAclBypass: 'None'
    backupPolicy: {
      type: 'Continuous'
      continuousModeProperties: {
        // This retention requirement has separate backup-storage charges.
        tier: 'Continuous30Days'
      }
    }
  }
}

resource database 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases@2025-04-15' = {
  parent: account
  name: databaseName
  properties: {
    resource: {
      id: databaseName
    }
    options: {
      throughput: 400
    }
  }
}

resource workspaceItems 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers@2025-04-15' = {
  parent: database
  name: containerName
  properties: {
    resource: {
      id: containerName
      partitionKey: {
        paths: [
          '/workspaceId'
        ]
        kind: 'Hash'
        version: 2
      }
      // No TTL. Revisioned maintenance owns retention, acknowledgements and pins.
    }
    options: {}
  }
}

var containerScope = '${account.id}/dbs/${databaseName}/colls/${containerName}'
var dataContributorRoleDefinitionId = '${account.id}/sqlRoleDefinitions/${dataContributorRoleId}'

// The backend-hosting module will grant its identity access to these outputs.
// Passing that identity back into this module would create a deployment cycle.
// No diagnostic export enables query text or request/response body collection.
output accountId string = account.id
output accountName string = account.name
output endpoint string = account.properties.documentEndpoint
output databaseName string = database.name
output containerName string = workspaceItems.name
output containerScope string = containerScope
output dataContributorRoleDefinitionId string = dataContributorRoleDefinitionId
