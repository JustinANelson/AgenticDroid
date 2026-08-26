#!/usr/bin/env python3
"""Build a 16 KB-page-aligned arm64 libtermux.so, overriding the one bundled in the
termux-app terminal-emulator AAR.

The upstream AAR (see gradle/libs.versions.toml's `termux` version ref) still ships
libtermux.so built with the default 4 KB max-page-size, which fails Google Play's 16 KB
native-library requirement. The JNI source itself (termux.c) is small, self-contained
(libc + jni.h only), and unchanged here - only the compiler/linker flags differ from
upstream's own ndk-build invocation (see terminal-emulator/src/main/jni/Android.mk and
terminal-emulator/build.gradle in https://github.com/termux/termux-app at the pinned tag),
adding -Wl,-z,max-page-size=16384. build.gradle.kts's packaging.jniLibs.pickFirsts makes
this override win over the AAR's own copy at the same jniLibs path.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

TERMUX_APP_TAG = "v0.118.3"
SOURCE_URL = (
    f"https://raw.githubusercontent.com/termux/termux-app/{TERMUX_APP_TAG}"
    "/terminal-emulator/src/main/jni/termux.c"
)
SOURCE_SHA256 = "af9485e2f170eb91b5c5594063190727fd5d4b7932a30e32349c6fdea0b7eee5"
ANDROID_API = 26

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
OUTPUT_DIR = REPO_ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
OUTPUT = OUTPUT_DIR / "libtermux.so"


def _versions_toml_pin() -> str:
    versions_toml = REPO_ROOT / "gradle" / "libs.versions.toml"
    match = re.search(r'^termux\s*=\s*"([^"]+)"', versions_toml.read_text(encoding="utf-8"), re.MULTILINE)
    if not match:
        raise RuntimeError(f"Could not find the 'termux' version pin in {versions_toml}")
    return match.group(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_ndk() -> Path:
    configured = os.environ.get("ANDROID_NDK_HOME")
    if configured and Path(configured).is_dir():
        return Path(configured)

    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        properties = REPO_ROOT / "local.properties"
        if properties.is_file():
            for line in properties.read_text(encoding="utf-8").splitlines():
                if line.startswith("sdk.dir="):
                    sdk = line.partition("=")[2].replace("\\:", ":").replace("\\\\", "\\")
                    break
    if not sdk:
        raise RuntimeError("Android SDK not found; set ANDROID_NDK_HOME or ANDROID_SDK_ROOT")
    versions = sorted((Path(sdk) / "ndk").glob("*"), reverse=True)
    if not versions:
        raise RuntimeError(f"No Android NDK installed below {Path(sdk) / 'ndk'}")
    return versions[0]


def download_source(dest: Path) -> None:
    if not dest.is_file() or sha256(dest) != SOURCE_SHA256:
        dest.unlink(missing_ok=True)
        print(f"Downloading termux.c ({TERMUX_APP_TAG})...")
        urllib.request.urlretrieve(SOURCE_URL, dest)
    actual = sha256(dest)
    if actual != SOURCE_SHA256:
        raise RuntimeError(f"termux.c checksum mismatch: expected {SOURCE_SHA256}, got {actual}")


def build() -> None:
    pinned = _versions_toml_pin()
    if pinned != TERMUX_APP_TAG:
        raise RuntimeError(
            f"gradle/libs.versions.toml pins termux={pinned!r} but this script is pinned to "
            f"{TERMUX_APP_TAG!r} - update TERMUX_APP_TAG/SOURCE_SHA256 to match before rebuilding."
        )

    ndk = find_ndk()
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    toolchain = ndk / "toolchains" / "llvm" / "prebuilt" / host
    clang = toolchain / "bin" / ("clang.exe" if os.name == "nt" else "clang")
    if not clang.is_file():
        raise RuntimeError(f"NDK clang not found: {clang}")

    cache = REPO_ROOT / "build" / "libtermux-cache"
    cache.mkdir(parents=True, exist_ok=True)
    source = cache / "termux.c"
    download_source(source)

    temporary_parent = REPO_ROOT / "build" / "libtermux-tmp"
    temporary_parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="libtermux-", dir=temporary_parent) as temporary:
        build_root = Path(temporary)
        temporary_output = build_root / "libtermux.so"
        command = [
            str(clang),
            f"--target=aarch64-linux-android{ANDROID_API}",
            "--sysroot=" + str(toolchain / "sysroot"),
            "-shared",
            "-fPIC",
            # Matches upstream's own ndk-build cFlags (see Android.mk/build.gradle at the
            # pinned tag) so this rebuild changes only the page-size alignment, not codegen.
            "-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector",
            f"-ffile-prefix-map={build_root}=.",
            str(source),
            "-Wl,-soname,libtermux.so",
            "-Wl,--gc-sections",
            "-Wl,-z,max-page-size=16384",
            "-o",
            str(temporary_output),
        ]
        subprocess.run(command, check=True)
        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(temporary_output, OUTPUT)

    print(f"Wrote {OUTPUT} ({OUTPUT.stat().st_size} bytes, sha256={sha256(OUTPUT)})")


if __name__ == "__main__":
    try:
        build()
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
