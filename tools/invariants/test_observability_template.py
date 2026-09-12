"""Verify telemetry retention and identity-only access in the compiled module."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('bicep'), 'Observability checks require Bicep CLI')
class ObservabilityTemplateTests(unittest.TestCase):
    def test_retention_ingestion_and_authentication(self):
        template = json.loads(subprocess.check_output([
            'bicep', 'build', str(ROOT / 'infra/modules/observability.bicep'), '--stdout',
        ], text=True))
        resources = {r['type']: r for r in template['resources']}
        workspace = resources['Microsoft.OperationalInsights/workspaces']['properties']
        self.assertEqual(workspace['retentionInDays'], 30)
        self.assertEqual(workspace['workspaceCapping']['dailyQuotaGb'], "[json('0.09765625')]")
        self.assertTrue(workspace['features']['disableLocalAuth'])
        self.assertTrue(workspace['features']['immediatePurgeDataOn30Days'])
        table = resources['Microsoft.OperationalInsights/workspaces/tables']
        self.assertEqual(table['properties'], {'retentionInDays': 30, 'totalRetentionInDays': 30})
        self.assertIn('AppDependencies', json.dumps(table))
        self.assertIn('AppExceptions', json.dumps(table))
        self.assertIn('AppRequests', json.dumps(table))
        self.assertNotIn('AppMetrics', json.dumps(table))
        insights = resources['Microsoft.Insights/components']['properties']
        self.assertTrue(insights['DisableLocalAuth'])
        self.assertEqual(insights['RetentionInDays'], 30)
        self.assertIn('WorkspaceResourceId', insights)
        self.assertNotIn('Microsoft.Consumption/budgets', resources)
        alert = resources['microsoft.alertsManagement/smartDetectorAlertRules']
        self.assertEqual(alert['properties']['state'], 'Disabled')
        self.assertEqual(alert['properties']['actionGroups']['groupIds'], [])
        self.assertEqual(alert['properties']['scope'], [
            "[resourceId('Microsoft.Insights/components', parameters('name'))]",
        ])


if __name__ == '__main__':
    unittest.main()
