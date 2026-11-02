#!/usr/bin/env python3
"""Verify every pinned Chaquopy wheel hash, including ABI-specific native wheels.

Chaquopy installs native wheels in separate per-ABI pip invocations. This preflight
uses pip's strict --require-hashes mode independently for the universal wheels and
for each Android ABI/API-level wheel set before Gradle packages Python assets.
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

CHAQUOPY_INDEX = "https://chaquo.com/pypi-13.1"
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
            f"Package classification mismatch. Missing={sorted(expected-actual)}, "
            f"unclassified={sorted(actual-expected)}"
        )
    return packages


def requirements_text(names: set[str], packages: dict[str, tuple[str, list[str]]]) -> str:
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
    index_dir: Path,
    *,
    platform: str | None = None,
) -> None:
    command = [
        python,
        "-m",
        "pip",
        "download",
        "--disable-pip-version-check",
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
    command.extend(["-r", str(req_file), "-d", str(index_dir)])
    subprocess.run(command, check=True)


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: verify_chaquopy_wheel_hashes.py <python-executable> <requirements-file>")
        return 2

    python, requirements_path = sys.argv[1], Path(sys.argv[2])
    packages = parse_requirements(requirements_path)

    with tempfile.TemporaryDirectory(prefix="fulltv-wheel-hash-check-") as tmp:
        root = Path(tmp)
        pure_file = root / "pure-requirements.txt"
        pure_file.write_text(requirements_text(PURE_PACKAGES, packages), encoding="utf-8")
        verify_group(python, pure_file, root / "pure-wheels")
        print(f"Verified SHA-256 for {len(PURE_PACKAGES)} universal Python wheels", flush=True)

        for abi, groups in ABI_TAGS.items():
            for group_name, platform in groups.items():
                requirement_names = NATIVE_GROUPS[group_name]
                req_file = root / f"{group_name}-{abi}.txt"
                req_file.write_text(requirements_text(requirement_names, packages), encoding="utf-8")
                verify_group(
                    python,
                    req_file,
                    root / f"{group_name}-{abi}-wheels",
                    platform=platform,
                )
                print(f"Verified SHA-256 for {group_name} wheels on {abi}", flush=True)

    print("All pinned Chaquopy wheels passed strict SHA-256 verification.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
