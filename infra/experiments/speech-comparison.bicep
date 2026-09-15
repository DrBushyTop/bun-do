targetScope = 'resourceGroup'

// Explicit opt-in experiment, not part of main.bicep or the application runtime.
// Deploy only to rg-bun-do-dev-swc after inspecting what-if. Owner approved
// European regions and sending test audio to Azure on September 12, 2026.
param openAiAccountName string
param speechAccountName string
param testerPrincipalId string

resource openAi 'Microsoft.CognitiveServices/accounts@2025-06-01' existing = {
  name: openAiAccountName
}

resource transcription 'Microsoft.CognitiveServices/accounts/deployments@2025-06-01' = {
  parent: openAi
  name: 'bun-do-speech-file-test'
  sku: { name: 'GlobalStandard', capacity: 10 }
  properties: {
    model: { format: 'OpenAI', name: 'gpt-transcribe', version: '2026-07-28' }
    versionUpgradeOption: 'NoAutoUpgrade'
    raiPolicyName: 'Microsoft.Default'
  }
}

resource liveTranscription 'Microsoft.CognitiveServices/accounts/deployments@2025-06-01' = {
  parent: openAi
  name: 'bun-do-speech-live-test'
  // Azure rejected simultaneous deployment writes against this account.
  dependsOn: [transcription]
  sku: { name: 'GlobalStandard', capacity: 10 }
  properties: {
    model: { format: 'OpenAI', name: 'gpt-live-transcribe', version: '2026-07-28' }
    versionUpgradeOption: 'NoAutoUpgrade'
    raiPolicyName: 'Microsoft.Default'
  }
}

// The normal foundation owns this account. Experiments may only grant test access.
resource speech 'Microsoft.CognitiveServices/accounts@2025-06-01' existing = {
  name: speechAccountName
}

var openAiUserRole = '5e0bd9bd-7b93-4f28-af87-19fc36ad61bd'
resource testOpenAiAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(openAi.id, testerPrincipalId, openAiUserRole)
  scope: openAi
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', openAiUserRole)
    principalId: testerPrincipalId
    principalType: 'User'
  }
}

var speechUserRole = 'f2dc8367-1007-4938-bd23-fe263f013447'
resource testSpeechAccess 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(speech.id, testerPrincipalId, speechUserRole)
  scope: speech
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', speechUserRole)
    principalId: testerPrincipalId
    principalType: 'User'
  }
}
