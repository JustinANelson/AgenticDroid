# Security policy

AgenticDroid is pre-release, sideload-only software. It runs commands, downloads executables,
handles credentials, installs APKs, and can connect to remote filesystems. Do not use it as a
security boundary for untrusted agents or repositories.

## Supported versions

| Version | Security updates |
|---|---|
| Current `main` branch | Best effort |
| APKs or older commits | Not supported |

There are no supported binary releases yet.

## Report a vulnerability

Use GitHub's private vulnerability reporting flow at
<https://github.com/JustinANelson/AgenticDroid/security/advisories/new>. Include:

- the affected commit and Android/device details;
- the execution environment (local, SSH, or LAN);
- clear reproduction steps and security impact; and
- a minimal proof of concept with all real credentials and private repository data removed.

If private reporting is unavailable, open a minimal public issue asking the maintainer to establish
a private contact. Do not include exploit details. Please do not report vulnerabilities through
public pull requests.

Never include access tokens, passwords, private keys, pairing tokens, repository contents,
personal data, or unredacted logs. Rotate any credential that may have been exposed during testing.

The project does not currently offer a bug bounty, guaranteed response time, or coordinated
disclosure SLA. The maintainer will acknowledge valid reports and coordinate remediation and
disclosure on a best-effort basis.

## High-risk areas

- archive extraction and workspace path containment;
- executable/toolchain downloads and native-library provenance;
- Git and shell argument construction;
- GitHub OAuth, project-secret, and SSH credential storage;
- SSH host verification and private-key handling;
- the LAN companion server's bearer authentication and cleartext transport;
- WebView navigation and local preview servers;
- APK installation and Android component exposure; and
- terminal and background-service lifecycle.

The LAN companion server provides arbitrary command execution and filesystem access within the
permissions of its operating-system account. Its bearer token does not provide transport
encryption. Never expose it directly to the public internet.
