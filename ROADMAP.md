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

### 2. [ ] Persistent Remote Sessions (tmux/screen over SSH)
- **Impact**: Critical
- **Description**: Auto-launch or attach to a `tmux`/`screen` session on the remote host
  for SSH-backed terminal/agent sessions, so a dropped mobile connection doesn't kill an
  in-flight remote process.
- **Details**: Directly closes the gap `READINESS_REVIEW.md` R7 only tracks as an
  untested risk ("verify behavior when the phone backgrounds or loses network
  mid-session"). A mobile network dropping mid-run is the normal case, not an edge case,
  for the SSH path. Pairs with item 1 for remote hosts.

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

### 6. [/] Custom Agent Profiles UI
- **Impact**: Medium
- **Status**: **Partial** (Manager logic implemented; UI form pending)
- **Description**: Allow users to add their own CLI agents via a simple form.
- **Details**: `AgentManager.addAgent` is ready; needs a "Custom Agent" button and form in
  the Agents screen. Removes the per-CLI maintenance burden for any future agent that's
  pure-JS/pure-Python (no native binary to smuggle past Zygote's seccomp filter).

### 7. [ ] Headless Run Tool-Approval UX
- **Impact**: Medium
- **Description**: A notification action (approve/deny) or a pre-authorized permission
  profile (e.g. "auto-approve file edits in the project, always ask before network/shell")
  for agent tool-use prompts that occur during a headless run.
- **Details**: Depends on item 1. Today this only works because the agent is attached to
  a live PTY the user is looking at; a backgrounded run has no surface for it yet.

### 8. [ ] Active Sessions Panel
- **Impact**: Medium
- **Description**: A panel listing live agent runs, terminal sessions, and any forwarded
  ports, each with a stop/reattach action.
- **Details**: Successor to the existing single-session "active-agent tracking with a
  Stop command" once runs can outlive the screen that started them (items 1–2).

### 9. [ ] Terminal Extra-Keys Expansion
- **Impact**: Medium
- **Description**: Add Esc / Tab / arrow-keys / Ctrl-modifier-for-next-keypress to the
  terminal's extra-keys row, and confirm/expose the quick-command chip as user-saveable.
- **Details**: The scrollable extra-keys row already exists (mic/voice input, a
  quick-command chip, copy, ENTER, CTRL-C) but is missing the keys that matter most for
  `vim`/`less`/REPL use inside an agent's shell tool on a soft keyboard with no physical
  Ctrl/Esc.

### 10. [ ] Markdown Preview
- **Impact**: Medium
- **Description**: A "Live Preview" mode for `.md` files.
- **Details**: Use a library like `commonmark` to render READMEs and documentation nicely.

## ✅ Completed Features
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
