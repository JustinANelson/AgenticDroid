# Privacy and data handling

AgenticDroid stores workspaces and toolchains on the device. It does not operate a project
backend of its own, but user-initiated features communicate with third-party services:

- GitHub for OAuth, repository listing, cloning, repository creation, fetch, and push;
- configured SSH servers for remote commands and file access;
- Termux, Debian, Alpine, npm, and agent-vendor endpoints for toolchain/agent downloads.

GitHub tokens are stored using Keystore-backed encrypted preferences. SSH passwords are
currently held only by the in-memory SSH environment configuration; they are not persisted.
Cloud backup and device transfer are disabled. Terminal contents, source files, credentials,
and repository contents should be treated as sensitive.

Downloaded command-line agents have their own data practices and may send prompts, source
content, diagnostics, or account information to their providers. Review each provider's
terms and privacy policy before signing in or running an agent against a workspace.

The in-app wipe action removes preferences, stored credentials, workspaces, and the bundled
toolchain. Uninstalling the app also removes app-private data; source code not pushed to a
remote may be unrecoverable.
