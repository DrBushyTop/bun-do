import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
DOMAIN = "src/BunDo.Domain/BunDo.Domain.csproj"
SCHEMA = "src/BunDo.Android/app/schemas/fi.bundo.data.InboxDatabase/1.json"
VALID_DOMAIN = "<Project />"
BAD_DOMAIN = '<Project><ItemGroup><PackageReference Include="Azure.Core" /></ItemGroup></Project>'


class InvariantHookTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bundo-hook-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Hook fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", ".githooks")
        for name in ("tools/check_invariants.py", "tools/run_invariant_hook.py", ".githooks/pre-commit"):
            destination = self.root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(REPO / name, destination)
        (self.root / ".githooks/pre-commit").chmod(0o755)
        self.write(DOMAIN, VALID_DOMAIN)
        self.git("add", ".")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.STDOUT)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def commit_baseline(self):
        self.git("-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "fixture")

    def hook(self, event="pre-commit", payload=None, cwd=None):
        return subprocess.run(
            [sys.executable, str(self.root / "tools/run_invariant_hook.py"), event],
            cwd=cwd or self.root, input=json.dumps(payload) if payload is not None else None,
            text=True, capture_output=True,
        )

    def test_initial_commit_has_no_schema_baseline(self):
        self.write(SCHEMA, '{"database":{"version":1}}')
        self.git("add", ".")
        result = self.hook()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_commit_rejects_staged_invalid_even_if_worktree_fixed(self):
        self.commit_baseline()
        self.write(DOMAIN, BAD_DOMAIN)
        self.git("add", DOMAIN)
        self.write(DOMAIN, VALID_DOMAIN)
        # The unstaged checker must not mask the staged checker either.
        self.write("tools/check_invariants.py", "raise SystemExit(0)\n")
        before_tree = self.git("write-tree")
        before_status = self.git("status", "--porcelain")
        result = subprocess.run(
            ["git", "commit", "-m", "must fail"], cwd=self.root, text=True, capture_output=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ARCH001", result.stdout + result.stderr)
        self.assertEqual(before_tree, self.git("write-tree"))
        self.assertEqual(before_status, self.git("status", "--porcelain"))
        self.assertEqual(VALID_DOMAIN, (self.root / DOMAIN).read_text())
        self.assertEqual("raise SystemExit(0)\n", (self.root / "tools/check_invariants.py").read_text())

    def test_staged_valid_ignores_unstaged_invalid(self):
        self.commit_baseline()
        self.write(DOMAIN, BAD_DOMAIN)
        result = self.hook()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(BAD_DOMAIN, (self.root / DOMAIN).read_text())

    def test_staged_schema_change_compares_original_repository_head(self):
        self.write(SCHEMA, '{"database":{"version":1}}')
        self.git("add", SCHEMA)
        self.commit_baseline()
        self.write(SCHEMA, '{"database":{"version":1,"changed":true}}')
        self.git("add", SCHEMA)
        self.write(SCHEMA, '{"database":{"version":1}}')
        result = self.hook()
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("DATA002", result.stdout)

    def test_stop_from_subdirectory_passes_with_json_output(self):
        self.commit_baseline()
        result = self.hook("codex-stop", {"stop_hook_active": False}, self.root / "tools")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, json.loads(result.stdout))

    def test_stop_requests_only_one_continuation(self):
        self.commit_baseline()
        self.write(DOMAIN, BAD_DOMAIN)
        first = json.loads(self.hook("codex-stop", {"stop_hook_active": False}).stdout)
        self.assertEqual("block", first["decision"])
        self.assertIn("ARCH001", first["reason"])
        repeated = json.loads(self.hook("codex-stop", {"stop_hook_active": True}).stdout)
        self.assertNotIn("decision", repeated)
        self.assertIn("ARCH001", repeated["systemMessage"])


if __name__ == "__main__":
    unittest.main()
