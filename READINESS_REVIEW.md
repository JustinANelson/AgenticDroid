# AgenticDroid Readiness Review

**Updated:** 2026-08-20  
**Product goal:** Let a user do real agentic software development from a phone — running agent CLIs, editing, building, and previewing projects either fully on-device or against a remote machine reached over SSH.  
**Distribution decision:** Sideload-only while the downloaded-toolchain architecture requires `targetSdk = 28`.  
**Current verdict:** **Ready for controlled developer sideload testing on local (on-device) workflows; remote-environment workflows are functionally present but unverified against live servers; not yet ready for a production/store release.**

## Local vs. remote environment readiness, at a glance

| Environment | Status | Notes |
|---|---|---|
| **Local (on-device toolchain)** | Usable, sideload-only | Node/Python/JVM/Rust/Go/C++ toolchains run via QEMU-hosted Termux/Alpine binaries downloaded to app-private storage; on-device Android self-build and web-project live preview both work end-to-end on the one configuration tested (see below). Blocked from `targetSdk` 29+ by W^X (see R1), which is the only thing forcing sideload-only distribution. |
| **Remote (SSH to another machine)** | Functionally present, unverified | Full remote project browsing/open/create/rename/delete, Cloudflare Tunnel (no public IP needed), mDNS discovery of local SSH servers, and modern host-key algorithms (X25519 via Bouncy Castle) are implemented and unit-tested at the configuration level, but none of it has been run against a live SSH server yet (R7). This is the more mobile-friendly path long-term, since it avoids the on-device toolchain's storage/battery/thermal cost and the W^X ceiling entirely — but it inherits ordinary mobile-network risk (drops, backgrounding, captive portals) that hasn't been exercised. |

## Current verification

| Gate | Result | Evidence |
|---|---|---|
| JVM unit tests | NOT RE-VERIFIED THIS PASS | Prior count (61 tests) is stale — new `SSHExecutionEnvironmentTest.kt`, a new `ui/` terminal test package, `NodeRuntimeTest.kt`, and `RunnerPackageGroupTest.kt` additions exist since the last count; a fresh full run against a cold Gradle daemon did not complete inside a single review pass. Re-run and record an updated total before the next release decision. |
| Android lint | PASS (as of 2026-08-18; not re-run this pass) | 0 errors, 4 dependency warnings |
| Debug APK | PASS | `app/build/outputs/apk/debug/app-debug.apk` |
| Release assembly | PASS | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Release signing | CONFIGURED, NOT VERIFIED | Secure Gradle-property/environment hooks exist, but no signing credentials were supplied during review |
| Instrumented/device tests | COMPILES, NOT RUN | 3 smoke tests compile; execution still requires an emulator/device matrix |
| CI | CONFIGURED | `.github/workflows/android.yml` runs tests, lint, wrapper validation, and debug assembly |

Verification command:

```text
.\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug assembleRelease --console=plain
```

## Work completed since the initial review

### Credential and OAuth hardening

- Removed the OAuth client secret from `BuildConfig` and the APK.
- Removed the web-flow callback and custom deep link; GitHub login now uses public-client device flow.
- Replaced a hardcoded OAuth client ID with the ignored `.secrets` configuration.
- Replaced primary credential storage with direct Android Keystore AES/GCM encryption, including corrupt/invalidated-key recovery.
- Migrates legacy plaintext and AndroidX Security-backed credentials into the direct Keystore store, then clears old values.
- Stopped exposing the current token to Compose configuration fields.
- Removed tokens from Git clone URLs and persisted Git remotes.
- Sends GitHub Git credentials through per-process Git configuration environment variables instead of command arguments or repository configuration.
- Replaced the shell-based GitHub repository API call with `HttpURLConnection` and bounded responses/timeouts.
- Removed command/auth-URL logging and redacts credential-shaped Git output/errors.
- Disabled cloud backup and device transfer; removed the unused template backup policies.

### SSH and command hardening

- Replaced `PromiscuousVerifier` with a required pinned SHA-256 host-key fingerprint.
- Added fingerprint format validation and password masking to SSH setup.
- Added environment support to execution backends so secrets do not need to be embedded in shell commands.
- Added centralized POSIX shell quoting for structured Git/SSH arguments.
- Quoted SSH working-directory and server-side copy paths.
- Added persisted SSH profiles with encrypted passwords, pasted private keys and passphrases, configurable ports, remote workspace roots, and explicit connection cleanup.
- Added an SFTP remote workspace browser with bounded text reads and safer temporary-file saves.
- Uses `--force-with-lease` rather than an unconditional force push.

### Filesystem and archive hardening

- Archive extraction now rejects absolute/traversal paths and link targets outside the extraction root.
- Only regular files and safe relative symlinks are processed.
- Added per-entry, total-size, and entry-count limits.
- Added real Alpine multi-member APK handling and streaming Debian `data.tar`, `data.tar.gz`, `data.tar.xz`, and `data.tar.zst` support.
- Project creation, clone destinations, and deletion now enforce canonical workspace containment.
- Workspace trees avoid symlink recursion and cap depth/entries; search is debounced, backgrounded, binary/size-limited, and excludes generated/vendor trees.
- Project file create/rename/copy/open/save operations enforce canonical containment, and saves/replacements use temporary files with exact replacement counts.
- Added regression tests for traversal, real package structure, compression variants, shell quoting, SSH configuration, replacement counts, and workspace deletion containment.

### Runtime executable supply chain

- Termux and Debian package records now carry SHA-256 values from their package indexes; every downloaded `.deb` is verified before extraction.
- Alpine musl is pinned to v3.24 package version `1.2.6-r2` with architecture-specific SHA-256 values.
- Antigravity download handling now enforces HTTPS, limits redirects, requires the vendor manifest's SHA-512 field, and verifies the compressed archive before extraction.
- Updated the Termux terminal dependency from vulnerable `v0.117` to stable `v0.118.3` and adapted the terminal interfaces.
- Bumped the bootstrap provisioning version so existing installations rebuild through the hardened path.
- Downloads now enforce advertised/global size limits and respond cooperatively to WorkManager cancellation and retry delays.

### Process and lifecycle reliability

- Git and build processes drain stdout/stderr concurrently with output caps and timeouts, preventing pipe-buffer deadlocks.
- Build output is visible and builds can be cancelled; generated APK discovery now includes a bounded local fallback search.
- Process sessions expose running/close semantics, and agent sessions no longer report exited children as running.
- Terminal sessions have explicit UI and notification stop actions, automatic exit cleanup, detached-client cleanup, and the required special-use foreground-service permission.
- App wipe now cancels background work and services and exits through the Android task lifecycle instead of `System.exit`.

### On-device self-hosted Android app builds

A new capability, verified end-to-end on a 16 KB-page-size x86_64 emulator (`Small_Phone`, `google_apis_playstore_ps16k`, Android 37.1): the in-app "Build & Install" action (and any on-device `gradlew` invocation through the Node Toolchain environment) can now run a real Android/AGP build - resource compilation, Kotlin/Java compilation, dexing, and APK packaging - entirely on-device, not just Node/npm-based agent CLIs.

- **16 KB page-size compatibility for `libandroid-spawn`.** Termux's live `libandroid-spawn` package (v0.3) is still 4096-byte `PT_LOAD`-aligned, so it `dlopen`-fails on any device/emulator enforcing 16 KB pages ("program alignment (4096) cannot be smaller than system page size (16384)") - confirmed by inspecting the live `.deb` from `packages-cf.termux.dev` directly. Termux's own 16 KB-alignment tracking issue (termux/termux-packages#21688) only covers its bootstrap package set; `libandroid-spawn` isn't in it and hasn't been rebuilt. Rebuilt it from upstream AOSP source with NDK r28c (which defaults to 16 KB-aligned linking) for `arm64-v8a`/`x86_64`, verified `PT_LOAD` alignment is 16384 via ELF program-header inspection, bundled the rebuilds as app assets, and `NodeBootstrapper` now overrides the Termux-served copy with these after extraction. This is a targeted fix for one package the on-device toolchain depends on, not a fix for R2 below, which is about libraries bundled inside this app's own APK.
- **Gradle daemon JVM toolchain pin.** The checked-in `gradle/gradle-daemon-jvm.properties` had `toolchainVersion=25` (generated on a machine that had a JDK 25 available), which controls the JVM that launches the Gradle daemon itself - independent of and not fixed by the project's `java { toolchain { ... } }` block, which only governs compile/test-task toolchains. Regenerated it with `gradlew updateDaemonJvm --jvm-version=17` to match the JDK 17 actually bundled on-device.
- **`java.io.tmpdir` fix.** The bundled Termux-built OpenJDK has `/data/data/com.termux/files/usr/tmp` compiled in as its default temp directory - unreadable from this app's sandbox (different app UID) - which crashed the Gradle daemon's own internal service construction before any project code ran. `NodeRuntime` now sets `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<app cache dir>`, picked up by any `java` invocation.
- **Native `aapt2`, not a QEMU-wrapped one.** Termux packages a Bionic-native `aapt2` build (depends on `abseil-cpp`/`libprotobuf`/`fmt`/`libpng`/`libzopfli`/`libc++`), so it runs as an ordinary child process with no QEMU-user translation needed - unlike AGP's own Maven-resolved `aapt2` ("linux" classifier), a glibc binary AGP execs directly with no wrapper hook, which trips the same Zygote seccomp-bpf block that motivates this app's QEMU-user machinery in the first place. Added as ordinary packages to `NodeBootstrapper`'s Termux package list.
- **`AndroidSdkBootstrapper`** provisions just enough of a real Android SDK to compile against: resolves and downloads the requested platform's `android.jar` from Google's public SDK repository index (with fractional-API-level fallback, e.g. `android-37` -> `android-37.1`/`android-37.0`, since recent Android versions publish fractional API levels), and writes the SDK license-acceptance marker AGP requires before touching any SDK component. Provisioning is reactive/per-project: `MainViewModel.buildAndInstall` parses the missing platform's exact hash straight out of AGP's own failure text and retries once after provisioning it, rather than needing every target project's `compileSdk` pre-declared. `ArchiveExtractor` gained plain-ZIP extraction support (`extractZip`) for these platform archives, which are ordinary PKZIPs unlike the ar/tar-based Termux/Alpine formats it already handled.
- **Routing AGP to the native `aapt2`.** `android.aapt2FromMavenOverride` must be delivered as a real Gradle property in `$GRADLE_USER_HOME/gradle.properties`, not as an `ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride` environment variable: empirically, the env-var form (a literal `.` in the variable name) was honored by the app's own main resource-processing tasks but not by `AarResourcesCompilerTransform`, the detached-configuration artifact transform AGP uses to precompile library AARs' resources (e.g. `androidx.core`) - that transform kept trying to launch AGP's own glibc `aapt2` and failing ("Daemon startup failed"). Writing the property into `gradle.properties` directly (the mechanism the property is documented for) fixed it for every codepath.
- **Daemon stability.** The default `org.gradle.jvmargs=-Xmx2048m` was too aggressive for this class of device (4 GB total RAM, shared with the Android system itself, Zygote, and this app's own foreground process); the Gradle daemon was repeatedly killed mid-build ("Gradle build daemon disappeared unexpectedly"). Lowered to `-Xmx640m`, after which a full build completed without a daemon crash.
- Verified with a real, complete build: the in-app hammer/"Build & Install" action on this app's own project successfully compiled, resource-processed, dexed, and packaged a debug APK on-device, landing at both `.../files/downloads/latest_build.apk` and the project's normal `app/build/outputs/apk/debug/app-debug.apk` (both on external/shared storage, reachable without `run-as`). The only thing that stopped an actual install was the app's own (correct) signature-mismatch safety check, since the on-device build's auto-generated debug keystore differs from the one used to install the currently-running app.

### W^X (targetSdk 29+) prototype — native-library-packaged toolchain binaries

**Status: research + working prototype code, not yet load-bearing in production (`targetSdk` is still 28).**

`AGENT_RUNTIME_RESEARCH.md` documents a real attempt to remove the single architectural blocker (R1) that forces sideload-only, API-28-only distribution: Android's post-API-28 W^X policy blocks executing files from app-private storage, which is where the current toolchain downloads Node/git/Python/etc. The prototype instead packages the toolchain as `jniLibs/` — `PackageManager`-extracted native libraries, which live in `nativeLibraryDir` and are exempt from W^X on any target SDK.

- **What's bundled today:** ~269 files, ~164 MiB, under `app/src/main/jniLibs/arm64-v8a/` — `qemu-user-aarch64`, `node`, `git`, `aapt2`, `npm`/`npx`, Python plus its native extension modules, and a JVM/OpenJDK closure, each renamed to `lib*_native_<arch>.so` with their full recursively-resolved `DT_NEEDED` dependency closures verified by real ELF parsing (not package-manager metadata). A reproducible fetch pipeline (`tools/fetch_native_libs.py`, `tools/fetch_jvm_native_libs.py`, `tools/fetch_python_native_libs.py`, checksum-pinned manifests) regenerates this closure from Termux's package index — the same pinned-hash pattern already used for other runtime downloads (see R4).
- **Verified on real devices, arm64 only, at raised `targetSdk` (34–36) in throwaway test builds:** QEMU, node, git (including the HTTPS remote helper via a real `execve`), `aapt2`, `npm`/`npx`, and Python all launch correctly from `nativeLibraryDir`, including running real agent CLIs (Claude Code, Codex) end-to-end as QEMU guests.
- **Known remaining blocker: the JVM launcher.** OpenJDK derives its install root from `/proc/self/exe`, which breaks under `PackageManager`'s flat native-library directory layout. A patched `libjli.so` was built and works for `java`/`javac`/`kotlinc`, but `libjvm.so` (HotSpot itself) still needs app-private writable storage and currently only emits a deprecation warning on affected Android versions — this is explicitly unresolved, not worked around.
- **Not yet attempted:** x86_64 ABI (arm64-only so far); no full interactive-PTY terminal session test through the native-lib path; no App Bundle / install-size measurement; no license/SBOM inventory for the newly-bundled third-party binaries.
- **A separate, newly-identified gate: Play Store policy, independent of the technical fix.** Even a complete W^X workaround doesn't establish that Google Play's Device and Network Abuse policy permits this class of app at all. The research notes that Node/git/Python/the JVM — executed directly, with no VM or interpreter sandboxing boundary visible to the platform — plausibly look *harder* to justify under that policy than the QEMU-hosted agent CLIs. This is a policy/compliance question, not an engineering one, and needs to be answered before the technical work is treated as "unblocks the store."
- **Present-day cost with no present-day benefit:** since `targetSdk` remains 28, the native-lib code paths are currently dead (they only activate when a native-lib file is present) and the ~164 MiB of `jniLibs/` ships in every build regardless, inflating APK/install size for a capability not yet switched on.

This changes R1 from "no known path forward" to "a verified technical path exists for most of the toolchain, with one specific known JVM-launcher gap and a separate unresolved store-policy question" — see the revised R1 below.

### Remote environments and mobile-network resilience

Directly targets the "agentic development while mobile" goal by making a remote machine (reached over SSH) a first-class alternative to the on-device toolchain, and hardening the on-device path against the flakier networks a phone actually sees.

- **Cloudflare Tunnel (`cloudflared`) support** lets a phone reach a home/dev machine that has no public IP or port-forwarding, avoiding the most common mobile-network blocker for remote development.
- **Wireless connectivity helper** surfaces the device's local IPv4 addresses (Tailscale interfaces preferred when present) to make pairing with a remote dev machine easier.
- **mDNS discovery** of local SSH servers (`_ssh._tcp`) for same-network setup without typing an IP.
- **Full remote project management over SSH**: browse a remote filesystem, open a remote directory as a project, and create/rename/delete files remotely, without a local checkout. `ProjectType.detectFromPaths()` was added specifically so project-type detection works from a flat SFTP directory listing (no local `File` walk available).
- **Modern SSH algorithm support**: Bouncy Castle added as a security provider for X25519 and other algorithms current OpenSSH servers default to, which the previous SSHJ-only configuration could not negotiate.
- **Agent CLI installs now detect non-Android hosts** (`AgentProfile.kt` checks for `/system/bin`) and fall back to plain `npm install -g` when installing onto a remote SSH host rather than the on-device QEMU guest.
- **Mobile-network flakiness mitigations** on the existing on-device/download paths: clone/GitHub-repo-fetch failures now surface inline instead of failing silently; the terminal shows "Connecting..." instead of a static "no session" and reports specific exit/disconnect reasons with input buffering during cold start; the Antigravity installer retries manifest/archive downloads; bootstrap extraction now has resumable markers; DNS resolution is derived from the device's active network config rather than assumed.
- **Test coverage:** new `SSHExecutionEnvironmentTest.kt` and a new `ui/` terminal test package cover configuration and view-model behavior. None of the SSH/remote surface (Cloudflare Tunnel, mDNS, X25519 negotiation, remote file CRUD) has been exercised against a live server yet — folded into R7 below.

### Web project live preview

`WebProjectPreflight.kt` (new) inspects a project's `package.json` and runs its dev server (e.g. Vite) via the project-local entry point (`node node_modules/vite/bin/vite.js`) rather than depending on a global install or npm's `.bin` wrapper, which the native-exec work above doesn't cover yet. It also detects when `npm install` is needed and handles the Rollup/esbuild optional-package platform mismatch that shows up when a lockfile was generated on desktop and installed on Android. This is a real, working piece of the mobile web-dev story — not a prototype — but has no dedicated unit test yet, which is a coverage gap worth closing given it's pure, easily-testable logic.

### Agent launcher hardening

- Gemini CLI added as a supported agent, alongside the existing npm-distributed agents and Antigravity.
- Active-agent tracking with a "Stop" command, and proactive install-status probing before launch.
- APK self-update hardened: signature verification, "install unknown apps" permission handling, and backup/restore for rollback.

### Self-build fixes made durable (partially closes R10)

The two on-device Android self-build fixes noted in the prior review as "verified but only ever lived in test-device state" — the lowered Gradle daemon heap (`-Xmx640m`) and the `aapt2FromMavenOverride` `gradle.properties` write — are now committed into the project template/config rather than manually applied during testing, so a fresh install reproduces the working build path without hand-tuning. The rest of R10's scope (second real-world project, other AGP versions, missing-SDK-component recovery, cross-environment debug-keystore matching) remains open.

### Modular runner-package toolchain and agent extensibility

The Node/Python/Rust/... toolchain bootstrap - previously one all-or-nothing, multi-hundred-MB download - is now split into independently installable `RunnerPackageGroup`s (`CORE`, `PYTHON`, `JVM`, `RUST`, `GOLANG`, `CPP`, `SSG`), so a project of one type no longer forces a download of every other language's toolchain.

- **Per-group install/uninstall/refresh, with reference-counted shared packages.** `NodeBootstrapper.uninstallGroup()` only deletes a package's files once no other still-installed group (`CORE` always counts) still lists it - verified against real cross-group overlap this work surfaced (`RUST` and `CPP` both need `clang`/`binutils`; `CORE` and `PYTHON` both need `readline`/`ncurses`, the latter discovered because the bundled `sqlite3` CLI transitively depends on them). A "refresh" action re-downloads a group from Termux's live index in place, overwriting files package-by-package so there's never a window where a tool goes missing mid-update. Per-group disk usage is tracked and shown in the Environment screen.
- **`CORE` now bundles the everyday CLI toolkit agent CLIs assume exists** - `ripgrep`, `jq`, `fd`, `tree`, `unzip`, `patch`, `diffutils`, and the `sqlite3` CLI - not just node/git/npm. Dependency chains (`jq`->`oniguruma`, `sqlite`->`readline`+`ncurses`, etc.) were verified against Termux's live package index and each package's `build.sh`, not guessed.
- **Wi-Fi-only downloads** (default on) gate the bootstrap WorkManager job behind `NetworkType.UNMETERED`, protecting a user's mobile data plan from an unexpected multi-hundred-MB transfer.
- **Bundled Gradle wrapper for new Android projects.** The `ANDROID_STARTER` template now writes a real `gradlew`/`gradle-wrapper.jar`/`gradle-wrapper.properties` (embedded base64, re-encoded to LF since git checked the source out as CRLF on the Windows dev machine this was built on - a POSIX `/bin/sh` would choke on a `#!/bin/sh\r\n` shebang) plus `gradle-daemon-jvm.properties`/`gradle.properties` pinned the same way this project's own build was tuned (see "On-device self-hosted Android app builds" above). Previously only this app's own repo could self-build on-device; a freshly scaffolded Android project could not.
- **Starter/example project templates for every runner type** (Node.js, Kotlin/JVM, Rust, Go, C, Hugo), each verified to match its `ProjectRunnerAction`'s default commands exactly.
- **Agent CLI update checking.** `AgentProfile` can report installed-vs-latest-published version (via `npm view` for the three npm-distributed agents; Antigravity is excluded, since it isn't npm-distributed and its manifest endpoint has no separate version-query concept) and force a reinstall on demand from the Agent Launcher screen.
- **Per-project secrets** (API keys/tokens), encrypted via the existing Keystore-backed `CredentialManager`, injected as a real process environment for build actions and as a shell `export` prelude (values always single-quoted, names restricted to valid shell identifiers) for terminal-typed actions and agent launches.
- **MCP server configuration.** A project's `.mcp.json` - the file Claude Code and Codex both discover automatically from a project directory - can now be managed from the app, extending agent capability with no app-specific plugin plumbing. Deliberately scoped to just this one standard file rather than each CLI's own bespoke plugin subcommands, which couldn't be verified without live per-CLI testing.
- **Toolchain diagnostics.** A "Run Diagnostics" action actually execs each installed runner group's key binaries (not just its ready-marker file), since this toolchain's QEMU/musl/glibc behavior is empirically per-device-fragile (see the 16 KB-alignment and native-`aapt2` findings above, both discovered exactly this way).
- Fixed a `ProjectType.detect()` bug where a bare `Makefile` (used by many non-C/C++ projects for generic build orchestration) was unconditionally misclassified as a C/C++ project.

### Build, test, and project hygiene
- Resolved the stale failing installer test and replaced the template arithmetic test with security-boundary tests.
- Reduced lint from 1 error/29 warnings to 0 errors/4 dependency warnings.
- Added narrowly scoped lint documentation for the intentional sideload/API-28 exception.
- Added optional release signing from protected Gradle properties or environment variables.
- Added CI, a project README, security reporting guidance, and a privacy/data-handling document.
- Removed unused resources and fixed the terminal service reference leak and several lifecycle/lint issues.
- Added three compiled device smoke tests for manifest permissions, direct-Keystore credential round trips, and workspace traversal rejection.
- Found and fixed two source files (`RunnerPackageGroup.kt`, `GradleWrapperAssets.kt`) that a prior commit's code already depended on but that had never actually been `git add`-ed - `HEAD` would not have compiled as committed. A process gap, not a design one: worth a `git status`/clean-clone build check before trusting "committed" as "compiles."

## Remaining blockers and risks

### R1 — API 28 compatibility architecture

**Severity:** Blocker for Play/modern production distribution. **Updated: a verified technical path now exists; two new sub-gates identified.**

The downloaded Node/Git/QEMU toolchain executes from app-private storage and relies on the API 28 compatibility policy. A prototype (see "W^X (targetSdk 29+) prototype" above) repackages the toolchain as `PackageManager`-extracted native libraries (`jniLibs/`), which are exempt from W^X at any target SDK, and has verified QEMU/node/git/aapt2/npm/Python running correctly this way at raised target SDKs (34–36) on real arm64 hardware. `targetSdk` in `build.gradle.kts` is still 28 today — none of this is live in production yet.

Two sub-gates remain before this can actually raise `targetSdk`:
- **R1a — JVM launcher.** OpenJDK's `/proc/self/exe`-based install-root detection breaks under the flat native-lib layout; `java`/`javac`/`kotlinc` work via a patched `libjli.so`, but `libjvm.so` (HotSpot) still needs writable app-private storage and only warns, rather than breaks, on current Android — a genuine gap, not a documentation nicety.
- **R1b — Play Store policy risk, independent of the technical fix.** Google Play's Device and Network Abuse policy on downloadable/executable code has not been evaluated against this architecture even in its native-lib form. Node/git/Python/the JVM run directly with no VM/interpreter sandbox boundary visible to the platform, which plausibly reads as *harder* to justify under that policy than the already-QEMU-hosted agent CLIs. This must be resolved (or the store-distribution goal dropped in favor of a permanent sideload/direct-distribution model) regardless of R1a's outcome.

Also unaddressed: x86_64 ABI (arm64-only so far), no interactive-PTY test through the native-lib path, no install-size/App-Bundle measurement (current `jniLibs/` payload is ~164 MiB and ships in every build today even though it's inert at `targetSdk = 28`), and no license/SBOM inventory for the newly-bundled binaries (relevant to R9).

**Next step:** Resolve R1a (JVM launcher) and validate on x86_64. Separately and in parallel, get an explicit read on Play policy (R1b) before investing further engineering, since a "no" there makes the rest of R1 moot for store distribution specifically (sideload/direct distribution would be unaffected). Once both are clear, flip `targetSdk` in a test track and re-run the full device matrix (R5) before considering it resolved.

### R2 — Termux native library lacks 16 KB alignment

**Severity:** High.

Lint reports `arm64-v8a/libtermux.so` (and, on the 16 KB test emulator, `libandroidx.graphics.path.so`) from stable `terminal-emulator:v0.118.3` is not 16 KB page-aligned. The same warning remains after upgrading from v0.117, and the upstream Termux project tracks 16 KB support as unresolved for these libraries specifically. Confirmed live and reproducible on a real 16 KB-page-size AVD (`google_apis_playstore_ps16k`, Android 37.1): the app installs and runs in Android's page-size compatibility mode, with an in-app warning dialog listing the misaligned libraries.

One related but narrower case is now fixed: the on-device Node/Git toolchain's own `libandroid-spawn` dependency (downloaded from Termux's live package index at runtime, not bundled in the APK) hit the same class of failure as a hard `dlopen` crash rather than a compatibility-mode warning, since it's loaded outside the APK's own page-size backward-compat handling. That one has a targeted fix (rebuilt with NDK r28c and bundled as an override asset - see "On-device self-hosted Android app builds" above). `libtermux.so` and `libandroidx.graphics.path.so`, which are bundled directly inside this app's own APK, remain unresolved - fixing those requires upstream Termux publishing 16 KB-aligned builds, or this project vendoring/rebuilding them the same way `libandroid-spawn` was.

**Next step:** Exclude devices requiring 16 KB-only native libraries from the supported matrix for now. Replace/rebuild `libtermux.so`/`libandroidx.graphics.path.so` with 16 KB ELF alignment when upstream support or a maintained fork is available (or vendor a custom rebuild, as done for `libandroid-spawn`), then confirm the compatibility warning disappears on a 16 KB emulator/device.

### R3 — Production signing and versioning have not been exercised

**Severity:** High.

The secure signing configuration is present, but this review produced an unsigned release because no keystore was supplied. `versionCode = 1` and `versionName = "1.0"` remain placeholders.

**Next step:** Establish key custody and recovery, provide signing values through a protected release environment, define a version policy, create a signed candidate, and verify its certificate/signature and upgrade path on-device.

### R4 — Some runtime sources remain mutable trust roots

**Severity:** High.

Termux/Debian artifact hashes arrive through their live HTTPS indexes, so a compromised index origin could replace both URL and digest. npm-based agent installs are intentionally updateable. Antigravity's digest arrives from its live vendor manifest, which protects transfer integrity but not a compromised manifest service.

**Next step:** Generate a project-controlled, signed, versioned bootstrap manifest containing every artifact URL, version, size, and digest. Make updates an explicit reviewed release process, enforce rollback protection, retain provenance/licenses, and test corrupt/downgraded manifests.

### R5 — Device and end-to-end coverage is still shallow

**Severity:** High.

Sixteen JVM tests now cover important boundaries and three instrumented smoke tests compile, but there is still no verified emulator/device run for OAuth, live SSH host-key behavior, bootstrap interruption/recovery, terminal backgrounding/process death, APK installation, storage pressure, or upgrades.

**Next step:** Run the instrumented tests and complete a manual matrix across supported API levels and both supported ABIs. Include failed network/hash, changed SSH key, rotation, background/restore, wipe, and low-storage scenarios. Also unverified on-device (unit-tested and compiled only so far): per-group runner install/uninstall/refresh, the Wi-Fi-only download constraint, the bundled Gradle wrapper on a freshly scaffolded Android project, agent CLI update checking, project secrets injection, MCP server config, and the toolchain diagnostics action.

### R6 — Legacy credential migration shim remains temporarily

**Severity:** Low.

Primary credential reads/writes now use direct Android Keystore AES/GCM. Deprecated AndroidX Security APIs remain only in a one-time migration shim so existing sideload installations do not lose tokens and SSH secrets.

**Next step:** Retain the shim for the documented migration window, then remove the AndroidX Security dependency in a later compatibility-breaking release.

### R7 — Live SSH interoperability still needs device coverage

**Severity:** Raised from Low to Medium — the remote-environment workflow is now a primary path to the "agentic development while mobile" goal, not a side feature, so its verification gap matters more.

SSH profiles are persisted with encrypted secrets and support passwords or encrypted private keys/passphrases, configurable ports, pinned host identities, cleanup, and remote browsing. Since the last review this surface grew substantially: Cloudflare Tunnel support, mDNS discovery of local SSH servers, Bouncy Castle-backed modern algorithm negotiation (X25519), and full remote project management (browse/open/create/rename/delete over SFTP, via the new `ProjectType.detectFromPaths()`). Automated tests validate configuration boundaries and unit-level behavior, but none of this — old or new — has been exercised against a live SSH server.

**Next step:** Exercise accepted, unknown, and changed host keys plus password, encrypted-key, reconnect, and SFTP behavior against controlled servers during the device matrix. Specifically add: a Cloudflare Tunnel end-to-end connection, mDNS discovery against a real LAN SSH server, X25519 negotiation against a current OpenSSH server (the reason Bouncy Castle was added), and full remote file CRUD (create/rename/delete) against a real filesystem including error paths (permission denied, disk full, path already exists). Also verify behavior when the phone backgrounds or loses network mid-session, since that is the dominant real-world failure mode for a mobile SSH client that on-device workflows don't share.

### R8 — Remaining dependency warnings require ownership

**Severity:** Medium.

Lint's four warnings are three reports of the Termux 16 KB issue and one `TrustAllX509TrustManager` report inside Bouncy Castle. Application SSH connections use `FingerprintVerifier`; nevertheless, the dependency warning should be reviewed when SSHJ/Bouncy Castle versions change.

**Next step:** Track the warnings as explicit dependency exceptions with owner and review date. Do not add a broad lint baseline that hides new application warnings.

### R9 — Distribution/legal choices remain

**Severity:** Medium.

README, privacy, and security guidance now exist, but the repository has no chosen license and no compiled third-party notices/SBOM for the runtime-downloaded toolchain.

**Next step:** Choose a project license, generate dependency/runtime license notices and an SBOM, document redistribution obligations, and publish a private vulnerability-reporting channel.

### R10 — On-device self-build has narrow verified coverage

**Severity:** Medium. **Partially closed this pass:** the two fixes previously flagged as "verified but only living in test-device state" (`-Xmx640m` heap, `aapt2FromMavenOverride` write) are now committed into the project config, so they no longer need to be manually reapplied. Everything below is still open.

The on-device Android build path (`AndroidSdkBootstrapper`, native `aapt2`, the `aapt2FromMavenOverride` wiring) is verified end-to-end for exactly one configuration: this app's own project, AGP as currently pinned, `compileSdk = 37`, on one x86_64 emulator. Several parts are unverified generalizations:

- No `build-tools/` directory is provisioned at all - the build relies entirely on `aapt2FromMavenOverride` plus AGP's Maven-sourced (pure-JVM) D8/R8 and packaging/signing libraries. Whether every AGP version tolerates a completely absent `build-tools/` directory, or whether some path still shells out to a legacy native `zipalign`/`aapt`, hasn't been tested against other AGP versions.
- Termux's native `aapt2` build (v16.0.0.4-1) needs to speak a daemon protocol compatible with whatever `aapt2` version AGP itself is pinned to. This was compatible for the AGP version exercised here; there's no guarantee across other AGP versions, and no automatic detection if it stops being compatible (it would surface as an opaque `aapt2` daemon failure).
- The reactive provisioning step (`AndroidSdkBootstrapper.parsePlatformHashFromBuildError` + one retry in `MainViewModel.buildAndInstall`) only recognizes a missing SDK *platform* and only retries once. Other missing SDK components (NDK, CMake, a different `build-tools` version some plugin insists on) aren't provisioned and would surface as an ordinary build failure with no automatic recovery.
- The lowered Gradle daemon heap (`-Xmx640m`) was tuned against one 4 GB emulator; no testing yet on lower-memory real devices or larger projects that might need more heap than that to compile at all.
- A self-build's output APK is debug-signed with an auto-generated on-device debug keystore, which will generally differ from whatever signed the currently-installed app - self-update installs are correctly blocked by the app's own signature-mismatch check, but there's no supported path yet for a self-build to actually replace a debug install produced elsewhere (e.g. Android Studio) short of manually aligning debug keystores.

**Next step:** Exercise a second, dependency-heavier real-world Android project (not just this app) through the same on-device build path before relying on it generally. Decide whether unsupported SDK-component gaps should surface a clearer in-app message rather than a raw Gradle failure. If cross-environment self-update installs are wanted, document or automate a shared/matching debug keystore.

The `ANDROID_STARTER` template now bundles its own Gradle wrapper (see "Modular runner-package toolchain and agent extensibility" above), so a freshly scaffolded project - not just this app's own repo - can in principle exercise this same on-device build path. That specific combination (new project, bundled wrapper, first build fetching the pinned Gradle distribution over the network) has not yet been run on a device.

### R11 — Build/toolchain payload growing ahead of activation (new)

**Severity:** Low, but worth tracking.

`app/src/main/jniLibs/` now bundles roughly 164 MiB of native-packaged toolchain binaries (arm64 only) that are inert in every build shipped today, since `targetSdk` remains 28 and the native-lib code paths only activate when a native-lib file is present. This inflates APK/install size with no current runtime benefit.

**Next step:** Either gate `jniLibs/` inclusion behind a build variant so today's sideload builds stay lean, or accept the size cost explicitly and track it until R1 is resolved and the payload starts earning its place.

### Process note — large uncommitted working tree at time of review

As with a prior finding in this document ("a process gap, not a design one" — see Build/test/project hygiene above), this review was conducted against a working tree with substantial uncommitted changes across `MainActivity.kt`, `MainViewModel.kt`, `AgentProfile.kt`, `NodeBootstrapper.kt`, `RunnerPackageGroup.kt`, `SSHExecutionEnvironment.kt`, several UI files, and multiple new untracked files (`WebProjectPreflight.kt`, `SSHExecutionEnvironmentTest.kt`, a new `ui/` test package, `tools/`, `jniLibs/`, and the native-links asset manifests). All work described above was assessed from the working tree as it stood on 2026-08-20, not from `HEAD` alone. Recommend committing this work in reviewed, logically-scoped commits before it accumulates further, both to keep `git log` a trustworthy record and to re-establish a clean-clone build check (per the prior finding) before trusting it as "done."

## Production exit gate

For the current sideload flavor, require a signed and versioned artifact, green CI, a completed device/ABI matrix, verified bootstrap failure recovery, and explicit acceptance of the API-28 and 16 KB limitations. A broader production/store claim additionally requires resolving R1 (including its R1a JVM-launcher and R1b Play-policy sub-gates) and R2 rather than suppressing or documenting them.
