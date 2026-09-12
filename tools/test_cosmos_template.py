"""Check the compiled Cosmos template without contacting Azure."""

import json
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("bicep"), "Cosmos template checks require Bicep CLI")
class CosmosTemplateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        build = subprocess.run(
            ["bicep", "build", str(ROOT / "infra/modules/workspace-store.bicep"), "--stdout"],
            check=True,
            capture_output=True,
            text=True,
        )
        cls.template = json.loads(build.stdout)

    def resource(self, resource_type):
        matches = [
            item for item in self.template["resources"]
            if item["type"] == resource_type
        ]
        self.assertEqual(len(matches), 1, f"Expected exactly one {resource_type}")
        return matches[0]

    def account(self):
        return self.resource("Microsoft.DocumentDB/databaseAccounts")["properties"]

    def test_one_strong_region_without_multiwrite(self):
        properties = self.account()
        self.assertEqual(properties["consistencyPolicy"]["defaultConsistencyLevel"], "Strong")
        self.assertEqual(properties["locations"], [{
            "locationName": "[parameters('location')]",
            "failoverPriority": 0,
            "isZoneRedundant": False,
        }])
        self.assertFalse(properties["enableAutomaticFailover"])
        self.assertFalse(properties["enableMultipleWriteLocations"])
        self.assertEqual(
            self.template["parameters"]["location"]["defaultValue"], "swedencentral"
        )
        self.assertEqual(
            self.resource("Microsoft.DocumentDB/databaseAccounts")["kind"], "GlobalDocumentDB"
        )

    def test_free_tier_and_manual_shared_throughput_are_bounded(self):
        properties = self.account()
        self.assertIs(properties["enableFreeTier"], True)
        self.assertEqual(properties["capacity"], {"totalThroughputLimit": 1000})
        database = self.resource("Microsoft.DocumentDB/databaseAccounts/sqlDatabases")
        self.assertEqual(database["properties"]["options"], {"throughput": 400})
        container = self.resource(
            "Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers"
        )
        self.assertEqual(container["properties"]["options"], {})
        self.assertNotIn("EnableServerless", json.dumps(properties))

    def test_retention_needs_revisioned_maintenance_not_ttl(self):
        self.assertEqual(self.account()["backupPolicy"], {
            "type": "Continuous",
            "continuousModeProperties": {"tier": "Continuous30Days"},
        })
        container = self.resource(
            "Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers"
        )["properties"]["resource"]
        self.assertNotIn("defaultTtl", container)
        self.assertNotIn("analyticalStorageTtl", container)

    def test_workspace_is_the_only_transaction_partition(self):
        variables = self.template["variables"]
        self.assertEqual(variables["databaseName"], "bun-do")
        self.assertEqual(variables["containerName"], "workspace-items")
        container = self.resource(
            "Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers"
        )["properties"]["resource"]
        self.assertEqual(container["id"], "[variables('containerName')]")
        self.assertEqual(container["partitionKey"], {
            "paths": ["/workspaceId"], "kind": "Hash", "version": 2,
        })

    def test_only_identity_auth_and_no_query_body_diagnostics(self):
        properties = self.account()
        self.assertIs(properties["disableLocalAuth"], True)
        self.assertIs(properties["disableKeyBasedMetadataWriteAccess"], True)
        self.assertEqual(properties["minimalTlsVersion"], "Tls12")
        self.assertEqual(properties["networkAclBypass"], "None")
        self.assertNotIn("diagnosticLogSettings", properties)
        self.assertFalse(any(
            item["type"].lower().endswith("/diagnosticsettings")
            for item in self.template["resources"]
        ))
        self.assertNotIn("listKeys", json.dumps(self.template))

    def test_access_contract_identifies_container_without_granting_an_unknown_identity(self):
        self.assertFalse(any(
            item["type"] == "Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments"
            for item in self.template["resources"]
        ))
        variables = self.template["variables"]
        self.assertEqual(variables["dataContributorRoleId"], "00000000-0000-0000-0000-000000000002")
        self.assertEqual(
            variables["containerScope"],
            "[format('{0}/dbs/{1}/colls/{2}', resourceId('Microsoft.DocumentDB/databaseAccounts', "
            "parameters('accountName')), variables('databaseName'), variables('containerName'))]",
        )
        self.assertNotIn("backendPrincipalId", self.template["parameters"])


if __name__ == "__main__":
    unittest.main()
