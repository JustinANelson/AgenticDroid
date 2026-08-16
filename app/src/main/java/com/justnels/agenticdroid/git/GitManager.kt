package com.justnels.agenticdroid.git

import android.util.Log
import com.justnels.agenticdroid.env.ExecutionEnvironment

/**
 * Manages Git operations by wrapping the Git CLI.
 */
class GitManager(
    private val env: ExecutionEnvironment, 
    private val projectPath: String,
    private val sslCertPath: String? = null
) {
    private val TAG = "GitManager"

    private fun executeGit(args: String): GitResult {
        return try {
            // core.filemode=false is critical on Android's FUSE/FAT32-like external storage 
            val baseCommand = StringBuilder("git -c core.filemode=false")
            if (sslCertPath != null) {
                baseCommand.append(" -c http.sslCAInfo=\"$sslCertPath\"")
            }
            val gitCommand = "$baseCommand $args"
            Log.d(TAG, "Executing: $gitCommand in $projectPath")
            
            val session = env.exec(gitCommand, projectPath)
            val exitCode = session.waitFor()
            val output = session.inputStream.bufferedReader().readText() + 
                         session.errorStream.bufferedReader().readText()
            
            Log.d(TAG, "Exit Code: $exitCode, Output: ${output.trim()}")
            
            if (exitCode == 0) {
                GitResult.Success(output.trim())
            } else {
                GitResult.Failure("Git error (code $exitCode) in $projectPath:\n$output")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
            GitResult.Failure("Git execution failed in $projectPath:\n${e.message}")
        }
    }

    fun getStatus(): List<String> {
        val result = executeGit("status --porcelain")
        return if (result is GitResult.Success) {
            result.output.lines().filter { it.isNotBlank() }
        } else {
            emptyList()
        }
    }

    fun getStatusRaw(): String {
        val result = executeGit("status")
        return if (result is GitResult.Success) result.output else (result as GitResult.Failure).message
    }

    fun getCurrentBranch(): String {
        // 1. Try symbolic-ref (most reliable for active branch)
        val symResult = executeGit("symbolic-ref --short HEAD")
        if (symResult is GitResult.Success && symResult.output.isNotBlank()) {
            return symResult.output
        }
        
        // 2. Try rev-parse (works for detached HEAD)
        val revResult = executeGit("rev-parse --abbrev-ref HEAD")
        if (revResult is GitResult.Success && revResult.output.isNotBlank() && revResult.output != "HEAD") {
            return revResult.output
        }
        
        // 3. Fallback for newly initialized repos with NO commits
        // In this state, HEAD points to a branch that doesn't exist as a ref yet.
        // We can read it from the .git/HEAD file via cat.
        val headFileResult = executeGit("rev-parse --git-path HEAD")
        if (headFileResult is GitResult.Success) {
            try {
                // We'll use a shell command to read the branch name from the HEAD file
                // format is "ref: refs/heads/branchname"
                val catResult = executeGit("cat \"${headFileResult.output}\"")
                if (catResult is GitResult.Success && catResult.output.startsWith("ref: ")) {
                    return catResult.output.substringAfterLast("/").trim()
                }
            } catch (e: Exception) {}
        }

        // If all attempts to find a branch fail, it's likely not a git repository
        return "unknown"
    }

    fun addAll(): GitResult = executeGit("add .")
    fun commit(message: String): GitResult = executeGit("commit -m \"$message\"")
    fun getLog(maxCount: Int = 10): GitResult = executeGit("log -n $maxCount --oneline")

    fun push(remote: String? = null, branch: String? = null, setUpstream: Boolean = false, force: Boolean = false): GitResult {
        val args = StringBuilder("push")
        if (setUpstream) args.append(" -u")
        if (force) args.append(" -f")
        // Always try to be explicit if possible
        val targetRemote = remote ?: "origin"
        val targetBranch = branch ?: getCurrentBranch()
        
        args.append(" $targetRemote")
        args.append(" $targetBranch")
        
        return executeGit(args.toString())
    }
    fun pull(rebase: Boolean = false): GitResult {
        val args = if (rebase) "pull --rebase" else "pull"
        return executeGit(args)
    }
    fun init(): GitResult = executeGit("init")

    fun clone(url: String, destination: String): GitResult {
        // Clone is run outside the project path usually, but env.exec handles it.
        // We use -c core.filemode=false and SSL info for clone too.
        val baseCommand = StringBuilder("git -c core.filemode=false")
        if (sslCertPath != null) {
            baseCommand.append(" -c http.sslCAInfo=\"$sslCertPath\"")
        }
        val gitCommand = "$baseCommand clone \"$url\" \"$destination\""
        return executeGitCommandRaw(gitCommand)
    }

    private fun executeGitCommandRaw(fullCommand: String): GitResult {
        return try {
            Log.d(TAG, "Executing raw: $fullCommand")
            val session = env.exec(fullCommand, "/") // Root or any valid dir for clone
            val exitCode = session.waitFor()
            val output = session.inputStream.bufferedReader().readText() + 
                         session.errorStream.bufferedReader().readText()
            
            Log.d(TAG, "Exit Code: $exitCode, Output: ${output.trim()}")
            
            if (exitCode == 0) {
                GitResult.Success(output.trim())
            } else {
                GitResult.Failure("Git error (code $exitCode):\n$output")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
            GitResult.Failure("Git execution failed:\n${e.message}")
        }
    }

    fun getRemotes(): List<String> {
        val result = executeGit("remote -v")
        return if (result is GitResult.Success) {
            result.output.lines().filter { it.isNotBlank() }
        } else {
            emptyList()
        }
    }

    fun addRemote(name: String, url: String): GitResult = executeGit("remote add $name $url")

    fun setConfig(key: String, value: String): GitResult = executeGit("config $key \"$value\"")

    fun fetch(): GitResult = executeGit("fetch")

    fun renameBranch(newName: String): GitResult = executeGit("branch -M $newName")

    fun hasCommits(): Boolean {
        // rev-parse --head checks if HEAD points to a valid commit
        val result = executeGit("rev-parse HEAD")
        return result is GitResult.Success
    }

    fun checkRemoteConnectivity(remoteName: String): GitResult {
        // ls-remote is a good way to check if we can actually reach the server
        // without downloading data.
        return executeGit("ls-remote $remoteName")
    }

    fun createGitHubRepo(token: String, name: String, isPrivate: Boolean): GitResult {
        return try {
            val privacy = if (isPrivate) "true" else "false"
            val data = "{\\\"name\\\":\\\"$name\\\",\\\"private\\\":$privacy}"
            // Using curl via the execution environment to make the API call
            val curlCommand = "curl -H \"Authorization: token $token\" " +
                              "-d \"$data\" https://api.github.com/user/repos"
            
            val session = env.exec(curlCommand, projectPath)
            val exitCode = session.waitFor()
            val output = session.inputStream.bufferedReader().readText() + 
                         session.errorStream.bufferedReader().readText()
            
            if (exitCode == 0 && (output.contains("\"id\":") || output.contains("\"name\":\"$name\""))) {
                GitResult.Success("Repository created successfully.")
            } else {
                GitResult.Failure("Failed to create repository on GitHub.\n$output")
            }
        } catch (e: Exception) {
            GitResult.Failure("Error creating GitHub repository: ${e.message}")
        }
    }
}

sealed class GitResult {
    data class Success(val output: String) : GitResult()
    data class Failure(val message: String) : GitResult()
}
