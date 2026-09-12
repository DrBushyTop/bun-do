#!/usr/bin/env python3
"""Exercise a real force-stop with saved synthetic task/draft text on a Bun Do AVD."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="Explicit emulator serial, such as emulator-5554.")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    prefix = [str(sdk / "platform-tools/adb"), "-s", args.serial]

    def adb(*command):
        return subprocess.check_output([*prefix, *command], text=True)

    profile = adb("emu", "avd", "name").splitlines()[0].strip()
    if profile not in {"bun-do-a", "bun-do-b"}:
        raise SystemExit("Use a dedicated bun-do-a or bun-do-b AVD. This test writes synthetic tasks.")

    def tree():
        adb("shell", "uiautomator", "dump", "/sdcard/bundo-smoke.xml")
        return ET.fromstring(adb("shell", "cat", "/sdcard/bundo-smoke.xml"))

    def node(tag=None, text=None):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            for item in tree().iter("node"):
                if (tag is None or item.get("resource-id") == tag) and (
                    text is None or text in item.get("text", "")
                ):
                    return item
            time.sleep(0.1)
        raise AssertionError(f"Screen never showed tag={tag!r}, text={text!r}")

    def tap(tag=None, text=None):
        item = node(tag, text)
        assert item.get("enabled") == "true", f"Control {tag or text} is disabled"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", item.get("bounds")))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def replace_title(value):
        tap(tag="title")
        adb("shell", "input", "keycombination", "113", "29")  # Control+A
        adb("shell", "input", "text", value)
        node(text=value)
        node(tag="draft-saved")

    def restart():
        adb("shell", "am", "force-stop", "fi.bundo")
        adb("shell", "am", "start", "-W", "-n", "fi.bundo/.MainActivity")

    airplane = adb("shell", "settings", "get", "global", "airplane_mode_on").strip()
    wifi = adb("shell", "settings", "get", "global", "wifi_on").strip()
    marker = str(time.time_ns())
    task = f"Offline-task-{marker}"
    draft = f"Recover-draft-{marker}"
    try:
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
        adb("shell", "svc", "wifi", "disable")
        restart()
        tap(tag="capture")
        replace_title(task)
        tap(tag="save")
        restart()
        node(text=task)
        tap(tag="capture")
        replace_title(draft)
        # Do not press Back: the displayed saved indicator must mean disk durability.
        restart()
        tap(tag="capture")
        node(text=draft)
        tap(tag="back")
        node(text=task)
        print(f"{profile}: offline task and unfinished draft survived real force-stop/restart.")
    finally:
        adb("shell", "cmd", "connectivity", "airplane-mode", "enable" if airplane == "1" else "disable")
        adb("shell", "svc", "wifi", "enable" if wifi == "1" else "disable")
        adb("shell", "rm", "-f", "/sdcard/bundo-smoke.xml")


if __name__ == "__main__":
    main()
