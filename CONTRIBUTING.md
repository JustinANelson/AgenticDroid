# Contributing to AgenticDroid

Thank you for helping improve AgenticDroid. The project is experimental and security-sensitive:
changes can affect source repositories, credentials, remote machines, or executable downloads.
Small, reviewable pull requests with tests are the easiest to evaluate.

## Before opening an issue

- Search existing issues and the current `main` branch.
- Use the issue forms and include the Android version, device ABI, app commit, execution
  environment (local, SSH, or LAN), and relevant logs.
- Remove tokens, keys, repository content, usernames, hostnames, IP addresses, and filesystem
  paths from logs.
- Follow [SECURITY.md](SECURITY.md) for vulnerabilities. Do not disclose them in an issue.
- Use GitHub Discussions or a focused issue for design questions that are not yet actionable.

## Development setup

Follow [README.md](README.md#build-from-source). The standard pre-push check is:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Use `./gradlew` on macOS or Linux. Instrumented tests require an emulator or device:

```sh
./gradlew connectedDebugAndroidTest
```

The release native-library closure is intentionally not required for ordinary Kotlin/UI changes.
If a change affects runtime packaging, bootstrap downloads, ELF wrappers, or the pinned manifests,
also reconstruct the closure using the commands in the README and follow
[DISTRIBUTION.md](docs/DISTRIBUTION.md).

## Pull requests

1. Create a topic branch from the latest `main`.
2. Keep generated files, local configuration, credentials, signing material, and build output out
   of the commit.
3. Add or update tests for behavior changes.
4. Update public documentation when behavior, permissions, storage, networking, or supported
   environments change.
5. Run the relevant checks and complete the pull request template accurately.

Prefer clear commit messages such as `fix: contain LAN file operations to workspace root`.
Maintainers may squash commits when merging.

## Security expectations

- Treat all workspace paths, archive entries, Git data, remote responses, terminal output, and
  agent-generated content as untrusted.
- Never place credentials in command arguments, URLs, logs, exceptions, screenshots, fixtures,
  or build artifacts.
- Pin executable downloads by cryptographic digest and document their origin.
- Preserve SSH host-key verification and path-containment checks.
- Do not weaken LAN authentication or enable unauthenticated operation by default.
- New Android permissions or exported components require a security and privacy rationale.

## Dependencies and generated runtimes

Explain why a new dependency is necessary and prefer maintained, narrowly scoped components from
authoritative repositories. Record its license and update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
when it is distributed.

Do not update a Termux/OpenJDK/Python native manifest by merely accepting new hashes. Use the
documented discovery flow, review the dependency closure, verify source and license information,
and call out every changed package/version in the pull request.

## Licensing contributions

By submitting a contribution, you agree that it is licensed under the Apache License 2.0 in
[LICENSE](LICENSE). You must have the right to submit the work. Identify copied or adapted material
and preserve its copyright and license notices. A Contributor License Agreement is not currently
required.

All contributors must follow the [Code of Conduct](CODE_OF_CONDUCT.md).
