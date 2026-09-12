#!/usr/bin/env python3
"""Read deployed storage policy. Never deploy, fetch keys, or access application data."""

import argparse
from datetime import datetime, timezone
import json
import re
import subprocess
import sys
from uuid import UUID

GROUP = "rg-bun-do-dev-swc"
COSMOS_API = "2025-04-15"
STORAGE_API = "2025-06-01"


def run_json(*command):
    result = subprocess.run(command, text=True, capture_output=True, timeout=90)
    if result.returncode:
        # Do not copy arbitrary CLI response bodies into the verification report.
        raise ValueError(f"Azure read failed with exit {result.returncode}. Check login, Reader access "
                         f"and resource existence: {' '.join(command)}")
    return json.loads(result.stdout)


def collect(subscription):
    """Only management-plane GETs, scoped to the explicitly selected development group."""
    subscription = str(UUID(subscription))
    group_id = f"/subscriptions/{subscription}/resourceGroups/{GROUP}"

    def resource(resource_id, api):
        result = run_json("az", "resource", "show", "--ids", resource_id, "--api-version", api,
                          "--subscription", subscription, "--output", "json")
        if not isinstance(result, dict) or not isinstance(result.get("id"), str) or \
                result["id"].lower() != resource_id.lower():
            raise ValueError(f"Azure returned a different resource for {resource_id}. Stop and inspect the target.")
        return result

    group = resource(group_id, "2025-04-01")
    tags = group.get("tags")
    if group.get("location") != "swedencentral" or not isinstance(tags, dict) or \
            tags.get("application") != "bun-do" or tags.get("environment") != "development":
        raise ValueError("Target is not the tagged Bun Do development group in Sweden Central.")
    deployment = run_json("az", "deployment", "sub", "show", "--name", "bun-do-dev-foundation",
                          "--subscription", subscription, "--output", "json")
    outputs = deployment["properties"]["outputs"]
    if outputs["resourceGroupName"]["value"] != GROUP:
        raise ValueError("Deployment outputs refer to another group. Stop and inspect the target.")

    def name(output):
        value = outputs[output]["value"]
        # Output names supply path segments, never URLs or resource scopes.
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", value):
            raise ValueError(f"Unsafe resource name in deployment output {output}.")
        return value

    cosmos = group_id + "/providers/Microsoft.DocumentDB/databaseAccounts/" + name("cosmosAccountName")
    database = cosmos + "/sqlDatabases/" + name("databaseName")
    container = database + "/containers/" + name("containerName")
    snapshots = group_id + "/providers/Microsoft.Storage/storageAccounts/" + name("snapshotAccountName")
    blobs = snapshots + "/blobServices/default"
    snapshot_container = name("snapshotContainerName")
    return {
        "cosmos": resource(cosmos, COSMOS_API),
        "throughput": resource(database + "/throughputSettings/default", COSMOS_API),
        "workspace": resource(container, COSMOS_API),
        "snapshots": resource(snapshots, STORAGE_API),
        "blobs": resource(blobs, STORAGE_API),
        "snapshotContainer": resource(blobs + "/containers/" + snapshot_container, STORAGE_API),
        "cleanup": resource(snapshots + "/managementPolicies/default", STORAGE_API),
        "snapshotPrefix": snapshot_container + "/",
    }


def checks(resources):
    """Check costly policy failures, ignoring names, ordering and unrelated provider defaults."""
    results = []
    missing = "<missing>"

    def lookup(key, path):
        value = resources[key]
        for part in path.split("."):
            value = value.get(part, missing) if isinstance(value, dict) else missing
        return value

    def expect(key, path, expected, repair, *, optional=False):
        value = lookup(key, path)
        if optional and value == missing:
            value = None
        results.append({
            "resource": resources[key]["id"], "property": path,
            "expected": expected, "observed": value,
            "passed": type(value) is type(expected) and value == expected,
            "repair": repair,
        })

    for path, expected in (
        ("consistencyPolicy.defaultConsistencyLevel", "Strong"),
        ("enableMultipleWriteLocations", False),
        ("enableFreeTier", True),
        ("capacity.totalThroughputLimit", 1000),
        ("disableLocalAuth", True),
        ("disableKeyBasedMetadataWriteAccess", True),
        ("minimalTlsVersion", "Tls12"),
        ("networkAclBypass", "None"),
        ("backupPolicy.type", "Continuous"),
        ("backupPolicy.continuousModeProperties.tier", "Continuous30Days"),
    ):
        expect("cosmos", "properties." + path, expected,
               "Review workspace-store.bicep and live drift; do not weaken consistency, recovery or keyless access.")
    expect("throughput", "properties.resource.throughput", 400,
           "Restore manual shared database throughput; do not silently accept paid scaling.")
    expect("throughput", "properties.resource.autoscaleSettings", None,
           "Review unexpected autoscale; this database must use manual throughput.", optional=True)
    expect("workspace", "properties.resource.partitionKey.paths", ["/workspaceId"],
           "Stop sync work. Workspace transactions require the workspaceId partition boundary.")
    expect("workspace", "properties.resource.defaultTtl", None,
           "Remove automatic document TTL; revisioned maintenance owns deletion.", optional=True)
    for path, expected in (
        ("allowBlobPublicAccess", False),
        ("allowSharedKeyAccess", False),
        ("supportsHttpsTrafficOnly", True),
        ("minimumTlsVersion", "TLS1_2"),
        ("allowCrossTenantReplication", False),
    ):
        expect("snapshots", "properties." + path, expected,
               "Review snapshot-artifacts.bicep and live drift; keep snapshots private and identity-only.")
    expect("snapshotContainer", "properties.publicAccess", "None",
           "Disable anonymous container access; do not publish recovery copies.")
    expect("blobs", "properties.isVersioningEnabled", False,
           "Disable native version retention for disposable snapshots.")
    for policy in ("deleteRetentionPolicy", "containerDeleteRetentionPolicy", "restorePolicy", "changeFeed"):
        expect("blobs", f"properties.{policy}.enabled", False,
               "Disable native recovery retention; these are expiring copies, not durable backups.")

    # Rule names and order do not matter. Every enabled rule must be restricted to
    # these disposable copies, and at least one must provide the orphan fallback.
    rules = lookup("cleanup", "properties.policy.rules")
    valid = isinstance(rules, list) and bool(rules)
    enabled = []
    if valid:
        for rule in rules:
            if not isinstance(rule, dict) or type(rule.get("enabled")) is not bool:
                valid = False
                break
            if rule["enabled"]:
                enabled.append(rule)
        valid = valid and bool(enabled)
    expected_definition = {
        "filters": {"blobTypes": ["blockBlob"], "prefixMatch": [resources["snapshotPrefix"]]},
        "actions": {
            "baseBlob": {"delete": {"daysAfterModificationGreaterThan": 1}},
            "snapshot": {"delete": {"daysAfterCreationGreaterThan": 1}},
            "version": {"delete": {"daysAfterCreationGreaterThan": 1}},
        },
    }
    valid = valid and all(rule.get("type") == "Lifecycle" and rule.get("definition") == expected_definition
                          for rule in enabled)
    # Only policy fields are reported, never raw resource bodies or credentials.
    results.append({
        "resource": resources["cleanup"]["id"], "property": "properties.policy.rules",
        "expected": {"enabledRules": "at least one", "definition": expected_definition},
        "observed": rules, "passed": valid,
        "repair": "Restrict enabled lifecycle rules to this snapshot container and one-day orphan deletion. "
                  "Never widen cleanup to durable or host data. This does not enforce API expiry.",
    })
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--subscription", required=True, help="Explicit subscription UUID; never changes az defaults.")
    args = parser.parse_args()
    report = {
        "checkedAt": datetime.now(timezone.utc).isoformat(), "subscription": args.subscription,
        "scope": "storage management-plane policy only", "checks": [], "status": "INCOMPLETE",
    }
    try:
        report["checks"] = checks(collect(args.subscription))
        report["status"] = "PASS" if all(check["passed"] for check in report["checks"]) else "FAIL"
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error)
    print(json.dumps(report, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
