targetScope = 'resourceGroup'

// Owns telemetry retention and ingestion policy. Budget alerts are owner-skipped.
@allowed(['swedencentral'])
param location string = 'swedencentral'
param name string

resource workspace 'Microsoft.OperationalInsights/workspaces@2025-07-01' = {
  name: '${name}-logs'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
    workspaceCapping: {
      // 100 MiB expressed in GiB. ARM accepts a fractional JSON number.
      dailyQuotaGb: json('0.09765625')
    }
    features: {
      enableLogAccessUsingOnlyResourcePermissions: true
      disableLocalAuth: true
      immediatePurgeDataOn30Days: true
    }
  }
}

// App tables otherwise retain 90 days even in a 30-day workspace.
resource emittedTables 'Microsoft.OperationalInsights/workspaces/tables@2025-07-01' = [for table in ['AppDependencies', 'AppExceptions', 'AppRequests']: {
  parent: workspace
  name: table
  properties: {
    retentionInDays: 30
    totalRetentionInDays: 30
  }
  dependsOn: [insights]
}]

resource insights 'Microsoft.Insights/components@2020-02-02' = {
  name: name
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: workspace.id
    RetentionInDays: 30
    DisableLocalAuth: true
    IngestionMode: 'LogAnalytics'
  }
}

// Azure auto-creates this rule and may attach another project's action group.
// Own it explicitly so the isolated dev stack sends no cross-project alerts.
resource failureAnomalies 'microsoft.alertsManagement/smartDetectorAlertRules@2021-04-01' = {
  name: 'Failure Anomalies - ${name}'
  location: 'global'
  properties: {
    description: 'Failure anomaly notifications are disabled for the isolated Bun Do development stack.'
    state: 'Disabled'
    severity: 'Sev3'
    frequency: 'PT1M'
    detector: { id: 'FailureAnomaliesDetector' }
    scope: [insights.id]
    actionGroups: { groupIds: [] }
  }
}

output insightsName string = insights.name
output connectionString string = insights.properties.ConnectionString
output workspaceName string = workspace.name
