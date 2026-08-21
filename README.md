# AgenticDroid

AgenticDroid is an Android development workspace and terminal that bootstraps a Node, Git,
and QEMU user-mode toolchain into app-private storage so agent CLIs can run on a phone.

> [!WARNING]
> AgenticDroid is currently **sideload-only**. It intentionally targets API 28 because
> Android's API 29+ app-data W^X policy prevents its current downloaded-toolchain execution
> model. Do not present the current flavor as Google Play compatible.

## Supported environment

- Android 8.0 or newer (`minSdk 26`)
- `arm64-v8a` or `x86_64` for the bootstrapped QEMU/toolchain path
- Network access for initial bootstrap and agent installation
- Several hundred MB of free app storage, depending on installed agents

The upstream Termux terminal native library is not currently 16 KB page-aligned. Devices
that require 16 KB page alignment are not supported until that dependency is fixed or
replaced.

## Local setup

1. Install JDK 21 and an Android SDK containing API 37.
2. Add the usual local Android SDK path to `local.properties`.
3. Create an ignored `.secrets` file when GitHub device login is needed:

   ```text
   gh_client_id: your_public_github_oauth_client_id
   ```

   Configure the GitHub OAuth app for device flow. No OAuth client secret belongs in this
   repository or APK.
4. Run the local quality gate:

   ```powershell
   .\gradlew.bat testDebugUnitTest lintDebug assembleDebug
   ```

## Release signing

Release signing is loaded only from Gradle properties or environment variables. Never add
a keystore or passwords to the repository.

| Gradle property | Environment variable |
|---|---|
| `agenticdroid.release.storeFile` | `AGENTICDROID_RELEASE_STORE_FILE` |
| `agenticdroid.release.storePassword` | `AGENTICDROID_RELEASE_STORE_PASSWORD` |
| `agenticdroid.release.keyAlias` | `AGENTICDROID_RELEASE_KEY_ALIAS` |
| `agenticdroid.release.keyPassword` | `AGENTICDROID_RELEASE_KEY_PASSWORD` |

With all four values configured, run `./gradlew assembleRelease`. Without them, Gradle may
produce an unsigned developer artifact; it is not a distributable release.

## Security model

- GitHub login uses OAuth device flow. Tokens are kept in Keystore-backed encrypted storage,
  are not embedded in Git remote URLs, and are not compiled into the APK.
- SSH connections require a host-key fingerprint obtained through a trusted channel. Profiles
  support encrypted passwords or pasted private keys/passphrases, custom ports, persisted remote
  workspace roots, and SFTP browsing. A
  changed fingerprint is a blocking connection failure.
- Termux and Debian packages are verified against SHA-256 values from their HTTPS package
  indexes. Alpine musl packages are version/hash-pinned to the supported v3.24 repository,
  and Antigravity archives are verified against the updater manifest's SHA-512 value.
- Archive extraction rejects absolute paths, traversal, unsafe link targets, excessive
  entry counts, and excessive sizes.
- Cloud backup/device transfer is disabled because workspaces and credentials are sensitive.

The remaining mutable package indexes are an acknowledged trust boundary. A future release
should replace them with a project-controlled, signed, versioned manifest for fully
reproducible bootstrap artifacts.

## SSH fingerprints

Before adding an SSH environment, obtain the server host-key fingerprint from its
administrator through a trusted channel. For example, an administrator can run:

```sh
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

Paste the complete `SHA256:...` fingerprint into AgenticDroid. Do not accept a fingerprint
supplied only by the same network connection being verified.

## Data and reset behavior

Workspaces, the bundled toolchain, and tokens live in app-private storage. Internal storage is
required because Android prevents npm symlinks and native Node module loading from emulated
external storage. On upgrade, legacy external-files workspaces are copied into internal storage
without `node_modules` (dependencies are restored on demand); the legacy copies are left intact
as a recovery backup. The app's wipe action removes preferences, credentials, active internal
workspaces, legacy workspace backups, and the bootstrapped toolchain. Back up source repositories
through a trusted Git remote before wiping or uninstalling the app.

See [READINESS_REVIEW.md](READINESS_REVIEW.md) for the latest readiness status and remaining
production work.
