#!/usr/bin/env python3
"""Verify personal isolation and visibility transfers against the Local Functions host."""
import base64
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import uuid

spec = importlib.util.spec_from_file_location("identity_smoke", Path(__file__).with_name("local-identity-smoke.py"))
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)


def main():
    sessions = {}
    for account in ("alice", "bob"):
        status, result, _ = identity.request(f"/dev/token/{account}/valid", method="POST")
        assert status == 200
        token = result["access_token"]
        status, registration, _ = identity.request("/identity/registrations", token=token, method="POST",
            body={"installationId": str(uuid.uuid5(uuid.NAMESPACE_DNS, "bun-do-local-http-smoke"))})
        assert status == 200
        sessions[account] = token, registration["registrationId"]

    def call(account, action, expected="ACCEPTED", **fields):
        token, device = sessions[account]
        status, result, _ = identity.request("/households", token=token, method="POST", maximum_response_bytes=1024 * 1024,
            body={"action": action, "registrationId": device, **fields})
        assert result.get("code") == expected, (action, status, result.get("code"))
        return result

    home = call("alice", "create", workspaceId=str(uuid.uuid4()), name="Private-task HTTP check", displayName="Alice")["household"]
    personal = call("alice", "personal")["household"]
    assert personal["personal"] and len(personal["members"]) == 1
    scope = {"workspaceId": home["id"], "stateEpoch": home["stateEpoch"]}
    invite = call("alice", "invite", **scope)["invitationLink"].split("#", 1)[1].split("/")
    candidate = call("bob", "redeem", **scope, invitationId=invite[2], secret=invite[3], displayName="Bob")["household"]["invitations"][0]
    call("alice", "approve", **scope, invitationId=invite[2], expectedVersion=candidate["version"], confirmationCode=candidate["confirmationCode"])
    token, device = sessions["alice"]
    now = datetime.now(timezone.utc)
    context = {"capturedInstant": now.isoformat().replace("+00:00", "Z"),
        "capturedLocal": now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}",
        "captureZoneId": "Etc/UTC", "captureOffsetSeconds": 0, "zoneSource": "DEVICE", "locale": "en-US", "clockConfidence": "UNKNOWN"}

    def sync(workspace, account="alice", operation=None):
        access, registration = sessions[account]
        return identity.request("/sync", token=access, method="POST", maximum_response_bytes=4 * 1024 * 1024,
            body={"workspaceId": workspace["id"], "stateEpoch": workspace["stateEpoch"], "registrationId": registration,
                "cursor": None, "acknowledgedThrough": "0", "envelopes": [base64.b64encode(operation).decode()] if operation else []})[:2]

    from urllib.parse import urlencode
    status, outcomes, _ = identity.request("/devices/self/outcomes?" + urlencode({"workspaceId": personal["id"],
        "stateEpoch": personal["stateEpoch"], "registrationId": device, "firstSequence": "1", "count": "1"}), token=token)
    assert status == 200
    sequence = int(outcomes["highWater"]) + 1
    task_id = str(uuid.uuid5(uuid.UUID(device), f"task/{sequence}/0"))
    operation = json.dumps({"protocolVersion": 1, "commandVersion": 1, "workspaceId": personal["id"],
        "stateEpoch": personal["stateEpoch"], "deviceId": device, "sequence": str(sequence), "command": "CreateTask",
        "payload": {"taskId": task_id, "title": "Private HTTP canary", "description": "Current private notes"},
        "dependencies": [], "observedVersions": {}, "occurredAtContext": context}).encode()
    owned_private = {task_id}
    try:
        status, created = sync(personal, operation=operation)
        assert status == 200 and created["code"] == "ACCEPTED"
        assert sync(personal, account="bob")[0] == 403
        call("alice", "invite", "PERSONAL_WORKSPACE", workspaceId=personal["id"], stateEpoch=personal["stateEpoch"])
        assert "Private HTTP canary" not in json.dumps(sync(home, account="bob")[1])
        transfer = dict(workspaceId=personal["id"], stateEpoch=personal["stateEpoch"], taskId=task_id,
            transferId=str(uuid.uuid4()), expectedRevision=created["headRevision"], targetWorkspaceId=home["id"], targetEpoch=home["stateEpoch"])
        shared = call("alice", "visibility", **transfer)
        assert call("alice", "visibility", **transfer) == shared
        status, page = sync(home, account="bob")
        assert status == 200
        entities = [task for group in page["groups"] for part in group["parts"] for task in json.loads(part["payload"])]
        task = next(t for t in entities if t.get("id") == shared["taskId"])
        assert task["title"] == "Private HTTP canary" and task.get("capture") is None and task.get("lastChange") is None
        bob_personal = call("bob", "personal")["household"]
        call("bob", "visibility", "NOT_CREATOR", workspaceId=home["id"], stateEpoch=home["stateEpoch"], taskId=task["id"],
            transferId=str(uuid.uuid4()), expectedRevision=page["headRevision"], targetWorkspaceId=bob_personal["id"], targetEpoch=bob_personal["stateEpoch"])
        back = call("alice", "visibility", workspaceId=home["id"], stateEpoch=home["stateEpoch"], taskId=task["id"],
            transferId=str(uuid.uuid4()), expectedRevision=page["headRevision"], targetWorkspaceId=personal["id"], targetEpoch=personal["stateEpoch"])
        owned_private.add(back["taskId"])
        assert back["taskId"] != task["id"]
        assert sync(personal, account="bob")[0] == 403
        print("PASS Local HTTP: private capture, member isolation, invitation denial, sanitized share, exact retry, creator-only return to private")
    finally:
        # Delete only this invocation's private fixtures. Never remove the personal workspace.
        status, page = sync(personal)
        if status == 200:
            latest = {}
            for group in page["groups"]:
                for part in group["parts"]:
                    for item in json.loads(part["payload"]):
                        if item.get("id") in owned_private:
                            latest[item["id"]] = item
            for item in latest.values():
                if item.get("deletion") is not None:
                    continue
                status, outcomes, _ = identity.request("/devices/self/outcomes?" + urlencode({"workspaceId": personal["id"],
                    "stateEpoch": personal["stateEpoch"], "registrationId": device, "firstSequence": "1", "count": "1"}), token=token)
                assert status == 200
                delete = json.loads(operation)
                delete.update(sequence=str(int(outcomes["highWater"]) + 1), command="DeleteTask", payload={"taskId": item["id"]},
                    observedVersions={field: {"fieldVersion": item[field + "Version"]} for field in ("lifecycle", "claim", "hierarchy", "deletion")})
                status, result = sync(personal, operation=json.dumps(delete).encode())
                assert status == 200 and result["code"] == "ACCEPTED"
        current = call("alice", "get", workspaceId=home["id"])["household"]
        call("alice", "delete", **scope, expectedVersion=current["membershipVersion"])


if __name__ == "__main__":
    main()
