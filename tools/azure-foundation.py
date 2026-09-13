#!/usr/bin/env python3
"""Plan or deploy only Bun Do's dedicated development foundation."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
GROUP = "rg-bun-do-dev-swc"
REGION = "swedencentral"
EVIDENCE = ROOT / ".azure/foundation"


def run_json(command):
    return json.loads(subprocess.check_output(command, text=True))


def azure_command(subscription, *arguments):
    # Azure CLI requires a command before its command-scoped subscription option.
    return ["az", *arguments, "--subscription", subscription]


def validate_changes(plan, subscription, allowed_ignored=()):
    """Reject cross-resource-group effects, deletion, and failed what-if evaluation."""
    if plan.get("status") != "Succeeded":
        raise ValueError("What-if did not succeed; inspect the saved plan.")
    changes = plan.get("changes")
    if not isinstance(changes, list) or not changes:
        raise ValueError("What-if did not return inspected resources for this non-empty foundation.")
    group_id = f"/subscriptions/{subscription}/resourcegroups/{GROUP}".lower()
    allowed_ignored = {resource.lower() for resource in allowed_ignored}
    for change in changes:
        resource = change["resourceId"].lower()
        if resource != group_id and not resource.startswith(group_id + "/"):
            raise ValueError(f"What-if includes a resource outside {GROUP}: {change['resourceId']}")
        # An owner-reviewed resource outside the template may legitimately be ignored.
        # This exception never permits deletion, scope changes or reported evaluation failures.
        if change["changeType"] not in {"Create", "Modify", "NoChange"} and not (
            change["changeType"] == "Ignore" and resource in allowed_ignored
        ):
            raise ValueError(f"Review unsupported change type {change['changeType']} before deployment.")
        if change.get("unsupportedReason"):
            raise ValueError("What-if could not inspect a resource. Do not deploy an incomplete plan.")


def digest(template, parameters):
    return hashlib.sha256(template + b"\0" + parameters).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "deploy"))
    parser.add_argument("--allow-ignored-resource", action="append", default=[],
                        help="Exact reviewed resource ID outside the template. Repeat if needed.")
    args = parser.parse_args()
    subscription = run_json(["az", "account", "show", "--output", "json"])["id"]
    exists = run_json(azure_command(subscription, "group", "exists", "--name", GROUP))
    if exists:
        group = run_json(azure_command(subscription, "group", "show", "--name", GROUP, "--output", "json"))
        if (
            group.get("tags", {}).get("application") != "bun-do"
            or group.get("tags", {}).get("environment") != "development"
            or group["location"] != REGION
        ):
            raise ValueError("Existing resource group is not the tagged Bun Do development group.")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    template = subprocess.check_output(["bicep", "build", str(ROOT / "infra/main.bicep"), "--stdout"])
    parameters = (ROOT / "infra/dev.parameters.json").read_bytes()
    current_digest = digest(template, parameters)
    template_file = EVIDENCE / "template.json"
    parameters_file = EVIDENCE / "parameters.json"
    stamp_file = EVIDENCE / "plan-stamp.json"
    plan_file = EVIDENCE / "what-if.json"
    deployment = azure_command(
        subscription, "deployment", "sub", "what-if" if args.action == "plan" else "create",
        "--name", "bun-do-dev-foundation", "--location", REGION,
        "--template-file", str(template_file), "--parameters", "@" + str(parameters_file),
        "--output", "json",
    )
    if args.action == "plan":
        # An unsuccessful new plan must invalidate any older deployment permission.
        stamp_file.unlink(missing_ok=True)
        template_file.write_bytes(template)
        parameters_file.write_bytes(parameters)
        plan = run_json([*deployment, "--no-pretty-print", "--result-format", "FullResourcePayloads"])
        plan_file.write_text(json.dumps(plan, indent=2) + "\n")
        validate_changes(plan, subscription, args.allow_ignored_resource)
        stamp_file.write_text(json.dumps({
            "subscription": subscription,
            "digest": current_digest,
            "planDigest": hashlib.sha256(plan_file.read_bytes()).hexdigest(),
            "createdAt": time.time(),
            "allowedIgnored": sorted(resource.lower() for resource in args.allow_ignored_resource),
        }) + "\n")
        for change in plan.get("changes", []):
            print(f"{change['changeType']}: {change['resourceId']}")
        print(f"Review full properties in {plan_file} before running deploy.")
    else:
        stamp = json.loads(stamp_file.read_text())
        if stamp["subscription"] != subscription or stamp["digest"] != current_digest:
            raise ValueError("Subscription or source changed. Run and review plan again.")
        if stamp.get("allowedIgnored", []) != sorted(resource.lower() for resource in args.allow_ignored_resource):
            raise ValueError("Ignored-resource exceptions changed. Run and review plan again.")
        if not 0 <= time.time() - stamp["createdAt"] < 3600:
            raise ValueError("Plan is more than one hour old. Run and review plan again.")
        if digest(template_file.read_bytes(), parameters_file.read_bytes()) != current_digest:
            raise ValueError("Compiled deployment files changed. Run and review plan again.")
        if hashlib.sha256(plan_file.read_bytes()).hexdigest() != stamp["planDigest"]:
            raise ValueError("Saved what-if changed. Run and review plan again.")
        validate_changes(json.loads(plan_file.read_text()), subscription, args.allow_ignored_resource)
        stamp_file.unlink()
        result = run_json(deployment)
        (EVIDENCE / "deployment.json").write_text(json.dumps(result, indent=2) + "\n")
        if result.get("properties", {}).get("provisioningState") != "Succeeded":
            raise ValueError("Deployment did not succeed. Inspect local deployment evidence.")
        print(f"Development foundation deployed to {GROUP}. No other v1 deployment gate is implied.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Foundation operation stopped: {error}") from error
