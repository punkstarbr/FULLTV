import hashlib
from pathlib import Path
import tempfile
import unittest

import importlib.util


SCRIPT = Path(__file__).with_name("verify_chaquopy_wheel_hashes.py")
SPEC = importlib.util.spec_from_file_location("verify_chaquopy_wheel_hashes", SCRIPT)
assert SPEC and SPEC.loader
WHEELS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(WHEELS)


class ChaquopyWheelhouseTest(unittest.TestCase):
    def make_locked_wheelhouse(self, directory):
        packages = {}
        for package, count in WHEELS.expected_wheel_counts().items():
            hashes = []
            wheel_prefix = package.replace("-", "_")
            for index in range(count):
                content = f"{package}-{index}".encode()
                filename = f"{wheel_prefix}-1.0-{index}-py3-none-any.whl"
                (directory / filename).write_bytes(content)
                hashes.append(hashlib.sha256(content).hexdigest())
            packages[package] = ("1.0", hashes)
        return packages

    def test_validation_attests_all_four_abi_slots_and_hashes(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            packages = self.make_locked_wheelhouse(directory)
            manifest = WHEELS.validate_wheelhouse(directory, packages)
            self.assertEqual(len(manifest), sum(WHEELS.expected_wheel_counts().values()))
            self.assertEqual({entry["file"] for entry in manifest}, {
                wheel.name for wheel in directory.glob("*.whl")
            })

    def test_validation_rejects_bytes_changed_after_download(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            packages = self.make_locked_wheelhouse(directory)
            wheel = next(directory.glob("lxml-*.whl"))
            wheel.write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "not pinned"):
                WHEELS.validate_wheelhouse(directory, packages)

    def test_install_time_revalidation_checks_manifest_and_every_wheel(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            packages = self.make_locked_wheelhouse(directory)
            manifest = WHEELS.validate_wheelhouse(directory, packages)
            WHEELS.write_manifest(directory, manifest)
            self.assertEqual(
                WHEELS.verify_existing_wheelhouse(directory, packages), manifest
            )
            (directory / manifest[0]["file"]).write_bytes(b"changed after preflight")
            with self.assertRaisesRegex(ValueError, "not pinned"):
                WHEELS.verify_existing_wheelhouse(directory, packages)

    def test_download_command_uses_hash_mode_and_single_destination(self):
        # This is a source-level contract test: actual downloads belong to an Android build and
        # are intentionally not performed in isolated unit tests.
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('"--require-hashes"', source)
        self.assertIn('"--no-cache-dir"', source)
        self.assertIn('"--extra-index-url"', source)
        self.assertIn('"--wheelhouse"', source)
        self.assertNotIn("import tempfile", source)
        self.assertNotIn("tempfile.", source)


if __name__ == "__main__":
    unittest.main()
