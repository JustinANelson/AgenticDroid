package com.justnels.agenticdroid.env

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Interface defining the execution environment for running commands and accessing the file system.
 * This can be a local Android environment, the bundled native Node toolchain, or a remote SSH environment.
 */
interface ExecutionEnvironment {
    /**
     * Executes a command in the specified working directory.
     * @param command The command to execute.
     * @param workingDirectory The directory where the command should be executed.
     * @return A session object representing the running process.
     */
    fun exec(
        command: String,
        workingDirectory: String,
        environment: Map<String, String> = emptyMap()
    ): ProcessSession

    /** Executes an argv-style command without allowing argument values to become shell syntax. */
    fun exec(
        arguments: List<String>,
        workingDirectory: String,
        environment: Map<String, String> = emptyMap()
    ): ProcessSession {
        require(arguments.isNotEmpty()) { "Command arguments cannot be empty" }
        return exec(arguments.joinToString(" ", transform = ShellEscaping::quote), workingDirectory, environment)
    }

    /**
     * Returns an interface for file system operations.
     */
    fun filesystem(): FileSystemAccess

    /**
     * Returns metadata about the environment (e.g., OS, installed tools).
     */
    fun getEnvironmentInfo(): EnvironmentInfo

    /**
     * Spec for spawning an interactive, PTY-backed shell in this environment (used by the
     * Terminal screen and agent launches, both of which need a real controlling terminal -
     * TUIs like Claude Code/Codex/Antigravity open /dev/tty directly and fail without one).
     * Returns null if this environment doesn't support a local PTY session - e.g. SSH,
     * which allocates its own PTY over the network channel instead.
     */
    fun ptyShellSpec(workingDirectory: String): PtyShellSpec? = null

    /** Releases environment-owned resources such as persistent SSH connections. */
    fun close() = Unit
}

/** Everything needed to fork a PTY-attached shell: see [ExecutionEnvironment.ptyShellSpec]. */
data class PtyShellSpec(
    val shellPath: String,
    val args: Array<String>,
    val cwd: String,
    val env: Array<String>
)

interface ProcessSession : AutoCloseable {
    val pid: Int
    fun kill()
    fun waitFor(): Int
    fun isRunning(): Boolean
    val inputStream: InputStream
    val errorStream: InputStream
    val outputStream: OutputStream
}

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val truncated: Boolean)

/** Drains both pipes concurrently so a verbose child cannot block on a full OS pipe. */
fun ProcessSession.capture(
    timeoutMillis: Long = 10 * 60 * 1000L,
    maxOutputBytes: Int = 2 * 1024 * 1024
): ProcessResult {
    require(timeoutMillis > 0)
    require(maxOutputBytes > 0)
    val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "agenticdroid-process-io").apply { isDaemon = true }
    }
    fun drain(stream: InputStream) = executor.submit<Pair<String, Boolean>> {
        val retained = java.io.ByteArrayOutputStream(minOf(maxOutputBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var truncated = false
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            val remaining = maxOutputBytes - retained.size()
            if (remaining > 0) retained.write(buffer, 0, minOf(read, remaining))
            if (read > remaining) truncated = true
        }
        retained.toString(Charsets.UTF_8.name()) to truncated
    }

    val stdout = drain(inputStream)
    val stderr = drain(errorStream)
    val exit = executor.submit<Int> { waitFor() }
    return try {
        val code = exit.get(timeoutMillis, TimeUnit.MILLISECONDS)
        val remainingSeconds = 30L
        val out = stdout.get(remainingSeconds, TimeUnit.SECONDS)
        val err = stderr.get(remainingSeconds, TimeUnit.SECONDS)
        ProcessResult(code, out.first, err.first, out.second || err.second)
    } catch (e: java.util.concurrent.TimeoutException) {
        kill()
        throw java.io.IOException("Command timed out after ${timeoutMillis}ms", e)
    } finally {
        close()
        executor.shutdownNow()
    }
}

interface FileSystemAccess {
    fun listEntries(path: String): List<FileSystemEntry>
    fun listFiles(path: String): List<File> = listEntries(path).map { File(it.path) }
    fun readFile(path: String): String
    fun writeFile(path: String, content: String)
    fun deleteFile(path: String): Boolean
    fun exists(path: String): Boolean
    fun renameFile(oldPath: String, newPath: String): Boolean
    fun copyFile(srcPath: String, destPath: String): Boolean
    /** Downloads a remote file to a local destination. */
    fun downloadFile(remotePath: String, localDest: File)
}

data class FileSystemEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0
)

data class EnvironmentInfo(
    val name: String,
    val os: String,
    val architecture: String,
    val installedTools: List<String>
)
