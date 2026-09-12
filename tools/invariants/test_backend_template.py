"""Check backend deployment boundaries without Azure credentials."""

import json
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("bicep"), "Backend checks require Bicep CLI")
class BackendTemplateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.template = json.loads(subprocess.check_output([
            "bicep", "build", str(ROOT / "infra/modules/backend-hosting.bicep"), "--stdout",
        ], text=True))

    def resources(self, kind):
        return [r for r in self.template["resources"] if r["type"] == kind]

    def test_host_is_bounded_dotnet_ten_flex_without_always_ready_instances(self):
        plan, = self.resources("Microsoft.Web/serverfarms")
        self.assertEqual(plan["sku"], {"name": "FC1", "tier": "FlexConsumption"})
        self.assertTrue(plan["properties"]["reserved"])
        app, = self.resources("Microsoft.Web/sites")
        self.assertEqual(app["kind"], "functionapp,linux")
        config = app["properties"]["functionAppConfig"]
        self.assertEqual(config["runtime"], {"name": "dotnet-isolated", "version": "10.0"})
        self.assertEqual(config["scaleAndConcurrency"], {
            "maximumInstanceCount": 2, "instanceMemoryMB": 512, "alwaysReady": [],
        })
        self.assertEqual(app["identity"]["type"], "UserAssigned")
        self.assertEqual(config["deployment"]["storage"]["authentication"]["type"], "UserAssignedIdentity")

    def test_host_storage_and_publishing_require_identity(self):
        storage, = self.resources("Microsoft.Storage/storageAccounts")
        self.assertEqual(storage["name"], "[parameters('runtimeStorageName')]")
        self.assertEqual(storage["sku"]["name"], "Standard_LRS")
        for setting in ("allowBlobPublicAccess", "allowSharedKeyAccess", "allowCrossTenantReplication"):
            self.assertFalse(storage["properties"][setting])
        self.assertTrue(storage["properties"]["supportsHttpsTrafficOnly"])
        self.assertEqual(storage["properties"]["minimumTlsVersion"], "TLS1_2")
        app, = self.resources("Microsoft.Web/sites")
        self.assertTrue(app["properties"]["httpsOnly"])
        site = app["properties"]["siteConfig"]
        self.assertEqual(site["ftpsState"], "Disabled")
        settings = {s["name"]: s["value"] for s in site["appSettings"]}
        self.assertEqual(settings["AzureWebJobsStorage__credential"], "managedidentity")
        self.assertEqual(settings["AzureWebJobsStorage__clientId"], settings["AZURE_CLIENT_ID"])
        self.assertNotIn("AzureWebJobsStorage", settings)
        self.assertEqual(settings["AI__Enabled"], "false")
        self.assertEqual(settings["AI__Endpoint"], "[parameters('aiEndpoint')]")
        self.assertEqual(settings["AI__LunaDeployment"], "[parameters('lunaDeployment')]")
        self.assertEqual(settings["AI__TerraDeployment"], "[parameters('terraDeployment')]")
        self.assertIn("BunDoTelemetry__ConnectionString", settings)
        self.assertEqual(settings["AzureFunctionsJobHost__logging__logLevel__default"], "None")
        self.assertNotIn("APPLICATIONINSIGHTS_CONNECTION_STRING", settings)
        self.assertNotIn("APPINSIGHTS_INSTRUMENTATIONKEY", settings)
        self.assertNotIn("OTEL_EXPORTER_OTLP_ENDPOINT", settings)
        self.assertEqual(settings["OTEL_DOTNET_AZURE_MONITOR_ENABLE_RESOURCE_METRICS"], "false")
        self.assertEqual(settings["APPLICATIONINSIGHTS_STATSBEAT_DISABLED"], "true")
        self.assertEqual(settings["APPLICATIONINSIGHTS_SDKSTATS_DISABLED"], "true")
        policies = self.resources("Microsoft.Web/sites/basicPublishingCredentialsPolicies")
        self.assertEqual(len(policies), 2)
        self.assertTrue(all(p["properties"]["allow"] is False for p in policies))
        self.assertNotIn("listkeys", json.dumps(self.template).lower())

    def test_grants_are_limited_to_host_storage_and_application_containers(self):
        grants = self.resources("Microsoft.Authorization/roleAssignments")
        self.assertEqual(len(grants), 4)
        owner, = [g for g in grants if "b7e6dc6d" in g["properties"]["roleDefinitionId"]]
        contributor, = [g for g in grants if "ba92f5b4" in g["properties"]["roleDefinitionId"]]
        self.assertEqual(owner["scope"], "[resourceId('Microsoft.Storage/storageAccounts', parameters('runtimeStorageName'))]")
        self.assertIn("blobServices/containers", contributor["scope"])
        self.assertIn("snapshotAccountName", contributor["scope"])
        self.assertIn("snapshotContainerName", contributor["scope"])
        publisher, = [g for g in grants if "3913510d" in g["properties"]["roleDefinitionId"]]
        self.assertEqual(publisher["scope"], "[resourceId('Microsoft.Insights/components', parameters('insightsName'))]")
        inference, = [g for g in grants if "5e0bd9bd" in g["properties"]["roleDefinitionId"]]
        self.assertEqual(inference["scope"], "[resourceId('Microsoft.CognitiveServices/accounts', parameters('aiAccountName'))]")
        cosmos, = self.resources("Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments")
        self.assertEqual(cosmos["properties"]["scope"], "[parameters('cosmosContainerScope')]")
        self.assertEqual(cosmos["properties"]["roleDefinitionId"], "[parameters('cosmosDataContributorRoleDefinitionId')]")
        self.assertEqual(len({g["properties"]["principalId"] for g in [*grants, cosmos]}), 1)

    def test_exception_boundary_wraps_http_result_execution(self):
        # The HTTP integration executes IActionResult after its inner delegate.
        # Reversing these registrations silently loses serialization failures.
        program = (ROOT / "src/BunDo.Functions/Program.cs").read_text()
        self.assertLess(
            program.index("builder.UseMiddleware<FunctionTelemetryMiddleware>()"),
            program.index("builder.ConfigureFunctionsWebApplication()"),
        )
        self.assertLess(
            program.index("builder.ConfigureFunctionsWebApplication()"),
            program.index("builder.UseMiddleware<FunctionExecutionTelemetryMiddleware>()"),
        )
        self.assertIn("builder.Logging.ClearProviders()", program)


if __name__ == "__main__":
    unittest.main()
