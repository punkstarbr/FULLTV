#!/usr/bin/env python3
"""Download and verify the exact Chaquopy wheels used by the Android build.

The wheelhouse is deliberately persistent: Gradle's Chaquopy install tasks consume this
same directory with ``--no-index --find-links`` and ``--require-hashes``. The previous
implementation verified downloads in an ephemeral directory and then let Chaquopy perform a
second, unverified online download; that is not a supply-chain check.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from collections import Counter
from pathlib import Path

CHAQUOPY_INDEX = "https://chaquo.com/pypi-13.1"
MANIFEST_NAME = "wheelhouse-manifest.json"
NATIVE_GROUPS = {
    "api24": {"lxml", "pycryptodome"},
    "xml-libs": {"chaquopy-libxml2", "chaquopy-libxslt"},
}
PURE_PACKAGES = {
    "pysocks",
    "attrs",
    "certifi",
    "charset-normalizer",
    "h11",
    "idna",
    "isodate",
    "outcome",
    "pycountry",
    "requests",
    "sniffio",
    "sortedcontainers",
    "streamlink",
    "trio",
    "trio-websocket",
    "urllib3",
    "websocket-client",
    "wsproto",
    "yt-dlp",
}
ABI_TAGS = {
    "arm64-v8a": {"api24": "android_24_arm64_v8a", "xml-libs": "android_21_arm64_v8a"},
    "armeabi-v7a": {"api24": "android_24_armeabi_v7a", "xml-libs": "android_16_armeabi_v7a"},
    "x86": {"api24": "android_24_x86", "xml-libs": "android_16_x86"},
    "x86_64": {"api24": "android_24_x86_64", "xml-libs": "android_21_x86_64"},
}


def parse_requirements(path: Path) -> dict[str, tuple[str, list[str]]]:
    """Parse the small, intentionally pinned requirements format used by the app."""
    packages: dict[str, tuple[str, list[str]]] = {}
    current_name: str | None = None
    current_version: str | None = None
    current_hashes: list[str] = []

    def save() -> None:
        if current_name is None or current_version is None:
            return
        key = current_name.lower().replace("_", "-")
        if not current_hashes:
            raise ValueError(f"No hashes recorded for {current_name}=={current_version}")
        packages[key] = (current_version, list(current_hashes))

    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^([A-Za-z0-9_.-]+)==([^\s\\]+)", line)
        if match:
            save()
            current_name, current_version = match.group(1), match.group(2)
            current_hashes = []
            continue
        hash_match = re.search(r"--hash=sha256:([0-9a-f]{64})", line)
        if hash_match and current_name is not None:
            current_hashes.append(hash_match.group(1))
    save()

    expected = PURE_PACKAGES | set().union(*NATIVE_GROUPS.values())
    actual = set(packages)
    if actual != expected:
        raise ValueError(
            f"Package classification mismatch. Missing={sorted(expected - actual)}, "
            f"unclassified={sorted(actual - expected)}"
        )
    return packages


def requirements_text(
    names: set[str], packages: dict[str, tuple[str, list[str]]]
) -> str:
    lines: list[str] = []
    for name in sorted(names):
        version, hashes = packages[name]
        lines.append(f"{name}=={version} \\")
        lines.extend(f"    --hash=sha256:{digest} \\" for digest in hashes[:-1])
        lines.append(f"    --hash=sha256:{hashes[-1]}")
    return "\n".join(lines) + "\n"


def verify_group(
    python: str,
    req_file: Path,
    wheelhouse: Path,
    *,
    platform: str | None = None,
) -> None:
    """Download one locked group into the shared wheelhouse with pip hash checking."""
    wheelhouse.mkdir(parents=True, exist_ok=True)
    command = [
        python,
        "-m",
        "pip",
        "download",
        "--disable-pip-version-check",
        "--no-cache-dir",
        "--require-hashes",
        "--only-binary=:all:",
        "--no-deps",
        "--extra-index-url",
        CHAQUOPY_INDEX,
    ]
    if platform:
        command.extend(
            [
                "--platform",
                platform,
                "--python-version",
                "3.11",
                "--implementation",
                "cp",
                "--abi",
                "cp311",
            ]
        )
    command.extend(["-r", str(req_file), "-d", str(wheelhouse)])
    subprocess.run(command, check=True)


def reset_wheelhouse(path: Path) -> None:
    """Remove stale/unreviewed files before creating the verified input directory."""
    path = path.absolute()
    if path == path.parent or path.is_symlink():
        raise ValueError(f"Refusing to reset unsafe wheelhouse path: {path}")
    if path.exists() and not path.is_dir():
        raise ValueError(f"Wheelhouse path is not a directory: {path}")
    path.mkdir(parents=True, exist_ok=True)
    for child in path.iterdir():
        if child.is_dir() and not child.is_symlink():
            shutil.rmtree(child)
        else:
            child.unlink()


def expected_wheel_counts() -> Counter[str]:
    counts = Counter({name: 1 for name in PURE_PACKAGES})
    for _abi in ABI_TAGS:
        for names in NATIVE_GROUPS.values():
            counts.update(names)
    return counts


def wheel_package_name(filename: str) -> str:
    """Normalize the distribution part of a wheel filename."""
    return filename.split("-", 1)[0].replace("_", "-").lower()


def validate_wheelhouse(
    wheelhouse: Path,
    packages: dict[str, tuple[str, list[str]]],
) -> list[dict[str, str]]:
    """Ensure every downloaded wheel is locked and every requested wheel is present."""
    if wheelhouse.is_symlink() or not wheelhouse.is_dir():
        raise ValueError(f"Wheelhouse is missing or unsafe: {wheelhouse}")
    wheels = sorted(wheelhouse.glob("*.whl"))
    if any(wheel.is_symlink() for wheel in wheels):
        raise ValueError("Wheelhouse must not contain symbolic-link wheels")
    actual_counts = Counter(wheel_package_name(wheel.name) for wheel in wheels)
    expected_counts = expected_wheel_counts()
    if actual_counts != expected_counts:
        raise ValueError(
            "Wheelhouse contents do not match the locked ABI matrix. "
            f"Expected={dict(sorted(expected_counts.items()))}, "
            f"actual={dict(sorted(actual_counts.items()))}"
        )

    manifest: list[dict[str, str]] = []
    for wheel in wheels:
        package = wheel_package_name(wheel.name)
        digest = hashlib.sha256(wheel.read_bytes()).hexdigest()
        allowed = packages[package][1]
        if digest not in allowed:
            raise ValueError(
                f"Wheel hash is not pinned for {wheel.name}: sha256:{digest}"
            )
        manifest.append({"file": wheel.name, "sha256": digest})
    return manifest


def write_manifest(wheelhouse: Path, manifest: list[dict[str, str]]) -> None:
    (wheelhouse / MANIFEST_NAME).write_text(
        json.dumps({"schema": 1, "wheels": manifest}, indent=2) + "\n",
        encoding="utf-8",
    )


def verify_existing_wheelhouse(
    wheelhouse: Path,
    packages: dict[str, tuple[str, list[str]]],
) -> list[dict[str, str]]:
    """Re-hash the exact offline install directory against source pins and its manifest."""
    manifest_path = wheelhouse / MANIFEST_NAME
    if manifest_path.is_symlink() or not manifest_path.is_file():
        raise ValueError("Verified wheelhouse manifest is missing or unsafe")
    actual = validate_wheelhouse(wheelhouse, packages)
    try:
        recorded = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("Verified wheelhouse manifest is invalid") from error
    if recorded != {"schema": 1, "wheels": actual}:
        raise ValueError("Wheelhouse contents no longer match the verified manifest")
    return actual


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify and persist all pinned Chaquopy wheels for all four Android ABIs."
    )
    parser.add_argument("python", help="Python executable used by Chaquopy")
    parser.add_argument("requirements", type=Path)
    parser.add_argument(
        "--wheelhouse",
        type=Path,
        required=True,
        help="Persistent directory consumed by Chaquopy's offline pip install",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    packages = parse_requirements(args.requirements)
    reset_wheelhouse(args.wheelhouse)

    pure_file = args.wheelhouse / ".pure-requirements.txt"
    pure_file.write_text(requirements_text(PURE_PACKAGES, packages), encoding="utf-8")
    try:
        verify_group(args.python, pure_file, args.wheelhouse)
        print(f"Verified SHA-256 for {len(PURE_PACKAGES)} universal Python wheels", flush=True)
        for abi, groups in ABI_TAGS.items():
            for group_name, platform in groups.items():
                names = NATIVE_GROUPS[group_name]
                req_file = args.wheelhouse / f".{group_name}-{abi}.txt"
                req_file.write_text(requirements_text(names, packages), encoding="utf-8")
                verify_group(
                    args.python,
                    req_file,
                    args.wheelhouse,
                    platform=platform,
                )
                print(f"Verified SHA-256 for {group_name} wheels on {abi}", flush=True)
        manifest = validate_wheelhouse(args.wheelhouse, packages)
        write_manifest(args.wheelhouse, manifest)
    finally:
        # Requirement fragments are not installation inputs and must not be mistaken for
        # wheels if a later build inspects this directory.
        pure_file.unlink(missing_ok=True)
        for fragment in args.wheelhouse.glob(".*-*.txt"):
            fragment.unlink(missing_ok=True)

    print(
        f"All {len(manifest)} pinned Chaquopy wheels passed strict SHA-256 verification "
        f"and are ready in {args.wheelhouse}.",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
