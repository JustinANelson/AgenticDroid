# AgenticDroid: Path to a Viable Standalone/Remote Dev Environment

**Written:** 2026-08-20
**Scope:** Product/feature gaps only — not the engineering-risk register. For signing,
W^X, 16 KB alignment, supply-chain trust, and device-matrix coverage, see
[READINESS_REVIEW.md](READINESS_REVIEW.md) (R1–R11). For already-planned items, see
[ROADMAP.md](ROADMAP.md). This document does not repeat either; it asks "assuming the
current build is stable, what's still missing to make this a *daily-driver* dev
environment on a phone, local or remote?"

**Method:** Read the source tree (agents, LSP, MCP bridge, terminal, editor, git, SSH/
remote env, file transfer), the current uncommitted working tree, and both docs above;
drove the live app on a connected device (Files/Terminal tabs, an active SSH session to
a Windows host). Findings below are scoped to what's actually missing, not restated from
those docs.

---

## 1. Agent lifecycle on a phone — the single highest-leverage gap

Every agent launch path (`AgentProfile.launchCommand()`) execs the CLI into the
*interactive* terminal PTY the user is looking at. That's the right default for a
human pairing with an agent, but it's the only mode. Two consequences:

- **No background/long-run agent execution.** A phone gets backgrounded, Doze-throttled,
  or the terminal's foreground service gets swiped away mid-task. There's no headless
  invocation path (`claude -p`, `codex exec`, `gemini -p`, etc.) that could run under
  WorkManager/a foreground service independent of an attached PTY, survive activity
  destruction, and post a notification on completion or on a permission/tool-approval
  prompt. Today, "start a long agent task and put the phone away" is not a supported
  workflow — it's a race against the OS.
- **No run history / session resume.** If the process does survive, there is no
  transcript store the user can reopen after the app is killed and relaunched — the
  agent's own session/resume flags (`claude --resume`, `codex resume`, etc.) exist but
  aren't surfaced as an app concept (list of past runs, re-attach, diff of what changed).

**Recommendation:** Add a "Run" concept distinct from "Session" — a headless agent
invocation with a defined start/end, backed by a foreground service, that pushes a
system notification on completion/failure/approval-needed, and whose output is logged to
a file the user can reopen from an "Agent Runs" list even after the process/app died.
This turns AgentProfile from "one shell command wrapper" into two real modes: interactive
(today) and unattended (missing). This is what actually makes "agentic development from
a phone" different from "a terminal emulator with npm install scripts."

## 2. Custom agent CLIs — ship the UI, not just the backend

ROADMAP item 3 already tracks this as partial: `AgentManager.addAgent` exists with no
form. Every agent today is a hardcoded `AgentProfile` in `DefaultAgents`, several with
50–150 lines of Android-specific QEMU/musl-wrapping logic per CLI (Codex, Claude,
Antigravity all needed bespoke binary-patching). That bespoke cost means the list of
*officially* supported agents will always lag the market (Cursor CLI, Amp, OpenHands,
whatever ships next quarter). A user-facing "Add custom agent" form (command, install
command, args, env vars — the fields `AgentProfile` already has) turns every future CLI
that's pure-JS/pure-Python (no native binary to smuggle past Zygote's seccomp filter)
into a zero-code addition. Native-binary agents will still need engineering work, but
that's a shrinking fraction of the CLI landscape.

## 3. Remote-session durability (tmux/screen persistence over SSH)

The SSH path already does a lot right (Cloudflare Tunnel, mDNS, X25519, full remote file
CRUD — see READINESS_REVIEW R7 for what's unverified). But R7 only tracks backgrounding/
network-loss as an *untested risk*; nothing proposes what happens when it's hit. A mobile
network dropping mid-agent-run is not an edge case, it's the normal case. The fix isn't
purely a reliability fix — it's a missing feature: auto-launch (or offer to attach to) a
`tmux`/`screen` session on the remote host for every SSH-backed terminal/agent launch, so
a dropped connection leaves the remote process running and a reconnect re-attaches to the
same PTY with scrollback intact. Right now a dropped SSH connection during a live Claude
Code/Codex run on a remote host almost certainly kills the run.

## 4. Headless agent tool-approval / permission UX

Related to #1: agent CLIs that run non-interactively still sometimes need to ask "may I
run this command / write this file?" There's currently no app-level surface for that
(no notification action, no MCP-bridge hook for it) — it only works today because the
agent is attached to a live PTY the user is staring at. Any move toward backgrounded runs
needs a companion notification-with-actions (approve/deny) or a pre-authorized
permission profile (e.g., "auto-approve file edits within the project, always ask before
network/shell") the agent's own `--dangerously-skip-permissions`-style flags can be set
from.

## 5. Remote dev-server preview (the SSH analogue of `WebProjectPreflight`)

`WebProjectPreflight.kt` already runs a local dev server (Vite etc.) and presumably feeds
it to an in-app browser/preview for on-device projects. There's no equivalent for a
project opened over SSH: no SSH local-port-forward UI to tunnel a remote `localhost:5173`
back to the phone and preview it in-app or hand it to Chrome. Given the readiness review
already frames the SSH/remote path as "the more mobile-friendly path long-term" (avoids
the on-device toolchain's storage/battery/W^X cost entirely), shipping this closes the
biggest remaining functional gap between the local and remote workflows — right now,
remote-mode is missing the single most visually satisfying feature (see your code run in
a browser) that local-mode already has.

## 6. LSP breadth

`LspManager` maps exactly one extension today (`"py" -> pyright`). ROADMAP already tracks
JS/TS (tsserver) as the pending item; worth widening the ask: this app's own toolchain
groups already provision Rust, Go, and C/C++ runners (`RunnerPackageGroup`), so
`rust-analyzer`, `gopls`, and `clangd` are natural next additions once tsserver proves the
QEMU/node-hosted LSP-over-stdio pattern works end-to-end on-device. Otherwise the editor's
"real IDE" pitch stays true for exactly one language.

## 7. Terminal ergonomics — narrow, not absent

Correcting an initial assumption: there **is** already a scrollable extra-keys row above
the keyboard (mic/voice input, a saved quick-command chip, copy, ENTER, CTRL-C observed
live). Worth extending rather than building from scratch:
- No visible Esc / Tab / arrow-key / Ctrl-modifier-for-next-keypress keys in the row as
  currently populated — these are the ones that matter for `vim`/`less`/REPL use inside
  an agent CLI's shell tool, and their absence is a real one-handed-usability gap on a
  soft keyboard with no physical Ctrl/Esc.
- The "quick command" chip mechanism already exists (seen: `openssh.co...` truncated
  chip) — worth confirming it's user-configurable (save-your-own-command) rather than a
  single hardcoded suggestion, and if not, that's a small, high-value addition (agent CLI
  users constantly retype the same 3–4 commands: `git status`, run the dev server, launch
  an agent).

## 8. Port/process visibility for backgrounded remote work

If #1 and #3 ship (headless runs, tmux persistence), the app needs a place to *see* what's
still running — a small "Active" panel listing live agent runs, terminal sessions, and any
forwarded ports, each with a stop/reattach action. Today, `TerminalView`'s
"active-agent tracking with a Stop command" (per ROADMAP) covers the single-session case;
this is the multi-session, background-aware successor once runs can outlive the screen
that started them.

## 9. Docker/remote-container awareness (already tracked, re-scoped)

ROADMAP item 2 already lists "Docker Remote Integration." Worth noting given the rest of
this document: this is more valuable paired with #5 (port forwarding) and #3 (session
persistence) than as a standalone tab — a phone user's actual use case is almost always
"my remote host runs my dev stack in Docker Compose, let me see it's up and reach its
ports," not general Docker fleet management.

## 10. What's already in flight and shouldn't be re-proposed

The uncommitted working tree already contains a substantial file-transfer feature
(`FileTransferManager`, `TransferProgressBanner`, `FileShare` — upload/download/
directory-archive-download with progress, cancel, ETA) that isn't reflected in
ROADMAP.md yet. It should be finished, tested, and folded into ROADMAP's "Completed"
section rather than treated as a gap — flagging here only so it isn't accidentally
re-requested as a "new feature" later.

---

## Suggested sequencing

1. **Headless agent runs + notifications (#1)** — the one item that changes what kind of
   product this is, not just what it can do.
2. **tmux/screen session persistence over SSH (#3)** — directly unblocks #1 for the
   remote path, and independently fixes the dominant real-world SSH failure mode R7 only
   documents as untested.
3. **Custom agent form (#2)** — cheap, already 80% built, removes the "CLI of the month"
   maintenance burden.
4. **Remote dev-server preview (#5)** — closes the local/remote feature gap.
5. Tool-approval UX (#4), terminal extra-keys (#7), Active-sessions panel (#8), LSP
   breadth (#6), Docker (#9) — valuable, none individually blocking.
