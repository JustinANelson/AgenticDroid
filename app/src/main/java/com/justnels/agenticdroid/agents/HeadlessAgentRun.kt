package com.justnels.agenticdroid.agents

import org.json.JSONObject

/** Lifecycle state of a [HeadlessAgentRun]. RUNNING is the only state a live process is
 * still attached to; the rest are terminal and only differ in how the run ended. */
enum class HeadlessRunStatus {
    RUNNING, SUCCEEDED, FAILED, KILLED, TIMED_OUT
}

/**
 * Metadata for one unattended (non-PTY) agent invocation, e.g. `claude -p "<prompt>"`,
 * started by [HeadlessAgentRunService] and persisted by [HeadlessRunStore] so it survives
 * app/process death - the whole point being a user can background the phone mid-run and
 * come back to a notification plus a reopenable transcript, rather than the run existing
 * only inside a live terminal PTY the user has to keep watching.
 *
 * The prompt/response text itself is never embedded here - only a pointer
 * ([HeadlessRunStore.logFile]) to the on-disk transcript, so this metadata stays cheap to
 * keep an in-memory list of and to re-serialize on every status change.
 */
data class HeadlessAgentRun(
    val id: String,
    val agentId: String,
    val agentName: String,
    val prompt: String,
    val projectPath: String,
    val workingDirectory: String,
    val environmentLabel: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: HeadlessRunStatus = HeadlessRunStatus.RUNNING,
    val exitCode: Int? = null,
    val truncated: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("agentId", agentId)
        put("agentName", agentName)
        put("prompt", prompt)
        put("projectPath", projectPath)
        put("workingDirectory", workingDirectory)
        put("environmentLabel", environmentLabel)
        put("startedAt", startedAt)
        put("finishedAt", finishedAt ?: JSONObject.NULL)
        put("status", status.name)
        put("exitCode", exitCode ?: JSONObject.NULL)
        put("truncated", truncated)
    }

    companion object {
        fun fromJson(json: JSONObject): HeadlessAgentRun = HeadlessAgentRun(
            id = json.getString("id"),
            agentId = json.getString("agentId"),
            agentName = json.getString("agentName"),
            prompt = json.getString("prompt"),
            projectPath = json.optString("projectPath", ""),
            workingDirectory = json.optString("workingDirectory", ""),
            environmentLabel = json.optString("environmentLabel", "Local"),
            startedAt = json.getLong("startedAt"),
            finishedAt = if (json.isNull("finishedAt")) null else json.getLong("finishedAt"),
            status = runCatching { HeadlessRunStatus.valueOf(json.getString("status")) }
                .getOrDefault(HeadlessRunStatus.FAILED),
            exitCode = if (json.isNull("exitCode")) null else json.getInt("exitCode"),
            truncated = json.optBoolean("truncated", false)
        )
    }
}
