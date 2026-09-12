package com.justnels.agenticdroid.agents.compat

import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists [CompatCheckResult] history as a single flat JSON index, mirroring
 * [com.justnels.agenticdroid.agents.HeadlessRunStore]'s reasoning: the access pattern
 * ("list everything", "latest for one agent") doesn't warrant a database, and results are
 * small enough to keep inline rather than pointing at per-check log files. Takes a plain
 * [File] rather than a `Context` so it's constructible in a JVM unit test.
 */
class CompatCheckStore(baseDir: File) {
    constructor(context: android.content.Context) : this(context.filesDir)

    private val dir = File(baseDir, "compat_checks").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val lock = Any()

    fun listAll(): List<CompatCheckResult> = synchronized(lock) {
        readAllUnsorted().sortedByDescending { it.checkedAt }
    }

    fun latestFor(agentId: String): CompatCheckResult? = synchronized(lock) {
        readAllUnsorted().filter { it.agentId == agentId }.maxByOrNull { it.checkedAt }
    }

    /** Appends [result] and trims that agent's history to the most recent
     * [MAX_PER_AGENT] entries - unlike [com.justnels.agenticdroid.agents.HeadlessRunStore],
     * these accumulate on a schedule rather than one-per-user-action, so left untrimmed the
     * index would grow without bound. */
    fun record(result: CompatCheckResult) = synchronized(lock) {
        val existing = readAllUnsorted()
        val forOtherAgents = existing.filterNot { it.agentId == result.agentId }
        val forThisAgent = (existing.filter { it.agentId == result.agentId } + result)
            .sortedByDescending { it.checkedAt }
            .take(MAX_PER_AGENT)
        writeIndex(forOtherAgents + forThisAgent)
    }

    private fun readAllUnsorted(): List<CompatCheckResult> {
        if (!indexFile.exists()) return emptyList()
        val text = runCatching { indexFile.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching { CompatCheckResult.fromJson(array.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun writeIndex(results: List<CompatCheckResult>) {
        val array = JSONArray()
        results.forEach { array.put(it.toJson()) }
        val temp = File(dir, "index.json.tmp")
        temp.writeText(array.toString())
        Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val MAX_PER_AGENT = 15
    }
}
