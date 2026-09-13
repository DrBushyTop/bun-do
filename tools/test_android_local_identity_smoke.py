import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "android_local_identity_smoke", Path(__file__).with_name("android-local-identity-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class DraftSafetyTests(unittest.TestCase):
    def test_empty_draft_can_be_used(self):
        smoke.require_empty_draft("", "")

    def test_existing_work_is_never_overwritten_even_if_it_looks_synthetic(self):
        for title, description in [
            ("Unfinished task", ""),
            ("", "Unfinished note"),
            ("Synthetic-Alice-123", ""),
            (" ", ""),
        ]:
            with self.subTest(title=title, description=description), self.assertRaises(AssertionError):
                smoke.require_empty_draft(title, description)
