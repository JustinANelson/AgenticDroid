package com.justnels.agenticdroid.workspace

import java.io.File

/**
 * Environment guidance given to coding agents working inside AgenticDroid's on-device
 * toolchain. Agents otherwise assume a desktop (docker, sudo, adb, an emulator, a GUI) and
 * waste turns on commands that can never work here.
 *
 * Delivered two ways: per-run through the agent's system-prompt flag (no files touched, so
 * it's safe for cloned repos - see [com.justnels.agenticdroid.agents.AgentProfile.withHeadlessGuidance]),
 * and seeded as CLAUDE.md/AGENTS.md for projects created from a template, never overwriting either.
 */
object AgentGuidance {
    private val base = """
        # Environment

        You are running on an Android phone inside the AgenticDroid app, in app-private storage. This is not a desktop Linux machine:
        - There is no sudo, apt, docker, systemd, or GUI. Do not try to install system packages.
        - Available: git, gh, node/npm/npx, ripgrep (rg), fd, jq, curl, tar, sqlite3, and python/pip, OpenJDK 17 and kotlinc when those runner groups are installed. Check with `command -v <tool>` before assuming a tool exists.
        - The CPU is arm64 on Android (Bionic libc). Native npm/pip packages without arm64 Android builds may fail to install; prefer pure-JS/pure-Python alternatives.
        - There is no adb, emulator or display. Verify work with automated tests, build output and CLI/HTTP checks (curl against a local dev server), not by looking at a screen.
        - Builds are slow and memory is limited. Run one build at a time, prefer incremental/targeted test runs, and stop any background servers you start.
        - Keep changes inside the project directory.
    """.trimIndent()

    private fun typeSection(type: ProjectType): String = when (type) {
        ProjectType.ANDROID -> """
            # Android project

            - Build with the Gradle wrapper: `./gradlew assembleDebug`. Run unit tests with `./gradlew testDebugUnitTest`.
            - The Android SDK is preconfigured (ANDROID_HOME); the app downloads the compileSdk platform before builds. Do not edit local.properties or install SDK packages manually.
            - Instrumented/UI tests cannot run here (no emulator). Cover logic with JVM unit tests.
            - You cannot install the APK yourself. After a successful build, the user installs it from the AgenticDroid app.
        """.trimIndent()
        ProjectType.WEB, ProjectType.NODE_JS, ProjectType.SSG -> """
            # JavaScript / web project

            - Use npm (`npm install`, `npm test`, `npm run build`). Dev servers must bind to all interfaces (e.g. `--host`) to appear in the in-app preview.
            - Check a running server with `curl -s localhost:<port>`.
        """.trimIndent()
        ProjectType.PYTHON -> """
            # Python project

            - Use `python`/`pip` (Python must be installed in Settings > Environments). Prefer pure-Python dependencies; packages needing native compilation often fail on-device.
        """.trimIndent()
        ProjectType.JVM -> """
            # Java/Kotlin project

            - OpenJDK 17 and kotlinc are available when the JVM runner group is installed. Prefer the project's Gradle wrapper if it has one; otherwise compile with kotlinc/javac directly.
        """.trimIndent()
        ProjectType.RUST, ProjectType.GOLANG, ProjectType.CPP -> """
            # ${type.displayName} project

            - The ${type.displayName} toolchain is not installed on-device. Edit code and reason about it, but builds/tests need an SSH or LAN environment - say so rather than retrying failing commands.
        """.trimIndent()
        ProjectType.CUSTOM -> ""
    }

    fun render(type: ProjectType, actions: List<ProjectRunnerAction>): String {
        val commands = actions.filter { it.command.isNotBlank() }
            .joinToString("\n") { "- ${it.label}: `${it.command}`" }
        return listOf(
            base,
            typeSection(type),
            if (commands.isNotEmpty()) "# Project commands (configured in the app)\n\n$commands" else ""
        ).filter(String::isNotBlank).joinToString("\n\n") + "\n"
    }

    /** Writes CLAUDE.md and AGENTS.md into [projectDir] unless either already exists. */
    fun seedFiles(projectDir: File, type: ProjectType, actions: List<ProjectRunnerAction>) {
        val text = render(type, actions)
        for (name in listOf("CLAUDE.md", "AGENTS.md")) {
            val file = File(projectDir, name)
            if (!file.exists()) file.writeText(text)
        }
    }
}
