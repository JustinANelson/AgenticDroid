# Third-party software notices

AgenticDroid depends on and can download third-party software. Each component remains governed by
its own license. The Apache License 2.0 in this repository applies only to AgenticDroid's original
work and does not replace those licenses.

This file is a source-tree inventory, not yet a complete binary-release notice bundle. See
[DISTRIBUTION.md](docs/DISTRIBUTION.md) before distributing an APK.

## Source dependencies

The Gradle version catalog and build files declare AndroidX/Jetpack Compose, Android Gradle Plugin,
Kotlin, Bouncy Castle, SSHJ, Apache Commons Compress, XZ for Java, zstd-jni, OkHttp, Eclipse LSP4J,
JSON-java, JUnit, and AndroidX test libraries. Their exact versions are recorded in
`gradle/libs.versions.toml` and `app/build.gradle.kts`.

The terminal emulator and view are built from Termux app tag `v0.118.3` through JitPack. Those two
modules are identified by the Termux project as Apache-2.0 exceptions to the broader Termux app
license. `tools/build_libtermux.py` rebuilds the terminal JNI source from that pinned tag with a
16 KB maximum page size; the source hash is pinned in the script.

The optional LAN companion's npm dependency tree is locked in `tools/package-lock.json` and
includes Express, ws, node-pty, and bonjour-service.

## Generated native runtime

The files under `tools/*_native_libs_manifest.json` pin binaries extracted from Termux packages.
At the time of this inventory, the package set is:

```text
aapt2, abseil-cpp, argp, c-ares, fmt, gdbm, git, glib,
libandroid-posix-semaphore, libandroid-shmem, libandroid-support, libbz2, libcrypt,
libcurl, libdw, libelf, libevent, libexpat, libffi, libgmp, libgnutls, libiconv,
libicu, libidn2, libjpeg-turbo, liblzma, libnettle, libnghttp2, libnghttp3,
libngtcp2, libpixman, libpng, libprotobuf, libsqlite, libssh2, libtasn1,
libunbound, libunistring, littlecms, ncurses, ncurses-ui-libs, ndk-multilib,
nodejs, openjdk-17, openssl, p11-kit, pcre2, python, qemu-user-aarch64,
readline, zlib, zstd
```

The authoritative versions, source-package SHA-256 values, source paths, and generated filenames
are the manifest entries—not this summary. Termux package recipes identify the upstream source and
license, and each `.deb` carries applicable copyright files under its `share/doc` tree. Some of
these components use GPL, LGPL, or other copyleft licenses and may require corresponding source
and relinking/material obligations when binaries are distributed.

The OpenJDK launcher patch in `tools/openjdk_launcher/java-home.patch` is applied to the pinned
OpenJDK 17 source by `tools/build_openjdk_launcher.py`. OpenJDK code retains its upstream license,
including applicable Classpath Exception terms.

## Tracked native overrides

`app/src/main/assets/native-overrides/libandroid-spawn/` contains arm64-v8a and x86_64 builds of
Termux's `libandroid-spawn`, whose package recipe identifies the code as BSD-2-Clause. The exact
build provenance of the currently tracked objects has not yet been recorded; this is a binary
release blocker even though their SHA-256 hashes are reviewable from Git. The upstream notice is
reproduced in [licenses/libandroid-spawn-BSD-2-Clause.txt](licenses/libandroid-spawn-BSD-2-Clause.txt).

## Agent CLIs and downloaded packages

AgenticDroid can install command-line agents and project dependencies at the user's request. Those
downloads are not part of this repository and are governed by their publishers' licenses and
terms. Inclusion in the UI does not imply affiliation, endorsement, or relicensing.

Product and project names such as Android, GitHub, Termux, OpenJDK, Node.js, Python, and the names
of agent providers are the property of their respective owners.
