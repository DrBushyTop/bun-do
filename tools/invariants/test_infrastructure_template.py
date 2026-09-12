"""Check the application modules are wired only into the dedicated dev group."""

import copy
import json
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]


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
        self.assertEqual(groups[0]["tags"]["application"], "bun-do")
        self.assertEqual(groups[0]["tags"]["environment"], "development")
        self.assertEqual(groups[0]["name"], "[parameters('resourceGroupName')]")
        self.assertEqual(groups[0]["location"], "[parameters('location')]")

    def assert_development_scope(self, template):
        for resource in template["resources"]:
            if resource["type"] == "Microsoft.Resources/resourceGroups":
                continue  # The separate entrypoint test constrains this group's name and region.
            self.assertEqual(resource["type"], "Microsoft.Resources/deployments",
                             "Do not add subscription-wide resources to the development deployment.")
            self.assertEqual(resource["resourceGroup"], "[parameters('resourceGroupName')]",
                             "Every root module must deploy inside the dedicated development group.")
            self.assertNotIn("subscriptionId", resource, "Do not redirect modules to another subscription.")
            self.assertEqual(resource["properties"]["mode"], "Incremental",
                             "Complete mode can delete resources outside this slice.")

    def test_all_modules_stay_in_the_development_group_without_destructive_mode(self):
        self.assert_development_scope(self.template)

    def assert_storage_identity(self, template):
        # Unlike a deployment label, changing either account name creates new
        # storage and can repoint the backend away from existing work. Keep these
        # exact persisted identities until an explicit migration replaces them.
        responsibilities = {
            "Microsoft.DocumentDB/databaseAccounts": "cos-bun-do-dev-",
            "Microsoft.Storage/storageAccounts/managementPolicies": "stbundosnap",
        }
        for resource_type, prefix in responsibilities.items():
            modules = [
                resource for resource in template["resources"]
                if resource["type"] == "Microsoft.Resources/deployments"
                and any(child["type"] == resource_type
                        for child in resource["properties"]["template"]["resources"])
            ]
            self.assertEqual(len(modules), 1, f"Expected one owner of persisted {resource_type}.")
            self.assertEqual(
                modules[0]["properties"]["parameters"]["accountName"]["value"],
                f"[format('{prefix}{{0}}', uniqueString(subscription().subscriptionId, parameters('resourceGroupName')))]",
                "Changing a persisted storage account identity requires a migration, not a naming cleanup.",
            )

    def test_persisted_storage_account_identities_do_not_change(self):
        self.assert_storage_identity(self.template)

    def test_identity_guard_rejects_replacement_but_allows_deployment_label_changes(self):
        template = copy.deepcopy(self.template)
        modules = [r for r in template["resources"] if r["type"] == "Microsoft.Resources/deployments"]
        for index, module in enumerate(modules):
            module["name"] = f"renamed-deployment-{index}"
        template["resources"].reverse()
        self.assert_storage_identity(template)
        for resource_type in ("Microsoft.DocumentDB/databaseAccounts",
                              "Microsoft.Storage/storageAccounts/managementPolicies"):
            changed = copy.deepcopy(template)
            module = next(r for r in changed["resources"] if r["type"] == "Microsoft.Resources/deployments"
                          and any(c["type"] == resource_type for c in r["properties"]["template"]["resources"]))
            module["properties"]["parameters"]["accountName"]["value"] = "replacement-account"
            with self.subTest(resource_type=resource_type), self.assertRaises(AssertionError):
                self.assert_storage_identity(changed)

    def test_scope_guard_rejects_escape_and_complete_mode(self):
        for field, value in (("resourceGroup", "unrelated"), ("subscriptionId", "other")):
            template = copy.deepcopy(self.template)
            module = next(r for r in template["resources"] if r["type"] == "Microsoft.Resources/deployments")
            module[field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                self.assert_development_scope(template)
        template = copy.deepcopy(self.template)
        module = next(r for r in template["resources"] if r["type"] == "Microsoft.Resources/deployments")
        module["properties"]["mode"] = "Complete"
        with self.assertRaises(AssertionError):
            self.assert_development_scope(template)

    def test_scope_guard_allows_module_rename_addition_and_reordering(self):
        template = copy.deepcopy(self.template)
        module = copy.deepcopy(next(r for r in template["resources"] if r["type"] == "Microsoft.Resources/deployments"))
        module["name"] = "another-application-responsibility"
        template["resources"].append(module)
        template["resources"].reverse()
        self.assert_development_scope(template)


if __name__ == "__main__":
    unittest.main()
