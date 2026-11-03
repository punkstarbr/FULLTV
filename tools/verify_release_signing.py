#!/usr/bin/env python3
"""Validate the production release-signing environment before Gradle packages a release.

This verifier intentionally accepts only explicit production inputs. It never falls back to
Android's debug keystore and never creates, discovers, or reads a private key.
"""
from __future__ import annotations

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


def fail(message: str) -> NoReturn:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    keystore_path = os.environ.get("KEYSTORE_PATH", "").strip()
    if not keystore_path:
        fail(
            "Production release signing is not configured. Set KEYSTORE_PATH, "
            "KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD and RELEASE_CERT_SHA256."
        )
    if not Path(keystore_path).is_file():
        fail("KEYSTORE_PATH does not point to an existing production keystore file.")

    missing = [name for name in REQUIRED_ENV if not os.environ.get(name, "").strip()]
    if missing:
        fail("Missing required production release-signing variables: " + ", ".join(missing))

    certificate = os.environ["RELEASE_CERT_SHA256"].strip()
    if not CERTIFICATE_SHA256.fullmatch(certificate):
        fail("RELEASE_CERT_SHA256 must be exactly 64 hexadecimal SHA-256 characters.")

    print("Production release-signing inputs are explicitly configured.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
