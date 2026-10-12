#!/usr/bin/env python3
"""Stages (or checks) a content-addressed mirror of every .deb pinned by the native manifests.

Termux's package index only serves the current build of each package, so a pinned SHA-256
stops being fetchable whenever Termux republishes it. This copies the exact pinned files
into a directory you can host anywhere (object storage, a private release, an internal
web server), named `<package>-<sha256>.deb`. tools/fetch_*_native_libs.py then prefer it
when NATIVE_MIRROR_URL is set, and still verify every byte against the manifest.

Stage the mirror (run while the pins are still current, i.e. right after refreshing them):

    python3 tools/mirror_native_closure.py stage --out build/native-mirror

Verify that a hosted mirror serves every pinned file:

    NATIVE_MIRROR_URL=https://example.invalid/native-mirror \\
        python3 tools/mirror_native_closure.py check

Do NOT publish the staged files from a public location without first completing the
license/source steps in docs/DISTRIBUTION.md: they include copyleft binaries.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

import fetch_native_libs as common

MANIFESTS = (
    "native_libs_manifest.json",
    "python_native_libs_manifest.json",
    "jvm_native_libs_manifest.json",
)


def pinned_packages() -> list[tuple[str, str, str]]:
    """Unique (package, version, sha256) triples across all manifests, sorted."""
    seen: dict[tuple[str, str], str] = {}
    for name in MANIFESTS:
        with open(os.path.join(common.SCRIPT_DIR, name), encoding="utf-8") as f:
            for entry in json.load(f)["libraries"]:
                seen[(entry["termux_package"], entry["source_deb_sha256"])] = entry["version"]
    return sorted((pkg, ver, sha) for (pkg, sha), ver in seen.items())


def stage(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    common.MIRROR_URL = ""  # always fetch from Termux itself, never from a mirror
    packages = pinned_packages()
    index = common.load_packages_index()
    index_entries = []
    for pkg, version, sha in packages:
        path = os.path.join(out_dir, common.mirror_filename(pkg, sha))
        if os.path.isfile(path) and hashlib.sha256(open(path, "rb").read()).hexdigest() == sha:
            print(f"  have {pkg} {version}")
        else:
            print(f"  fetching {pkg} {version} ...")
            data = common.download_and_verify(index, pkg, sha, version)
            with open(path, "wb") as out:
                out.write(data)
        index_entries.append({"package": pkg, "version": version, "sha256": sha,
                              "file": common.mirror_filename(pkg, sha)})
    with open(os.path.join(out_dir, "index.json"), "w", encoding="utf-8") as f:
        json.dump({"source_index": common.BASE_URL, "files": index_entries}, f, indent=2)
        f.write("\n")
    with open(os.path.join(out_dir, "SHA256SUMS"), "w", encoding="utf-8", newline="\n") as f:
        for e in index_entries:
            f.write(f"{e['sha256']}  {e['file']}\n")
    print(f"Staged {len(index_entries)} packages in {os.path.abspath(out_dir)}")
    return 0


def check() -> int:
    if not common.MIRROR_URL:
        print("NATIVE_MIRROR_URL is not set", file=sys.stderr)
        return 2
    missing = [(pkg, ver) for pkg, ver, sha in pinned_packages()
               if common.fetch_from_mirror(pkg, sha) is None]
    for pkg, ver in missing:
        print(f"MISSING from mirror: {pkg} {ver}", file=sys.stderr)
    print(f"{len(missing)} missing" if missing else "Mirror holds every pinned package")
    return 1 if missing else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    stage_cmd = sub.add_parser("stage", help="download pinned .debs into a directory")
    stage_cmd.add_argument("--out", required=True)
    sub.add_parser("check", help="verify NATIVE_MIRROR_URL serves every pinned .deb")
    args = parser.parse_args()
    return stage(args.out) if args.command == "stage" else check()


if __name__ == "__main__":
    sys.exit(main())
