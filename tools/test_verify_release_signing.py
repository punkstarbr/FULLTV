import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("verify_release_signing.py")
REQUIRED = (
    "KEYSTORE_PATH",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS",
    "KEY_PASSWORD",
    "RELEASE_CERT_SHA256",
)


class ReleaseSigningVerifierTest(unittest.TestCase):
    def run_verifier(self, values=None):
        env = os.environ.copy()
        for name in REQUIRED:
            env.pop(name, None)
        if values:
            env.update(values)
        return subprocess.run(
            [sys.executable, str(SCRIPT)],
            env=env,
            check=False,
            capture_output=True,
            text=True,
        )

    def test_missing_production_inputs_fail_closed(self):
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Production release signing is not configured", result.stderr)

    def test_old_debug_escape_is_ignored(self):
        # Construct the removed variable name without leaving an accepted escape in the tree.
        removed_escape = "FULLTV_ALLOW_" + "DEBUG_SIGNED_RELEASE"
        result = self.run_verifier({removed_escape: "true"})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("debug", result.stdout.lower())

    def test_fake_temporary_keystore_and_fingerprint_are_accepted_as_shape_only(self):
        # This deliberately uses an empty temporary file: the verifier checks explicit inputs and
        # format, while apksigner/keytool must validate the actual user-owned keystore later.
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / "fake-production.jks"
            keystore.write_bytes(b"not a real keystore; never use this to build")
            result = self.run_verifier(
                {
                    "KEYSTORE_PATH": str(keystore),
                    "KEYSTORE_PASSWORD": "fake-password",
                    "KEY_ALIAS": "fake-alias",
                    "KEY_PASSWORD": "fake-key-password",
                    "RELEASE_CERT_SHA256": "a" * 64,
                }
            )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_fingerprint_must_be_sha256_hex(self):
        with tempfile.NamedTemporaryFile() as keystore:
            result = self.run_verifier(
                {
                    "KEYSTORE_PATH": keystore.name,
                    "KEYSTORE_PASSWORD": "fake-password",
                    "KEY_ALIAS": "fake-alias",
                    "KEY_PASSWORD": "fake-key-password",
                    "RELEASE_CERT_SHA256": "not-a-fingerprint",
                }
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("RELEASE_CERT_SHA256", result.stderr)


if __name__ == "__main__":
    unittest.main()
