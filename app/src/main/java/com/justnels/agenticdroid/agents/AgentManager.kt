package com.justnels.agenticdroid.agents

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages available AI Agent profiles.
 */
class AgentManager(private val context: Context) {
    private val _agents = mutableStateListOf<AgentProfile>()
    val agents: List<AgentProfile> = _agents
    private val prefs = context.getSharedPreferences("agents", Context.MODE_PRIVATE)

    init {
        _agents.addAll(DefaultAgents.All)
        loadCustomAgents()
    }

    fun addAgent(profile: AgentProfile) {
        _agents.add(profile)
        saveCustomAgents()
    }

    fun updateAgent(profile: AgentProfile) {
        val index = _agents.indexOfFirst { it.id == profile.id }
        if (index != -1) {
            _agents[index] = profile
            saveCustomAgents()
        }
    }

    fun removeAgent(agentId: String) {
        // Only allow removing custom agents (not in DefaultAgents.All)
        if (DefaultAgents.All.none { it.id == agentId }) {
            _agents.removeAll { it.id == agentId }
            saveCustomAgents()
        }
    }

    private fun saveCustomAgents() {
        val customAgents = _agents.filter { agent ->
            DefaultAgents.All.none { it.id == agent.id }
        }
        val array = JSONArray()
        customAgents.forEach { agent ->
            array.put(JSONObject().apply {
                put("id", agent.id)
                put("name", agent.name)
                put("command", agent.command)
                put("installCommand", agent.installCommand)
                put("prepareCommand", agent.prepareCommand ?: JSONObject.NULL)
                put("defaultArgs", JSONArray(agent.defaultArgs))
                put("headlessPromptArgs", agent.headlessPromptArgs?.let { JSONArray(it) } ?: JSONObject.NULL)
            })
        }
        prefs.edit { putString("custom_agents", array.toString()) }
    }

    private fun loadCustomAgents() {
        val json = prefs.getString("custom_agents", null) ?: return
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                // Avoid duplicates if somehow persisted multiple times or overlaps with default
                if (_agents.none { it.id == id }) {
                    _agents.add(AgentProfile(
                        id = id,
                        name = obj.getString("name"),
                        command = obj.getString("command"),
                        installCommand = obj.getString("installCommand"),
                        prepareCommand = obj.optString("prepareCommand").takeIf { it.isNotBlank() },
                        defaultArgs = obj.optJSONArray("defaultArgs")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: emptyList(),
                        headlessPromptArgs = obj.optJSONArray("headlessPromptArgs")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        }
                    ))
                }
            }
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
