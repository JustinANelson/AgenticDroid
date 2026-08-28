#!/usr/bin/env python3
"""Discover, pin, and reproduce Python's complete native surface for arm64 Android.

Python is not just its small launcher ELF: every module under lib-dynload is independently
dlopen'd. This tool bundles the launcher, libpython, every standard extension module, and
the shared libraries from Python's explicitly installed runner packages. It also writes a
path mapping used by NodeRuntime to replace the downloaded files with symlinks to
PackageManager's W^X-exempt nativeLibraryDir.

Run with --discover after intentionally updating Termux package versions. With no flag,
the normal reproducible mode re-downloads exactly the pinned manifest entries through the
existing fetch_native_libs.py verifier.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import lzma
import os
import re
import tarfile
import urllib.request

import fetch_native_libs as common

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MANIFEST_PATH = os.path.join(SCRIPT_DIR, "python_native_libs_manifest.json")
LINKS_PATH = os.path.join(
    SCRIPT_DIR, "..", "app", "src", "main", "assets", "python-native-links.json"
)
OUTPUT_DIR = common.OUTPUT_DIR
PACKAGES = (
    "python",
    "gdbm",
    "libandroid-posix-semaphore",
    "libcrypt",
    "ncurses",
    "ncurses-ui-libs",
    "readline",
)
PREFIX_MARKER = "data/data/com.termux/files/usr/"


def download_current(meta: dict[str, str]) -> bytes:
    req = urllib.request.Request(
        f"{common.BASE_URL}/{meta['Filename']}", headers={"User-Agent": "curl/8.0"}
    )
    data = urllib.request.urlopen(req).read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != meta["SHA256"]:
        raise RuntimeError(f"SHA-256 mismatch for {meta['Package']}: {actual}")
    return data


def deb_tar(deb: bytes) -> tarfile.TarFile:
    pos = 8
    members: dict[str, bytes] = {}
    while pos < len(deb):
        header = deb[pos : pos + 60]
        if len(header) < 60:
            break
        name = header[:16].decode("ascii").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        start = pos + 60
        members[name] = deb[start : start + size]
        pos = start + size + (size % 2)
    name = next((key for key in members if key.startswith("data.tar")), None)
    if name is None:
        raise RuntimeError("no data.tar member")
    raw = members[name]
    if name.endswith(".xz"):
        raw = lzma.decompress(raw)
    elif name.endswith(".gz"):
        import gzip

        raw = gzip.decompress(raw)
    elif name.endswith(".zst"):
        raise RuntimeError("zstd data.tar is not supported; install zstandard and extend this tool")
    return tarfile.open(fileobj=io.BytesIO(raw))


def normalized(name: str) -> str:
    return name.removeprefix("./").lstrip("/")


def resolve_member(tf: tarfile.TarFile, member: tarfile.TarInfo) -> tuple[tarfile.TarInfo, bytes]:
    by_name = {normalized(item.name): item for item in tf.getmembers()}
    current = member
    seen: set[str] = set()
    while current.issym() or current.islnk():
        key = normalized(current.name)
        if key in seen:
            raise RuntimeError(f"symlink loop at {key}")
        seen.add(key)
        target = current.linkname
        target_name = normalized(
            os.path.normpath(
                target if target.startswith("/") else os.path.join(os.path.dirname(key), target)
            ).replace("\\", "/")
        )
        current = by_name.get(target_name) or next(
            (item for name, item in by_name.items() if name.endswith(target_name)), None
        )
        if current is None:
            raise RuntimeError(f"broken symlink {key} -> {target}")
    if not current.isreg():
        raise RuntimeError(f"not a regular file: {current.name}")
    return current, tf.extractfile(current).read()


def relative_usr_path(path: str) -> str:
    path = normalized(path)
    marker_at = path.find(PREFIX_MARKER)
    if marker_at < 0:
        raise RuntimeError(f"not under Termux usr prefix: {path}")
    return path[marker_at + len(PREFIX_MARKER) :]


def extension_filename(original: str) -> str:
    stem = re.sub(r"[^A-Za-z0-9]+", "_", original.removesuffix(".so")).strip("_")
    return f"libpyext_{stem}.so"


def candidate_kind(package: str, relative: str) -> str | None:
    base = os.path.basename(relative)
    if package == "python":
        if relative.startswith("bin/python3.") and "." in base:
            return "main"
        if relative.startswith("lib/python") and "/lib-dynload/" in relative and base.endswith(".so"):
            return "extension"
        if relative.startswith("lib/") and base.startswith("libpython") and ".so" in base:
            return "library"
        return None
    if relative.startswith("lib/") and ".so" in base:
        return "library"
    return None


def bundled_name(kind: str, relative: str) -> str:
    base = os.path.basename(relative)
    if kind == "main":
        return "libpython_native_aarch64.so"
    if kind == "extension":
        return extension_filename(base)
    if base.startswith("libpython"):
        return "libpython3_14.so"
    return common.canonicalize(base)


def discover() -> None:
    index = common.load_packages_index()
    existing_manifest = json.load(open(common.MANIFEST_PATH, encoding="utf-8"))
    existing = {entry["bundled_filename"] for entry in existing_manifest["libraries"]}
    entries: dict[str, dict] = {}
    links: dict[str, str] = {}
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    for package in PACKAGES:
        meta = index[package]
        print(f"Inspecting {package} {meta['Version']}...")
        deb = download_current(meta)
        with deb_tar(deb) as tf:
            for member in tf.getmembers():
                if member.isdir():
                    continue
                try:
                    relative = relative_usr_path(member.name)
                except RuntimeError:
                    continue
                kind = candidate_kind(package, relative)
                if kind is None:
                    continue
                try:
                    _, content = resolve_member(tf, member)
                except RuntimeError:
                    continue
                if common.elf_machine(content) != common.EM_AARCH64:
                    continue
                output = bundled_name(kind, relative)
                links[relative] = output
                if output in existing or output in entries:
                    continue
                with open(os.path.join(OUTPUT_DIR, output), "wb") as out:
                    out.write(content)
                entries[output] = {
                    "bundled_filename": output,
                    "termux_package": package,
                    "version": meta["Version"],
                    "source_deb_sha256": meta["SHA256"],
                    "source_path": normalized(member.name),
                    "used_by": ["python"],
                    "role": kind,
                }

    manifest = {
        "$schema_note": "Pinned Python launcher, libpython, extension modules, and runner-package libraries. Regenerate with tools/fetch_python_native_libs.py --discover only when intentionally updating Termux packages; normal runs verify these pins.",
        "source_index": common.BASE_URL,
        "target_abi": "aarch64 (arm64-v8a)",
        "libraries": sorted(entries.values(), key=lambda item: item["bundled_filename"]),
    }
    os.makedirs(os.path.dirname(LINKS_PATH), exist_ok=True)
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


def reproduce() -> None:
    if not os.path.isfile(MANIFEST_PATH):
        raise RuntimeError("manifest missing; run once with --discover")
    manifest = json.load(open(MANIFEST_PATH, encoding="utf-8"))
    index = common.load_packages_index()
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    for entry in manifest["libraries"]:
        print(f"Fetching {entry['bundled_filename']}...")
        deb = common.download_and_verify(
            index, entry["termux_package"], entry["source_deb_sha256"], entry["version"]
        )
        content = common.extract_member(deb, entry["bundled_filename"], entry["source_path"])
        with open(os.path.join(OUTPUT_DIR, entry["bundled_filename"]), "wb") as out:
            out.write(content)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--discover", action="store_true")
    args = parser.parse_args()
    discover() if args.discover else reproduce()
