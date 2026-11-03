#!/usr/bin/env python3
"""Re-hash the offline Chaquopy wheelhouse immediately before pip installs it."""
from __future__ import annotations

import argparse
from pathlib import Path

from verify_chaquopy_wheel_hashes import parse_requirements, verify_existing_wheelhouse


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("requirements", type=Path)
    parser.add_argument("wheelhouse", type=Path)
    args = parser.parse_args()
    packages = parse_requirements(args.requirements)
    manifest = verify_existing_wheelhouse(args.wheelhouse, packages)
    print(
        f"Revalidated {len(manifest)} pinned Chaquopy wheels against the source lock "
        f"and manifest in {args.wheelhouse}.",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
