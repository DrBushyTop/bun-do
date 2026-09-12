"""Deployment gate lifecycle checks without Azure access."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("azure_gates", Path(__file__).with_name("azure-gates.py"))
gates = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gates)


class GateLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.patch = patch.object(gates, "EVIDENCE", self.path)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        manifest = {"sourceDigest": "reviewed-source"}
        for role in ("gate", "normal"):
            content = role.encode()
            (self.path / (role + ".zip")).write_bytes(content)
            manifest[role] = {"sha256": hashlib.sha256(content).hexdigest(), "names": ["Health"]}
        (self.path / "artifacts.json").write_text(json.dumps(manifest))

    def test_failed_gate_always_restores_normal_and_cannot_replay(self):
        publishes = []
        with patch.object(gates, "digest", return_value="reviewed-source"), \
                patch.object(gates, "inspect_target", return_value=("sub", "app", "host")), \
                patch.object(gates, "publish", side_effect=lambda sub, app, role: publishes.append(role)), \
                patch.object(gates, "run_json", return_value={"functionKeys": {"default": "private-key"}}), \
                patch.object(gates, "request", side_effect=[(401, b""), (200, b'{"result":"FAILED"}')]), \
                patch.object(gates, "verify_normal", return_value={"health": 200, "removedGate": 404}):
            with self.assertRaisesRegex(ValueError, "storage did not prove"):
                gates.execute(False)
            self.assertEqual(publishes, ["gate", "normal"])
            report = json.loads((self.path / "dispatch.json").read_text())
            self.assertEqual(list(report["calls"]), ["storage"])
            self.assertEqual(report["restoration"]["removedGate"], 404)
            self.assertNotIn("private-key", json.dumps(report))
            with self.assertRaises(FileExistsError):
                gates.execute(False)
            self.assertEqual(publishes, ["gate", "normal"])

    def test_partial_publish_failure_still_restores_normal(self):
        publishes = []
        def publish(sub, name, role):
            publishes.append(role)
            if role == "gate":
                raise RuntimeError("Partial publish failure")
        with patch.object(gates, "digest", return_value="reviewed-source"), \
                patch.object(gates, "inspect_target", return_value=("sub", "app", "host")), \
                patch.object(gates, "publish", side_effect=publish), \
                patch.object(gates, "verify_normal", return_value={"health": 200}):
            with self.assertRaisesRegex(RuntimeError, "Partial"):
                gates.execute(False)
        self.assertEqual(publishes, ["gate", "normal"])

    def test_source_or_package_changes_stop_before_cloud_inspection(self):
        with patch.object(gates, "digest", return_value="changed-source"), \
                patch.object(gates, "inspect_target") as inspect:
            with self.assertRaisesRegex(ValueError, "Source changed"):
                gates.execute(False)
            inspect.assert_not_called()
        (self.path / "gate.zip").write_bytes(b"tampered")
        with patch.object(gates, "inspect_target") as inspect:
            with self.assertRaisesRegex(ValueError, "Package changed"):
                gates.execute(False)
            inspect.assert_not_called()

    def test_restore_uses_reviewed_package_even_when_source_changed(self):
        (self.path / "gate.zip").unlink()
        with patch.object(gates, "digest", side_effect=AssertionError("Must not rebuild or inspect source")), \
                patch.object(gates, "inspect_target", return_value=("sub", "app", "host")), \
                patch.object(gates, "publish") as publish, \
                patch.object(gates, "verify_normal", return_value={"health": 200}):
            gates.execute(True)
            publish.assert_called_once_with("sub", "app", "normal")

    def test_preflight_uses_arm_hostname_and_rejects_endpoint_mismatch(self):
        base = "/subscriptions/sub/resourceGroups/rg-bun-do-dev-swc/providers/"
        identity = {"id": base + "Microsoft.ManagedIdentity/userAssignedIdentities/app-identity",
                    "properties": {"clientId": "client"}}
        resources = {
            "cosmos": {"id": base + "Microsoft.DocumentDB/databaseAccounts/cosmos",
                       "properties": {"documentEndpoint": "https://cosmos/"}},
            "blobs": {"id": base + "Microsoft.Storage/storageAccounts/blobs",
                      "properties": {"primaryEndpoints": {"blob": "https://blobs/"}}},
            "ai": {"id": base + "Microsoft.CognitiveServices/accounts/ai",
                   "properties": {"customSubDomainName": "ai"}},
            "app-identity": identity,
        }
        settings = {
            "WorkspaceStore__Endpoint": "https://cosmos/", "WorkspaceStore__DatabaseName": "bun-do",
            "WorkspaceStore__ContainerName": "workspace-items", "Snapshots__BlobEndpoint": "https://blobs/",
            "Snapshots__ContainerName": "sync-snapshots", "AI__Endpoint": "https://ai.openai.azure.com/openai/v1/",
            "AI__LunaDeployment": "bun-do-luna", "AI__Enabled": "false", "AZURE_CLIENT_ID": "client",
        }
        def azure(*args):
            if args[1:3] == ("account", "show"):
                return {"id": "sub"}
            if args[1:3] == ("group", "show"):
                return {"location": "swedencentral", "tags": {"application": "bun-do", "environment": "development"}}
            if args[1:4] == ("deployment", "sub", "show"):
                values = {"resourceGroupName": gates.GROUP, "functionAppName": "app",
                          "cosmosAccountName": "cosmos", "snapshotAccountName": "blobs", "aiAccountName": "ai"}
                return {"properties": {"outputs": {k: {"value": v} for k, v in values.items()}}}
            if args[1:3] == ("functionapp", "show"):
                return {"id": base + "Microsoft.Web/sites/app", "properties": {"defaultHostName": "app.azurewebsites.net"},
                        "identity": {"userAssignedIdentities": {identity["id"]: {}}}}
            if args[1:4] == ("functionapp", "config", "appsettings"):
                return [{"name": k, "value": v} for k, v in settings.items()]
            if args[1:3] == ("resource", "show"):
                return resources[args[args.index("--ids") + 1].split("/")[-1]]
            self.fail("Unexpected Azure command")
        with patch.object(gates, "run_json", side_effect=azure):
            self.assertEqual(("sub", "app", "app.azurewebsites.net"), gates.inspect_target())
            settings["AI__Endpoint"] = "https://unrelated.openai.azure.com/openai/v1/"
            with self.assertRaisesRegex(ValueError, "endpoints/identity"):
                gates.inspect_target()


if __name__ == "__main__":
    unittest.main()
