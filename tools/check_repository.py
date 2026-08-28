#!/usr/bin/env python3
"""Fast, dependency-free checks for repository health and contributor metadata."""

from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent
REQUIRED_FILES = (
    "README.md",
    "LICENSE",
    "CONTRIBUTING.md",
    "CODE_OF_CONDUCT.md",
    "SECURITY.md",
    "SUPPORT.md",
    "GOVERNANCE.md",
    "CHANGELOG.md",
    "THIRD_PARTY_NOTICES.md",
)
MARKDOWN_LINK = re.compile(r"\[[^]]*]\(([^)]+)\)")
ACTION_REF = re.compile(r"^\s*uses:\s*([^\s#]+)@([^\s#]+)", re.MULTILINE)
FULL_COMMIT = re.compile(r"[0-9a-f]{40}")


def repository_files() -> list[Path]:
    commands = (
        ["git", "ls-files"],
        ["git", "ls-files", "--others", "--exclude-standard"],
    )
    names: set[str] = set()
    for command in commands:
        result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
        names.update(line for line in result.stdout.splitlines() if line)
    return sorted((ROOT / name for name in names if (ROOT / name).is_file()), key=str)


def validate_required_files(errors: list[str]) -> None:
    for name in REQUIRED_FILES:
        if not (ROOT / name).is_file():
            errors.append(f"missing community file: {name}")


def validate_json(files: list[Path], errors: list[str]) -> None:
    for path in (path for path in files if path.suffix == ".json"):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            errors.append(f"invalid JSON in {path.relative_to(ROOT)}: {error}")


def validate_markdown_links(files: list[Path], errors: list[str]) -> None:
    for path in (path for path in files if path.suffix.lower() == ".md"):
        text = path.read_text(encoding="utf-8")
        for match in MARKDOWN_LINK.finditer(text):
            destination = match.group(1).strip()
            if not destination or destination.startswith(("#", "http://", "https://", "mailto:")):
                continue
            destination = destination.split("#", 1)[0].strip("<>")
            if not destination:
                continue
            target = (path.parent / destination).resolve()
            try:
                target.relative_to(ROOT)
            except ValueError:
                errors.append(f"link escapes repository in {path.relative_to(ROOT)}: {destination}")
                continue
            if not target.exists():
                errors.append(f"broken link in {path.relative_to(ROOT)}: {destination}")


def validate_workflow_pins(files: list[Path], errors: list[str]) -> None:
    workflow_root = ROOT / ".github" / "workflows"
    for path in files:
        if path.parent != workflow_root or path.suffix not in {".yml", ".yaml"}:
            continue
        for action, ref in ACTION_REF.findall(path.read_text(encoding="utf-8")):
            if action.startswith("./"):
                continue
            if not FULL_COMMIT.fullmatch(ref):
                errors.append(
                    f"GitHub Action is not pinned to a full commit in "
                    f"{path.relative_to(ROOT)}: {action}@{ref}"
                )


def validate_native_manifests(errors: list[str]) -> None:
    for path in sorted((ROOT / "tools").glob("*native_libs_manifest.json")):
        manifest = json.loads(path.read_text(encoding="utf-8"))
        libraries = manifest.get("libraries")
        if not isinstance(libraries, list) or not libraries:
            errors.append(f"native manifest has no libraries: {path.relative_to(ROOT)}")
            continue
        names = [entry.get("bundled_filename") for entry in libraries]
        duplicates = sorted({name for name in names if name and names.count(name) > 1})
        if duplicates:
            errors.append(
                f"duplicate bundled filenames in {path.relative_to(ROOT)}: {', '.join(duplicates)}"
            )
        for index, entry in enumerate(libraries):
            required = ("bundled_filename", "termux_package", "version", "source_deb_sha256")
            missing = [key for key in required if not entry.get(key)]
            if missing:
                errors.append(
                    f"{path.relative_to(ROOT)} entry {index} missing: {', '.join(missing)}"
                )


def main() -> int:
    files = repository_files()
    errors: list[str] = []
    validate_required_files(errors)
    validate_json(files, errors)
    validate_markdown_links(files, errors)
    validate_workflow_pins(files, errors)
    validate_native_manifests(errors)

    if errors:
        print("Repository health checks failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"Repository health checks passed ({len(files)} files checked).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
