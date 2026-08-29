# Agent Runtime Research: how Acode runs proot/Alpine on Play-Store-eligible Android

**Purpose:** Determine whether Acode's proot/Alpine terminal architecture can inform a
redesign of this app's toolchain to remove the API 28 `targetSdk` ceiling
(`READINESS_REVIEW.md` blocker **R1**) that currently rules out Play Store distribution.

**Status:** Verified against Acode's actual public source (`Acode-Foundation/Acode`,
`src/plugins/terminal/` and `src/plugins/proot/`, read via `gh api` on 2026-08-19).
This supersedes the earlier version of this document, which speculated the exec-restriction
fix was a `memfd_create`/`fexecve` shim (Termux's `termux-exec` technique) — that guess was
**wrong**. Acode uses a different, simpler mechanism, documented below with exact file
citations.

**External review (2026-08-19):** an independent review (via Codex) of this document and
the resulting prototype was requested and applied. It correctly caught several real
mistakes — `useLegacyPackaging` was described backwards throughout (Section 2), the proot
mechanism explanation overclaimed a mechanism this document couldn't directly verify from
source alone (Section 3), and it flagged that Play's actual target-API deadline is API 36
(not the 34 used here as a convenient test point — Section 6b), that Play's downloadable
-executable-code policy deserves release-critical status rather than an offhand open
question (Section 6c), and that the 34 bundled native libraries needed a reproducible,
auditable generation process rather than being hand-produced once and committed as opaque
blobs (Section 6d — building that reproduction script independently caught a real bug: the
original prototype had bundled the *wrong CPU architecture's* `libc++_shared.so`). One of
its findings (a leftover "remove before merging" diagnostic hook) was already stale by the
time it was raised — confirmed via `git diff` showing `MainViewModel.kt` clean — included
here only so the record is accurate about what was and wasn't still true.

## 1. The two separate problems (unchanged from prior analysis)

- **Problem A — Android won't execute files written to the app's own storage at runtime**
  on `targetSdk >= 29` (W^X: SELinux + linker deny `execve()`/`mmap(PROT_EXEC)` on
  anything the app itself could have written). This is the actual Play Store blocker (R1);
  it applies to `proot` itself, not just to the musl/glibc agent binaries it runs.
- **Problem B — Zygote's seccomp-bpf filter kills a real app-spawned child that directly
  `execve()`s a foreign (musl/glibc) dynamic loader mid-relocation.** This is what this
  app's QEMU-user wrapping currently works around.

## 2. How Acode solves Problem A — verified

Acode ships two Cordova native plugins that matter here:

- `src/plugins/proot/` — packages `proot`'s binaries as **APK native libraries**, not
  runtime downloads. Its `plugin.xml` (verified) declares, per ABI:
  `libproot.so`, `libproot32.so`, `libproot-xed.so`, `libtalloc.so`, and `libaxs.so` under
  `<source-file target-dir="libs/<abi>">` — the standard Cordova/Gradle convention that
  places them in `jniLibs`. It also bundles a prebuilt Alpine rootfs image
  (`resource-file src="assets/alpine_assets/<abi>/alpine.rootfs"`) as a plain APK asset, per
  architecture (x86_64, arm64, armhf).
- `src/plugins/terminal/` — the Java glue that actually launches things
  (`ProcessManager.java`, `Executor.java`, `TerminalService.java`) plus the shell scripts
  that assemble the proot invocation (`scripts/init-sandbox.sh`, `init-alpine.sh`).

**The trick:** files named `lib*.so` and declared as Gradle/Cordova native libraries get
installed by Android's **PackageManager**, at APK install time, into
`ApplicationInfo.nativeLibraryDir` (`context.getApplicationInfo().nativeLibraryDir` in
`ProcessManager.java`) — a system-managed directory the app itself never writes to at
runtime. That directory is explicitly exempted from the W^X "no exec from app-writable
storage" restriction, because it's how every Android app has always shipped JNI native
code; `proot` (an ordinary ELF executable) is just *renamed* to look like a shared library
(`libproot.so`, `libproot-xed.so`, `libproot32.so`, `libtalloc.so`, and `libaxs.so`, the
axs helper binary) so Android's own packaging pipeline treats it as one and extracts it
there instead of into a writable app-private path.

One extra Gradle setting is required for the native lib files to exist on disk as real,
`exec`-able files at all. Modern AGP's default (`useLegacyPackaging = false`) stores native
libraries **uncompressed and page-aligned inside the APK**, so the OS can `mmap()` them
directly out of the APK zip at load time with no separate on-disk copy — which is exactly
why a plain `ProcessBuilder`/`execve()` can't reliably target them: there may be no ordinary
file path to point at. Setting it to `true` reverts to the pre-Android-6 legacy behavior:
native libraries are stored **compressed inside the APK** (smaller APK download) and
**extracted to real files on disk** in `nativeLibraryDir` at install time (larger installed
footprint) — see the [`JniLibsApkPackaging`](https://developer.android.com/reference/tools/gradle-api/7.2/com/android/build/api/variant/JniLibsApkPackaging)
API docs and Android's [16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes)
for the authoritative behavior (App Bundle-based installs have a related but separate
`useLegacyPackagingFromBundle` switch, not investigated here). Acode's
`hooks/post-process.js` injects into the app module's `build.gradle`:

```groovy
packagingOptions {
    jniLibs {
        useLegacyPackaging = true
    }
}
```

`init-sandbox.sh` (verified, full text read) then picks the executable paths based on
build flavor:

```sh
if [ "$FDROID" = "true" ]; then
    # F-Droid build: targetSdk pinned <= 28 (same shortcut this app currently takes).
    # Runs proot straight out of the app's own writable files dir.
    export PROOT_LOADER="$PREFIX/libproot.so"
    export PROOT="$PREFIX/libproot-xed.so"
    chmod +x $PREFIX/*
else
    # Play Store build: targetSdk 29+. Runs proot out of the OS-managed,
    # W^X-exempt native library directory instead.
    export PROOT_LOADER="$NATIVE_DIR/libproot.so"
    export PROOT="$NATIVE_DIR/libproot-xed.so"
    ln -s "$NATIVE_DIR/libtalloc.so" "$PREFIX/libtalloc.so.2"
fi
...
exec "$PROOT" $ARGS /bin/sh "$PREFIX/init-alpine.sh" "$@"
```

`ProcessManager.java` has the same branch as a code comment, verbatim: *"Play Store builds
package axs as a native library."* `isFdroidBuild()` there is literally `targetSdkVersion
<= 28` — confirming Acode's F-Droid flavor takes the identical grandfather-exception
shortcut this app currently does, and reserves the native-library trick specifically for
its Play Store flavor.

**Why this appears sufficient even though the Alpine rootfs itself lives in writable
storage — observed vs. inferred:** what's directly confirmed from source and from Acode's
own behavior: `$PREFIX/alpine` (the extracted rootfs — busybox, `/bin/sh`, `apk`, node,
agent CLIs, all of it) is bind-mounted via proot's `-r $PREFIX/alpine` rootfs flag, and only
`$PROOT`/`$PROOT_LOADER`/`$PROOT_LOADER32`/`libtalloc.so` themselves are placed in the
W^X-exempt native-lib directory — nothing inside the rootfs is. That much is a direct
reading of `init-sandbox.sh`. *Why* that's sufficient — the specific mechanism by which
proot avoids the kernel independently W^X-checking every file inside the rootfs — is this
document's **inference**, not something verified against proot's own source or Android's
kernel/linker source in this pass: proot is ptrace-based (well documented upstream), and it
virtualizes syscalls, memory layout, and path resolution for the traced process, but the
traced process's actual instructions still execute directly on the CPU (proot is not a CPU
emulator like QEMU) — describing everything inside the rootfs as merely "interpreted through
ptrace" overstates what ptrace does and could mislead readers about the actual mechanism.
The plausible reason W^X doesn't independently re-check every file the rootfs's own dynamic
linker maps is that proot's own "loader" companion binary (`libproot.so`/`libproot32.so`,
kept separate from the main `libproot-xed.so` proot binary) is itself the only file the
*kernel's* own `execve()` ever directly loads for the traced target — proot's documented
design routes program launches through a small loader process it controls via ptrace from
the first instruction, then maps the real target's memory itself in userspace, rather than
letting the kernel's normal ELF-loading path (the one W^X hooks) touch the target file
directly. This is a plausible, testable hypothesis consistent with what `init-sandbox.sh`
does, not a confirmed fact — verifying it would require reading proot's own source or
Android's kernel/linker enforcement point directly, neither of which was done here.

## 3. How Acode solves Problem B

`proot` is documented upstream as a ptrace-based syscall interceptor, not a CPU emulator —
confirmed here only in the negative sense that no QEMU dependency appears anywhere in
`src/plugins/proot/` or `src/plugins/terminal/`, so Acode isn't using QEMU for this. The
claim that "Zygote's seccomp-bpf filter has nothing to kill because proot substitutes the
syscalls it would block before they reach the kernel" is this document's inference about
*why* proot avoids that filter, carried over unchanged from the previous version of this
research — plausible given proot's documented ptrace-substitution design, but not verified
against proot's own source, Android's exact seccomp filter contents, or the specific
ordering of ptrace-interception vs. seccomp-evaluation in the kernel. Treat it as a
reasonable working hypothesis this document has not independently confirmed, not an
established fact.

## 4. Comparison

| | This app today (QEMU + Termux `.deb`) | Acode (proot + Alpine, verified) |
|---|---|---|
| Problem A (writable-storage exec / R1) | Not solved — `targetSdk` pinned to 28 | Solved: `proot`'s own binaries shipped as renamed `lib*.so` native libraries, extracted to real files on disk in the exempt `nativeLibraryDir` at install time via `useLegacyPackaging = true` |
| Problem B (foreign-libc seccomp block) | Solved via full QEMU user-mode emulation, applied per-agent-binary (confirmed, this app's own behavior) | Apparently solved via proot's ptrace-based syscall interception, applied once at the rootfs level (this document's inference — see Section 3) |
| What's bundled vs. downloaded | Node/git/QEMU downloaded at runtime from Termux's live package index; agent CLIs installed via `npm` at runtime | Alpine rootfs (and proot's own binaries) bundled directly in the APK as build-time assets/native libs, per ABI; `apk`/`npm` installs still happen at runtime *inside* the rootfs |
| Flavor split | Single flavor, `targetSdk = 28` everywhere | Two flavors: F-Droid (`targetSdk <= 28`, writable-storage exec) and Play Store (`targetSdk 29+`, native-lib-dir exec) — same codebase, env-driven (`$FDROID`) branch |

## 5. Implications for this app's R1 fix

The core idea — **ship the trusted native-code entry point as an APK native library instead
of downloading it into writable storage, and only exec that one file directly** — applies
independently of the QEMU-vs-proot question:

1. This does **not** require adopting proot. Bundling `qemu-*-static` itself (and the
   handful of native binaries this app currently execs directly — `libandroid-spawn`, native
   `aapt2`, etc.) the same way, as `jniLibs` renamed to `lib*.so` with
   `useLegacyPackaging = true`, would independently unblock raising `targetSdk` past 28 for
   this app's existing QEMU architecture. This is the smallest change that could plausibly
   fix R1 without touching R2 (16 KB alignment) or the Termux-package-vs-Alpine question at
   all.
2. Everything *downloaded at runtime* (Node/git/Termux packages, npm-installed agent CLIs)
   still needs to actually run as a real process. Per Acode's design, that's fine as long as
   those downloaded files are only ever exec'd *through* an already-exempt, natively-packaged
   interpreter/loader (proot, or in this app's case QEMU) — never `execve()`'d directly by
   the app itself. Confirm this app's current `NodeExecutionEnvironment`/`ProcessBuilder`
   call sites never directly exec a downloaded file without going through such a wrapper;
   audit before assuming the fix is purely packaging-level.
3. Switching from Termux `.deb` + QEMU to Alpine + proot wholesale is a much larger,
   separable decision (package ecosystem migration, R2's 16 KB alignment risk is unrelated
   either way, and the `ptrace` availability risk flagged below still applies). Don't couple
   it to the R1 fix.

## 6. Remaining open questions / risks

### 6a. `ptrace` availability on hardened devices

proot depends on `ptrace`, which some OEM/GKI SELinux policies block for app-spawned
processes independent of seccomp — the likely cause of AgenticDroid's earlier failed proot
attempt. Acode's source doesn't reveal how (or whether) they handle that failure mode;
worth checking their issue tracker or testing directly on a hardened device before
committing to proot specifically. This risk does **not** apply to the native-lib-packaging
trick if applied to this app's existing QEMU binaries instead of adopting proot (confirmed:
Sections 7-9's prototype uses only QEMU, no `ptrace` dependency anywhere).

### 6b. The real Play target-API deadline is API 36, not the 34 used in this prototype's tests

Sections 7-9 raised `targetSdk` to 34 for testing — chosen only as a convenient value above
28 that already triggers the W^X policy this document is investigating, **not** as a
proposed release target. Google Play's actual current requirement (confirmed via Play's own
[target API level requirements page](https://developer.android.com/google/play/requirements/target-sdk)):
starting **August 31, 2026**, new apps and updates to existing apps must target **Android 16
(API level 36)** (a narrower extension to November 1, 2026 is available on request; separate,
lower minimums apply to Wear OS/Android TV/Automotive, not relevant here). Any release-gate
testing this research eventually feeds into needs to be re-run at API 36, not assumed to
carry over unchanged from the API 34 experiments above — W^X and other platform behavior
relevant to this investigation has been stable since API 29, so a re-run is expected to
reconfirm rather than overturn Sections 7-9's results, but that is an expectation, not yet a
verified fact at API 36 specifically.

### 6c. Google Play policy on downloadable executable code — promoted to release-critical

Google Play's [Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646)
states that apps distributed via Google Play must not download executable code (the policy
names `.dex`, JAR, and `.so` files explicitly) from any source other than Google Play itself,
and may not self-modify/self-update outside Play's own update mechanism. There is a
narrow exception for code that runs inside a virtual machine or interpreter that only
provides *indirect* access to Android APIs (the policy's own example: JavaScript inside a
webview) — this is squarely aimed at scripting engines, not general-purpose native code
execution, and its applicability to QEMU hosting a real native musl/glibc agent CLI binary
downloaded at runtime is **not established** either way in this pass. This app's *other*
directly-`execve()`'d downloaded binaries (Node, git, Python, the JVM toolchain, Termux's
`aapt2` — see Section 6e's inventory) look **harder** to defend under this policy than the
QEMU-hosted agents do, since they're not behind any interpreter/VM boundary at all — they're
ordinary downloaded native executables run directly.

This is a genuine gap in this research: everything in Sections 7-9 establishes technical
*feasibility* (can Android's OS-level restrictions be satisfied), and says nothing about
Play *policy* compliance (will Google's review process, automated or human, accept this
app's category of downloaded/executed code at all). A definitive answer likely requires
either a direct policy consultation with Google, or a real submission-and-review test on a
throwaway listing — this document cannot resolve it through source reading or on-device
experiments. Until answered, treat Play distribution as blocked by policy risk independent
of and in addition to R1's technical W^X blocker.

### 6d. The 34 bundled native libraries needed a reproducible, auditable process — now added

Originally, Section 8's dependency closure was resolved with a one-off interactive script,
and the resulting 34 `.so` files were committed with no way to regenerate, audit, or update
them short of repeating that process by hand. This has been addressed: `tools/
fetch_qemu_native_libs.py` and `tools/qemu_native_libs_manifest.json` (committed alongside
this document) reproduce `app/src/main/jniLibs/arm64-v8a/`'s bundled libraries from pinned
Termux package name + version + source-`.deb` SHA-256 triples, re-verifying each download's
hash before use — the same trust model this app already applies to its own runtime
downloads (`READINESS_REVIEW.md`'s R4). See Section 8a for what building this script found.
(These were later merged and renamed to `tools/native_libs_manifest.json`/
`tools/fetch_native_libs.py` when the same pipeline was extended to node/git/aapt2 — see
Section 12; mentions of the qemu-only filenames elsewhere in this document reflect what was
true at that point in the research, not the current filenames.)

Still open: no license inventory or SBOM exists yet for these 34 third-party libraries
(glib is LGPL-2.1+, GnuTLS is LGPL-2.1+/GPL-3.0+ depending on component, OpenSSL is
Apache-2.0, GMP/Nettle are dual LGPL/GPL — stated here as a starting pointer, **not**
independently verified per-package in this pass), and there is no defined process yet for
tracking CVEs against pinned versions or deciding when/how to re-run the fetch script to
pick up security updates.

### 6e. Full inventory of what this app directly executes — updated, most items now closed

Solving Problem A for QEMU alone does not clear R1 app-wide. Every native binary or script
this app hands to `ProcessBuilder`/`execve()` is subject to the same W^X restriction once
`targetSdk` moves past 28, whether or not it involves QEMU at all. `NodeBootstrapper.kt`
(all citations below) is where most of these get `chmod +x`'d into app-private storage
after download/generation. **Node, git, and `aapt2` have since been addressed the same way
as qemu — see Section 12 for the verified work.**

| Binary/script | Where it comes from | Currently | W^X status at raised `targetSdk` |
|---|---|---|---|
| `qemu-aarch64` + dependency closure | Termux `.deb`, downloaded (`NodeBootstrapper.kt:478`) | **Also** bundled as native lib | **Solved** — Sections 7-9, verified |
| `node` | Termux `.deb`, downloaded | **Also** bundled as native lib | **Solved** — Section 12, verified |
| `git` and its HTTPS dispatch executable | Termux `.deb`, downloaded | **Also** bundled as native libs | **Solved** — Sections 12-13, including a real HTTPS clone through production routing |
| `aapt2` (Termux's Bionic-native build) | Termux `.deb`, downloaded | **Also** bundled as native lib | **Solved** — Section 12, verified. Also closes the specific item `READINESS_REVIEW.md` already called out by name |
| `npm`, `npx` | Generated `#!/system/bin/sh` wrapper scripts | **Also** bundled as native-lib scripts and exposed through PATH aliases | **Solved** — Section 14 verified that PackageManager extracts non-ELF `lib*.so` scripts executable; both explicit `NodeExecutionEnvironment` resolution and Terminal-style PATH lookup passed at `targetSdk = 36` |
| `python`/`python3`, `pip`/`pip3` | Termux `.deb` + generated wrapper scripts | **Also** packaged with native extensions and PATH aliases | **Solved for arm64** — Section 18, including native-module imports at target 36 |
| `java`, `javac`, `jar`, `keytool`, `javap`, `jlink` | Generated wrapper scripts pointing into a downloaded OpenJDK | Closure reproduced but not routed | **Blocked** — Section 19: OpenJDK derives its home from `/proc/self/exe`; needs a patched launcher, not another symlink |
| `libandroid-spawn.so` | Maintained 16 KB-aligned native override | Packaged through the `jniLibs` source set | **Solved** — Section 17; writable `dlopen()` warning also characterized |
| Codex/Antigravity's own vendor binaries | musl/glibc native binaries fetched by their own install commands (`AgentProfile.kt`) | Run **through** the native-lib QEMU wrapper, same as Claude | **Verified for Codex** (Section 9) via the same QEMU path; Antigravity (glibc-linked, not musl) untested |

Node/git/Python/Java/`aapt2` are Bionic-native Termux builds — they don't hit Problem B
(Zygote's seccomp block on foreign-libc loaders) the way agent CLIs do, since they're built
against Android's own libc. But they still hit Problem A identically to everything else:
they're `execve()`'d directly from app-private writable storage. Node, git, `aapt2`, npm/npx,
Python, and `libandroid-spawn` are now closed for arm64. Java's native closure is reproduced,
but the launcher's real-path-derived home remains the final known technical blocker — see
Section 19.

### 6f. Acode's own Play Store status is unconfirmed

Whether Acode's Play Store listing is actually live with the terminal feature enabled
(i.e. this architecture has cleared Play review in practice, not just in source) was not
independently confirmed in this pass — check the current Play Store listing directly.

## 7. Prototype result — verified on-device, 2026-08-19

Built and ran the trick end-to-end against this app's own `qemu-aarch64` binary on a real
connected device (arm64-v8a, Android 17 / API 37, not an emulator).

**What was done:**
1. Downloaded Termux's `qemu-user-aarch64` package directly (`packages-cf.termux.dev`),
   verified its SHA-256 against the pinned index value this app already trusts
   (`afc75a...4172be`), extracted the Bionic-native `qemu-aarch64` ELF from its `data.tar`.
2. Added it to the repo as `app/src/main/jniLibs/arm64-v8a/libqemu_user_aarch64.so` (renamed
   to the `lib*.so` convention, per Acode's precedent). `useLegacyPackaging = true` (see
   Section 2's corrected explanation of what this flag actually does) was **already set**
   in `app/build.gradle.kts` (presumably for `terminal-emulator`'s own bundled
   `libtermux.so`), so no packaging-config change was needed there.
3. Added `NodeRuntime.qemuNativeLibBinary()` and updated `NodeRuntime.qemuBinary()` to
   prefer it when present, falling back to the existing downloaded-at-runtime copy in
   `binDir` for any ABI without a bundled native lib (i.e. everything still works
   unchanged on x86_64).
4. Temporarily bumped `targetSdk` to 34 and added a one-shot diagnostic in
   `MainViewModel.init` that execs `--version` from both the new native-lib-dir path and a
   copy written to plain app-private storage and `chmod +x`'d at runtime (the app's current
   approach everywhere else) — **as a real Zygote-spawned child of the running app
   process**, not via `adb shell run-as` (which bypasses Zygote and is known to give false
   negatives for exactly this class of check — see `AgentProfile.kt`'s musl-loader
   comments).
5. Built, installed, launched, captured logcat, then reverted `targetSdk` to 28 and removed
   the diagnostic code. `NodeRuntime`'s native-lib resolution and the bundled `.so` were
   left in place as the actual prototype artifact. Full `testDebugUnitTest`/`lintDebug`
   still pass afterward.

**Result, captured live from `adb logcat`:**

```
native-lib-dir (new): exit=1 output=CANNOT LINK EXECUTABLE
  "/data/app/~~.../com.justnels.agenticdroid-.../lib/arm64/libqemu_user_aarch64.so":
  library "libandroid-shmem.so" not found: needed by main executable

app-writable-storage (old): THREW IOException: Cannot run program
  "/data/user/0/com.justnels.agenticdroid/files/qemu-wx-prototype/qemu-aarch64":
  error=13, Permission denied
```

This is an unambiguous, clean confirmation of the hypothesis in Section 2, on a real device
at `targetSdk = 34`:

- **Old approach (today's app-wide pattern):** `execve()` itself is rejected outright
  (`Permission denied`) — the live reproduction of R1, the actual Play Store blocker.
- **New approach (native-lib packaging):** `execve()` **succeeds** — the kernel/linker
  loaded and started the binary. It only failed afterward, at ordinary dynamic-linking, on a
  missing dependency (`libandroid-shmem.so`) that wasn't bundled in this prototype. That is
  an expected, unrelated, and fixable next step (see below) — categorically different from
  a permission/security rejection.

**What's still needed before this could replace the app-wide QEMU path:**

- Package `qemu-user-aarch64`'s full dependency closure (`libandroid-shmem`, `libdw`
  [→ `libelf`], `libgnutls` [→ `libp11-kit`, `libtasn1`, `libunistring`, `nettle`, `gmp`],
  `libpixman`, `glib`) the same way — every `.so` the dynamic linker loads needs to come
  from the exempt native-lib directory, or the same W^X check applies to it individually.
  Point `LD_LIBRARY_PATH` at `nativeLibraryDir` (or an equivalent resolution) for these,
  instead of (or in addition to) `NodeRuntime`'s current Termux-`usr/lib` path.
- Repeat for the x86_64 ABI (only arm64-v8a was prototyped, matching the one available test
  device).
- Decide whether to also apply this to the other binaries this app execs directly (native
  `aapt2`, `libandroid-spawn`) to fully clear R1, or scope an initial release to just the
  agent-launch path.
- `useLegacyPackaging = true` stores native libs compressed in the APK but extracts real
  copies to disk (`nativeLibraryDir`) at install time — see Section 2's corrected
  explanation. Installed-storage size impact of bundling qemu's full dependency closure
  (rather than downloading it) hasn't been measured.

## 8. Full dependency closure — verified on-device, 2026-08-19

Extended the prototype to resolve and bundle `qemu-aarch64`'s complete transitive shared
-library dependency closure, then verified the binary runs **to completion** (not just past
the initial `execve()` gate) as a real Zygote-spawned child at `targetSdk = 34`.

**How the closure was derived:** rather than trusting Termux's apt `Depends:` metadata
(which includes build-time/optional dependencies that aren't necessarily dynamically
linked), each library's actual ELF `DT_NEEDED` entries were parsed directly (a small
from-scratch ELF dynamic-section parser, since no `readelf`/`objdump` was available in this
environment) and walked breadth-first: `qemu-aarch64`'s own `DT_NEEDED` seeded the search;
each newly resolved `.so`'s own `DT_NEEDED` entries were parsed in turn; each soname was
mapped to its owning Termux package via the project's published `Contents-aarch64` index
(exact `.../lib/<soname>` path match); each package's `.deb` was downloaded and its SHA-256
verified against the same pinned `Packages` index this app already trusts for its runtime
downloads (matching the R4 threat model already documented in `READINESS_REVIEW.md`).
Bionic system libs already provided by the OS (`libc.so`, `libm.so`, `libdl.so`,
`liblog.so`) were excluded, since Termux binaries are built to link against the device's
own copies of those by design.

**Result: 33 shared libraries**, ~28.3 MiB total (~33 MiB combined with `qemu-aarch64`
itself), spanning glib, gnutls, pixman, libdw/libelf, nettle/hogweed/gmp, p11-kit, idn2,
tasn1, unbound, unistring, iconv, ffi, pcre2, lzma, bz2, zstd, zlib, openssl's libcrypto/
libssl, nghttp2/ngtcp2 (gnutls's QUIC/HTTP2 support), android-shmem, android-support, argp,
and libc++_shared. All added to `app/src/main/jniLibs/arm64-v8a/`, renamed to strip each
library's versioned soname suffix (`libz.so.1` -> `libz.so`, etc.) since Android's
native-library extraction only recognizes literal `lib*.so` filenames.

**The versioned-soname gap:** the binaries still request the *original* versioned sonames
at runtime (`qemu-aarch64` asks for `"libz.so.1"`, not `"libz.so"`), so `NodeRuntime.kt`
gained `ensureQemuNativeLibAliases()` - it (re)creates plain symlinks in a small
app-private directory, e.g. `libz.so.1 -> <nativeLibraryDir>/libz.so`, exactly mirroring
Acode's own `libtalloc.so.2 -> libtalloc.so` symlink in `ProcessManager.java`. This is safe
under W^X: the symlink *inode* lives in writable storage, but the kernel resolves it
transparently to the real file in the exempt native-lib directory before mapping any bytes
executable - the symlink itself is never treated as executable content.

**Verification:** temporarily bumped `targetSdk` to 34 again, added a one-shot diagnostic
that resolves the aliases, sets `LD_LIBRARY_PATH` to the alias dir + native-lib dir, and
runs `qemu-aarch64 --version` as a real forked child of the running app (same
run-as-avoiding methodology as Section 7). Captured live:

```
full-closure: exit=0 output=qemu-aarch64 version 11.0.3
Copyright (c) 2003-2026 Fabrice Bellard and the QEMU Project developers
```

Exit code 0, real version output - the full closure is correct and sufficient. Reverted
`targetSdk` to 28 and removed the diagnostic code afterward, as in Section 7; the bundled
libraries, `NodeRuntime.qemuNativeLibBinary()`/`ensureQemuNativeLibAliases()`/
`qemuNativeLibLdPath()`, and the aliasing logic were left in place as the real prototype
artifact. `testDebugUnitTest`/`lintDebug` still pass.

### 8a. Making the closure reproducible caught a real bug

Following external review (see the note at the top of this document), the closure was
re-derived from a committed, reproducible tool instead of the original one-off interactive
script: `tools/fetch_qemu_native_libs.py` + `tools/qemu_native_libs_manifest.json` (package
name, pinned version, and source-`.deb` SHA-256 per bundled file). Building this caught a
real defect in the original bundle: the `ndk-multilib` Termux package ships **four CPU
-architecture variants** of `libc++_shared.so` (aarch64, arm, x86, x86_64 - all under paths
that satisfy a naive "somewhere under `usr/lib`" match), and the original ad-hoc script had
picked the **x86_64** copy by incidental tar-member iteration order - a wrong-architecture
binary silently bundled for an arm64-v8a target.

None of Sections 7-9's on-device tests happened to exercise this file (Claude/Codex/qemu's
own `--version` paths apparently never actually force-load `libc++_shared.so`, so a wrong
-architecture copy sitting unused in the bundle produced no visible failure) - which is
itself worth noting as a gap: **a bundled-but-unexercised wrong-architecture library is a
latent defect that on-device testing alone did not catch**; only building a reproducible,
auditable pipeline surfaced it. `fetch_qemu_native_libs.py` now verifies each resolved
library's ELF machine field is `EM_AARCH64` before accepting it, and treats an ambiguous
architecture match as a hard error rather than picking one silently. The corrected file is
committed; **this specific fix has not been re-verified on-device** (Sections 7-9's
passing runs predate it) - low risk given it wasn't exercised by those tests either way, but
worth a fresh on-device pass before treating this bundle as final.

## 9. Claude Code wired through it end-to-end — verified on-device, 2026-08-19

Installed Claude Code CLI through the app's real, unmodified production install path
(`DefaultAgents.Claude.installCommand()`, run via a temporary hook that called the app's
own `NodeExecutionEnvironment` - not a hand-reconstructed shell command) at the normal
`targetSdk = 28`. This confirmed, as a side effect, that `NodeRuntime.qemuBinary()`
preferring the native-lib copy (added in Section 7) is already a transparent drop-in
replacement in production today: the generated wrapper script at
`.../global/bin/claude` resolved `$QEMU_BIN` straight to
`<nativeLibraryDir>/libqemu_user_aarch64.so` with no code path changes needed, and the
install's own self-test (`claude --version`) passed normally, install exit code 0, reporting
`2.1.236 (Claude Code)`.

That install produces the same artifacts a real user's install would: a musl-linked
native `claude` binary at
`.../global/lib/node_modules/@anthropic-ai/claude-code-linux-arm64-musl/claude`, and a
wrapper script whose relevant line is:

```sh
exec "$QEMU_BIN" -L "$QEMU_SYSROOT" "$natdir/claude" "$@"
```

`$QEMU_SYSROOT` is the *guest* sysroot (Termux's `usr/` tree, resolved by QEMU's `-L` flag
for the guest binary's own musl library lookups) - unaffected by W^X, since QEMU's guest
-side loading happens inside its own userspace CPU/syscall emulation (ordinary `read()`
+ writes into anonymous JIT-mapped memory), never through Android's own dynamic linker or a
kernel-level `mmap(PROT_EXEC)` of a guest file. Only the *host* side - `qemu-aarch64` itself
and its own directly-dlopen'd Bionic dependencies (Section 8's 33-library closure) - needs
the native-lib-dir treatment. This means **agent CLI binaries and their own musl libraries
never need to move out of app-private storage at all** - only the qemu binary and its own
host-side dependency closure do.

Temporarily raised `targetSdk` to 34 again and added a one-shot diagnostic that invoked the
already-installed `claude` binary directly through `qemu-aarch64`, identically to the
wrapper script above except overriding just the host `LD_LIBRARY_PATH` to
`NodeRuntime.qemuNativeLibLdPath()` (Section 8's alias dir + native-lib dir) instead of the
wrapper's default `$QEMU_SYSROOT/lib`. Captured live, as a real Zygote-spawned child:

```
claude-through-qemu: exit=0 output=2.1.236 (Claude Code)
```

This is the definitive result for this investigation: a real, installed, unmodified agent
CLI's actual native binary ran to completion through a natively-packaged
`qemu-aarch64` + its full dependency closure, at `targetSdk = 34`, exactly reproducing what
this app already does at `targetSdk = 28` today via the downloaded-storage path. Reverted
`targetSdk` to 28 and removed the diagnostic code afterward; `testDebugUnitTest`/`lintDebug`
still pass.

Repeated against Codex, using the same methodology (installed via
`DefaultAgents.Codex.installCommand()` through the app's real `NodeExecutionEnvironment`,
found the wrapper the install generated at
`.../vendor/aarch64-unknown-linux-musl/bin/codex.real`, re-ran it through
`qemu-aarch64` with `LD_LIBRARY_PATH` overridden to `qemuNativeLibLdPath()` at
`targetSdk = 34`, as a real Zygote-spawned child):

```
codex-through-qemu: exit=0 output=WARNING: proceeding, even though we could not create
  PATH aliases: Could not find home directory
codex-cli 0.148.0
```

Exit 0, real version output (the `HOME`-related warning is an ordinary Codex CLI message
from this diagnostic not setting `$HOME` - unrelated to QEMU/W^X). This is a meaningfully
different case from Claude Code: per `AgentProfile.kt`'s own comments, Codex's native
binary is confirmed **statically linked (static-pie, no ELF interpreter at all)**, a
categorically different problem from Claude's dynamically-linked musl relocation issue -
both are called out separately as distinct reasons this app needed QEMU in the first place.
Confirming both linking styles work through the natively-packaged qemu-aarch64 + closure
is stronger evidence than either alone that this approach generalizes across this app's
agent CLIs, not just Claude Code specifically.

## 10. What's still needed before this could replace the app-wide QEMU path

- **This closure was arm64-v8a-only as of Section 9; node, git, and `aapt2` have since
  been added the same way — see Section 12.** The x86_64 ABI still needs the same treatment
  for all four binaries (a different package set from Termux's `binary-x86_64` index).
  `libandroid-spawn`, Python, and the JVM toolchain remain unaddressed — see the updated
  Section 6e inventory.
- **Production wiring is a smaller lift than it looked.** Section 7-8's change to
  `NodeRuntime.qemuBinary()` already makes every install/launch that reads `$QEMU_BIN`
  transparently use the native-lib copy with no further code changes - confirmed here by
  Claude's own generated wrapper picking it up automatically. What's *not* yet wired is the
  host-side `LD_LIBRARY_PATH` override (`qemuNativeLibLdPath()`); `AgentProfile.kt`'s
  wrapper-script templates still hardcode `LD_LIBRARY_PATH="$QEMU_SYSROOT/lib"`, which only
  matters once `targetSdk` actually moves past 28 (harmless today, since nothing enforces
  W^X yet).
- **Installed storage footprint, not primarily APK download size.** `useLegacyPackaging =
  true` stores these libraries *compressed* inside the APK (so the download-size hit is
  smaller than the raw ~33 MiB), but extracts full, real copies to disk in
  `nativeLibraryDir` at install time (see Section 2) — that on-device footprint is the real
  cost, and hasn't been measured against Play's App Bundle per-ABI splitting (which should
  at least keep a single device's *download* to just its own ABI's slice; the on-disk
  extraction cost is a separate, unavoidable-under-`useLegacyPackaging` concern). Multiplied
  across ABIs and any other binaries moved the same way, this is worth sizing properly
  before treating it as settled.
- **Only `--version`-style invocations tested, not full sessions.** Claude Code and Codex
  (both dynamic-musl and static-pie linking styles) confirmed working end-to-end - see
  Section 9 - but that isn't the same as a full interactive session under this app's
  PTY-backed Terminal service, or Antigravity's own separate QEMU-wrapped, glibc-linked
  binary (a third, still-untested case).
- **`ptrace`/OEM-hardening risk (Section 6) is orthogonal and still unverified** - this
  prototype used QEMU (no `ptrace` dependency at all), not proot, so it doesn't carry that
  risk; noted here only so it isn't conflated with this result if proot is revisited later.

## 12. Node, git, and aapt2 native-lib-packaged and verified — 2026-08-19

Completed the Section 6e inventory for the three next-largest directly-executed binaries,
using the exact same process as qemu (Sections 7-9): pull the real installed binary,
parse its actual ELF DT_NEEDED entries (not apt `Depends:` metadata), resolve the closure
recursively against Termux's package index with SHA-256 verification per download, bundle
as renamed `lib*.so` native libraries, and verify on a real device as genuine Zygote
-spawned children at a raised `targetSdk`.

**Real DT_NEEDED, pulled from the actual installed binaries** (via `adb exec-out run-as`,
not `adb shell` — the latter corrupts binary transfers on this setup):

- `node`: `libz.so.1`, `libcares.so`, `libsqlite3.so`, `libffi.so`, `libcrypto.so.3`,
  `libssl.so.3`, `libicui18n.so.78`, `libicuuc.so.78`, `libc++_shared.so` (plus
  Bionic system libs).
- `git`: `libpcre2-8.so`, `libz.so.1`, `libiconv.so`, `libcrypto.so.3` — **every one of
  these was already bundled for qemu**, so `git` needed zero new library files, only its
  own ~3.5 MiB binary.
- `aapt2` (downloaded fresh and DT_NEEDED-parsed, since it wasn't installed on the test
  device's `RunnerPackageGroup.JVM`): `libfmt.so`, `libz.so.1`, `libpng16.so`,
  `libexpat.so.1`, `libprotobuf.so`, `libabsl_hash.so`, `libutf8_validity.so`,
  `libc++_shared.so` — `libabsl_hash.so` alone pulled in abseil-cpp's full ~68-library
  closure (each individually tiny, ~2 KB-150 KB) once walked recursively.

**Combined closure: 119 bundled files total** (up from 34) — the original 34 plus 82 new
canonical libraries plus the 3 new main binaries themselves
(`libnode_native_aarch64.so`, `libgit_native_aarch64.so`, `libaapt2_native_aarch64.so`,
alongside the existing `libqemu_user_aarch64.so`), ~132 MiB in
`app/src/main/jniLibs/arm64-v8a/`. A cross-check parsing DT_NEEDED of every one of the 119
bundled files and confirming every requested soname resolves to something bundled (directly
or via a versioned-soname alias) found **zero gaps** - the closure is self-consistent.

**Size breakdown, since it's dominated by one component:** `aapt2`'s own new closure
(abseil-cpp + fmt + png + protobuf) is a modest ~5.7 MiB. `node`'s new closure is
~38.3 MiB, almost entirely full ICU (`libicudata.so` alone is ~33 MiB — Node's
internationalization/`Intl` data, an unconditional eager-loaded dependency of a binary this
document doesn't control the build of). `git` added nothing.

**`tools/` generalized to a single reproducible pipeline**: `qemu_native_libs_manifest.json`
/`fetch_qemu_native_libs.py` were merged and renamed to `native_libs_manifest.json`/
`fetch_native_libs.py`, covering all four binaries in one manifest. Each of the 119 entries
now carries a `used_by` field recording which main binary(ies) actually need it per real
DT_NEEDED chasing (several are shared — e.g. `libz.so` is needed by all four). Re-ran the
script end-to-end from a clean manifest; it reproduced all 119 files with zero errors,
byte-identical to the versions already committed.

**`NodeRuntime.kt` generalized**: the qemu-specific `qemuNativeLibBinary`/
`ensureQemuNativeLibAliases`/`qemuNativeLibLdPath` became a shared
`nativeLibBinary(context, filename)`/`ensureNativeLibAliases`/`nativeLibLdPath`, with a
single combined 16-entry soname-alias map covering every versioned soname any of the 119
bundled files actually requests (derived the same cross-check way, not hand-assembled).
`nodeBinary()`, `gitBinary()`, and `aapt2Binary()` now prefer their native-lib copies the
same way `qemuBinary()` already did, falling back to the downloaded copy on any ABI without
a bundled native lib. `configureEnvironment()`'s `LD_LIBRARY_PATH` now prepends the
native-lib alias/dir paths ahead of the existing Termux `usr/lib` path unconditionally —
confirmed harmless when nothing native-lib-packaged is actually invoked, and necessary for
`node`/`aapt2` to find dependencies (`libcares`, `libsqlite3`, ICU, abseil-cpp, etc.) that
exist **only** in the native-lib bundle, never in the downloaded tree.

**Verification, real device, `targetSdk = 34`, genuine Zygote-spawned children** (same
run-as-avoiding methodology as Sections 7-9):

```
resolved: node=.../lib/arm64/libnode_native_aarch64.so
          git=.../lib/arm64/libgit_native_aarch64.so
          aapt2=.../lib/arm64/libaapt2_native_aarch64.so
node: exit=0 output=v26.4.0
git: exit=0 output=git version 2.55.0
aapt2: exit=0 output=Android Asset Packaging Tool (aapt) 2.20-android-16.0.0_r4
```

All three real Bionic-native binaries — not `--version`-only smoke tests but the actual
version strings these tools report — ran to completion at a raised `targetSdk`. Reverted
`targetSdk` to 28 and removed the diagnostic code afterward, as in every prior section;
`testDebugUnitTest`/`lintDebug` still pass; the 81 MiB debug APK (`useLegacyPackaging =
true` keeps these compressed in the APK itself — see Section 2) installs and runs
correctly at both `targetSdk = 28` and `34`.

**What this does and doesn't close, precisely:** four of the roughly nine items in Section
6e's inventory are now solved and verified (qemu, node, git's main binary, `aapt2`).
`git-core`'s helper binaries (`git-remote-https`, `git-upload-pack`, etc. — not inventoried
in this pass), `npm`/`npx`/the JDK tool wrappers (a different problem — see Section 6e's
updated row on why bundling isn't obviously the fix there), Python, the JVM toolchain, and
`libandroid-spawn` remain open.

## 13. `git`'s https remote helper closed and verified via real `execve()`, not just `dlopen` — 2026-08-19

Section 12 left `git-core`'s helper binaries open. Completed the DT_NEEDED closure for
`git-remote-https` (the transport helper `git clone`/`fetch`/`push` needs for any `https://`
remote) the same way as Section 12's three binaries — 4 new bundled files
(`libgit_remote_https_native_aarch64.so` plus `libcurl.so`/`libnghttp3.so`/`libssh2.so`, its
only genuinely new dependencies — everything else it needs was already bundled), taking the
manifest to 123 entries. This section is about what real on-device testing found once that
bundle was wired in — the mechanism turned out to be meaningfully different from what static
analysis predicted, in two ways.

**Finding 1 — a real thread-safety bug, caught only by testing with two real callers
running concurrently.** `ensureSymlink`'s `deleteIfExists()` + `createSymbolicLink()` isn't
atomic. The interactive Terminal's `TerminalViewModel` init (main thread) and this
diagnostic's background clone both call `configureEnvironment()`, and both raced to replace
the same symlink — one thread's `deleteIfExists()` ran between the other's delete and
create, so the second `createSymbolicLink()` failed with `FileAlreadyExistsException` and
crashed the app on launch. Fixed by wrapping `ensureSymlink` in a `synchronized` block (a
process-wide lock is sufficient — these calls are cheap and infrequent). This class of bug
is specific to real concurrent callers and wouldn't show up in a single-threaded diagnostic
hook, which is what every earlier verification in this document used.

**Finding 2 — the actual execve() target was not the file that was symlinked.** After fixing
the race, the real `git clone https://github.com/octocat/Hello-World.git` still failed:
`fatal: cannot exec 'remote-https': Permission denied`, and `logcat` showed the real cause —
an SELinux `avc: denied { execute_no_trans }` for
`.../files/node-runtime/usr/libexec/git-core/git` (not `git-remote-https`). Termux's own
package layout keeps a **second, separately-copied** `git` binary at
`usr/libexec/git-core/git` (confirmed via `ls -i`: a different inode than `usr/bin/git`, not
a hardlink in this app's extraction). Git's internal remote-helper dispatch execve()s *that*
file directly, with `argv[0]` forced to `"git-remote-https"`, rather than looking up a
genuinely separate `git-remote-https` file on `$GIT_EXEC_PATH` — so symlinking only the four
remote-helper names (per Section 8/12's original inventory) missed the file actually being
exec'd. Confirmed the bundled `git-remote-https` binary is **not** byte-identical to the
main `git` binary (different SHA-256 — it genuinely links libcurl, unlike core `git`), so
this isn't simply "the same file" — `ensureGitRemoteHelperLinks` now symlinks
`libexec/git-core/git` itself to the native-lib `git` binary (the same target
`gitBinary()`/`usr/bin/git` already resolves to), and keeps the original four remote-helper
symlinks as a harmless belt-and-suspenders fallback in case some other git build genuinely
looks for a separate file. This is the first real validation in this document that a
symlink-to-`nativeLibraryDir` target survives `execve()` (not just `dlopen()`, which every
prior soname-alias verification tested) — it does, once pointed at the file actually
exec'd.

**Also fixed while testing this path**: `NodeExecutionEnvironment.exec()`'s own binary
resolution (`binDir`/`globalBinDir`/`.local/bin` lookup by first word) never consulted the
native-lib copies at all — a plain `"git"`/`"node"`/`"aapt2"` command resolved straight to
the old writable-storage copy left over from the original download, since that file still
exists there and is found first. This meant **no previously-"verified" `git clone` through
the real `GitManager`/`NodeExecutionEnvironment` path had ever actually exercised the
native-lib `git` binary** — Section 12's verification called `NodeRuntime.gitBinary()`
directly from a diagnostic hook, which bypassed this gap entirely. Fixed by resolving
`git`/`node`/`aapt2` through `NodeRuntime`'s native-lib-aware getters first, before falling
back to the old search list. Separately, added plain-named `node`/`git`/`aapt2` symlinks
into `nativeLibAliasDir` (alongside the existing versioned-soname aliases) and put that
directory first on `PATH` — needed because the **interactive PTY Terminal** resolves
commands via its own shell's `PATH` search, a code path that never goes through
`NodeExecutionEnvironment.exec()`'s explicit resolution at all, so the explicit-resolution
fix above doesn't cover it on its own.

**Verified end-to-end, real device, `targetSdk = 34`, genuine Zygote-spawned child, through
the actual production `GitManager`/`NodeExecutionEnvironment` path** (not a hand-rolled
diagnostic exec):

```
GitCloneDiag: starting real https clone at targetSdk=34
NodeExec: Executing command: 'git' '-c' 'core.filemode=false' 'clone' 'https://github.com/octocat/Hello-World.git' '...'
GitCloneDiag: clone result: Success(output=Cloning into '.../diag-clone-test'...)
GitCloneDiag: dest listing: [.git, README]
```

One benign-looking residual denial (`avc: denied { link }` for a `tmp_pack_*` file during
the clone, `app_data_file` context) didn't block the clone — git evidently falls back when a
hardlink isn't permitted. Not yet explained; worth revisiting if a future symptom traces back
to it, but not currently blocking.

Reverted `targetSdk` to 28 and removed the diagnostic hook afterward, as in every prior
section; `assembleDebug`/`testDebugUnitTest` pass at `targetSdk = 28`.

**Updated Section 6e status**: `git-core`'s helper binaries are now closed and verified via
a real `execve()` path, not just `dlopen()`. At this point the remaining items were
`npm`/`npx`/JDK-tool wrappers, Python, the JVM toolchain, and `libandroid-spawn`; Section 14
subsequently closed `npm`/`npx` and proved the reusable wrapper-script mechanism.

**Note for whoever tackles the `npm`/`npx`/JDK-tool wrapper problem next** (Section 13,
item 2): this section's Finding 2 is a caution against assuming a "wrapper" problem is
solved by symlinking the file whose name matches what's being looked up — verify by tracing
the actual file SELinux denies, not the file being looked up in source. It's also worth
separately confirming (before writing a compiled dispatcher shim or any other new mechanism)
whether a **non-ELF file** — e.g. a plain `#!/system/bin/sh` script — placed in `jniLibs` and
named `lib*.so` actually survives PackageManager's native-lib extraction with the exec bit
set; extraction is filename-pattern-based, not content-validated, so it may. If it does, the
existing symlink-to-native-lib-dir mechanism this section just validated for `execve()`
extends directly, without needing a new binary format or build step.

## 14. Non-ELF native-lib scripts and `npm`/`npx` verified — 2026-08-19

Ran the cheap decision test proposed at the end of Section 13 before building a compiled
dispatcher. A 42-byte `#!/system/bin/sh` probe was placed in `jniLibs/arm64-v8a` under a
literal `lib*.so` filename, then installed and launched on the real arm64 device at
`targetSdk = 36`. PackageManager extracted it into `nativeLibraryDir` as a real executable
file despite its content not being ELF. Both direct execution and execution through a
plain-named symlink in app-private storage succeeded as genuine app-spawned children:

```
target=36 ... exists=true executable=true length=42 aliasIsSymlink=true
direct exit=0 output=JNI_SCRIPT_PROBE_OK
alias exit=0 output=JNI_SCRIPT_PROBE_OK
```

This is the decisive answer: **a compiled dispatcher shim is unnecessary.** The existing
native-lib-dir + writable-symlink mechanism works for scripts as well as ELF executables.
The temporary probe file, diagnostic hook, and target bump were removed after capture.

Applied the result to the two immediately solvable production wrappers:

- Added `libnpm_wrapper.so` and `libnpx_wrapper.so` as reproducibly generated shell scripts in
  `jniLibs/arm64-v8a` (generated by `tools/fetch_native_libs.py`; the output directory remains
  gitignored). They invoke the already native-lib-packaged `node`; npm's JS entry
  path comes from the existing `$NPM_CLI` runtime environment variable, so neither script
  embeds an installation-specific app-data path.
- Added plain `npm`/`npx` symlinks to `nativeLibAliasDir`, alongside the existing
  `node`/`git`/`aapt2` aliases, for interactive Terminal PATH lookup.
- Updated `NodeExecutionEnvironment.exec()`'s explicit first-command resolution so its
  separate non-PTY path selects these native-lib scripts instead of the legacy generated
  copies under writable `usr/bin`.
- Marked the two script patterns as `keepDebugSymbols` so AGP does not pass deliberately
  non-ELF inputs to `llvm-strip`; this changes no runtime bytes.

Verified the production-form wrappers at `targetSdk = 36` through both execution paths:

```
NodeExec command=npm --version exit=0 stdout=11.19.0 stderr=
NodeExec command=npx --version exit=0 stdout=11.19.0 stderr=
PATH exit=0 output=11.19.0
11.19.0
```

The same packaged-script mechanism can provide JDK command wrappers without a compiled
shim, but it does **not** make the downloaded OpenJDK executables they ultimately launch
W^X-safe. Java remains open until its actual ELF binaries and native-library closure are
packaged and sized; Python and `libandroid-spawn` likewise remain open.

Reverted `targetSdk` to 28 and removed the diagnostic hook afterward. The two production
scripts and their resolution/alias wiring remain.

## 15. Play App Bundle size gate checked — 2026-08-19

Checked the other cheap gate the external advisor placed ahead of the JVM work. Google
Play's current detailed size documentation allows a **500 MB compressed base module** and
a **4 GB cumulative compressed download to one device**. Crossing 200 MB produces a
non-blocking large-download warning on mobile data; it is no longer the base-module
rejection threshold. Limits are calculated from Play's compressed-download estimate, not
the raw source directory or the uploaded AAB's uncompressed contents:

- [Play Console size-limit documentation](https://support.google.com/googleplay/android-developer/answer/9859372)
- [Android App Bundle FAQ](https://developer.android.com/guide/app-bundle/faq)

Built the real debug bundle after Section 14's production changes and measured all three
relevant layers:

| Artifact/layer | Measured size |
|---|---:|
| Raw `app/src/main/jniLibs/arm64-v8a/` (125 files) | 140,803,326 bytes |
| Debug AAB compressed file | 75,427,143 bytes |
| Debug AAB total uncompressed ZIP entries | 218,405,734 bytes |
| Debug APK compressed file | 82,890,630 bytes |

The AAB itself is not Play Console's final per-device gzip estimate, so a release bundle
must still be checked with bundletool/Play Console before publication. But at ~75.4 MB it
has roughly 424 MB of base-module headroom against the current hard limit. The JVM closure
is therefore **not presently blocked by the Play upload-size limit**. Its likely cost still
matters for install footprint, download conversion, update bandwidth, and the 200 MB
warning threshold; those remain product/optimization concerns rather than reasons to stop
the closure experiment before sizing it.

## 16. Suggested next steps

Revised after external review (see the top of this document), Section 12's completion of
three more inventory items, Section 13's closure of `git-core`'s helper binaries, and
Section 14's closure of `npm`/`npx` without a compiled shim, and Section 15's confirmation
that the current AAB has ample headroom under Play's base-module limit, to
prioritize closing gaps that could invalidate the whole direction over mechanically
extending it further. **x86_64 support is deliberately last** — it's comparatively
mechanical once the harder open questions below are resolved, and not worth investing in if
this direction turns out to be blocked by Play policy (Section 6c) or unworkable once every
execution lane is accounted for (Section 6e).

1. **Housekeeping**: keep this document's corrected claims and the committed
   `tools/fetch_native_libs.py` pipeline as the source of truth going forward; confirm no
   temporary diagnostic code is left in the working tree before treating any of this as
   stable (checked clean as of every revision so far - see the top-of-document note and
   Sections 12-15's own cleanup).
2. **Finish the remaining Section 6e items**: size and bundle Python and the JVM
   toolchain's own executable/native-library closures, then use Section 14's proven
   packaged-script mechanism for their command aliases where needed; also move
   `libandroid-spawn` from its current runtime-extracted asset path. Expect the JVM
   specifically to be a large, separate undertaking — a real OpenJDK's native library set is
   substantial and wasn't sized in this pass. Section 15 cleared the hard Play-size gate,
   but the installed-size and download-UX costs still need measurement.
3. **Wire and test the complete arm64-v8a application at `targetSdk = 36`** (Section 6b) —
   not 34, which was only ever a convenient W^X test point — including interactive PTY
   Terminal sessions and the on-device build tooling path (`aapt2`, the Android SDK
   bootstrap, an actual project build), not just version-string invocations.
4. **Resolve the Play downloadable-code policy question (Section 6c)** — likely requiring a
   real submission-and-review test or a direct policy consultation, not something
   resolvable by further source reading or on-device experiments. This could independently
   block Play distribution regardless of how completely the technical W^X work above is
   finished, so treat it as a gating decision, not a background research item.
5. **Size the real installed-storage cost** (Section 10) of the now-132 MiB
   `jniLibs/arm64-v8a/` directory on an actual constrained device, and decide whether ICU's
   ~33 MiB (needed only for `node`, and only because Termux's build isn't configured
   small-icu/no-icu) is worth it as-is or worth pursuing a smaller Node build for.
6. **Only then** decide whether x86_64 support (repeating Sections 7-9 and 12's process for
   that ABI) is worth its size and maintenance cost, informed by whatever real device/traffic
   mix this app actually needs to support.

## 17. `libandroid-spawn` packaging and writable `dlopen()` nuance — 2026-08-19

The maintained, 16 KB-aligned `libandroid-spawn.so` files already existed under
`assets/native-overrides/libandroid-spawn`, but the readiness notes' claim that runtime code
copied them was wrong: no such copy path existed. The app now adds that directory as a
`jniLibs` source set, so PackageManager installs the library in `nativeLibraryDir`. NDK
`readelf` confirmed 16 KB `PT_LOAD` alignment and the expected dependency set.

On the Android 17/API 37 arm64 test device at `targetSdk = 36`, `System.load()` succeeded
from both a writable app-data copy and the PackageManager copy. Android emitted an explicit
warning for the writable case: `Attempt to load writable file ... This will throw on a
future Android version.` The accurate conclusion is therefore narrower than earlier text:
writable `dlopen()` is deprecated and not future-safe, but it is not yet rejected on every
current Android build. Native-library packaging is still the correct closure.

## 18. Python arm64 closure solved and verified — 2026-08-19

The production bootstrap installed Python 3.14.6 plus pip and measured 8,794,948 downloaded
package bytes. Raising the target to 36 then reproduced the expected SELinux
`execute_no_trans` denial for the writable `usr/bin/python3` launcher.

`tools/fetch_python_native_libs.py` now discovers or reproduces a checksum-pinned closure
from Termux packages. Its manifest contains the Python launcher, `libpython`, all 75 standard
library extension modules, and their Python-specific dependencies (95 packaged native files
and 124 runtime symlink mappings). `python-native-links.json` drives idempotent links back
into the ordinary downloaded stdlib tree, while packaged `pip`/`pip3` shell wrappers use the
non-ELF mechanism proven in Section 14. Both explicit command resolution and Terminal PATH
aliases select the packaged files.

Real-device verification at `targetSdk = 36`, through `NodeExecutionEnvironment`, passed:

```
python3 --version -> Python 3.14.6 (exit 0)
import ssl, sqlite3, ctypes, curses, lzma, bz2, readline, multiprocessing -> exit 0
pip --version -> pip 26.2.1 (exit 0)
```

Python is closed for arm64-v8a. Other ABIs remain unimplemented.

## 19. JVM closure reproduced; target-36 launcher remains blocked — 2026-08-19

The production JVM group was corrected to request OpenJDK's direct runtime dependencies
needed for command-line Java/Kotlin work: `openjdk-17`, `libjpeg-turbo`, `littlecms`, `aapt2`,
and `kotlin`. The live packages measured 183,323,524 downloaded bytes; the extracted JDK was
about 220 MB and Kotlin about 92.8 MB. The optional ALSA/PulseAudio dependency tree was not
added because it serves Java Sound rather than compilation or ordinary JVM execution.

`tools/fetch_jvm_native_libs.py` now records a checksum-pinned prototype closure: six JDK
launchers (`java`, `javac`, `jar`, `keytool`, `javap`, and `jlink`), 31 OpenJDK shared
libraries, and three direct dependency libraries. The generated manifest has 40 files and
45 intended runtime links. This is useful, reproducible closure work, but it is deliberately
**not routed in production** yet.

The decisive target-36 failure is OpenJDK launcher identity, not a missing dependency. A
launcher executed from Android's flat `nativeLibraryDir` reports `Could not find libjava.so`
because OpenJDK derives its installation root from the real executable path. Symlinking it
back under `JAVA_HOME/bin` does not help: Linux `SetExecname` reads `/proc/self/exe`, and the
launcher searches for `libjava.so` relative to that resolved location. QEMU follows the same
real launcher identity and does not change the result. The behavior is visible directly in
[OpenJDK's Unix libjli source](https://github.com/openjdk/jdk/blob/master/src/java.base/unix/native/libjli/java_md.c).

Consequently the native JDK PATH aliases, explicit resolver entries, and generated JDK
symlink application were gated back out before cleanup. Leaving them enabled would have
broken even the target-28 runtime by replacing working `JAVA_HOME/bin` launchers with files
whose resolved home is wrong. The next implementation must be a small maintained launcher
patched to accept the actual `JAVA_HOME` (or a direct JNI bootstrap with equivalent command
semantics); another shell wrapper or symlink cannot solve this layer.

One independent bootstrap bug was fixed now: Termux Kotlin 2.4.10 ships a shebang referring
to Termux's private `env bash`, which cannot exist in this app. `NodeBootstrapper` now writes
an idempotent `/system/bin/sh` wrapper that invokes Kotlin's preloader/compiler jars through
the installed JDK. On the final target-28 device pass, all of these succeeded through the
production execution environment:

```
java -version                         -> OpenJDK 17.0.20, exit 0
javac Hello.java; java -cp . Hello    -> JAVA_OK, exit 0
kotlinc -version                      -> kotlinc-jvm 2.4.10, exit 0
kotlinc Hello.kt ...; java -jar ...   -> KOTLIN_OK, exit 0
```

Kotlin prints a non-fatal Jansi warning because its bundled Linux/arm64 native library is
glibc-linked; it falls back normally and compilation succeeds.

After the Python and JVM prototype files, the arm64 `jniLibs` directory is 170,767,554 bytes
(269 files). The final debug APK is 94,860,304 bytes; the debug AAB is 86,547,190 bytes with
248,508,051 bytes of uncompressed ZIP entries. This remains well below the current 500 MB
compressed base-module limit, though unused JVM prototype files should be excluded from a
release if the launcher work is not completed.

Final cleanup restored `targetSdk = 28`, removed every diagnostic hook, and passed
`assembleDebug`, `testDebugUnitTest`, and `bundleDebug`.

## 20. Updated next steps

Section 21 completed item 1 below. The remaining sequence starts with the unified app test.

1. Run the complete arm64 application matrix at target 36, especially PTY Terminal lookup,
   Android SDK/AGP builds, `aapt2`, agent CLIs, git HTTPS, Python/pip, and JVM tools in one
   install rather than isolated probes.
2. Measure bundletool's per-device compressed download and actual installed storage for the
   complete native closure, including the now-routed JVM files.
3. Resolve the Play downloadable-code policy gate through policy consultation or a real
   review test. Technical W^X closure does not answer that distribution question.
4. Only after those gates, reproduce the pinned closures for x86_64 if device demand
   justifies the additional artifact and maintenance cost.

## 21. Minimal patched OpenJDK 17 launcher built and verified — 2026-08-19

Built a reproducible arm64 `libjli.so` from the official OpenJDK 17.0.20 sources, pinned to
the same release as the Termux runtime package. `tools/build_openjdk_launcher.py` downloads
the source archive, verifies SHA-256
`ba3ac4b9d7f2c050f46ddcec39b4258660a3f09836f5a71617fd3f7311d06c0b`, selectively extracts
only the libjli headers/sources, applies `tools/openjdk_launcher/java-home.patch`, and builds
with the installed Android NDK for arm64/API 26. `tools/fetch_jvm_native_libs.py` invokes
this build after both discovery and reproduction so the stock Termux `libjli` cannot
silently return.

The maintained patch has two narrowly scoped launcher changes:

- `GetApplicationHome()` accepts an absolute, bounded `JAVA_HOME`, because Android's flat
  PackageManager native directory destroys the original `JAVA_HOME/bin/java` identity seen
  through `/proc/self/exe`.
- `JLI_Launch()` disables Android heap pointer tagging before JVM startup. The first device
  pass proved why this established Termux workaround is required: `java` started, but
  `javac` aborted after truncating a tagged pointer. With the workaround, compilation is
  stable. The corresponding maintained implementation is visible in
  [Termux's OpenJDK patch](https://github.com/termux/termux-packages/blob/master/packages/openjdk-21/0021-Add-workaround-for-tagged-pointers-on-Android-12.patch).

Two independent builds produced the same 71,008-byte output with SHA-256
`c3435698c8774e9b97cc52c3738387c97d8aa5a41737b315c1dd2719f8fa3a8b`. The library has
SONAME `libjli.so`, only `libz.so.1`, `libdl.so`, and `libc.so` as direct dependencies, and
16 KB-aligned load segments.

The JDK launchers and libraries are now routed through the generated native-link mapping,
with one deliberate exception: `JAVA_HOME/lib/server/libjvm.so` remains the original file
in the downloaded JDK tree. HotSpot independently derives its boot-image home from the
real `libjvm.so` path; mapping that file into PackageManager's flat directory causes
`Failed setting boot class path`. Android 17/API 37 still loads this writable app-private
library but warns that doing so will throw on a future Android version. The launcher task
is complete for current Android, but a future-proof full W^X closure will require rebuilding
or minimally patching HotSpot's own home discovery as a separate task.

On the real arm64 Android 17 device at `targetSdk = 36`, through the production
`NodeExecutionEnvironment` resolution path:

```
java -version                         -> OpenJDK 17.0.20, exit 0
javac Hello.java; java -cp . Hello    -> JAVA_36_OK, exit 0
jar/keytool/javap/jlink version/help  -> exit 0
kotlinc -version                      -> kotlinc-jvm 2.4.10, exit 0
kotlinc Hello.kt ...; java -jar ...   -> KOTLIN_36_OK, exit 0
```

Kotlin's known glibc-linked Jansi warning remains non-fatal. Cleanup removed the diagnostic
hook and restored the intentional project default to `targetSdk = 28`; the production
launcher build, runtime routing, and native mappings remain.
