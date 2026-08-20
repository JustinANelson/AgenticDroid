#!/usr/bin/env python3
"""Build AgenticDroid's minimally patched arm64 OpenJDK 17 libjli launcher.

OpenJDK normally derives its image root from /proc/self/exe. Android installs executable
JNI libraries in a flat PackageManager directory, so the stock calculation cannot point
back to the downloaded JAVA_HOME. Android 12+ heap pointer tags also conflict with this
OpenJDK build. This rebuild changes only libjli's home lookup and early launcher setup;
the OpenJDK image, HotSpot VM, command launchers, and all Java modules remain Termux's
pinned 17.0.20 package.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

OPENJDK_VERSION = "17.0.20"
OPENJDK_URL = (
    "https://github.com/openjdk/jdk17u/archive/refs/tags/"
    f"jdk-{OPENJDK_VERSION}-ga.tar.gz"
)
OPENJDK_SHA256 = "ba3ac4b9d7f2c050f46ddcec39b4258660a3f09836f5a71617fd3f7311d06c0b"
ANDROID_API = 26
CLASSFILE_MAJOR = "61"

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
PATCH = SCRIPT_DIR / "openjdk_launcher" / "java-home.patch"
OUTPUT_DIR = REPO_ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
OUTPUT = OUTPUT_DIR / "libjli.so"


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


def download_source(archive: Path) -> None:
    if not archive.is_file() or sha256(archive) != OPENJDK_SHA256:
        archive.unlink(missing_ok=True)
        print(f"Downloading OpenJDK {OPENJDK_VERSION} source...")
        urllib.request.urlretrieve(OPENJDK_URL, archive)
    actual = sha256(archive)
    if actual != OPENJDK_SHA256:
        raise RuntimeError(f"OpenJDK source checksum mismatch: expected {OPENJDK_SHA256}, got {actual}")


def build() -> None:
    if not PATCH.is_file():
        raise RuntimeError(f"Missing launcher patch: {PATCH}")
    ndk = find_ndk()
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    toolchain = ndk / "toolchains" / "llvm" / "prebuilt" / host
    clang = toolchain / "bin" / ("clang.exe" if os.name == "nt" else "clang")
    if not clang.is_file():
        raise RuntimeError(f"NDK clang not found: {clang}")

    cache = REPO_ROOT / "build" / "openjdk-launcher-cache"
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f"jdk-{OPENJDK_VERSION}-ga.tar.gz"
    download_source(archive)

    temporary_parent = REPO_ROOT / "build" / "openjdk-launcher-tmp"
    temporary_parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="libjli-", dir=temporary_parent) as temporary:
        build_root = Path(temporary)
        with tarfile.open(archive, "r:gz") as source_tar:
            source_prefix = f"jdk17u-jdk-{OPENJDK_VERSION}-ga/"
            required_prefixes = tuple(
                source_prefix + relative
                for relative in (
                    "src/java.base/share/native/libjli/",
                    "src/java.base/unix/native/libjli/",
                    "src/java.base/share/native/include/",
                    "src/java.base/unix/native/include/",
                    "src/hotspot/share/include/",
                    "src/hotspot/os/posix/include/",
                )
            )
            members = [member for member in source_tar if member.name.startswith(required_prefixes)]
            source_tar.extractall(build_root, members=members, filter="data")
        source = build_root / f"jdk17u-jdk-{OPENJDK_VERSION}-ga"
        subprocess.run(
            ["git", "apply", "--unsafe-paths", str(PATCH)],
            cwd=source,
            check=True,
        )

        generated = build_root / "generated-include"
        generated.mkdir()
        template = source / "src/java.base/share/native/include/classfile_constants.h.template"
        constants = template.read_text(encoding="utf-8")
        constants = constants.replace("@@VERSION_CLASSFILE_MAJOR@@", CLASSFILE_MAJOR)
        constants = constants.replace("@@VERSION_CLASSFILE_MINOR@@", "0")
        (generated / "classfile_constants.h").write_text(constants, encoding="utf-8")

        share = source / "src/java.base/share/native/libjli"
        unix = source / "src/java.base/unix/native/libjli"
        sources = [
            share / "args.c",
            share / "java.c",
            share / "jli_util.c",
            share / "parse_manifest.c",
            share / "splashscreen_stubs.c",
            share / "wildcard.c",
            unix / "java_md.c",
            unix / "java_md_common.c",
        ]
        includes = [
            share,
            unix,
            source / "src/java.base/share/native/include",
            source / "src/java.base/unix/native/include",
            source / "src/hotspot/share/include",
            source / "src/hotspot/os/posix/include",
            generated,
        ]
        temporary_output = build_root / "libjli.so"
        command = [
            str(clang),
            f"--target=aarch64-linux-android{ANDROID_API}",
            "--sysroot=" + str(toolchain / "sysroot"),
            "-shared",
            "-fPIC",
            "-O2",
            f"-ffile-prefix-map={build_root}=.",
            "-D__ANDROID__=1",
            "-D__TERMUX__=1",
            "-DS_IEXEC=S_IXUSR",
            *[f"-I{path}" for path in includes],
            *[str(path) for path in sources],
            f"-L{OUTPUT_DIR}",
            "-Wl,-soname,libjli.so",
            "-Wl,-z,max-page-size=16384",
            "-lz",
            "-ldl",
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
