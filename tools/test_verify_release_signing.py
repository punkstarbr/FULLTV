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
LEGACY_CERT = "01ad68a30d48753a4909ce350ddfd98ec5a30af684c2efd54955184caac69b91"
LEGACY_OPT_IN = "FULLTV_ALLOW_LEGACY_COMPAT_SIGNING"


class ReleaseSigningVerifierTest(unittest.TestCase):
    def run_verifier(self, values=None, version=None, version_code=None):
        env = os.environ.copy()
        for name in (*REQUIRED, LEGACY_OPT_IN):
            env.pop(name, None)
        if values:
            env.update(values)
        command = [sys.executable, str(SCRIPT)]
        if version is not None:
            command.extend(["--version", version])
        if version_code is not None:
            command.extend(["--version-code", str(version_code)])
        return subprocess.run(
            command,
            env=env,
            check=False,
            capture_output=True,
            text=True,
        )

    def base_values(self, keystore, certificate):
        return {
            "KEYSTORE_PATH": str(keystore),
            "KEYSTORE_PASSWORD": "test-password",
            "KEY_ALIAS": "test-alias",
            "KEY_PASSWORD": "test-key-password",
            "RELEASE_CERT_SHA256": certificate,
        }

    def test_missing_production_inputs_fail_closed(self):
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Production release signing is not configured", result.stderr)

    def test_old_debug_escape_is_ignored(self):
        removed_escape = "FULLTV_ALLOW_" + "DEBUG_SIGNED_RELEASE"
        result = self.run_verifier({removed_escape: "true"})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("debug", result.stdout.lower())

    def test_fake_non_legacy_keystore_and_fingerprint_are_accepted_as_shape_only(self):
        # Actual certificate-to-APK matching is independently checked with apksigner after build.
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / "fake-production.jks"
            keystore.write_bytes(b"not a real keystore; never use this to build")
            result = self.run_verifier(self.base_values(keystore, "a" * 64))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_fingerprint_must_be_sha256_hex(self):
        with tempfile.NamedTemporaryFile() as keystore:
            result = self.run_verifier(self.base_values(keystore.name, "not-a-fingerprint"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("RELEASE_CERT_SHA256", result.stderr)

    def test_legacy_debug_certificate_is_rejected_without_explicit_opt_in(self):
        with tempfile.NamedTemporaryFile() as keystore:
            result = self.run_verifier(self.base_values(keystore.name, LEGACY_CERT), "0.11.26", 38)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("blocked by default", result.stderr)

    def test_legacy_debug_certificate_is_allowed_for_exact_approved_version(self):
        with tempfile.NamedTemporaryFile() as keystore:
            for version, version_code in (
                ("0.11.25", 37),
                ("0.11.26", 38),
                ("0.11.27", 39),
                ("0.11.28", 40),
                ("0.11.29", 41),
                ("0.11.30", 42),
                ("0.11.31", 43),
                ("0.11.32", 44),
                ("0.11.33", 45),
                ("0.11.34", 46),
                ("0.11.35", 47),
                ("0.11.36", 48),
            ):
                values = self.base_values(keystore.name, LEGACY_CERT)
                values[LEGACY_OPT_IN] = "1"
                result = self.run_verifier(values, version, version_code)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("not production signing", result.stdout)

    def test_legacy_debug_certificate_requires_exact_version_code(self):
        with tempfile.NamedTemporaryFile() as keystore:
            values = self.base_values(keystore.name, LEGACY_CERT)
            values[LEGACY_OPT_IN] = "1"
            result = self.run_verifier(values, "0.11.26", 37)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("blocked by default", result.stderr)

    def test_legacy_debug_opt_in_does_not_allow_future_version(self):
        with tempfile.NamedTemporaryFile() as keystore:
            values = self.base_values(keystore.name, LEGACY_CERT)
            values[LEGACY_OPT_IN] = "1"
            result = self.run_verifier(values, "0.11.37", 49)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("blocked by default", result.stderr)

    def test_legacy_opt_in_does_not_allow_other_certificates(self):
        with tempfile.NamedTemporaryFile() as keystore:
            values = self.base_values(keystore.name, "a" * 64)
            values[LEGACY_OPT_IN] = "1"
            result = self.run_verifier(values, "0.11.25", 37)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "limited to v0.11.25/code37, v0.11.26/code38, v0.11.27/code39, v0.11.28/code40, v0.11.29/code41, v0.11.30/code42, v0.11.31/code43, v0.11.32/code44, v0.11.33/code45, v0.11.34/code46, v0.11.35/code47, or v0.11.36/code48",
            result.stderr,
        )


if __name__ == "__main__":
    unittest.main()
