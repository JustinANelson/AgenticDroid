package com.justnels.agenticdroid.env

import android.content.Context
import java.io.File

/**
 * Runs commands with the bundled Node/git toolchain on PATH - as an ordinary Android
 * child process. No chroot, no ptrace, no bind mounts: working directories are real
 * Android paths, so agents operate directly on the app's actual workspace files.
 */
class NodeExecutionEnvironment(private val context: Context) : ExecutionEnvironment {

    // Cheap (a couple of small text-file writes) - refreshed on every launch below rather
    // than once at install time, since the device's DNS servers can change whenever the
    // phone switches networks after the toolchain was installed. See NodeBootstrapper.
    private val bootstrapper = NodeBootstrapper(context)

    override fun exec(
        command: String,
        workingDirectory: String,
        environment: Map<String, String>
    ): ProcessSession {
        bootstrapper.ensureResolvConf()
        // Resolve the first word (the tool) to an absolute path if it exists in our bin or global dir.
        val firstWord = command.substringBefore(" ").trim()
        val rest = command.substringAfter(firstWord, "").trim()
        
        val binary = listOf(
            File(NodeRuntime.binDir(context), firstWord),
            File(NodeRuntime.globalBinDir(context), firstWord),
            File(File(NodeRuntime.homeDir(context), ".local/bin"), firstWord)
        ).firstOrNull { it.exists() }
        
        val fullCommand = if (binary != null) {
            if (rest.isEmpty()) "\"${binary.absolutePath}\"" else "\"${binary.absolutePath}\" $rest"
        } else {
            command
        }

        android.util.Log.d("NodeExec", "Executing command: $fullCommand")
        
        val process = ProcessBuilder("/system/bin/sh", "-c", fullCommand)
            .directory(File(workingDirectory))
            .apply {
                // Don't clear() the entire environment, as we might lose system-critical vars.
                // Instead, just overwrite/set what we need.
                NodeRuntime.configureEnvironment(context, environment())
                environment()["TERM"] = "xterm-256color"
                environment()["COLORTERM"] = "truecolor"
                environment().putAll(environment)
            }
            .start()
        return LocalProcessSession(process)
    }

    override fun filesystem(): FileSystemAccess = LocalFileSystemAccess()

    override fun getEnvironmentInfo(): EnvironmentInfo {
        val tools = mutableListOf("node", "npm", "git")
        if (File(NodeRuntime.binDir(context), "python3").exists() || File(NodeRuntime.binDir(context), "python").exists()) {
            tools.add("python3")
            tools.add("pip")
        }
        if (NodeRuntime.qemuBinary(context).exists()) {
            tools.add("qemu")
        }
        if (File(NodeRuntime.binDir(context), "gh").exists()) {
            tools.add("gh")
        }
        return EnvironmentInfo(
            name = "Node & Python Toolchain",
            os = "Android (native)",
            architecture = System.getProperty("os.arch") ?: "unknown",
            installedTools = tools
        )
    }

    override fun ptyShellSpec(workingDirectory: String): PtyShellSpec {
        bootstrapper.ensureResolvConf()
        val envMap = mutableMapOf<String, String>()
        NodeRuntime.configureEnvironment(context, envMap)
        envMap["TERM"] = "xterm-256color"
        envMap["COLORTERM"] = "truecolor"
        return PtyShellSpec(
            shellPath = "/system/bin/sh",
            args = emptyArray(),
            cwd = workingDirectory,
            env = envMap.map { (key, value) -> "$key=$value" }.toTypedArray()
        )
    }
}
