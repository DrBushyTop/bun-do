import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "local_identity_smoke", Path(__file__).with_name("local-identity-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class RegistrationRetryTests(unittest.TestCase):
    def test_renewal_keeps_identity_and_can_extend_expiry(self):
        original = {"registrationId": "device", "expiresAt": "2026-09-15T12:00:00Z"}
        for expiry in ("2026-09-15T12:00:00Z", "2026-12-14T12:00:00Z"):
            smoke.assert_registration_retry(original, original | {"expiresAt": expiry})
        for changed in ({"registrationId": "other"}, {"expiresAt": "2026-09-14T12:00:00Z"}):
            with self.assertRaises(AssertionError):
                smoke.assert_registration_retry(original, original | changed)
