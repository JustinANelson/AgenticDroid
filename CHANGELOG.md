# Changelog

Notable project changes are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and intended releases use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The project has not made a supported
binary release, so all interfaces and storage formats remain subject to change.

## [Unreleased]

### Added

- Initial open-source community, security, privacy, contribution, governance, support, and
  distribution documentation.
- Contributor CI for Android, the LAN companion, and repository-health validation.
- Scheduled and path-scoped native release-closure verification.
- Verified major/minor development-version builds provided as debug-signed GitHub Actions artifacts.

### Security

- Canonical workspace-path containment for LAN companion filesystem operations.
- Explicit Android backup and device-transfer exclusions.

### Fixed

- Restored on-device agent installation and environment diagnostics on modern Android by
  reproducibly packaging the required command wrappers and core tools in the APK's executable
  native-library directory.

Changes should be added to this section in the same pull request that introduces them. Maintainers
move entries into a dated version section when preparing a release.
