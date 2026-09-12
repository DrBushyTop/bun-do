#!/usr/bin/env python3
"""Run the repository checks from Git or Codex without changing tracked files."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def git_root() -> Path:
    return Path(subprocess.check_output(
        ["git", "rev-parse", "--show-toplevel"], text=True,
    ).strip())


def check_command(root: Path, git: Path, base: str) -> list[str]:
    return [
        sys.executable, str(root / "tools/check_invariants.py"),
        "--root", str(root), "--git-root", str(git), "--git-base", base,
    ]


def pre_commit(root: Path) -> int:
    has_head = subprocess.run(
        ["git", "rev-parse", "--verify", "HEAD"],
        cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0
    # checkout-index reads the active index, including the temporary index used
    # by `git commit --only`. No stash, checkout, index refresh, or network access.
    with tempfile.TemporaryDirectory(prefix="bundo-staged-") as directory:
        snapshot = Path(directory)
        subprocess.run(
            ["git", "checkout-index", "--all", f"--prefix={snapshot}/"],
            cwd=root, check=True,
        )
        return subprocess.run(
            check_command(snapshot, root, "HEAD" if has_head else ""), cwd=snapshot,
        ).returncode


def codex_stop(root: Path) -> int:
    event = json.load(sys.stdin)
    result = subprocess.run(
        check_command(root, root, "HEAD"), cwd=root, text=True, capture_output=True,
    )
    if result.returncode:
        reason = (
            "Repository invariants failed. Fix violations or report the concrete blocker. "
            "Run python3 tools/check_invariants.py to verify.\n"
            + result.stdout + result.stderr
        )
        # Request at most one automatic continuation. A persistent failure must
        # reach the user, not trap the agent in an unlimited Stop-hook loop.
        if event.get("stop_hook_active"):
            print(json.dumps({"systemMessage": reason}))
        else:
            print(json.dumps({"decision": "block", "reason": reason}))
    else:
        print("{}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("event", choices=("pre-commit", "codex-stop"))
    args = parser.parse_args()
    try:
        root = git_root()
        return pre_commit(root) if args.event == "pre-commit" else codex_stop(root)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Invariant hook could not complete: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
