# AgenticDroid

[![Contributor checks](https://github.com/JustinANelson/AgenticDroid/actions/workflows/android.yml/badge.svg)](https://github.com/JustinANelson/AgenticDroid/actions/workflows/android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

AgenticDroid is an experimental, mobile-first Android development workspace for running
coding-agent CLIs, editing repositories, using Git, and building projects on a phone. It can
execute locally in app-private storage or connect to configured SSH and LAN environments.

> [!WARNING]
> AgenticDroid is pre-release, sideload-only software. It can execute commands, modify files,
> install APKs, and give third-party agents access to source code and configured credentials.
> Use it only with repositories, devices, accounts, and remote hosts you are prepared to trust.

## Project status

The project is under active development. The current Android build:

- supports Android 8.0 or newer (`minSdk 26`), targets Android API 36, and compiles
  against API 37;
- ships only for `arm64-v8a`;
- needs network access and substantial app-private storage to bootstrap toolchains; and
- is not represented as Google Play compatible. Its downloaded executable/toolchain model
  remains a policy and distribution constraint.

There are no supported binary releases yet. In particular, do not publish an APK until the
native-runtime attribution and corresponding-source checklist in
[DISTRIBUTION.md](docs/DISTRIBUTION.md) has been completed for that exact artifact.

## Capabilities

- Local terminal sessions and background agent runs, with built-in launch profiles for Codex,
  Claude Code, Gemini CLI, Aider, and Antigravity CLI
- Workspace browsing, editing, search, previews, and project templates
- Git and GitHub workflows with Keystore-backed credential storage
- Local, SSH, and authenticated LAN execution environments
- An on-device core toolchain with Node.js, npm, Git, GitHub CLI, OpenSSH, and common utilities
- Optional on-device Python and Java/Kotlin tooling, including OpenJDK 17 and Android SDK support
- QEMU-based compatibility for supported coding-agent binaries
- Project-scoped encrypted secrets and MCP server configuration

Rust, Go, C/C++, and Hugo runner groups are not offered for new local installs at the current
target SDK because their downloaded executables are not yet packaged through the app's native
library path. Use an SSH or LAN environment for those toolchains. Agent availability and
authentication remain subject to each third-party provider's packages, accounts, and terms.

See [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md) and
[AGENT_RUNTIME_RESEARCH.md](AGENT_RUNTIME_RESEARCH.md) for design history and runtime research.
Those dated review documents are snapshots; the source and this README are authoritative when
their status descriptions differ.

## Project resources

| Resource | Use it for |
|---|---|
| [Development guide](docs/DEVELOPMENT.md) | Repository layout, tests, and contributor workflow |
| [Roadmap](docs/ROADMAP.md) | Current direction and proposal process |
| [Support](SUPPORT.md) | Questions, troubleshooting, and support boundaries |
| [Governance](GOVERNANCE.md) | Roles, decisions, review, and merge policy |
| [Changelog](CHANGELOG.md) | User-visible changes and release history |
| [Security policy](SECURITY.md) | Private vulnerability reporting |

## Build from source

### Prerequisites

- Git
- JDK 21 for the host-side Gradle build (the optional on-device JVM runtime is OpenJDK 17)
- Android SDK with API 37
- Android NDK `27.2.12479018` only when reconstructing the release native-library closure

Clone the repository and add your SDK path to the ignored `local.properties` file as usual for
an Android project. Then run the local quality gate:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

On macOS or Linux, use `./gradlew` instead. Debug builds do not require a GitHub OAuth client ID;
GitHub device login is simply unavailable when it is omitted.

To enable device login, copy `.secrets.example` to `.secrets` and add the public client ID for
your own GitHub OAuth app. Enable device flow for that app. Never put an OAuth client secret in
this repository or an APK.

Release builds require a generated, pinned native-library closure:

```sh
python3 tools/fetch_native_libs.py
python3 tools/fetch_python_native_libs.py
python3 tools/fetch_jvm_native_libs.py
python3 tools/build_libtermux.py
./gradlew assembleRelease
```

The scripts download large external artifacts and fail if the fetched versions or hashes do not
match the pinned manifests. Review those manifests, the
[release-closure workflow](.github/workflows/release-closure.yml), and
[DISTRIBUTION.md](docs/DISTRIBUTION.md) before distributing the output.

## Release signing

Release signing is loaded only from Gradle properties or environment variables. Never commit a
keystore or passwords.

| Gradle property | Environment variable |
|---|---|
| `agenticdroid.release.storeFile` | `AGENTICDROID_RELEASE_STORE_FILE` |
| `agenticdroid.release.storePassword` | `AGENTICDROID_RELEASE_STORE_PASSWORD` |
| `agenticdroid.release.keyAlias` | `AGENTICDROID_RELEASE_KEY_ALIAS` |
| `agenticdroid.release.keyPassword` | `AGENTICDROID_RELEASE_KEY_PASSWORD` |

With all four values configured, `assembleRelease` produces a signed artifact. Without them, any
release output is an unsigned developer artifact and is not distributable.

## LAN companion server

The optional server in `tools/` gives the app filesystem and shell access on another machine.
It is high privilege by design. It uses a bearer pairing token but plain HTTP/WebSocket transport,
so run it only on a trusted local network or inside a trusted encrypted tunnel. Use a dedicated
unprivileged account and a narrow `WORKSPACE_ROOT`; the root contains structured file operations,
but arbitrary shell commands retain the account's full access. Do not expose the server directly
to the internet. See [REMOTE_SERVER.md](docs/REMOTE_SERVER.md).

## Security and data handling

Credentials, project secrets, and pasted SSH key material are encrypted with an app-owned
Android Keystore key. Workspaces and bootstrapped toolchains remain in app-private storage;
cloud backup and device transfer are disabled. Agent CLIs and user-initiated Git, SSH, LAN, and
download operations communicate with third parties and may disclose repository content.

Read [SECURITY.md](SECURITY.md) before reporting a vulnerability and [PRIVACY.md](PRIVACY.md)
before using the app with sensitive source code.

## Contributing

Issues and pull requests are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md) and follow
the [Code of Conduct](CODE_OF_CONDUCT.md). Security vulnerabilities must use the private process
in [SECURITY.md](SECURITY.md), not a public issue.

Good ways to help include reproducing device-specific bugs, adding focused tests, improving
accessibility and documentation, reviewing dependency provenance, and taking a scoped roadmap
issue. First-time contributors should read [DEVELOPMENT.md](docs/DEVELOPMENT.md) before starting.

## License

AgenticDroid's original source is licensed under the [Apache License 2.0](LICENSE). Dependencies,
generated toolchains, and bundled native binaries retain their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The AgenticDroid name and artwork are not
separately licensed as trademarks.
