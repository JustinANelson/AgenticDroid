# Security policy

AgenticDroid is pre-release, sideload-only software. Please do not include access tokens,
passwords, private keys, repository contents, or other secrets in a public issue.

Until a private reporting address is published, create a minimal public issue requesting a
private security contact without disclosing exploit details. Rotate any credential that may
have been exposed while reproducing a problem.

Security-sensitive areas include archive extraction, runtime executable downloads, Git and
shell argument handling, GitHub OAuth/token storage, SSH host verification, workspace path
containment, APK installation, and terminal-service lifecycle.
