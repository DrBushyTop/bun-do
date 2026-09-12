import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]
SCHEMA = "src/BunDo.Android/app/schemas/fi.bundo.data.InboxDatabase/1.json"


class CiSchemaBaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bundo-ci-base-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "CI fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", "/dev/null")
        schema = self.root / SCHEMA
        schema.parent.mkdir(parents=True)
        schema.write_text('{"database":{"version":1}}')
        self.commit()
        self.base = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/main", self.base)

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True).strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "--quiet", "-m", "fixture")

    def select(self, *, before="0" * 40, branch="feature", default="main"):
        env = {
            **os.environ, "BASE_REV": before, "CURRENT_REF": f"refs/heads/{branch}",
            "DEFAULT_BRANCH": default,
        }
        return subprocess.run(
            [sys.executable, str(REPO / "tools/ci_schema_base.py")],
            cwd=self.root, env=env, text=True, capture_output=True,
        )

    def test_new_branch_schema_change_then_unrelated_commit_is_rejected(self):
        (self.root / SCHEMA).write_text('{"database":{"version":1,"changed":true}}')
        self.commit()
        (self.root / "README.md").write_text("Later unrelated change\n")
        self.commit()
        selected = self.select()
        self.assertEqual(0, selected.returncode, selected.stderr)
        self.assertEqual(self.base, selected.stdout.strip())
        checked = subprocess.run(
            [
                sys.executable, str(REPO / "tools/check_invariants.py"),
                "--root", str(self.root), "--git-base", selected.stdout.strip(),
            ],
            text=True, capture_output=True,
        )
        self.assertEqual(1, checked.returncode, checked.stdout + checked.stderr)
        self.assertIn("DATA002", checked.stdout)

    def test_explicit_push_or_pull_request_base_is_preserved(self):
        selected = self.select(before=self.base, default="not-fetched")
        self.assertEqual(0, selected.returncode, selected.stderr)
        self.assertEqual(self.base, selected.stdout.strip())

    def test_missing_default_branch_fails_instead_of_using_head_parent(self):
        selected = self.select(default="not-fetched")
        self.assertNotEqual(0, selected.returncode)
        self.assertEqual("", selected.stdout)

    def test_first_default_branch_push_uses_root_commit(self):
        initial = self.select(branch="main")
        self.assertEqual(0, initial.returncode, initial.stderr)
        self.assertEqual(self.base, initial.stdout.strip())
        (self.root / "README.md").write_text("Second commit\n")
        self.commit()
        later = self.select(branch="main")
        self.assertEqual(0, later.returncode, later.stderr)
        self.assertEqual(self.base, later.stdout.strip())


if __name__ == "__main__":
    unittest.main()
