package com.justnels.agenticdroid.env

import java.io.File
import java.io.InputStream
import java.io.OutputStream

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
    fun exec(command: String, workingDirectory: String): ProcessSession

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
}

/** Everything needed to fork a PTY-attached shell: see [ExecutionEnvironment.ptyShellSpec]. */
data class PtyShellSpec(
    val shellPath: String,
    val args: Array<String>,
    val cwd: String,
    val env: Array<String>
)

interface ProcessSession {
    val pid: Int
    fun kill()
    fun waitFor(): Int
    val inputStream: InputStream
    val errorStream: InputStream
    val outputStream: OutputStream
}

interface FileSystemAccess {
    fun listFiles(path: String): List<File>
    fun readFile(path: String): String
    fun writeFile(path: String, content: String)
    fun deleteFile(path: String): Boolean
    fun exists(path: String): Boolean
    fun renameFile(oldPath: String, newPath: String): Boolean
    fun copyFile(srcPath: String, destPath: String): Boolean
    /** Downloads a remote file to a local destination. */
    fun downloadFile(remotePath: String, localDest: File)
}

data class EnvironmentInfo(
    val name: String,
    val os: String,
    val architecture: String,
    val installedTools: List<String>
)
