package com.justnels.agenticdroid.agents.compat

import org.json.JSONObject

/**
 * Outcome of running an agent's `--version` self-test (see [AgentCompatChecker]) - the
 * same invocation the QEMU-wrapped native binaries (Codex/Claude) or the QEMU-wrapped
 * Antigravity binary already have to succeed at to run at all, so a failure here is a
 * direct signal that the on-device toolchain wrapping (see AgentProfile.kt's
 * npmMuslAgentInstallCommand/codexInstallCommand/antigravityInstallCommand) has broken,
 * not that the agent itself is misbehaving.
 */
enum class CompatStatus { OK, BROKEN, TIMEOUT }

/**
 * One recorded compatibility check for one agent, persisted by [CompatCheckStore] so a
 * regression (an agent that was OK yesterday and is BROKEN today) is visible as history,
 * not just as a single point-in-time status.
 */
data class CompatCheckResult(
    val id: String,
    val agentId: String,
    val agentName: String,
    val checkedAt: Long,
    val status: CompatStatus,
    val exitCode: Int?,
    val outputSnippet: String,
    val environmentLabel: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("agentId", agentId)
        put("agentName", agentName)
        put("checkedAt", checkedAt)
        put("status", status.name)
        put("exitCode", exitCode ?: JSONObject.NULL)
        put("outputSnippet", outputSnippet)
        put("environmentLabel", environmentLabel)
    }

    companion object {
        fun fromJson(json: JSONObject): CompatCheckResult = CompatCheckResult(
            id = json.getString("id"),
            agentId = json.getString("agentId"),
            agentName = json.getString("agentName"),
            checkedAt = json.getLong("checkedAt"),
            status = runCatching { CompatStatus.valueOf(json.getString("status")) }
                .getOrDefault(CompatStatus.BROKEN),
            exitCode = if (json.isNull("exitCode")) null else json.getInt("exitCode"),
            outputSnippet = json.optString("outputSnippet", ""),
            environmentLabel = json.optString("environmentLabel", "Local")
        )
    }
}
