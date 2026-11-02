#!/usr/bin/env python3
"""Validate the release-signing environment before Gradle packages a release."""

from __future__ import annotations

import os
from pathlib import Path
import sys


keystore_path = os.environ.get("KEYSTORE_PATH")
if keystore_path and not Path(keystore_path).is_file():
    print("KEYSTORE_PATH does not point to an existing keystore file.", file=sys.stderr)
    raise SystemExit(1)

has_release_signing = bool(
    keystore_path
    and Path(keystore_path).is_file()
    and os.environ.get("KEYSTORE_PASSWORD")
    and os.environ.get("KEY_ALIAS")
    and os.environ.get("KEY_PASSWORD")
)
allow_debug_signed_release = os.environ.get("FULLTV_ALLOW_DEBUG_SIGNED_RELEASE") == "true"

if not has_release_signing and not allow_debug_signed_release:
    print(
        "Release signing is not configured. Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS "
        "and KEY_PASSWORD. FULLTV_ALLOW_DEBUG_SIGNED_RELEASE=true is an explicitly insecure "
        "temporary override only for preserving an existing certificate.",
        file=sys.stderr,
    )
    raise SystemExit(1)
