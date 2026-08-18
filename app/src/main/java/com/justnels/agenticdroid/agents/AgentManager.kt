package com.justnels.agenticdroid.agents

import androidx.compose.runtime.mutableStateListOf

/**
 * Manages available AI Agent profiles.
 */
class AgentManager {
    private val _agents = mutableStateListOf<AgentProfile>()
    val agents: List<AgentProfile> = _agents

    init {
        _agents.addAll(DefaultAgents.All)
    }

    fun addAgent(profile: AgentProfile) {
        _agents.add(profile)
    }

    fun updateAgent(profile: AgentProfile) {
        val index = _agents.indexOfFirst { it.id == profile.id }
        if (index != -1) {
            _agents[index] = profile
        }
    }
}

/** installed/latest as raw `--version`/`npm view` output - best-effort parsing only, shown
 *  as-is rather than risk misreading an agent's own version-string format. */
data class AgentVersionInfo(
    val installed: String? = null,
    val latest: String? = null
) {
    /** Best-effort: [installed] is a whole `--version` banner (format varies per agent,
     * e.g. "1.2.3 (Claude Code)" or "claude-code/1.2.3 linux-arm64 node-v20"), while
     * [latest] is a bare semver from `npm view`. Exact equality would almost always read
     * as "update available" just from the format mismatch, so this checks whether the
     * bare version appears anywhere in the banner instead. */
    val updateAvailable: Boolean
        get() {
            val bareLatest = latest?.trim().orEmpty()
            return installed != null && bareLatest.isNotEmpty() && bareLatest !in installed
        }
}
