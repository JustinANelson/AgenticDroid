# AgenticDroid Feature Roadmap

This document outlines the planned enhancements and new features for AgenticDroid, categorized by impact and status.

See [NEXT_FEATURES.md](NEXT_FEATURES.md) for the analysis behind the items added
2026-08-20, and [READINESS_REVIEW.md](READINESS_REVIEW.md) for engineering-risk items
(signing, W^X, device coverage) that are tracked separately from this feature roadmap.

## 🚀 High Impact & Critical

### 1. [/] Headless Agent Runs + Notifications
- **Impact**: Critical
- **Status**: **Code complete, unit-tested, not yet end-to-end verified.** Covers
  Claude/Codex/Gemini (each has a non-interactive prompt mode); Aider/Antigravity don't
  expose one, so they still only offer interactive Launch. `DrainToLog`/`HeadlessRunStore`
  have JVM unit tests (including a real cross-platform bug the tests caught: `File.renameTo`
  silently no-ops when the destination exists, which broke every save/delete after the
  first); navigating to the new screens and starting the service was smoke-tested live on
  device with no crash. What hasn't been run: an actual headless agent process to
  completion (needs an installed agent CLI), so `env.exec` behavior under the argv path,
  the stdin-close fix, and the completion notification are unverified against a real
  process. Verify before checking this fully done.
- **Description**: Run an agent CLI unattended on a single prompt (`claude -p`,
  `codex exec`, `gemini -p`), independent of the live interactive terminal PTY, backed by
  `HeadlessAgentRunService` - a foreground service that survives the app being
  backgrounded - with a system notification on completion/failure/timeout.
- **Details**: `AgentProfile.headlessArgv()` builds the non-interactive invocation;
  `HeadlessRunStore` persists run metadata and a bounded transcript per run to app-private
  storage so a run started before the app/process died is still visible after; the new
  "Agent Runs" screen (reached from a "Runs" button on the Agents tab, or a "Run in
  background" button per installed agent) lists past + in-progress runs, lets you stop one,
  and shows its captured output. Not yet covered: tool-approval prompts during a headless
  run (item 7 below), and SSH-backed remote runs haven't been exercised against a live
  server (they go through the same `ExecutionEnvironment.exec()` local runs do, so they
  should work, but see `READINESS_REVIEW.md` R7 for why that's unverified in general).

### 2. [x] Persistent Remote Sessions (tmux/screen over SSH)
- **Impact**: Critical
- **Status**: **Verified end-to-end against a real POSIX remote** (a WSL Ubuntu instance
  with `openssh-server`/`tmux`/`screen`, set up specifically to close this gap - see
  below). Opt-in per SSH profile (`SSHConfig.usePersistentSession`, default off),
  toggleable both when adding a profile and inline on an existing profile's card.
  Real test performed: connected with persistence on, ran a marker command inside the
  session, force-stopped the app (killing the local `ssh` process - a harder failure than
  a network drop, and the actual failure mode a killed/backgrounded app hits), relaunched,
  reopened Terminal, and confirmed it reattached to the *same* tmux session with the
  marker still in scrollback and no interruption to the remote shell. Also confirmed no
  stray `cd`/prompt-shortening keystrokes were injected into the reattached session (see
  `skipsShellCustomization` below).
  Two real bugs were caught and fixed only because this was actually run against a live
  target, not just unit-tested:
  - `TerminalViewModel`'s session-cache key (`env.getEnvironmentInfo().name`) was derived
    from host only, so two SSH profiles pointed at the same host on different
    ports/usernames collided and silently reattached to whichever one connected first,
    regardless of which was actually active. Fixed by including username/port in the
    environment name (`SSHExecutionEnvironment.getEnvironmentInfo()`); the Environments
    screen's profile-card label had the identical bug and was fixed the same way.
  - A single `-t` lets the local ssh client silently skip pty allocation if its own
    `isatty()` check is ambiguous - harmless for a plain login shell, fatal for
    tmux/screen (`Must be connected to a terminal`, exiting clean with zero output).
    `ptyShellSpec()` now sends `-tt` (force, don't just request) specifically when
    `usePersistentSession` is on.
  Still not exercised: the no-tmux-or-screen fallback path (would need a POSIX remote
  with neither installed) and Cloudflare Tunnel combined with persistence.
- **Description**: Auto-launch or attach to a `tmux`/`screen` session on the remote host
  for SSH-backed terminal/agent sessions, so a dropped mobile connection doesn't kill an
  in-flight remote process.
- **Details**: Directly closes the gap `READINESS_REVIEW.md` R7 only tracks as an
  untested risk ("verify behavior when the phone backgrounds or loses network
  mid-session"). A mobile network dropping mid-run is the normal case, not an edge case,
  for the SSH path. Pairs with item 1 for remote hosts. `SSHExecutionEnvironment.ptyShellSpec()`
  appends `tmux new-session -A -s agenticdroid -c "<dir>" || screen -xR agenticdroid ||
  <visible fallback message + plain shell>` as the remote command when enabled - `-c` only
  applies when *creating* a session (a no-op on an actual `-A` reattach), and the fallback
  prints a message rather than silently degrading. POSIX-only by design: sent as a literal
  remote command line, so a stock Windows OpenSSH remote (cmd.exe default shell) won't
  parse it - there's no POSIX-shell auto-detection here the way `exec()` has, since probing
  would mean blocking interactive terminal startup on a network round trip from
  `TerminalViewModel`'s `remember{}` construction. `TerminalViewModel` skips its
  `cd`/prompt-shortening writes entirely for a persistent-session SSH environment (new
  `skipsShellCustomization` flag), since it can't tell a freshly-created shell from a
  reattach into a session where an agent CLI might already be mid-run, and those writes
  would land as literal keystrokes inside whatever's actually running there.

### 3. [/] LSP (Language Server Protocol) Support
- **Impact**: High
- **Status**: **Partial** (Python/Pyright implemented; JS/TS/tsserver pending)
- **Description**: Add autocompletion, "Go to Definition," and real-time diagnostics to the code editor.
- **Details**: `LspManager` is functional and integrated with `MainViewModel`, currently
  mapping only `.py`. JS/TS (tsserver) is next; `rust-analyzer`, `gopls`, and `clangd` are
  natural follow-ons once tsserver proves the pattern, since the Rust/Go/C++
  `RunnerPackageGroup`s already provision those toolchains.

### 4. [ ] Remote Dev-Server Preview
- **Impact**: High
- **Description**: SSH local-port-forward UI to tunnel a remote dev server
  (`localhost:5173` etc.) back to the phone for in-app/browser preview.
- **Details**: `WebProjectPreflight.kt` already gives on-device (local-toolchain)
  projects a live preview. Remote (SSH) projects have no equivalent — the biggest
  remaining functional gap between the local and remote workflows.

### 5. [ ] Docker Remote Integration
- **Impact**: High
- **Description**: Connect to remote Docker daemons and manage containers.
- **Details**: Most valuable paired with items 2 and 4 — the real use case is "my remote
  host runs my stack in Compose, let me see it's up and reach its ports," not general
  fleet management. Add a dedicated "Docker" tab or integrate into the terminal.

## ✨ Productivity & UX

- [x] **Custom Agent Profiles UI**: Support for adding and managing user-defined CLI agents via a simple form, with persistence and deletion support.
- [x] **Remote File Transfer**: Upload/download and directory-archive-download over SSH,
  with progress, cancel, speed/ETA, and share-out (`FileTransferManager`,
  `TransferProgressBanner`, `FileShare`).
- [x] **Visual Git Diff**: Replaced raw text diffs with a side-by-side visual diff viewer in the UI.
- [x] **MCP Integration**: Deepened agentic capabilities with support for Model Context Protocol (MCP) servers.
- [x] **File Search Integration**: Moved file search into the Files tab with a project/workspace scope toggle.
- [x] **API 36 Support**: Added native wrapper support for Android API 36.
- [x] **Enhanced Terminal Stability**: Fixed `cmd.exe` crashes, improved directory shortening logic, and added POSIX shell auto-detection over SSH.
- [x] **Remote Path Handling**: Improved detection for SFTP-style drive paths on Windows remotes.
- [x] **Terminal Windows Fix**: Improved shell detection and atomic initialization for Windows SSH remotes.
- [x] **Tabbed Editor**: Support for multiple open files with dirty state tracking.
- [x] **Aider Agent**: Integrated the Aider AI pair-programming agent.
- [x] **Keyboard Padding**: Fixed the terminal being obscured by the soft keyboard.
- [x] **Auto-restore Workspace**: Automatically re-opens the last project on startup.
