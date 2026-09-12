"""Check the application modules are wired only into the dedicated dev group."""

import json
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("bicep"), "Infrastructure checks require Bicep CLI")
class InfrastructureTemplateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        build = subprocess.run(
            ["bicep", "build", str(ROOT / "infra/main.bicep"), "--stdout"],
            check=True, capture_output=True, text=True,
        )
        cls.template = json.loads(build.stdout)

    def test_entrypoint_is_limited_to_the_dedicated_development_group(self):
        self.assertIn("subscriptionDeploymentTemplate", self.template["$schema"])
        for name, expected in (
            ("location", "swedencentral"),
            ("resourceGroupName", "rg-bun-do-dev-swc"),
        ):
            parameter = self.template["parameters"][name]
            self.assertEqual(parameter["defaultValue"], expected)
            self.assertEqual(parameter["allowedValues"], [expected])
        groups = [
            resource for resource in self.template["resources"]
            if resource["type"] == "Microsoft.Resources/resourceGroups"
        ]
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0]["tags"], {
            "application": "bun-do", "environment": "development",
        })
        self.assertEqual(groups[0]["name"], "[parameters('resourceGroupName')]")
        self.assertEqual(groups[0]["location"], "[parameters('location')]")

    def test_storage_modules_have_stable_names_and_no_mutual_dependency(self):
        modules = {
            resource["name"]: resource for resource in self.template["resources"]
            if resource["type"] == "Microsoft.Resources/deployments"
        }
        self.assertEqual(set(modules), {"bun-do-workspace-store", "bun-do-snapshot-artifacts"})
        self.assertEqual(len(self.template["resources"]), 3)
        for name, prefix in (
            ("bun-do-workspace-store", "cos-bun-do-dev-"),
            ("bun-do-snapshot-artifacts", "stbundosnap"),
        ):
            with self.subTest(module=name):
                module = modules[name]
                self.assertEqual(module["resourceGroup"], "[parameters('resourceGroupName')]")
                self.assertEqual(module["dependsOn"], [
                    "[subscriptionResourceId('Microsoft.Resources/resourceGroups', "
                    "parameters('resourceGroupName'))]",
                ])
                self.assertEqual(module["properties"]["mode"], "Incremental")
                self.assertEqual(module["properties"]["parameters"], {
                    "accountName": {
                        "value": f"[format('{prefix}{{0}}', uniqueString(subscription()."
                        "subscriptionId, parameters('resourceGroupName')))]",
                    },
                    "location": {"value": "[parameters('location')]"},
                })
                self.assertNotIn("subscriptionId", module)


if __name__ == "__main__":
    unittest.main()
