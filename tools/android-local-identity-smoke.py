#!/usr/bin/env python3
"""Verify account isolation on a dedicated Bun Do AVD with the Local APK.

Start Aspire in Local mode and install app-local.apk first. This writes labeled
synthetic tasks without clearing existing data. Connectivity is restored after
execution. The Microsoft APK and its credentials are untouched.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def require_empty_draft(title, description):
    if title or description:
        raise AssertionError("An existing draft is open. Save or export it before running the smoke; it was not changed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    prefix = [str(sdk / "platform-tools/adb"), "-s", args.serial]
    package = "fi.bundo.local"
    dump = "/sdcard/bundo-local-identity-smoke.xml"

    def adb(*command):
        return subprocess.check_output([*prefix, *command], text=True, timeout=45)

    profile = adb("emu", "avd", "name").splitlines()[0].strip()
    if profile not in {"bun-do-a", "bun-do-b"}:
        raise SystemExit("Use a dedicated bun-do-a or bun-do-b AVD.")
    assert adb("shell", "getprop", "sys.boot_completed").strip() == "1"
    assert adb("shell", "pm", "path", package).startswith("package:"), "Install app-local.apk first"
    source = Path(__file__).resolve().parents[1] / "src/BunDo.Android/app/src"
    translations = []
    for locale in ("values", "values-en"):
        strings = {}
        for variant in ("main", "local"):
            for filename in ("identity_strings.xml", "account_strings.xml", "strings.xml"):
                path = source / variant / "res" / locale / filename
                if path.exists():
                    strings.update({item.attrib["name"]: item.text for item in ET.parse(path).findall("string")})
        translations.append(strings)

    def labels(key, account=None):
        return {strings[key].replace("%1$s", account or "") for strings in translations}

    def tree():
        adb("shell", "uiautomator", "dump", dump)
        return ET.fromstring(adb("shell", "cat", dump))

    def wait_for(key=None, tag=None, text=None, account=None, scroll=False):
        expected = labels(key, account) if key else {text}
        deadline = time.monotonic() + 50
        step = 0
        while time.monotonic() < deadline:
            for node in tree().iter("node"):
                if node.get("package") != package:
                    continue
                if (tag and node.get("resource-id") == tag) or (not tag and node.get("text") in expected):
                    return node
            if scroll:
                # First return toward the top, then search the whole scrollable form.
                if step % 10 < 3:
                    adb("shell", "input", "swipe", "540", "500", "540", "2000", "150")
                else:
                    adb("shell", "input", "swipe", "540", "2000", "540", "500", "150")
                step += 1
            time.sleep(0.2)
        raise AssertionError(f"Screen did not show {key or tag or text}, account={account}")

    def tap(key=None, tag=None, text=None, account=None):
        node = wait_for(key, tag, text, account, scroll=True)
        assert node.get("enabled") == "true", f"{key or tag or text} disabled"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def connectivity(online):
        adb("shell", "cmd", "connectivity", "airplane-mode", "disable" if online else "enable")
        adb("shell", "svc", "wifi", "enable" if online else "disable")
        time.sleep(3)

    def restart():
        adb("shell", "am", "force-stop", package)
        adb("shell", "am", "start", "-W", "-n", f"{package}/fi.bundo.MainActivity")
        wait_for(tag="settings")

    def account_page():
        tap(tag="settings")
        tap(tag="account")

    def inbox():
        adb("shell", "input", "keyevent", "4")
        wait_for(tag="capture")

    def sign_out():
        tap(tag="account-sign-out")
        tap(key="account_continue")
        wait_for(key="account_anonymous", scroll=True)

    def choose(account, switching=False):
        tap(tag=f"account-use-{account.lower()}")
        if switching:
            tap(key="account_continue")
        wait_for(key="identity_selected", account=account, scroll=True)

    def capture(title):
        tap(tag="capture")
        tap(tag="title")
        field = wait_for(tag="title")
        description = wait_for(tag="description")
        require_empty_draft(field.get("text", ""), description.get("text", ""))
        adb("shell", "input", "text", title)
        assert wait_for(tag="title").get("text") == title, "Keyboard did not enter the requested synthetic title"
        tap(tag="save")
        wait_for(text=title)

    def absent(title):
        assert not any(node.get("text") == title for node in tree().iter("node")), f"Other account leaked {title}"

    airplane = adb("shell", "settings", "get", "global", "airplane_mode_on").strip()
    wifi = adb("shell", "settings", "get", "global", "wifi_on").strip()
    marker = str(time.time_ns())
    alice = f"Synthetic-Alice-{marker}"
    bob = f"Synthetic-Bob-{marker}"
    offline = f"Synthetic-offline-{marker}"
    try:
        connectivity(True)
        restart()
        account_page()
        # An earlier run may have left an active account. Retain, never delete, its data.
        if any(node.get("text") in labels("account_signed_in") for node in tree().iter("node")):
            sign_out()
        choose("Alice")
        inbox()
        capture(alice)
        account_page()
        tap(tag="account-diagnostics")
        tap(tag="account-refresh")
        wait_for(key="identity_refreshed", scroll=True)
        choose("Bob", switching=True)
        inbox()
        absent(alice)
        capture(bob)
        print(f"PASS {profile}: Alice/Bob sign-in, refresh, account-specific task visibility")

        connectivity(False)
        restart()
        wait_for(text=bob)
        absent(alice)
        capture(offline)
        account_page()
        tap(tag="account-diagnostics")
        tap(tag="account-refresh")
        wait_for(key="identity_api_unavailable", scroll=True)
        wait_for(key="identity_selected", account="Bob", scroll=True)
        sign_out()
        inbox()
        absent(bob)
        absent(offline)
        account_page()
        tap(tag="account-use-alice")
        wait_for(key="identity_api_unavailable", scroll=True)
        print(f"PASS {profile}: offline restart/edit, failed refresh, sign-out quarantine and denied offline sign-in")

        connectivity(True)
        choose("Alice")
        inbox()
        wait_for(text=alice)
        absent(bob)
        absent(offline)
        account_page()
        choose("Bob", switching=True)
        inbox()
        wait_for(text=bob)
        wait_for(text=offline)
        absent(alice)
        account_page()
        sign_out()
        print(f"PASS {profile}: same-account unlock restores pending work without importing another account")
    finally:
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if airplane == "1" else "disable")
        adb("shell", "svc", "wifi", "enable" if wifi == "1" else "disable")
        adb("shell", "rm", "-f", dump)


if __name__ == "__main__":
    main()
