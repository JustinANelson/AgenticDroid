package com.justnels.agenticdroid.agents.compat

import com.justnels.agenticdroid.agents.AgentProfile
import com.justnels.agenticdroid.env.ExecutionEnvironment
import com.justnels.agenticdroid.env.capture
import java.io.IOException
import java.util.UUID

/**
 * Runs [AgentProfile.installedVersionCommand] and classifies the result - the single
 * place that turns "ran a --version self-test" into a [CompatCheckResult], shared by the
 * on-demand check ([com.justnels.agenticdroid.MainViewModel.checkAgentVersion]/
 * `checkAgentCompat`) and the periodic [CompatCheckWorker] so there's exactly one
 * definition of what "compatible" means. Only ever called for an agent already confirmed
 * installed (`command -v`) by the caller - a missing binary and a broken one both fail
 * this exec, but distinguishing them is the caller's job, not this checker's.
 */
object AgentCompatChecker {
    data class Outcome(val versionText: String?, val result: CompatCheckResult)

    suspend fun check(
        agent: AgentProfile,
        env: ExecutionEnvironment,
        workingDirectory: String,
        environmentLabel: String,
        timeoutMillis: Long = 30_000
    ): Outcome {
        var status: CompatStatus
        var exitCode: Int? = null
        var versionText: String? = null
        var snippet: String
        try {
            val captured = env.exec(agent.installedVersionCommand(), workingDirectory).capture(timeoutMillis)
            exitCode = captured.exitCode
            versionText = captured.stdout.trim().takeIf { captured.exitCode == 0 && it.isNotBlank() }
            status = if (versionText != null) CompatStatus.OK else CompatStatus.BROKEN
            snippet = (captured.stdout + captured.stderr).trim().take(SNIPPET_LIMIT)
        } catch (e: IOException) {
            status = CompatStatus.TIMEOUT
            snippet = (e.message ?: "Compatibility check timed out").take(SNIPPET_LIMIT)
        }
        val result = CompatCheckResult(
            id = UUID.randomUUID().toString(),
            agentId = agent.id,
            agentName = agent.name,
            checkedAt = System.currentTimeMillis(),
            status = status,
            exitCode = exitCode,
            outputSnippet = snippet,
            environmentLabel = environmentLabel
        )
        return Outcome(versionText, result)
    }

    private const val SNIPPET_LIMIT = 500
}
