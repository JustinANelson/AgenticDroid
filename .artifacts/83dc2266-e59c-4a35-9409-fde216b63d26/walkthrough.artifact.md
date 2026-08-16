# Walkthrough: Git, Multi-Project & Persistence

I have implemented the core features identified in the next steps to move the project from a UI skeleton to a functional development environment, including terminal persistence.

## Key Changes

### 1. Terminal Persistence (Foreground Service)
Implemented a robust background session management system using a Foreground Service.
- **Service Ownership**: `TerminalService` now owns the `TerminalSession` objects and the associated PTY processes.
- **Background Execution**: Terminal processes and AI agents (like Claude) continue running even if the app is backgrounded or the main activity is killed.
- **Notification**: A persistent notification informs the user that sessions are active, preventing the system from killing the background processes.
- **Session Recovery**: The `TerminalViewModel` automatically reattaches to existing sessions in the service when the UI is restored.

### 2. Git Integration
The Git screen is now powered by the real `git` binary in the terminal environment.
- **Status Monitoring**: Automatically parses `git status --porcelain` to show changed files.
- **Remote Integration**: Added support for managing Git remotes. The UI now supports **Auto-Linking** and **Repository Creation** on GitHub.
- **GitHub Device Flow (Web Login)**: Implemented the GitHub Device Authorization Flow. Users can now authorize the app via their web browser by entering a 8-character code, removing the need to manually copy-paste long PAT strings (though PAT is still supported).
- **Remote Status Indicators**: The Git tab now displays a connectivity status icon (Green/Red) for each remote, giving immediate feedback on whether GitHub is reachable.
- **GitHub Integration**: Added the ability to browse and clone repositories directly from your GitHub account. When cloning, you can now choose to "Browse Your GitHub Repos" to see a list of all your public and private repositories and select one to clone with a single tap.
- **Automatic Authentication**: Added secure persistence for GitHub Personal Access Tokens (PAT) and OAuth Web Flow.
- **Secrets Management**: Moved sensitive GitHub OAuth credentials (`CLIENT_ID`, `CLIENT_SECRET`) to a root `.secrets` file, which is excluded from version control via `.gitignore`. These secrets are now automatically injected into the app at build-time using `BuildConfig`.
- **Core Actions**: Support for `Commit`, `Push`, and `Pull` is now fully wired up.
- **Auto-Initialization**: Detects if a project is not a git repository and provides a one-tap "Initialize Git" button.
- **Cross-Environment Compatibility**: Fixed several critical bugs (code 127, 128) by optimizing the toolchain and enforcing sandbox-internal configuration paths. Specifically fixed `curl` and `git` SSL certificate errors by explicitly passing `http.sslCAInfo` and setting `SSL_CERT_FILE` to sandbox-relative paths. Added URL encoding for project names to support repositories with spaces.

### 3. Multi-Project Support
The Workspace now supports multiple isolated projects instead of a single root folder.
- **Project Selector**: A new screen for listing and selecting projects.
- **Project Creation**: Users can create new project directories from the UI.
- **GitHub Clone**: Added the ability to clone existing repositories from GitHub directly into the workspace. The app automatically uses your stored GitHub Token for authenticated cloning of private repos.
- **File Management**: Added the ability to create, delete, copy, and rename files within a project directly from the Files tab. Context menus are available via long-press or the 'more' icon on each file.
- **Project Context**: The Terminal, Git, and File Explorer now all operate within the context of the `selectedProject`.
- **Navigation**: Easily switch back to the project list or browse the current project's file tree.

### 4. Remote Build & Install (Tailscale Optimized)
Created a seamless pipeline for building and installing APKs from any environment, optimized for remote development over networks like Tailscale.
- **Cross-Environment Download**: `FileSystemAccess` now supports `exists()` and `downloadFile()`, allowing the app to verify and pull build artifacts from remote SSH servers via SFTP.
- **Tailscale Integration**: Since the app uses standard SSH/SFTP via `sshj`, it works transparently over Tailscale. Users can simply add their remote build machine's Tailscale IP as an SSH environment.
- **Automated Installation**: Added an `ApkInstaller` utility that leverages `FileProvider` to safely trigger the Android package installer once the APK is pulled to the phone.
- **One-Tap Build**: A new "Build & Install" action in the Workspace view automates the entire process: running the build command, locating the APK, downloading it, and prompting for installation.

### 5. Project-Aware Context
The Terminal and Git screens are now fully integrated with the selected project.
- **Dynamic Terminal**: The terminal session now automatically re-initializes when you switch projects, ensuring you always start at the project's root directory.
- **Git Project Branding**: The Git screen now clearly displays which project you are currently working on.
- **Contextual Agents**: Launching AI agents from the Agents tab now correctly scopes them to the current project's filesystem, allowing them to see and modify the correct files.

### 6. Environment & UI Responsiveness
Improved the UI to be fully reactive to background processes.
- **Reactive Toolchain Status**: The "Node Toolchain" status is now an observable state. The Environment and Agents tabs now update immediately as soon as a download completes, removing the need to navigate away and back.
- **Mirror & Indexing Optimization**: Switched to Cloudflare-backed mirrors and optimized index fetching for faster setup.

### 7. Onboarding & Hints
Added a user-friendly onboarding system with dismissible tooltips to help new users discover the app's features.
- **Dismissible Hints**: Added `HintBox` components across all major screens (Workspace, Git, Terminal, Agents, Environments) explaining key concepts like persistent sessions and remote deployment.
- **Persistent State**: User interactions with hints are saved in shared preferences so they don't reappear after being dismissed.
- **Developer Tools**: Added a "Wipe App Data" action in Settings to clear all projects, toolchains, and preferences—perfect for testing the clean-install experience.

## Verification
- [x] Verified `HintBox` animates out smoothly when dismissed.
- [x] Verified "Wipe App Data" successfully clears storage and terminates the app.
- [x] Verified hints re-appear correctly after a data wipe.

## Next Steps
- **Editor Enhancements**: Add syntax highlighting for more languages.
- **Agent Configuration**: Allow users to customize agent arguments and environment variables.
