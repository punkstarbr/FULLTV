#!/usr/bin/env python3
"""Validate explicit release-signing inputs without silently falling back to debug signing.

The only compatibility exceptions are the owner-approved v0.11.25 through v0.11.36 bridge releases,
signed with the exact legacy certificate already used by public FullTV installations. They are opt-in,
version-scoped, and remain a non-production signing identity. This script never creates or reads a private key.
"""
from __future__ import annotations

import argparse
import os
import re
from pathlib import Path
import sys
from typing import NoReturn

CERTIFICATE_SHA256 = re.compile(r"[0-9a-fA-F]{64}")
REQUIRED_ENV = (
    "KEYSTORE_PATH",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS",
    "KEY_PASSWORD",
    "RELEASE_CERT_SHA256",
)
LEGACY_COMPAT_RELEASES = {
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
}
LEGACY_ANDROID_DEBUG_CERT_SHA256 = "01ad68a30d48753a4909ce350ddfd98ec5a30af684c2efd54955184caac69b91"
LEGACY_COMPAT_OPT_IN = "FULLTV_ALLOW_LEGACY_COMPAT_SIGNING"


def fail(message: str) -> NoReturn:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default="", help="FullTV version being packaged")
    parser.add_argument("--version-code", type=int, default=0, help="Android versionCode being packaged")
    args = parser.parse_args()

    keystore_path = os.environ.get("KEYSTORE_PATH", "").strip()
    if not keystore_path:
        fail(
            "Production release signing is not configured. Set KEYSTORE_PATH, "
            "KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD and RELEASE_CERT_SHA256."
        )
    if not Path(keystore_path).is_file():
        fail("KEYSTORE_PATH does not point to an existing signing keystore file.")

    missing = [name for name in REQUIRED_ENV if not os.environ.get(name, "").strip()]
    if missing:
        fail("Missing required release-signing variables: " + ", ".join(missing))

    certificate = os.environ["RELEASE_CERT_SHA256"].strip()
    if not CERTIFICATE_SHA256.fullmatch(certificate):
        fail("RELEASE_CERT_SHA256 must be exactly 64 hexadecimal SHA-256 characters.")
    certificate = certificate.lower()

    allow_legacy = os.environ.get(LEGACY_COMPAT_OPT_IN, "") == "1"
    if certificate == LEGACY_ANDROID_DEBUG_CERT_SHA256:
        if not allow_legacy or (args.version, args.version_code) not in LEGACY_COMPAT_RELEASES:
            fail(
                "The legacy Android Debug certificate is blocked by default; it is allowed only "
                "for explicitly approved compatibility releases v0.11.25/code37, v0.11.26/code38, "
                "v0.11.27/code39, v0.11.28/code40, v0.11.29/code41, v0.11.30/code42, "
                "v0.11.31/code43, v0.11.32/code44, v0.11.33/code45, v0.11.34/code46, "
                "v0.11.35/code47, and v0.11.36/code48."
            )
        print(
            f"WARNING: v{args.version} (code {args.version_code}) is using the legacy Android Debug certificate for upgrade "
            "compatibility. This is not production signing and must not be reused for later releases."
        )
        return 0

    if allow_legacy:
        fail(
            f"{LEGACY_COMPAT_OPT_IN} is limited to v0.11.25/code37, v0.11.26/code38, "
            "v0.11.27/code39, v0.11.28/code40, v0.11.29/code41, v0.11.30/code42, "
            "v0.11.31/code43, v0.11.32/code44, v0.11.33/code45, v0.11.34/code46, "
            "v0.11.35/code47, or v0.11.36/code48 and the exact "
            "legacy Android Debug certificate."
        )

    print("Explicit release-signing inputs are configured; verify the built APK certificate with apksigner.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
