"""Reject dangerous live policy drift while accepting harmless provider metadata."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("verify", Path(__file__).with_name("azure-verify-storage.py"))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)
SUBSCRIPTION = "00000000-0000-0000-0000-000000000001"


def storage():
    # ARM response properties only. No keys, endpoints, application records or real account identifiers.
    properties = {
        "cosmos": {
            "consistencyPolicy": {"defaultConsistencyLevel": "Strong"},
            "enableMultipleWriteLocations": False, "enableFreeTier": True,
            "capacity": {"totalThroughputLimit": 1000}, "disableLocalAuth": True,
            "disableKeyBasedMetadataWriteAccess": True, "minimalTlsVersion": "Tls12",
            "networkAclBypass": "None",
            "backupPolicy": {"type": "Continuous", "continuousModeProperties": {"tier": "Continuous30Days"}},
        },
        "throughput": {"resource": {"throughput": 400}},
        "workspace": {"resource": {"partitionKey": {"paths": ["/workspaceId"]}}},
        "snapshots": {"allowBlobPublicAccess": False, "allowSharedKeyAccess": False,
                      "supportsHttpsTrafficOnly": True, "minimumTlsVersion": "TLS1_2",
                      "allowCrossTenantReplication": False},
        "snapshotContainer": {"publicAccess": "None"},
        "blobs": {"isVersioningEnabled": False,
                  **{p: {"enabled": False} for p in (
                      "deleteRetentionPolicy", "containerDeleteRetentionPolicy", "restorePolicy", "changeFeed")}},
        "cleanup": {"policy": {"rules": [{
            "name": "disposable-artifacts", "enabled": True, "type": "Lifecycle",
            "definition": {
                "filters": {"blobTypes": ["blockBlob"], "prefixMatch": ["sync-snapshots/"]},
                "actions": {"baseBlob": {"delete": {"daysAfterModificationGreaterThan": 1}},
                            "snapshot": {"delete": {"daysAfterCreationGreaterThan": 1}},
                            "version": {"delete": {"daysAfterCreationGreaterThan": 1}}},
            },
        }]}},
    }
    return {**{key: {"id": key, "properties": value} for key, value in properties.items()},
            "snapshotPrefix": "sync-snapshots/"}


class StoragePolicyTests(unittest.TestCase):
    def test_current_policy_and_unrelated_provider_defaults_pass(self):
        resources = storage()
        resources["cosmos"]["properties"]["providerDefault"] = "ignored"
        resources["workspace"]["properties"]["resource"]["defaultTtl"] = None
        resources["throughput"]["properties"]["resource"]["autoscaleSettings"] = None
        rules = resources["cleanup"]["properties"]["policy"]["rules"]
        rules[0]["name"] = "renaming-a-rule-is-not-policy-drift"
        rules.insert(0, {"name": "old-rule", "enabled": False})
        self.assertTrue(all(c["passed"] for c in verify.checks(resources)))

    def test_harmful_drift_reports_observed_value_and_repair(self):
        mutations = (
            ("cosmos", "consistencyPolicy.defaultConsistencyLevel", "Session"),
            ("cosmos", "enableFreeTier", False),
            ("cosmos", "disableLocalAuth", False),
            ("cosmos", "capacity.totalThroughputLimit", -1),
            ("cosmos", "backupPolicy.continuousModeProperties.tier", "Continuous7Days"),
            ("throughput", "resource.throughput", 1000),
            ("throughput", "resource.autoscaleSettings", {"maxThroughput": 4000}),
            ("workspace", "resource.defaultTtl", 3600),
            ("workspace", "resource.partitionKey.paths", ["/id"]),
            ("snapshots", "allowSharedKeyAccess", True),
            ("snapshotContainer", "publicAccess", "Blob"),
            ("blobs", "deleteRetentionPolicy.enabled", True),
        )
        for key, path, value in mutations:
            with self.subTest(key=key, path=path):
                resources = storage()
                parent = resources[key]["properties"]
                parts = path.split(".")
                for part in parts[:-1]:
                    parent = parent[part]
                parent[parts[-1]] = value
                failed = [c for c in verify.checks(resources) if not c["passed"]]
                self.assertEqual(len(failed), 1)
                self.assertEqual(failed[0]["observed"], value)
                self.assertTrue(failed[0]["repair"])

    def test_missing_or_wrongly_typed_authentication_policy_never_passes(self):
        for value in (None, 0, "false"):
            resources = storage()
            resources["snapshots"]["properties"]["allowSharedKeyAccess"] = value
            self.assertFalse(all(c["passed"] for c in verify.checks(resources)))
        resources["snapshots"]["properties"].pop("allowSharedKeyAccess")
        self.assertFalse(all(c["passed"] for c in verify.checks(resources)))

    def test_cleanup_rejects_broad_missing_disabled_or_delayed_rules(self):
        original = storage()["cleanup"]["properties"]["policy"]["rules"]
        broad = copy.deepcopy(original)
        broad[0]["definition"]["filters"]["prefixMatch"] = [""]
        delayed = copy.deepcopy(original)
        delayed[0]["definition"]["actions"]["baseBlob"]["delete"]["daysAfterModificationGreaterThan"] = 30
        for rules in (None, [], [{"enabled": False}], broad, original + broad, delayed):
            with self.subTest(rules=rules):
                resources = storage()
                resources["cleanup"]["properties"]["policy"]["rules"] = rules
                self.assertFalse(verify.checks(resources)[-1]["passed"])


class ReadBoundaryTests(unittest.TestCase):
    def response(self, *command):
        self.commands.append(command)
        self.assertIn("--subscription", command)
        self.assertEqual(command[command.index("--subscription") + 1], SUBSCRIPTION)
        if command[1:4] == ("deployment", "sub", "show"):
            return {"properties": {"outputs": {k: {"value": v} for k, v in self.outputs.items()}}}
        self.assertEqual(command[1:3], ("resource", "show"))
        resource_id = command[command.index("--ids") + 1]
        self.assertTrue(resource_id.startswith(self.group_id))
        return {"id": resource_id, "location": "swedencentral",
                "tags": {"application": "bun-do", "environment": "development"}}

    def setUp(self):
        self.commands = []
        self.group_id = f"/subscriptions/{SUBSCRIPTION}/resourceGroups/{verify.GROUP}"
        self.outputs = {"resourceGroupName": verify.GROUP, "cosmosAccountName": "cosmos",
                        "databaseName": "tasks", "containerName": "items",
                        "snapshotAccountName": "snapshots", "snapshotContainerName": "copies"}

    def test_collection_uses_only_scoped_management_reads(self):
        with patch.object(verify, "run_json", side_effect=self.response):
            result = verify.collect(SUBSCRIPTION)
        self.assertEqual(result["snapshotPrefix"], "copies/")
        self.assertTrue(result["throughput"]["id"].endswith("/sqlDatabases/tasks/throughputSettings/default"))
        self.assertEqual(len(self.commands), 9)

    def test_cross_group_or_path_injection_stops_before_resource_reads(self):
        for key, value in (("resourceGroupName", "other"), ("cosmosAccountName", "../other"),
                           ("snapshotContainerName", "https://other")):
            with self.subTest(key=key):
                self.setUp()
                self.outputs[key] = value
                with patch.object(verify, "run_json", side_effect=self.response), self.assertRaises(ValueError):
                    verify.collect(SUBSCRIPTION)
                self.assertEqual(len(self.commands), 2)

    def test_wrong_returned_identity_stops_before_further_reads(self):
        for response in ({"id": "other"}, {"id": None}, None, []):
            with self.subTest(response=response), patch.object(verify, "run_json", return_value=response) as read:
                with self.assertRaisesRegex(ValueError, "different resource"):
                    verify.collect(SUBSCRIPTION)
                self.assertEqual(read.call_count, 1)

    def test_unreadable_resources_produce_incomplete_not_success(self):
        output = io.StringIO()
        with patch("sys.argv", ["verify", "--subscription", SUBSCRIPTION]), \
                patch.object(verify, "collect", side_effect=ValueError("Reader access required")), \
                patch("sys.stdout", output):
            self.assertEqual(verify.main(), 1)
        self.assertEqual(json.loads(output.getvalue())["status"], "INCOMPLETE")

    def test_malformed_group_responses_still_produce_json_incomplete(self):
        for response in (None, [], {"id": self.group_id, "location": "swedencentral", "tags": None}):
            output = io.StringIO()
            with self.subTest(response=response), \
                    patch("sys.argv", ["verify", "--subscription", SUBSCRIPTION]), \
                    patch.object(verify, "run_json", return_value=response), patch("sys.stdout", output):
                self.assertEqual(verify.main(), 1)
            self.assertEqual(json.loads(output.getvalue())["status"], "INCOMPLETE")


if __name__ == "__main__":
    unittest.main()
