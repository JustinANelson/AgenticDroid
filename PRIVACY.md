# Privacy and data handling

AgenticDroid has no project-operated analytics, advertising, crash-reporting, telemetry, or cloud
backend in the current source tree. It stores workspaces and toolchains on the device, but
user-initiated features and installed agent CLIs communicate with third parties.

## Data stored on the device

- source repositories, generated files, terminal transcripts, run metadata, and toolchains in
  app-private storage;
- GitHub tokens, project secrets, LAN pairing tokens, SSH passwords, pasted private keys, and key
  passphrases encrypted with an app-owned AES-GCM key held by Android Keystore; and
- non-secret configuration such as remote hostnames, usernames, ports, host-key fingerprints,
  remote workspace paths, and local filesystem paths in app preferences.

Cloud backup and device-to-device transfer are disabled in the Android manifest. A compromised or
unlocked device, a malicious agent process, or code running with the app's privileges may still be
able to access data while the app is using it.

## Network communication

Depending on the features used, data may be sent to:

- GitHub for OAuth, repository listing/creation, clone, fetch, and push;
- configured SSH servers for commands and file access;
- configured AgenticDroid LAN companion servers for commands and file access;
- Termux, Debian, Alpine, npm, Google, Maven, Gradle, and agent-vendor endpoints for runtime,
  dependency, SDK, and agent downloads; and
- AI agent providers selected and authenticated by the user.

The LAN companion protocol uses authenticated but unencrypted HTTP and WebSocket connections.
Network observers can read its traffic and pairing token. Use it only on a trusted network or
inside a trusted encrypted tunnel.

Downloaded command-line agents have independent data practices and may send prompts, source code,
terminal content, diagnostics, credentials exposed to their environment, or account information
to their providers. Review each provider's terms and privacy policy before signing in or running
an agent against a workspace.

## Retention and deletion

Data remains until the user deletes it, uses the in-app wipe action, clears app storage, or
uninstalls the app. The wipe action removes preferences, encrypted credentials, active internal
workspaces, legacy workspace backups, and the bootstrapped toolchain. Uninstalling removes
app-private data. Source code not pushed or copied elsewhere may be unrecoverable.

Before filing an issue, redact secrets, repository content, terminal output, hostnames, usernames,
IP addresses, and local paths. See [SECURITY.md](SECURITY.md) for vulnerability reports.
