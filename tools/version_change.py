#!/usr/bin/env python3
"""Detect a deliberate major/minor Android development-version change."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess


VERSION_NAME = re.compile(r'^\s*versionName\s*=\s*"(\d+\.\d+(?:\.\d+)?)"\s*$', re.MULTILINE)
VERSION_CODE = re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*$", re.MULTILINE)


@dataclass(frozen=True)
class AndroidVersion:
    name: str
    parts: tuple[int, int, int]
    code: int


def parse_build_file(text: str) -> AndroidVersion:
    name_match = VERSION_NAME.search(text)
    code_match = VERSION_CODE.search(text)
    if not name_match or not code_match:
        raise ValueError("build file must contain numeric versionName and versionCode values")

    name = name_match.group(1)
    numeric = tuple(int(part) for part in name.split("."))
    parts = numeric if len(numeric) == 3 else (*numeric, 0)
    return AndroidVersion(name=name, parts=parts, code=int(code_match.group(1)))


def read_at_ref(repository: Path, ref: str, relative_path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"{ref}:{relative_path}"],
        cwd=repository,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def classify(previous: AndroidVersion, current: AndroidVersion) -> bool:
    if current.parts < previous.parts:
        raise ValueError(f"versionName decreased from {previous.name} to {current.name}")

    version_changed = current.parts != previous.parts
    major_or_minor_changed = current.parts[:2] != previous.parts[:2]
    if version_changed and current.code <= previous.code:
        raise ValueError(
            f"versionCode must increase for {previous.name} -> {current.name} "
            f"({previous.code} -> {current.code})"
        )
    return major_or_minor_changed


def write_output(path: Path, values: dict[str, str]) -> None:
    with path.open("a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--before", required=True, help="Git ref before the push")
    parser.add_argument("--after", required=True, help="Git ref after the push")
    parser.add_argument("--build-file", default="app/build.gradle.kts")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()

    previous = parse_build_file(read_at_ref(args.repository, args.before, args.build_file))
    current = parse_build_file(read_at_ref(args.repository, args.after, args.build_file))
    publish = classify(previous, current)
    values = {
        "publish": str(publish).lower(),
        "previous_version": previous.name,
        "version": current.name,
        "version_code": str(current.code),
    }
    if args.github_output:
        write_output(args.github_output, values)
    for key, value in values.items():
        print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
