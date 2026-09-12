"""Check transient snapshot storage without contacting Azure."""

import json
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("bicep"), "Snapshot template checks require Bicep CLI")
class SnapshotTemplateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        build = subprocess.run(
            ["bicep", "build", str(ROOT / "infra/modules/snapshot-artifacts.bicep"), "--stdout"],
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

    def test_disposable_artifacts_use_single_region_hot_lrs(self):
        account = self.resource("Microsoft.Storage/storageAccounts")
        self.assertEqual(account["kind"], "StorageV2")
        self.assertEqual(account["sku"], {"name": "Standard_LRS"})
        self.assertEqual(account["properties"]["accessTier"], "Hot")
        self.assertEqual(account["location"], "[parameters('location')]")
        self.assertEqual(self.template["parameters"]["location"]["allowedValues"], ["swedencentral"])
        self.assertEqual(self.template["parameters"]["location"]["defaultValue"], "swedencentral")
        self.assertFalse(account["properties"]["allowCrossTenantReplication"])

    def test_private_blob_data_requires_identity_and_tls(self):
        properties = self.resource("Microsoft.Storage/storageAccounts")["properties"]
        self.assertIs(properties["allowBlobPublicAccess"], False)
        self.assertIs(properties["allowSharedKeyAccess"], False)
        self.assertIs(properties["defaultToOAuthAuthentication"], True)
        self.assertIs(properties["supportsHttpsTrafficOnly"], True)
        self.assertEqual(properties["minimumTlsVersion"], "TLS1_2")
        self.assertEqual(properties["publicNetworkAccess"], "Enabled")
        self.assertEqual(properties["networkAcls"], {
            "bypass": "None", "defaultAction": "Allow", "ipRules": [], "virtualNetworkRules": [],
        })
        for alternate_protocol in ("isHnsEnabled", "isNfsV3Enabled", "isSftpEnabled"):
            self.assertIs(properties[alternate_protocol], False)
        self.assertEqual(properties["encryption"], {
            "keySource": "Microsoft.Storage",
            "services": {"blob": {"enabled": True, "keyType": "Account"}},
        })
        container = self.resource("Microsoft.Storage/storageAccounts/blobServices/containers")
        self.assertEqual(container["properties"], {"publicAccess": "None"})

    def test_no_automatic_recovery_retention(self):
        properties = self.resource("Microsoft.Storage/storageAccounts/blobServices")["properties"]
        self.assertIs(properties["isVersioningEnabled"], False)
        for policy in (
            "deleteRetentionPolicy", "containerDeleteRetentionPolicy", "restorePolicy", "changeFeed",
        ):
            self.assertEqual(properties[policy], {"enabled": False})
        self.assertEqual(properties["cors"], {"corsRules": []})

    def test_orphan_cleanup_targets_only_snapshot_container(self):
        self.assertEqual(self.template["variables"]["containerName"], "sync-snapshots")
        policy = self.resource("Microsoft.Storage/storageAccounts/managementPolicies")["properties"]
        rules = policy["policy"]["rules"]
        self.assertEqual(len(rules), 1)
        self.assertIs(rules[0]["enabled"], True)
        self.assertEqual(rules[0]["type"], "Lifecycle")
        self.assertEqual(rules[0]["definition"]["filters"], {
            "blobTypes": ["blockBlob"],
            "prefixMatch": ["[format('{0}/', variables('containerName'))]"],
        })
        self.assertEqual(rules[0]["definition"]["actions"], {
            "baseBlob": {"delete": {"daysAfterModificationGreaterThan": 1}},
            "snapshot": {"delete": {"daysAfterCreationGreaterThan": 1}},
            "version": {"delete": {"daysAfterCreationGreaterThan": 1}},
        })

    def test_module_exports_only_backend_connection_and_grant_inputs(self):
        outputs = self.template["outputs"]
        self.assertEqual(set(outputs), {"accountName", "blobEndpoint", "containerName", "containerId"})
        self.assertEqual(outputs["containerName"]["value"], "[variables('containerName')]")
        self.assertEqual(
            outputs["containerId"]["value"],
            "[resourceId('Microsoft.Storage/storageAccounts/blobServices/containers', "
            "parameters('accountName'), 'default', variables('containerName'))]",
        )
        self.assertEqual(set(self.template["parameters"]), {"accountName", "location"})
        serialized = json.dumps(self.template).lower()
        self.assertNotIn("listkeys", serialized)
        self.assertNotIn("listsas", serialized)

    def test_module_owns_artifact_storage_and_disposal_not_backend_roles_or_backups(self):
        resources = self.template["resources"]
        self.assertEqual({resource["type"] for resource in resources}, {
            "Microsoft.Storage/storageAccounts",
            "Microsoft.Storage/storageAccounts/blobServices",
            "Microsoft.Storage/storageAccounts/blobServices/containers",
            "Microsoft.Storage/storageAccounts/managementPolicies",
        })
        self.assertEqual(len(resources), 4)
        self.assertTrue(all(resource["apiVersion"] == "2025-06-01" for resource in resources))


if __name__ == "__main__":
    unittest.main()
