#!/usr/bin/env python3
"""Select the committed Room-schema baseline for GitHub Actions."""

import os
import subprocess
import sys


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def main() -> int:
    before = os.environ.get("BASE_REV", "")
    if before and set(before) != {"0"}:
        print(git("rev-parse", "--verify", f"{before}^{{commit}}"))
        return 0

    default_branch = os.environ["DEFAULT_BRANCH"]
    current_ref = os.environ["CURRENT_REF"]
    if current_ref == f"refs/heads/{default_branch}":
        # First push of the default branch has no earlier remote history.
        # Use its root commit, not its last parent, for multi-commit first pushes.
        roots = git("rev-list", "--max-parents=0", "HEAD").splitlines()
        if len(roots) != 1:
            raise ValueError("Initial default-branch history must have one root commit.")
        print(roots[0])
    else:
        # A newly pushed feature branch can contain many commits. HEAD^ may
        # already include the schema corruption that this check must reject.
        default_ref = f"refs/remotes/origin/{default_branch}"
        print(git("merge-base", "HEAD", default_ref))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (KeyError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Cannot select schema baseline: {error}", file=sys.stderr)
        sys.exit(1)
