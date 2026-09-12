#!/usr/bin/env python3
"""Build and run opt-in gates in the dedicated development Function, then restore it."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "src/BunDo.Functions"
GATES = ROOT / "tools/cloud-gates"
EVIDENCE = ROOT / ".azure/foundation-gates"
GROUP = "rg-bun-do-dev-swc"


def run_json(*args):
    return json.loads(subprocess.check_output(args, text=True))


def digest():
    files = sorted(p for p in APP.rglob("*") if p.is_file() and
                   not {"bin", "obj"} & set(p.relative_to(APP).parts) and
                   (p.suffix in {".cs", ".csproj"} or p.name in {"host.json", "packages.lock.json"}))
    files += sorted(GATES.glob("*.cs"))
    value = hashlib.sha256()
    for path in files:
        value.update(str(path.relative_to(ROOT)).encode() + b"\0" + path.read_bytes() + b"\0")
    return value.hexdigest()


def build():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    manifest = EVIDENCE / "artifacts.json"
    manifest.unlink(missing_ok=True)
    source = digest()
    targets = [(p, APP / p.name) for p in sorted(GATES.glob("*.cs"))]
    if not targets or any(target.exists() for _, target in targets):
        raise ValueError("Temporary gate source collides with application source.")
    artifacts = {"sourceDigest": source}

    def package(role):
        output = EVIDENCE / (role + "-publish")
        if output.exists():
            shutil.rmtree(output)
        version = f"0.0.0-{role}.{source[:12]}"
        subprocess.run(["dotnet", "publish", str(APP / "BunDo.Functions.csproj"), "-c", "Release",
                        "--no-restore", "-p:InformationalVersion=" + version,
                        "-p:IncludeSourceRevisionInInformationalVersion=false", "-o", str(output)], check=True)
        names = sorted(f["name"] for f in json.loads((output / "functions.metadata").read_text()))
        archive = Path(shutil.make_archive(str(EVIDENCE / role), "zip", output))
        with zipfile.ZipFile(archive) as zipped:
            if zipped.testzip() or "local.settings.json" in zipped.namelist():
                raise ValueError("Unsafe or corrupt package.")
        artifacts[role] = {"names": names, "version": version,
                           "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()}

    # Cleaning both transitions prevents stale Functions metadata from shipping.
    subprocess.run(["dotnet", "clean", str(APP / "BunDo.Functions.csproj"), "-c", "Release"], check=True,
                   stdout=subprocess.DEVNULL)
    try:
        for source_file, target in targets:
            shutil.copyfile(source_file, target)
        package("gate")
    finally:
        for _, target in targets:
            target.unlink(missing_ok=True)
        subprocess.run(["dotnet", "clean", str(APP / "BunDo.Functions.csproj"), "-c", "Release"], check=True,
                       stdout=subprocess.DEVNULL)
    package("normal")
    if "FoundationGate" in artifacts["normal"]["names"] or \
            artifacts["gate"]["names"] != sorted([*artifacts["normal"]["names"], "FoundationGate"]):
        raise ValueError("Gate/normal function metadata is not isolated.")
    if source != digest():
        raise ValueError("Source changed during build. Rebuild before review.")
    manifest.write_text(json.dumps(artifacts, indent=2) + "\n")
    print("Packages built. Review source, tests and .azure/foundation-gates/artifacts.json before run.")


def inspect_target():
    subscription = run_json("az", "account", "show", "-o", "json")["id"]

    def az(*args):
        return run_json("az", *args, "--subscription", subscription, "-o", "json")

    group = az("group", "show", "-n", GROUP)
    if group["location"] != "swedencentral" or group.get("tags", {}).get("application") != "bun-do" \
            or group.get("tags", {}).get("environment") != "development":
        raise ValueError("Wrong development group.")
    outputs = az("deployment", "sub", "show", "-n", "bun-do-dev-foundation")["properties"]["outputs"]
    if outputs["resourceGroupName"]["value"] != GROUP:
        raise ValueError("Deployment output is outside the permitted group.")
    name = outputs["functionAppName"]["value"]
    app = az("functionapp", "show", "-g", GROUP, "-n", name)
    settings = {s["name"]: s["value"] for s in az("functionapp", "config", "appsettings", "list", "-g", GROUP, "-n", name)}
    base = f"/subscriptions/{subscription}/resourceGroups/{GROUP}/providers/"

    def resource(kind, resource_name):
        return az("resource", "show", "--ids", base + kind + "/" + resource_name)

    cosmos = resource("Microsoft.DocumentDB/databaseAccounts", outputs["cosmosAccountName"]["value"])
    blobs = resource("Microsoft.Storage/storageAccounts", outputs["snapshotAccountName"]["value"])
    ai = resource("Microsoft.CognitiveServices/accounts", outputs["aiAccountName"]["value"])
    identity = resource("Microsoft.ManagedIdentity/userAssignedIdentities", name + "-identity")
    expected = {
        "WorkspaceStore__Endpoint": cosmos["properties"]["documentEndpoint"],
        "WorkspaceStore__DatabaseName": "bun-do", "WorkspaceStore__ContainerName": "workspace-items",
        "Snapshots__BlobEndpoint": blobs["properties"]["primaryEndpoints"]["blob"],
        "Snapshots__ContainerName": "sync-snapshots",
        "AI__Endpoint": "https://" + ai["properties"]["customSubDomainName"] + ".openai.azure.com/openai/v1/",
        "AI__LunaDeployment": "bun-do-luna", "AI__Enabled": "false",
        "AZURE_CLIENT_ID": identity["properties"]["clientId"],
    }
    if any(settings.get(key) != value for key, value in expected.items()):
        raise ValueError("Function endpoints/identity differ from the dedicated group's resources.")
    identities = app["identity"]["userAssignedIdentities"]
    if {i.lower() for i in identities} != {identity["id"].lower()}:
        raise ValueError("Function uses an unexpected identity.")
    hostname = app["properties"]["defaultHostName"]
    (EVIDENCE / "target.json").write_text(json.dumps({
        "subscription": subscription, "resourceGroup": GROUP, "functionId": app["id"],
        "identityId": identity["id"], "cosmosId": cosmos["id"], "blobId": blobs["id"],
        "aiId": ai["id"], "hostname": hostname, "exactConfigurationMatched": True,
    }, indent=2) + "\n")
    return subscription, name, hostname


def publish(subscription, name, role):
    result = run_json("az", "functionapp", "deployment", "source", "config-zip",
                      "--subscription", subscription, "-g", GROUP, "-n", name,
                      "--src", str(EVIDENCE / (role + ".zip")), "--build-remote", "false", "-o", "json")
    (EVIDENCE / (role + "-deployment.json")).write_text(json.dumps(result, indent=2) + "\n")


def request(hostname, route, method="GET", key=None):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    headers = {"x-functions-key": key} if key else {}
    req = urllib.request.Request(f"https://{hostname}/api/{route}", headers=headers, method=method,
                                 data=b"" if method == "POST" else None)
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=180) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def verify_normal(subscription, name, hostname, manifest):
    status, body = request(hostname, "health")
    if status != 200 or json.loads(body) != {"status": "ok"}:
        raise ValueError("Normal health did not pass after restoration.")
    status, _ = request(hostname, "foundation-gate/storage", "POST")
    functions = run_json("az", "functionapp", "function", "list", "--subscription", subscription,
                         "-g", GROUP, "-n", name, "-o", "json")
    names = sorted(f["name"].split("/")[-1] for f in functions)
    if status != 404 or names != manifest["normal"]["names"]:
        raise ValueError("Gate still exposed after restoration.")
    return {"health": 200, "removedGate": status, "functions": names}


def execute(restore_only):
    manifest = json.loads((EVIDENCE / "artifacts.json").read_text())
    # Recovery depends only on its normal artifact, never on the disposable gate ZIP.
    for role in (("normal",) if restore_only else ("gate", "normal")):
        if hashlib.sha256((EVIDENCE / (role + ".zip")).read_bytes()).hexdigest() != manifest[role]["sha256"]:
            raise ValueError("Package changed after review.")
    if not restore_only and manifest["sourceDigest"] != digest():
        raise ValueError("Source changed after build. Rebuild and review.")
    subscription, name, hostname = inspect_target()
    if restore_only:
        publish(subscription, name, "normal")
        result = verify_normal(subscription, name, hostname, manifest)
        (EVIDENCE / "restoration.json").write_text(json.dumps(result, indent=2) + "\n")
        print("Normal package restored and verified.")
        return
    marker = EVIDENCE / "dispatch.json"
    report = {"startedAt": datetime.now(timezone.utc).isoformat(), "calls": {}}
    # A failed/ambiguous run cannot silently repeat paid requests.
    with marker.open("x") as stream:
        json.dump(report, stream)

    def save():
        marker.write_text(json.dumps(report, indent=2) + "\n")

    try:
        publish(subscription, name, "gate")
        keys = run_json("az", "functionapp", "keys", "list", "--subscription", subscription,
                        "-g", GROUP, "-n", name, "-o", "json")
        key = keys["functionKeys"]["default"]  # Memory only, never URL/argv/evidence.
        unauthorized, _ = request(hostname, "foundation-gate/storage", "POST")
        report["unauthorized"] = unauthorized
        save()
        if unauthorized != 401:
            raise ValueError("Gate is not function-key protected.")
        for kind in ("storage", "ai-configuration", "ai-incomplete"):
            report["calls"][kind] = {"dispatchedAt": datetime.now(timezone.utc).isoformat()}
            save()
            status, body = request(hostname, "foundation-gate/" + kind, "POST", key)
            report["calls"][kind]["httpStatus"] = status
            save()
            if status != 200:
                raise ValueError(f"{kind} returned HTTP {status}; no retry.")
            result = json.loads(body)
            allowed = {
                "syntheticId", "result", "errorType", "cosmosCreate", "cosmosRead", "cosmosContentMatches",
                "blobCreate", "blobRead", "blobContentMatches", "blobAccountList", "blobDelete",
                "blobAfterDelete", "cosmosDelete", "cosmosAfterDelete", "blobCleanupError", "cosmosCleanupError",
                "classification", "httpStatus", "inputTokens", "outputTokens", "elapsedMs",
                "providerCode", "providerParameter", "incompleteReason", "schemaRejected",
            }
            if not isinstance(result, dict) or set(result) - allowed:
                raise ValueError("Unexpected gate metadata; response body not saved.")
            report["calls"][kind]["result"] = result
            save()
            if kind == "storage":
                valid = result["result"] == "SUCCEEDED" and result["cosmosAfterDelete"] == result["blobAfterDelete"] == 404
            elif kind == "ai-configuration":
                valid = result["classification"] == "FAILED_CONFIGURATION" and result["schemaRejected"] is True
            else:
                valid = result["classification"] == "INCOMPLETE" and result["incompleteReason"] == "max_output_tokens"
            if not valid:
                raise ValueError(f"{kind} did not prove the expected result; inspect safe metadata, no retry.")
    finally:
        publish(subscription, name, "normal")
        report["restoration"] = verify_normal(subscription, name, hostname, manifest)
        save()
    print("Storage identity and AI negative gates passed; normal package restored.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "run", "restore"))
    action = parser.parse_args().action
    if action == "build":
        build()
    else:
        execute(action == "restore")
