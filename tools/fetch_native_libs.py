#!/usr/bin/env python3
"""Reproducibly regenerates app/src/main/jniLibs/arm64-v8a/*.so for the native-lib
-packaging prototype (see AGENT_RUNTIME_RESEARCH.md) - covering qemu-user-aarch64 (plus
the agent CLI guest binaries it hosts), node, git, and aapt2.

Downloads each package pinned in native_libs_manifest.json from Termux's package CDN,
verifies its SHA-256 against the pinned value, extracts the named library/binary from
its data.tar, and writes it to jniLibs/arm64-v8a/<bundled filename> - the same process
used to originally produce that directory, so the bundled binaries can be regenerated
and audited rather than trusted as opaque blobs. Each manifest entry's "used_by" field
records which main binary(ies) actually need it, per real DT_NEEDED chasing (not
Termux's broader apt Depends: metadata) - several libraries are shared across binaries
(e.g. libz.so is needed by all four).

This does NOT re-derive the dependency closure itself (which soname needs which
package) - that was done once per binary by walking its real ELF DT_NEEDED entries
recursively against Termux's package index. Re-run that discovery (see
AGENT_RUNTIME_RESEARCH.md Sections 8 and 12) if any of these binaries or their
dependencies change enough to add/drop a shared library, then update the manifest by
hand.

Usage:
    python3 tools/fetch_native_libs.py
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import tarfile
import urllib.request

BASE_URL = "https://packages-cf.termux.dev/apt/termux-main"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MANIFEST_PATH = os.path.join(SCRIPT_DIR, "native_libs_manifest.json")
OUTPUT_DIR = os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "jniLibs", "arm64-v8a")


def load_packages_index() -> dict:
    """Fetches Termux's aarch64 Packages index fresh, so package Filename/SHA256
    fields are current even if a package has since been superseded on the CDN."""
    url = f"{BASE_URL}/dists/stable/main/binary-aarch64/Packages.gz"
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8.0"})
    import gzip
    raw = gzip.decompress(urllib.request.urlopen(req).read()).decode("utf-8", "replace")
    pkgs: dict[str, dict[str, str]] = {}
    cur: dict[str, str] = {}
    for line in raw.splitlines():
        if line == "":
            if cur.get("Package"):
                pkgs[cur["Package"]] = cur
            cur = {}
            continue
        m = re.match(r"^([A-Za-z0-9-]+): (.*)$", line)
        if m:
            cur[m.group(1)] = m.group(2)
    if cur.get("Package"):
        pkgs[cur["Package"]] = cur
    return pkgs


EM_AARCH64 = 183


def elf_machine(data: bytes) -> int | None:
    if data[:4] != b"\x7fELF":
        return None
    import struct
    endian = "<" if data[5] == 1 else ">"
    return struct.unpack_from(endian + "H", data, 18)[0]


def canonicalize(name: str) -> str:
    """Strips trailing versioned soname segments: libfoo.so.1.2.3 -> libfoo.so.
    Android's native-library extraction only recognizes literal `lib*.so`
    filenames - see NodeRuntime.ensureQemuNativeLibAliases for the runtime side of
    this (symlinks bridging the real, versioned soname back to this canonical name)."""
    m = re.match(r"^(.*\.so)(\.\d+)*$", name)
    return m.group(1) if m else name


def download_and_verify(pkgs: dict, pkg_name: str, expected_sha256: str, expected_version: str) -> bytes:
    meta = pkgs.get(pkg_name)
    if meta is None:
        raise RuntimeError(f"package {pkg_name!r} not found in current Termux index")
    if meta["Version"] != expected_version:
        print(
            f"!! WARNING: {pkg_name} is now at version {meta['Version']}, manifest pins "
            f"{expected_version} - Termux may have republished. Verifying against the "
            f"manifest's pinned SHA-256 regardless; a mismatch will hard-fail below.",
            file=sys.stderr,
        )
    url = f"{BASE_URL}/{meta['Filename']}"
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8.0"})
    data = urllib.request.urlopen(req).read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected_sha256:
        raise RuntimeError(
            f"SHA-256 mismatch for {pkg_name}: expected {expected_sha256}, got {actual}. "
            "Refusing to use this download - do not silently accept a changed artifact."
        )
    return data


def extract_member(deb_bytes: bytes, real_soname_hint: str, source_path: str | None = None) -> bytes:
    """Deb = ar archive of {debian-binary, control.tar.*, data.tar.*}. Extracts
    data.tar(.xz) and returns the bytes of either the exact member at
    [source_path] (for files outside usr/lib, e.g. qemu-user-aarch64's own binary
    under usr/bin - see the manifest entry's "source_path"), or - when
    source_path is None - the first regular file under usr/lib whose
    canonicalized basename matches real_soname_hint, resolving symlinks."""
    import io
    import lzma

    # Minimal ar archive parser (no `ar` binary available on all dev machines).
    pos = 8  # skip "!<arch>\n" magic
    members = {}
    while pos < len(deb_bytes):
        header = deb_bytes[pos:pos + 60]
        if len(header) < 60:
            break
        name = header[0:16].decode("ascii").strip()
        size = int(header[48:58].decode("ascii").strip())
        content_start = pos + 60
        content = deb_bytes[content_start:content_start + size]
        members[name.rstrip("/")] = content
        pos = content_start + size
        if pos % 2 == 1:
            pos += 1

    data_tar_member = next((k for k in members if k.startswith("data.tar")), None)
    if data_tar_member is None:
        raise RuntimeError("no data.tar* member found in .deb")
    raw = members[data_tar_member]
    if data_tar_member.endswith(".xz"):
        raw = lzma.decompress(raw)
    elif data_tar_member.endswith(".zst"):
        raise RuntimeError("zstd-compressed data.tar not supported by this script")

    with tarfile.open(fileobj=io.BytesIO(raw)) as tf:
        members_by_name = {m.name: m for m in tf.getmembers()}

        if source_path is not None:
            candidates = [k for k in members_by_name if k.endswith(source_path)]
            if not candidates:
                raise RuntimeError(f"source_path {source_path!r} not found in this package")
            matches = [(candidates[0], members_by_name[candidates[0]])]
        else:
            matches = [
                (member.name, member) for member in tf.getmembers()
                if "/lib/" in member.name and not member.isdir()
                and canonicalize(os.path.basename(member.name)) == real_soname_hint
            ]

        # A package can ship several architecture variants of the same canonical
        # soname under different paths (confirmed: ndk-multilib's libc++_shared.so
        # exists for aarch64/arm/x86/x86_64-linux-android, all under a "/lib/"
        # path segment that satisfies the match above). Resolve every candidate,
        # then keep only the ones that are actually aarch64 ELF - never trust
        # tarfile's member-iteration order to pick the right one, since an
        # earlier version of this discovery process silently grabbed a wrong-
        # architecture file this way (see AGENT_RUNTIME_RESEARCH.md).
        aarch64_matches: list[tuple[str, bytes]] = []
        for match_name, member in matches:
            cur = member
            seen = set()
            while cur.issym() or cur.islnk():
                if cur.name in seen:
                    raise RuntimeError(f"symlink loop resolving {member.name}")
                seen.add(cur.name)
                target = cur.linkname
                target_path = os.path.normpath(
                    target if target.startswith("/") or "/" in target
                    else os.path.join(os.path.dirname(cur.name), target)
                ).replace("\\", "/")
                if target_path not in members_by_name:
                    candidates = [k for k in members_by_name if k.endswith(target_path)]
                    if not candidates:
                        raise RuntimeError(f"broken symlink {cur.name} -> {target}")
                    target_path = candidates[0]
                cur = members_by_name[target_path]
            if not cur.isreg():
                continue
            content = tf.extractfile(cur).read()
            if elf_machine(content) == EM_AARCH64:
                aarch64_matches.append((match_name, content))

        # A package's own real file, plus its dpkg-style unversioned/major-version
        # symlinks (libbz2.so, libbz2.so.1.0, libbz2.so.1.0.8 all pointing at the
        # same bytes), can each independently satisfy the match above once
        # resolved. That's not a real conflict - only flag it if the resolved
        # *content* actually differs (a genuine architecture/build mismatch).
        distinct_contents = {content for _, content in aarch64_matches}
        if len(distinct_contents) == 1:
            return next(iter(distinct_contents))
        if len(distinct_contents) > 1:
            names = ", ".join(n for n, _ in aarch64_matches)
            raise RuntimeError(
                f"ambiguous: {len(distinct_contents)} distinct aarch64 files match canonical "
                f"name {real_soname_hint!r}: {names} - narrow this manually, don't guess"
            )
    raise RuntimeError(
        f"no aarch64 ELF matching canonical name {real_soname_hint!r} found under usr/lib "
        "in this package (matches existed but were the wrong architecture, or none existed)"
    )


def main() -> None:
    with open(MANIFEST_PATH, encoding="utf-8") as f:
        manifest = json.load(f)

    print("Fetching current Termux aarch64 package index...")
    pkgs = load_packages_index()
    package_cache: dict[tuple[str, str, str], bytes] = {}

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    for entry in manifest["libraries"]:
        fname = entry["bundled_filename"]
        pkg_name = entry["termux_package"]
        version = entry["version"]
        sha256 = entry["source_deb_sha256"]
        print(f"  {fname} <- {pkg_name} {version} ...")
        cache_key = (pkg_name, version, sha256)
        if cache_key not in package_cache:
            package_cache[cache_key] = download_and_verify(pkgs, pkg_name, sha256, version)
        deb_bytes = package_cache[cache_key]
        content = extract_member(deb_bytes, fname, source_path=entry.get("source_path"))
        out_path = os.path.join(OUTPUT_DIR, fname)
        with open(out_path, "wb") as out:
            out.write(content)

    print(f"\nWrote {len(manifest['libraries'])} files to {os.path.abspath(OUTPUT_DIR)}")


if __name__ == "__main__":
    main()
