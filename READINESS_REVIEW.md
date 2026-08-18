# AgenticDroid Readiness Review

**Updated:** 2026-08-17  
**Distribution decision:** Sideload-only while the downloaded-toolchain architecture requires `targetSdk = 28`.  
**Current verdict:** **Ready for controlled developer sideload testing; not yet ready for a production release.**

## Current verification

| Gate | Result | Evidence |
|---|---|---|
| JVM unit tests | PASS | 16 tests, 0 failures, 0 errors |
| Android lint | PASS | 0 errors, 4 dependency warnings |
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

### Build, test, and project hygiene
- Resolved the stale failing installer test and replaced the template arithmetic test with security-boundary tests.
- Reduced lint from 1 error/29 warnings to 0 errors/4 dependency warnings.
- Added narrowly scoped lint documentation for the intentional sideload/API-28 exception.
- Added optional release signing from protected Gradle properties or environment variables.
- Added CI, a project README, security reporting guidance, and a privacy/data-handling document.
- Removed unused resources and fixed the terminal service reference leak and several lifecycle/lint issues.
- Added three compiled device smoke tests for manifest permissions, direct-Keystore credential round trips, and workspace traversal rejection.

## Remaining blockers and risks

### R1 — API 28 compatibility architecture

**Severity:** Blocker for Play/modern production distribution.

The downloaded Node/Git/QEMU toolchain executes from app-private storage and relies on the API 28 compatibility policy. Moving to API 29+ requires an execution redesign; lint suppression only records the deliberate sideload exception.

**Next step:** Keep the current artifact explicitly sideload-only. Investigate a separately installed runtime, isolated service/runtime APK, supported native-library packaging, remote execution, or another architecture that permits a current target SDK before pursuing store distribution.

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

**Next step:** Run the instrumented tests and complete a manual matrix across supported API levels and both supported ABIs. Include failed network/hash, changed SSH key, rotation, background/restore, wipe, and low-storage scenarios.

### R6 — Legacy credential migration shim remains temporarily

**Severity:** Low.

Primary credential reads/writes now use direct Android Keystore AES/GCM. Deprecated AndroidX Security APIs remain only in a one-time migration shim so existing sideload installations do not lose tokens and SSH secrets.

**Next step:** Retain the shim for the documented migration window, then remove the AndroidX Security dependency in a later compatibility-breaking release.

### R7 — Live SSH interoperability still needs device coverage

**Severity:** Low.

SSH profiles are persisted with encrypted secrets and support passwords or encrypted private keys/passphrases, configurable ports, pinned host identities, cleanup, and remote browsing. Automated tests validate configuration boundaries, but no live test server was available for this pass.

**Next step:** Exercise accepted, unknown, and changed host keys plus password, encrypted-key, reconnect, and SFTP behavior against controlled servers during the device matrix.

### R8 — Remaining dependency warnings require ownership

**Severity:** Medium.

Lint's four warnings are three reports of the Termux 16 KB issue and one `TrustAllX509TrustManager` report inside Bouncy Castle. Application SSH connections use `FingerprintVerifier`; nevertheless, the dependency warning should be reviewed when SSHJ/Bouncy Castle versions change.

**Next step:** Track the warnings as explicit dependency exceptions with owner and review date. Do not add a broad lint baseline that hides new application warnings.

### R9 — Distribution/legal choices remain

**Severity:** Medium.

README, privacy, and security guidance now exist, but the repository has no chosen license and no compiled third-party notices/SBOM for the runtime-downloaded toolchain.

**Next step:** Choose a project license, generate dependency/runtime license notices and an SBOM, document redistribution obligations, and publish a private vulnerability-reporting channel.

### R10 — On-device self-build has narrow verified coverage

**Severity:** Medium.

The on-device Android build path (`AndroidSdkBootstrapper`, native `aapt2`, the `aapt2FromMavenOverride` wiring) is verified end-to-end for exactly one configuration: this app's own project, AGP as currently pinned, `compileSdk = 37`, on one x86_64 emulator. Several parts are unverified generalizations:

- No `build-tools/` directory is provisioned at all - the build relies entirely on `aapt2FromMavenOverride` plus AGP's Maven-sourced (pure-JVM) D8/R8 and packaging/signing libraries. Whether every AGP version tolerates a completely absent `build-tools/` directory, or whether some path still shells out to a legacy native `zipalign`/`aapt`, hasn't been tested against other AGP versions.
- Termux's native `aapt2` build (v16.0.0.4-1) needs to speak a daemon protocol compatible with whatever `aapt2` version AGP itself is pinned to. This was compatible for the AGP version exercised here; there's no guarantee across other AGP versions, and no automatic detection if it stops being compatible (it would surface as an opaque `aapt2` daemon failure).
- The reactive provisioning step (`AndroidSdkBootstrapper.parsePlatformHashFromBuildError` + one retry in `MainViewModel.buildAndInstall`) only recognizes a missing SDK *platform* and only retries once. Other missing SDK components (NDK, CMake, a different `build-tools` version some plugin insists on) aren't provisioned and would surface as an ordinary build failure with no automatic recovery.
- The lowered Gradle daemon heap (`-Xmx640m`) was tuned against one 4 GB emulator; no testing yet on lower-memory real devices or larger projects that might need more heap than that to compile at all.
- A self-build's output APK is debug-signed with an auto-generated on-device debug keystore, which will generally differ from whatever signed the currently-installed app - self-update installs are correctly blocked by the app's own signature-mismatch check, but there's no supported path yet for a self-build to actually replace a debug install produced elsewhere (e.g. Android Studio) short of manually aligning debug keystores.

**Next step:** Exercise a second, dependency-heavier real-world Android project (not just this app) through the same on-device build path before relying on it generally. Decide whether unsupported SDK-component gaps should surface a clearer in-app message rather than a raw Gradle failure. If cross-environment self-update installs are wanted, document or automate a shared/matching debug keystore.

## Production exit gate

For the current sideload flavor, require a signed and versioned artifact, green CI, a completed device/ABI matrix, verified bootstrap failure recovery, and explicit acceptance of the API-28 and 16 KB limitations. A broader production/store claim additionally requires resolving R1 and R2 rather than suppressing or documenting them.
