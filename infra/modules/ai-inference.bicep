targetScope = 'resourceGroup'

// Dedicated inference account and deployments. The consuming backend owns RBAC.
param accountName string
param speechAccountName string
@allowed(['swedencentral'])
param location string = 'swedencentral'
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

resource account 'Microsoft.CognitiveServices/accounts@2025-06-01' = {
  name: accountName
  location: location
  kind: 'OpenAI'
  sku: { name: 'S0' }
  properties: {
    customSubDomainName: accountName
    disableLocalAuth: true
    publicNetworkAccess: 'Enabled'
    networkAcls: { defaultAction: 'Allow' }
    dynamicThrottlingEnabled: false
  }
}

resource luna 'Microsoft.CognitiveServices/accounts/deployments@2025-06-01' = {
  parent: account
  name: 'bun-do-luna'
  sku: { name: lunaSku, capacity: lunaCapacity }
  properties: {
    model: { format: 'OpenAI', name: lunaModel, version: lunaVersion }
    versionUpgradeOption: 'NoAutoUpgrade'
    raiPolicyName: 'Microsoft.Default'
  }
}

resource terra 'Microsoft.CognitiveServices/accounts/deployments@2025-06-01' = if (terraEnabled) {
  parent: account
  name: 'bun-do-terra'
  sku: { name: terraSku, capacity: terraCapacity }
  properties: {
    model: { format: 'OpenAI', name: terraModel, version: terraVersion }
    versionUpgradeOption: 'NoAutoUpgrade'
    raiPolicyName: 'Microsoft.Default'
  }
}

// Image generation has an independent low allocation and explicit model upgrades.
resource artwork 'Microsoft.CognitiveServices/accounts/deployments@2025-06-01' = {
  // The provider rejects concurrent sibling deployment writes.
  dependsOn: [luna, terra]
  parent: account
  name: 'bun-do-artwork'
  sku: { name: 'GlobalStandard', capacity: 3 }
  properties: {
    model: { format: 'OpenAI', name: 'gpt-image-2', version: '2026-04-21' }
    versionUpgradeOption: 'NoAutoUpgrade'
    raiPolicyName: 'Microsoft.Default'
  }
}
output artworkDeployment string = artwork.name

output accountName string = account.name
output endpoint string = 'https://${account.properties.customSubDomainName}.openai.azure.com/openai/v1/'
output lunaDeployment string = luna.name
output terraDeployment string = terraEnabled ? terra!.name : ''

// Retain the owner-approved North Europe Speech account and its resource identity.
resource speech 'Microsoft.CognitiveServices/accounts@2025-06-01' = {
  name: speechAccountName
  location: 'northeurope'
  kind: 'AIServices'
  sku: { name: 'S0' }
  tags: { application: 'bun-do', environment: 'development' }
  properties: {
    customSubDomainName: speechAccountName
    disableLocalAuth: true
    publicNetworkAccess: 'Enabled'
    networkAcls: { defaultAction: 'Allow' }
  }
}

output speechAccountName string = speech.name
output speechEndpoint string = 'https://${speech.properties.customSubDomainName}.cognitiveservices.azure.com'
