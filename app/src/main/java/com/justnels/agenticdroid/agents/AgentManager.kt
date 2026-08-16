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
