package com.justnels.agenticdroid.git

import android.util.Log
import com.justnels.agenticdroid.env.ExecutionEnvironment
import com.justnels.agenticdroid.env.capture
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Manages structured Git operations without interpolating user values into shell syntax. */
class GitManager(
    private val env: ExecutionEnvironment,
    private val projectPath: String,
    private val sslCertPath: String? = null,
    private val githubToken: String? = null
) {
    private val tag = "GitManager"

    private fun gitEnvironment(): Map<String, String> {
        val token = githubToken?.takeIf(String::isNotBlank) ?: return emptyMap()
        val encoded = Base64.getEncoder()
            .encodeToString("x-access-token:$token".toByteArray(Charsets.UTF_8))
        return mapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "http.https://github.com/.extraHeader",
            "GIT_CONFIG_VALUE_0" to "Authorization: Basic $encoded"
        )
    }

    private fun redact(value: String): String = value
        .replace(Regex("https://[^/@\\s]+@github\\.com/"), "https://***@github.com/")
        .replace(Regex("(?i)Authorization:\\s*(?:token|bearer|basic)\\s+[^\\s]+"), "Authorization: ***")

    private fun executeGit(args: List<String>): GitResult {
        val command = mutableListOf("git", "-c", "core.filemode=false")
        sslCertPath?.takeIf(String::isNotBlank)?.let {
            command += listOf("-c", "http.sslCAInfo=$it")
        }
        command += args

        return try {
            Log.d(tag, "Executing git ${args.firstOrNull() ?: "command"} in $projectPath")
            val result = env.exec(command, projectPath, gitEnvironment()).capture()
            val suffix = if (result.truncated) "\n[output truncated]" else ""
            val output = redact(result.stdout + result.stderr + suffix)
            if (result.exitCode == 0) {
                GitResult.Success(output.trim())
            } else {
                GitResult.Failure("Git error (code ${result.exitCode}) in $projectPath:\n$output")
            }
        } catch (e: Exception) {
            Log.e(tag, "Git ${args.firstOrNull() ?: "command"} failed")
            GitResult.Failure("Git execution failed in $projectPath:\n${redact(e.message.orEmpty())}")
        }
    }

    fun getStatus(): List<String> = when (val result = executeGit(listOf("status", "--porcelain"))) {
        is GitResult.Success -> result.output.lines().filter(String::isNotBlank)
        is GitResult.Failure -> emptyList()
    }

    fun getStatusRaw(): String = when (val result = executeGit(listOf("status"))) {
        is GitResult.Success -> result.output
        is GitResult.Failure -> result.message
    }

    fun getCurrentBranch(): String {
        val symbolic = executeGit(listOf("symbolic-ref", "--short", "HEAD"))
        if (symbolic is GitResult.Success && symbolic.output.isNotBlank()) return symbolic.output

        val detached = executeGit(listOf("rev-parse", "--abbrev-ref", "HEAD"))
        if (detached is GitResult.Success && detached.output.isNotBlank() && detached.output != "HEAD") {
            return detached.output
        }
        return "unknown"
    }

    fun addAll(): GitResult = executeGit(listOf("add", "."))
    fun commit(message: String): GitResult = executeGit(listOf("commit", "-m", message))
    fun getLog(maxCount: Int = 10): GitResult =
        executeGit(listOf("log", "-n", maxCount.coerceIn(1, 100).toString(), "--oneline"))

    fun push(
        remote: String? = null,
        branch: String? = null,
        setUpstream: Boolean = false,
        force: Boolean = false
    ): GitResult {
        val args = mutableListOf("push")
        if (setUpstream) args += "-u"
        if (force) args += "--force-with-lease"
        args += remote ?: "origin"
        args += branch ?: getCurrentBranch()
        return executeGit(args)
    }

    fun pull(rebase: Boolean = false): GitResult =
        executeGit(if (rebase) listOf("pull", "--rebase") else listOf("pull"))

    fun init(): GitResult = executeGit(listOf("init"))
    fun clone(url: String, destination: String): GitResult = executeGit(listOf("clone", url, destination))

    fun getRemotes(): List<String> = when (val result = executeGit(listOf("remote", "-v"))) {
        is GitResult.Success -> result.output.lines().filter(String::isNotBlank)
        is GitResult.Failure -> emptyList()
    }

    fun addRemote(name: String, url: String): GitResult =
        executeGit(listOf("remote", "add", name, url))

    fun setConfig(key: String, value: String): GitResult =
        executeGit(listOf("config", key, value))

    /**
     * Unified diff of everything not yet committed (staged and unstaged) against HEAD, for
     * a pre-commit review view. Falls back to a plain working-tree diff on a repo with no
     * commits yet, since "HEAD" isn't a valid revision there. Untracked new files never
     * appear in either form - git only diffs paths it already knows about - so callers
     * that want new-file content should read those directly from the workspace.
     */
    fun getDiff(): GitResult {
        val args = if (hasCommits()) listOf("diff", "HEAD", "--no-color") else listOf("diff", "--no-color")
        return executeGit(args)
    }

    /**
     * Discards every pending change in the working tree: restores tracked files to HEAD,
     * then deletes untracked files/directories. Irreversible for untracked content (there's
     * no git history to recover it from) - callers must confirm with the user first. Meant
     * for backing out of a failed agent experiment before it's committed; anything already
     * committed is unaffected and can still be reached through the normal git log.
     */
    fun discardAllChanges(): GitResult {
        if (hasCommits()) {
            val checkoutResult = executeGit(listOf("checkout", "--", "."))
            if (checkoutResult is GitResult.Failure) return checkoutResult
        }
        return executeGit(listOf("clean", "-fd"))
    }

    fun fetch(): GitResult = executeGit(listOf("fetch"))
    fun renameBranch(newName: String): GitResult = executeGit(listOf("branch", "-M", newName))
    fun hasCommits(): Boolean = executeGit(listOf("rev-parse", "HEAD")) is GitResult.Success
    fun checkRemoteConnectivity(remoteName: String): GitResult =
        executeGit(listOf("ls-remote", remoteName))

    fun createGitHubRepo(token: String, name: String, isPrivate: Boolean): GitResult {
        val connection = URL("https://api.github.com/user/repos").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "AgenticDroid/1.0")
            val payload = JSONObject().put("name", name).put("private", isPrivate).toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(4096)
                    while (result.length < 1_000_000) {
                        val read = reader.read(buffer, 0, minOf(buffer.size, 1_000_000 - result.length))
                        if (read < 0) break
                        result.append(buffer, 0, read)
                    }
                    result.toString()
                }.orEmpty()
            if (status in 200..299) {
                GitResult.Success("Repository created successfully.")
            } else {
                val message = runCatching { JSONObject(response).optString("message") }
                    .getOrNull().orEmpty().ifBlank { "GitHub returned HTTP $status" }
                GitResult.Failure("Failed to create repository on GitHub: $message")
            }
        } catch (e: Exception) {
            GitResult.Failure("Error creating GitHub repository: ${redact(e.message.orEmpty())}")
        } finally {
            connection.disconnect()
        }
    }
}

sealed class GitResult {
    data class Success(val output: String) : GitResult()
    data class Failure(val message: String) : GitResult()
}
