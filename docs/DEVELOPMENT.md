# Development guide

This guide expands on the quick build instructions in [README.md](../README.md). It is intended for
contributors working on AgenticDroid itself.

## Repository map

| Path | Purpose |
|---|---|
| `app/src/main/java/` | Android application and Compose UI |
| `app/src/test/` | JVM unit tests |
| `app/src/androidTest/` | On-device/instrumented tests |
| `app/src/main/assets/` | Runtime mappings, helper scripts, and tracked native overrides |
| `tools/` | LAN companion and reproducible native-closure tooling |
| `gradle/libs.versions.toml` | Central dependency versions |
| `.github/` | Contributor automation and templates |
| `docs/` | Maintainer, runtime, distribution, and support documentation |

## First build

1. Install Git, JDK 21, and an Android SDK containing API 37.
2. Clone the repository and open it in Android Studio, or create an ignored `local.properties`
   containing your SDK path.
3. Run the contributor gate:

   ```sh
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```

4. Run repository and LAN companion checks:

   ```sh
   python3 tools/check_repository.py
   cd tools
   npm ci
   npm test
   npm audit --omit=dev
   ```

Use `gradlew.bat` and `py -3` on Windows when those are the configured launchers.

## Choosing a first contribution

Documentation corrections, focused unit tests, reproducible bug reports, accessibility fixes, and
small UI improvements are good entry points. Comment on an issue before substantial work so scope
and approach can be aligned. An issue marked `good first issue` should be self-contained and avoid
the native runtime, credential storage, or remote-execution security boundaries.

## Test expectations

| Change | Minimum validation |
|---|---|
| Documentation/community files | `tools/check_repository.py` |
| Kotlin/business logic | Relevant unit tests plus contributor Gradle gate |
| Compose UI | Contributor gate, screenshots, and device/emulator exercise |
| Android services/permissions/storage | Contributor gate and instrumented device testing |
| LAN companion | `npm test`, `npm audit`, and authenticated live exercise |
| Native/runtime manifests | All above plus the native release-closure workflow |

Tests should prove both the expected behavior and important failure cases. Do not create a lint or
test baseline merely to hide a new finding.

## Pull-request workflow

Keep one logical change per pull request. Draft pull requests are welcome for early feedback.
Link an issue for significant work, explain user impact and risk, and list exact validation results.
Review your own diff before requesting review. Rebase or merge the current default branch when a
maintainer asks; do not force-push after review without explaining what changed.

AI-assisted contributions are welcome, but the contributor must understand, test, and take
responsibility for every submitted line. Do not include generated license-incompatible material,
private prompts, credentials, or unreviewed bulk changes. Disclose substantial AI assistance in the
pull request when it helps reviewers understand provenance or review risk.

## Native closure changes

The ignored `app/src/main/jniLibs/` directory is generated and must never be committed. Manifest
updates require intentional discovery, source and license review, digest verification, and a clear
pull-request inventory. A current Termux index changing is not permission to silently replace a
pin. See [DISTRIBUTION.md](DISTRIBUTION.md).

## Troubleshooting

- If Gradle cannot find the SDK, check `local.properties` or `ANDROID_SDK_ROOT`.
- If the Gradle wrapper cannot write its cache, use a writable `GRADLE_USER_HOME`.
- If release assembly reports missing native libraries, run the native fetch/build sequence from
  the README; ordinary debug development does not require it.
- If CI fails only in **Native release closure**, inspect package-version and digest drift before
  changing a manifest.
- If an issue involves credentials or private source, create a sanitized minimal repository rather
  than sharing the original.
