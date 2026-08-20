#!/usr/bin/env python3
"""Discover, pin, and reproduce the arm64 native surface used by JVM project builds.

The downloaded OpenJDK image keeps Java modules/resources in app storage, but every JDK
launcher and .so that Android executes or maps must resolve to nativeLibraryDir. This tool
packages the launchers AgenticDroid exposes, all OpenJDK shared libraries, and the two
external image/color dependencies used by those libraries. Runtime path mappings are
written as an asset for NodeRuntime.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

import fetch_native_libs as common
import fetch_python_native_libs as archive

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MANIFEST_PATH = os.path.join(SCRIPT_DIR, "jvm_native_libs_manifest.json")
LINKS_PATH = os.path.join(
    SCRIPT_DIR, "..", "app", "src", "main", "assets", "jvm-native-links.json"
)
PACKAGES = ("openjdk-17", "libjpeg-turbo", "littlecms")
JDK_PREFIX = "lib/jvm/java-17-openjdk/"
LAUNCHERS = ("java", "javac", "jar", "keytool", "javap", "jlink")


def classify(package: str, relative: str) -> tuple[str, str] | None:
    base = os.path.basename(relative)
    if package == "openjdk-17":
        if relative.startswith(f"{JDK_PREFIX}bin/") and base in LAUNCHERS:
            return "launcher", f"libjdk_{base}_aarch64.so"
        if relative.startswith(f"{JDK_PREFIX}lib/") and ".so" in base:
            return "jdk-library", common.canonicalize(base)
        return None
    if relative.startswith("lib/") and ".so" in base:
        return "dependency", common.canonicalize(base)
    return None


def discover() -> None:
    index = common.load_packages_index()
    existing = {
        entry["bundled_filename"]
        for entry in json.load(open(common.MANIFEST_PATH, encoding="utf-8"))["libraries"]
    }
    python_manifest = os.path.join(SCRIPT_DIR, "python_native_libs_manifest.json")
    if os.path.isfile(python_manifest):
        existing.update(
            entry["bundled_filename"]
            for entry in json.load(open(python_manifest, encoding="utf-8"))["libraries"]
        )
    entries: dict[str, dict] = {}
    entry_content: dict[str, bytes] = {}
    links: dict[str, str] = {}
    os.makedirs(common.OUTPUT_DIR, exist_ok=True)

    for package in PACKAGES:
        meta = index[package]
        print(f"Inspecting {package} {meta['Version']}...")
        deb = archive.download_current(meta)
        with archive.deb_tar(deb) as tf:
            for member in tf.getmembers():
                if member.isdir():
                    continue
                try:
                    relative = archive.relative_usr_path(member.name)
                except RuntimeError:
                    continue
                match = classify(package, relative)
                if match is None:
                    continue
                role, output = match
                try:
                    _, content = archive.resolve_member(tf, member)
                except RuntimeError:
                    continue
                if common.elf_machine(content) != common.EM_AARCH64:
                    continue
                links[relative] = output
                if output in existing:
                    continue
                if output in entry_content:
                    if entry_content[output] != content:
                        raise RuntimeError(
                            f"two distinct JVM files map to {output}; give one a path-specific name"
                        )
                    continue
                entry_content[output] = content
                with open(os.path.join(common.OUTPUT_DIR, output), "wb") as out:
                    out.write(content)
                entries[output] = {
                    "bundled_filename": output,
                    "termux_package": package,
                    "version": meta["Version"],
                    "source_deb_sha256": meta["SHA256"],
                    "source_path": archive.normalized(member.name),
                    "used_by": ["jvm"],
                    "role": role,
                }

    manifest = {
        "$schema_note": "Pinned OpenJDK launchers/shared libraries and required image dependencies. Regenerate with tools/fetch_jvm_native_libs.py --discover only for intentional package upgrades.",
        "source_index": common.BASE_URL,
        "target_abi": "aarch64 (arm64-v8a)",
        "libraries": sorted(entries.values(), key=lambda item: item["bundled_filename"]),
    }
    with open(MANIFEST_PATH, "w", encoding="utf-8") as out:
        json.dump(manifest, out, indent=2)
        out.write("\n")
    with open(LINKS_PATH, "w", encoding="utf-8") as out:
        json.dump(
            [{"path": path, "target": target} for path, target in sorted(links.items())],
            out,
            indent=2,
        )
        out.write("\n")
    print(f"Wrote {len(entries)} pinned native files and {len(links)} runtime links")
    build_patched_libjli()


def reproduce() -> None:
    manifest = json.load(open(MANIFEST_PATH, encoding="utf-8"))
    index = common.load_packages_index()
    os.makedirs(common.OUTPUT_DIR, exist_ok=True)
    for entry in manifest["libraries"]:
        print(f"Fetching {entry['bundled_filename']}...")
        deb = common.download_and_verify(
            index, entry["termux_package"], entry["source_deb_sha256"], entry["version"]
        )
        content = common.extract_member(deb, entry["bundled_filename"], entry["source_path"])
        with open(os.path.join(common.OUTPUT_DIR, entry["bundled_filename"]), "wb") as out:
            out.write(content)
    build_patched_libjli()


def build_patched_libjli() -> None:
    """Replace stock libjli with the pinned Android-aware launcher rebuild."""
    subprocess.run(
        [sys.executable, os.path.join(SCRIPT_DIR, "build_openjdk_launcher.py")],
        check=True,
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--discover", action="store_true")
    args = parser.parse_args()
    discover() if args.discover else reproduce()
