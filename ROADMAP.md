# AgenticDroid Feature Roadmap

This document outlines the planned enhancements and new features for AgenticDroid, categorized by impact and status.

## 🚀 High Impact & Critical

### 1. [/] LSP (Language Server Protocol) Support
- **Impact**: Critical
- **Status**: **Partial** (Python/Pyright implemented; JS/TS/tsserver pending)
- **Description**: Add autocompletion, "Go to Definition," and real-time diagnostics to the code editor.
- **Details**: `LspManager` is functional and integrated with `MainViewModel`.

### 2. [ ] Docker Remote Integration
- **Impact**: High
- **Description**: Connect to remote Docker daemons and manage containers.
- **Details**: Add a dedicated "Docker" tab or integrate container management into the terminal.

## ✨ Productivity & UX

### 3. [/] Custom Agent Profiles UI
- **Impact**: Medium
- **Status**: **Partial** (Manager logic implemented; UI form pending)
- **Description**: Allow users to add their own CLI agents via a simple form.
- **Details**: `AgentManager.addAgent` is ready; needs a "Custom Agent" button and form in the Agents screen.

### 4. [ ] Markdown Preview
- **Impact**: Medium
- **Description**: A "Live Preview" mode for `.md` files.
- **Details**: Use a library like `commonmark` to render READMEs and documentation nicely.

## ✅ Completed Features
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
