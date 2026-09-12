import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("azure_foundation", Path(__file__).with_name("azure-foundation.py"))
foundation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(foundation)


class FoundationPlanTests(unittest.TestCase):
    def test_subscription_option_follows_the_azure_command(self):
        self.assertEqual(
            ["az", "group", "exists", "--name", foundation.GROUP, "--subscription", "test"],
            foundation.azure_command("test", "group", "exists", "--name", foundation.GROUP),
        )

    def plan(self, resource=None, change="Create", **extra):
        return {"status": "Succeeded", "changes": [{
            "resourceId": resource or "/subscriptions/test/resourceGroups/rg-bun-do-dev-swc/providers/Microsoft.DocumentDB/databaseAccounts/cosmos",
            "changeType": change,
            **extra,
        }]}

    def test_allows_only_target_group_and_its_resources(self):
        foundation.validate_changes(self.plan(), "test")
        foundation.validate_changes(self.plan("/subscriptions/test/resourceGroups/rg-bun-do-dev-swc"), "test")
        for resource in (
            "/subscriptions/other/resourceGroups/rg-bun-do-dev-swc/providers/Microsoft.DocumentDB/databaseAccounts/cosmos",
            "/subscriptions/test/resourceGroups/rg-unrelated",
            "/subscriptions/test/resourceGroups/rg-bun-do-dev-swc-imposter",
        ):
            with self.subTest(resource=resource), self.assertRaises(ValueError):
                foundation.validate_changes(self.plan(resource), "test")

    def test_deletions_and_uninspected_changes_stop(self):
        for change in ("Delete", "Deploy", "Unsupported", "Ignore"):
            with self.subTest(change=change), self.assertRaises(ValueError):
                foundation.validate_changes(self.plan(change=change), "test")
        with self.assertRaises(ValueError):
            foundation.validate_changes(self.plan(unsupportedReason="not inspected"), "test")
        with self.assertRaises(ValueError):
            foundation.validate_changes({"status": "Failed", "changes": []}, "test")

    def test_missing_or_empty_inspection_never_authorizes_deploy(self):
        for payload in (
            {"status": "Succeeded"},
            {"status": "Succeeded", "changes": []},
            {"status": "Succeeded", "changes": {}},
            {"status": "Succeeded", "changes": None},
        ):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                foundation.validate_changes(payload, "test")

    def test_digest_covers_template_and_parameters_separately(self):
        self.assertNotEqual(foundation.digest(b"a", b"bc"), foundation.digest(b"ab", b"c"))
        self.assertNotEqual(foundation.digest(b"a", b"bc"), foundation.digest(b"a", b"bd"))
