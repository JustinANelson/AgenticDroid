package com.justnels.agenticdroid.agents

import com.justnels.agenticdroid.env.ExecutionEnvironment
import com.justnels.agenticdroid.env.ProcessSession

/**
 * Manages the lifecycle of an active agent process.
 */
class AgentSession(
    val profile: AgentProfile,
    private val env: ExecutionEnvironment,
    private val workingDirectory: String
) {
    private var activeSession: ProcessSession? = null

    fun start() {
        if (activeSession == null) {
            activeSession = env.exec(profile.launchCommand(), workingDirectory)
        }
    }

    fun stop() {
        activeSession?.kill()
        activeSession = null
    }

    fun isRunning(): Boolean {
        return activeSession != null
    }
}
