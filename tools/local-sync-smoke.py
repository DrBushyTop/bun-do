#!/usr/bin/env python3
"""Exercise exact typed sync envelopes against the running Local Functions host."""
import base64
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import uuid
from urllib.parse import urlencode

spec = importlib.util.spec_from_file_location("identity_smoke", Path(__file__).with_name("local-identity-smoke.py"))
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)


def main():
    status, body, _ = identity.request("/dev/token/alice/valid", method="POST")
    assert status == 200
    token = body["access_token"]
    status, registration, _ = identity.request("/identity/registrations", token=token, method="POST", body={
        "installationId": str(uuid.uuid5(uuid.NAMESPACE_DNS, "bun-do-local-http-smoke"))})
    assert status == 200
    device = registration["registrationId"]
    workspace = str(uuid.uuid4())
    status, result, _ = identity.request("/households", token=token, method="POST", body={
        "action": "create", "registrationId": device, "workspaceId": workspace,
        "name": "Synthetic sync check", "displayName": "Alice"})
    assert status == 200
    epoch = result["household"]["stateEpoch"]
    now = datetime.now(timezone.utc)
    context = {"capturedInstant": now.isoformat().replace("+00:00", "Z"),
        "capturedLocal": now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}",
        "captureZoneId": "Etc/UTC", "captureOffsetSeconds": 0, "zoneSource": "DEVICE",
        "locale": "en-US", "clockConfidence": "UNKNOWN"}

    def task_id(sequence):
        return str(uuid.uuid5(uuid.UUID(device), f"task/{sequence}/0"))

    def envelope(sequence, command, payload, observed=None, dependencies=None):
        return json.dumps({"protocolVersion": 1, "commandVersion": 1,
            "workspaceId": workspace, "stateEpoch": epoch, "deviceId": device, "sequence": str(sequence),
            "command": command, "payload": payload, "dependencies": dependencies or [],
            "observedVersions": observed or {}, "occurredAtContext": context}, separators=(",", ":")).encode()

    def send(operation=None, cursor=None, acknowledged=0):
        status, response, headers = identity.request("/sync", token=token, method="POST", body={
            "workspaceId": workspace, "stateEpoch": epoch, "registrationId": device, "cursor": cursor,
            "acknowledgedThrough": str(acknowledged),
            "envelopes": [base64.b64encode(operation).decode()] if operation else []})
        assert status == 200, f"sync HTTP {status}"
        assert headers.get("Cache-Control") == "no-store"
        revision = int(response["afterRevision"])
        for group in response["groups"]:
            assert int(group["revision"]) == revision + 1
            assert group["partCount"] == 1 and len(group["parts"]) == 1
            assert group["parts"][0]["partIndex"] == 0
            payload = group["parts"][0]["payload"]
            assert hashlib.sha256(payload.encode()).hexdigest() == group["digest"]
            revision += 1
        assert revision == int(response["throughRevision"])
        return response

    try:
        create = envelope(1, "CreateTask", {"taskId": task_id(1), "title": "Synthetic coffee", "description": None})
        first = send(create)
        assert first["code"] == "ACCEPTED"
        assert send(create)["receipts"] == first["receipts"]
        assert send(b" " + create)["code"] == "OPERATION_ID_REUSED"
        assert send(envelope(3, "CreateTask", {"taskId": task_id(3), "title": "Gap", "description": None}))["code"] == "SEQUENCE_GAP"
        task = first["receipts"][0]["task"]
        edit = envelope(2, "EditTask", {"taskId": task_id(1), "title": "Synthetic tea"},
            {"title": {"humanVersion": task["titleVersion"]["humanVersion"]},
             "deletion": {"fieldVersion": task["deletionVersion"]}}, [f"{device}:1"])
        assert send(edit)["code"] == "ACCEPTED"
        rejected = envelope(3, "CreateTask", {"taskId": task_id(3), "title": "", "description": None})
        assert send(rejected)["code"] == "INVALID_TITLE"
        discarded = envelope(4, "DiscardBlockedIntent", {"rejectedDependency": f"{device}:3"},
            dependencies=[f"{device}:3"])
        assert send(discarded)["code"] == "BLOCKED_DEPENDENCY"
        independent = envelope(5, "CreateTask", {"taskId": task_id(5), "title": "Independent", "description": None})
        assert send(independent)["code"] == "ACCEPTED"
        page = send(acknowledged=5)
        assert len(page["groups"]) == 6
        assert send(cursor=page["cursor"], acknowledged=5)["groups"] == []
        status, outcomes, _ = identity.request("/devices/self/outcomes?" + urlencode({
            "workspaceId": workspace, "stateEpoch": epoch, "registrationId": device,
            "firstSequence": "1", "count": "6"}), token=token)
        assert status == 200
        assert outcomes["highWater"] == "5"
        assert [item["state"] for item in outcomes["outcomes"]] == [
            "ACCEPTED", "ACCEPTED", "REJECTED", "REJECTED", "ACCEPTED", "NOT_SEEN"]
        assert outcomes["outcomes"][0]["fingerprint"] == hashlib.sha256(create).hexdigest()
        assert outcomes["outcomes"][0]["receipt"] == first["receipts"][0]
        print("PASS Local HTTP: exact retry/hash, sequence gap, versioned edit, rejected create, blocked dependency, independent continuation, complete groups and acknowledgement")
        print("PASS Local HTTP: bounded outcome lookup returns original receipts and hashes without replay")
        snapshot_id = str(uuid.uuid4())
        query = urlencode({"workspaceId": workspace, "stateEpoch": epoch, "registrationId": device})
        route = f"/snapshots/{snapshot_id}/manifest?{query}"
        status, manifest, headers = identity.request(route, token=token, method="POST")
        assert status == 200, (status, manifest)
        assert headers.get("Cache-Control") == "no-store"
        assert manifest["documentCount"] == 2 and manifest["schemaVersion"] == 1
        assert identity.request(route, token=token, method="POST")[1] == manifest
        snapshots = []
        for chunk in manifest["chunks"]:
            # Read exact bytes, not reserialized JSON, for artifact digest verification.
            import urllib.request
            request = urllib.request.Request(identity.BASE + f"/snapshots/{snapshot_id}/{chunk['index']}?{query}",
                headers={"Authorization": f"Bearer {token}"})
            with urllib.request.urlopen(request) as response:
                content = response.read()
                assert response.headers.get("Cache-Control") == "no-store"
            assert len(content) == chunk["bytes"] and hashlib.sha256(content).hexdigest() == chunk["digest"]
            snapshots.extend(json.loads(content)["tasks"])
        assert {item["title"] for item in snapshots} == {"Synthetic tea", "Independent"}
        assert identity.request(route)[0] == 401
        assert identity.request(f"/snapshots/{snapshot_id}/99?{query}", token=token)[0] == 409
        assert send(cursor=manifest["cursor"])["throughRevision"] == str(int(manifest["revision"]) + 1)
        print("PASS Local HTTP: immutable authenticated snapshot, retry, chunk digests, range rejection and delta continuation")
    finally:
        status, response, _ = identity.request("/households", token=token, method="POST", body={
            "action": "get", "registrationId": device, "workspaceId": workspace})
        if status == 200:
            status, _, _ = identity.request("/households", token=token, method="POST", body={
                "action": "delete", "registrationId": device, "workspaceId": workspace, "stateEpoch": epoch,
                "expectedVersion": response["household"]["membershipVersion"]})
            assert status == 200


if __name__ == "__main__":
    main()
