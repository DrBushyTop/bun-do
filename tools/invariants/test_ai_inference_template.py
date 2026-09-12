"""Check the AI account and pinned model deployment without Azure credentials."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("bicep"), "AI deployment checks require Bicep CLI")
class AiInferenceTemplateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.template = json.loads(subprocess.check_output([
            "bicep", "build", str(ROOT / "infra/modules/ai-inference.bicep"), "--stdout",
        ], text=True))

    def test_account_requires_identity_and_has_no_authorization_back_edge(self):
        account, = [r for r in self.template["resources"]
                    if r["type"] == "Microsoft.CognitiveServices/accounts"]
        self.assertEqual(account["kind"], "OpenAI")
        self.assertEqual(account["sku"], {"name": "S0"})
        self.assertTrue(account["properties"]["disableLocalAuth"],
                        "Model access must require identity, not account keys.")
        self.assertFalse(account["properties"]["dynamicThrottlingEnabled"],
                         "Do not opt into usage above the provisioned model capacity.")
        self.assertEqual(self.template["parameters"]["location"]["allowedValues"], ["swedencentral"])
        self.assertNotIn("Microsoft.Authorization/roleAssignments", json.dumps(self.template))
        self.assertNotIn("listkeys", json.dumps(self.template).lower())

    def test_deployments_are_pinned_configurable_and_terra_is_opt_in(self):
        deployments = [r for r in self.template["resources"]
                       if r["type"] == "Microsoft.CognitiveServices/accounts/deployments"]
        self.assertEqual(len(deployments), 2)
        for role in ("luna", "terra"):
            deployment, = [r for r in deployments if f"bun-do-{role}" in r["name"]]
            self.assertEqual(deployment["sku"], {
                "name": f"[parameters('{role}Sku')]",
                "capacity": f"[parameters('{role}Capacity')]",
            })
            self.assertEqual(deployment["properties"], {
                "model": {"format": "OpenAI", "name": f"[parameters('{role}Model')]",
                          "version": f"[parameters('{role}Version')]"},
                "versionUpgradeOption": "NoAutoUpgrade",
                "raiPolicyName": "Microsoft.Default",
            })
            self.assertEqual(self.template["parameters"][f"{role}Sku"]["allowedValues"],
                             ["DataZoneStandard", "GlobalStandard"])
            self.assertEqual(self.template["parameters"][f"{role}Capacity"]["minValue"], 1)
            if role == "terra":
                self.assertEqual(deployment["condition"], "[parameters('terraEnabled')]")
            else:
                self.assertNotIn("condition", deployment)
        self.assertFalse(self.template["parameters"]["terraEnabled"]["defaultValue"])

    def test_dev_settings_choose_pay_per_token_eu_luna_without_terra(self):
        # These are the owner's pinned deployment/cost inputs, not naming preferences.
        parameters = json.loads((ROOT / "infra/dev.parameters.json").read_text())["parameters"]
        self.assertEqual(parameters["lunaModel"]["value"], "gpt-5.6-luna")
        self.assertEqual(parameters["lunaVersion"]["value"], "2026-07-09")
        self.assertEqual(parameters["lunaSku"]["value"], "DataZoneStandard")
        self.assertEqual(parameters["lunaCapacity"]["value"], 10)
        self.assertFalse(parameters["terraEnabled"]["value"])


if __name__ == "__main__":
    unittest.main()
