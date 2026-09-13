#!/usr/bin/env python3
"""Verify local identity HTTP behavior. Start Aspire in Local mode before running."""
import json
import uuid
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


BASE = "http://127.0.0.1:7275/api"
OPENER = build_opener(NoRedirect())


def request(path, *, token=None, method="GET", body=None):
    headers = {"Accept": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = Request(BASE + path, headers=headers, method=method, data=data)
    try:
        response = OPENER.open(req, timeout=20)
    except HTTPError as error:
        response = error
    with response:
        body = response.read(32769)
        assert len(body) <= 32768, "Response exceeds expected bound"
        return response.status, json.loads(body) if body else None, response.headers


def main():
    for account in ("alice", "bob"):
        expected = {"issuer": "urn:bun-do:local", "subject": account}
        for attempt in range(2):
            status, body, headers = request(f"/dev/token/{account}/valid", method="POST")
            assert status == 200, "Start Aspire with BunDoIdentity__Mode=Local"
            assert headers.get("Cache-Control") == "no-store"
            token = body["access_token"]
            status, identity, headers = request("/identity", token=token)
            assert status == 200 and identity == expected, "Local identity was not validated"
            assert headers.get("Cache-Control") == "no-store"
            status, registered, _ = request("/identity/registrations", token=token, method="POST",
                body={"installationId": str(uuid.uuid5(uuid.NAMESPACE_DNS, "bun-do-local-http-smoke"))})
            assert status == 200, "Local device registration failed"
            if attempt == 0:
                previous = registered
            else:
                assert registered == previous, "Registration retry changed device identity or expiry"
        print(f"PASS {account}: sign-in and fresh token preserve validated identity")

    for scenario, expected_status in (("expired", 401), ("wrong-audience", 401), ("wrong-scope", 403)):
        status, body, _ = request(f"/dev/token/alice/{scenario}", method="POST")
        assert status == 200
        status, _, _ = request("/identity", token=body["access_token"])
        assert status == expected_status, f"Unexpected result for {scenario}"
        print(f"PASS {scenario}: HTTP {status}")
    assert request("/dev/token/alice/refresh-failure", method="POST")[0] == 503
    assert request("/dev/token/unknown/valid", method="POST")[0] == 400
    assert request("/identity")[0] == 401
    print("PASS refresh failure, unknown account and missing credentials")
    # Tokens stay in process memory and never appear in output or artifacts.


if __name__ == "__main__":
    main()
