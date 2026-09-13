#!/usr/bin/env python3
"""Exercise household membership over Local HTTP. Never prints invitation secrets or codes."""
import importlib.util
from pathlib import Path
import uuid

spec = importlib.util.spec_from_file_location("identity_smoke", Path(__file__).with_name("local-identity-smoke.py"))
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)
request = identity.request


def main():
    sessions = {}
    for person in ("alice", "bob"):
        status, token, _ = request(f"/dev/token/{person}/valid", method="POST")
        assert status == 200
        token = token["access_token"]
        status, registration, _ = request("/identity/registrations", token=token, method="POST", body={
            "installationId": str(uuid.uuid5(uuid.NAMESPACE_DNS, "bun-do-local-http-smoke"))})
        assert status == 200
        sessions[person] = (token, registration["registrationId"])

    def call(person, action, expected="ACCEPTED", **body):
        token, registration = sessions[person]
        status, response, headers = request("/households", token=token, method="POST", body={
            "action": action, "registrationId": registration, **body})
        assert headers.get("Cache-Control") == "no-store"
        assert response.get("code") == expected, f"{action}: unexpected result {status}, {response.get('code')}"
        assert "secretHash" not in str(response)
        return response

    home = call("alice", "create", workspaceId=str(uuid.uuid4()), name="HTTP household smoke", displayName="Alice")["household"]
    scope = {"workspaceId": home["id"], "stateEpoch": home["stateEpoch"]}
    try:
        invite = call("alice", "invite", **scope)
        parts = invite["invitationLink"].split("#", 1)[1].split("/")
        invitation = {"invitationId": parts[2], "secret": parts[3], "displayName": "Bob"}
        pending = call("bob", "redeem", **scope, **invitation)["household"]
        assert not pending["active"] and pending["members"] == [] and pending["name"] == ""
        candidate = pending["invitations"][0]
        retry = call("bob", "redeem", **scope, **invitation)["household"]
        assert retry["invitations"][0]["confirmationCode"] == candidate["confirmationCode"]
        call("bob", "invite", "FORBIDDEN", **scope)
        call("alice", "approve", "CONFIRMATION_MISMATCH", **scope, invitationId=parts[2],
            expectedVersion=candidate["version"], confirmationCode="not-the-code")
        approved = call("alice", "approve", **scope, invitationId=parts[2],
            expectedVersion=candidate["version"], confirmationCode=candidate["confirmationCode"])["household"]
        joined = call("bob", "get", workspaceId=home["id"])["household"]
        assert joined["active"] and len(joined["members"]) == 2
        call("bob", "invite", "FORBIDDEN", **scope)
        owner = next(m for m in joined["members"] if m["owner"])
        member = next(m for m in joined["members"] if not m["owner"])
        call("alice", "leave", "OWNER_MUST_TRANSFER", **scope, expectedVersion=owner["version"])
        cancelled = call("alice", "invite", **scope)
        cancelled_parts = cancelled["invitationLink"].split("#", 1)[1].split("/")
        record = next(i for i in cancelled["household"]["invitations"] if i["id"] == cancelled_parts[2])
        call("alice", "cancel", **scope, invitationId=record["id"], expectedVersion=record["version"])
        call("bob", "redeem", "INVITATION_UNAVAILABLE", **scope, invitationId=record["id"],
            secret=cancelled_parts[3], displayName="Bob")
        transferred = call("alice", "transfer", **scope, memberId=member["id"], expectedVersion=approved["membershipVersion"])["household"]
        call("alice", "invite", "FORBIDDEN", **scope)
        call("bob", "remove", **scope, memberId=owner["id"], expectedVersion=owner["version"])
        call("alice", "get", "FORBIDDEN", workspaceId=home["id"])
        call("bob", "delete", **scope, expectedVersion=transferred["membershipVersion"])
        deleted = call("bob", "get", workspaceId=home["id"])["household"]
        assert deleted["deletedAt"] and not deleted["active"] and deleted["members"] == []
        print("PASS Local HTTP: create, secret redaction, pending/retry, code approval, permissions, cancel, transfer, removal and deletion")
    finally:
        # Only this newly created synthetic household is eligible for cleanup.
        for person in ("alice", "bob"):
            token, registration = sessions[person]
            status, response, _ = request("/households", token=token, method="POST", body={
                "action": "get", "registrationId": registration, "workspaceId": home["id"]})
            view = response.get("household") if isinstance(response, dict) else None
            if status == 200 and view and view["active"] and view["owner"]:
                call(person, "delete", **scope, expectedVersion=view["membershipVersion"])


if __name__ == "__main__":
    main()
