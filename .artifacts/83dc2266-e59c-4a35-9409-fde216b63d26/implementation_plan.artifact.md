# Implementation Plan: Agentic Mobile Coding CLI - Phase 1

This plan covers the foundational "hard" parts of the Agentic Mobile Coding CLI: the JNI-based PTY Engine and the State Persistence mechanism using a Foreground Service.

## User Review Required

> [!IMPORTANT]
> This plan involves adding Native (C++) code to the project. This requires the Android NDK to be installed. I will configure the project to use the NDK.

> [!NOTE]
> The PTY bridge will be implemented from scratch using standard Linux system calls (`posix_openpt`, `grantpt`, `unlockpt`, `ptsname`, `fork`, `execvp`). This provides the most control for terminal interactions.

## Proposed Changes

### 1. Project Infrastructure

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/justnels/AndroidStudioProjects/AgenticDroid/app/build.gradle.kts)
- Enable Jetpack Compose for the UI.
- Add NDK/CMake configuration.
- Add necessary dependencies (Lifecycle, Service, Compose).

#### [NEW] [CMakeLists.txt](file:///C:/Users/justnels/AndroidStudioProjects/AgenticDroid/app/src/main/cpp/CMakeLists.txt)
- Define the `ptybridge` native library.

### 2. PTY Engine (JNI)

#### [NEW] [ptybridge.cpp](file:///C:/Users/justnels/AndroidStudioProjects/AgenticDroid/app/src/main/cpp/ptybridge.cpp)
- Implementation of `openpty`, `fork`, and `exec`.
- JNI methods to start a session, read/write to the master FD, and resize the terminal.

#### [NEW] [PtyBridge.kt](file:///C:/Users/justnels/AndroidStudioProjects/AgenticDroid/app/src/main/java/com/justnels/agenticdroid/terminal/PtyBridge.kt)
- Kotlin interface to the native library.
- Defines the `TerminalSession` handle.

### 3. State Persistence (Foreground Service)

#### [NEW] [TerminalService.kt](file:///C:/Users/justnels/AndroidStudioProjects/AgenticDroid/app/src/main/java/com/justnels/agenticdroid/terminal/TerminalService.kt)
- A Foreground Service that owns and maintains all active `TerminalSession`s.
- Holds the master File Descriptors for the PTYs.
- Provides a Binder interface for the UI to connect and interact with sessions.

#### [NEW] [TerminalSession.kt](file:///C:/Users/justnels/AndroidStudioProjects/AgenticDroid/app/src/main/java/com/justnels/agenticdroid/terminal/TerminalSession.kt)
- Represents an active terminal process.
- Manages I/O threads for reading/writing.
- Buffers output for the UI.

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/justnels/AndroidStudioProjects/AgenticDroid/app/src/main/AndroidManifest.xml)
- Register `TerminalService`.
- Add `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (or similar) permissions.

### 4. Basic Terminal UI

#### [NEW] [MainActivity.kt](file:///C:/Users/justnels/AndroidStudioProjects/AgenticDroid/app/src/main/java/com/justnels/agenticdroid/MainActivity.kt)
- Main entry point.
- Connects to `TerminalService`.
- Hosts the `TerminalScreen` (Compose).

#### [NEW] [TerminalScreen.kt](file:///C:/Users/justnels/AndroidStudioProjects/AgenticDroid/app/src/main/java/com/justnels/agenticdroid/ui/TerminalScreen.kt)
- A simple Compose-based terminal renderer.
- Initially, it will show raw text output and allow keyboard input.

## Verification Plan

### Automated Tests
- Unit tests for `PtyBridge` (if possible in emulator).
- Service lifecycle tests.

### Manual Verification
1.  **PTY Interaction**: Launch the app, verify it starts a shell (`/system/bin/sh`).
2.  **Interactive Commands**: Type `ls`, `echo "hello"`, and verify output.
3.  **Persistence**: Start a long-running command (e.g., `ping localhost`), switch apps, wait 10 seconds, return, and verify it's still running.
4.  **Terminal TUI**: Try running `top` or `vi` (if available) to verify ANSI handling and resizing (basic support).
