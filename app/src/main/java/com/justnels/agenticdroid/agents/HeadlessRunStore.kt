package com.justnels.agenticdroid.agents

import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists [HeadlessAgentRun] metadata (an index JSON file) and each run's captured
 * stdout/stderr (one log file per run ID) under the app's private files directory, so a
 * run started while the app was open is still visible - status, exit code, and transcript
 * - after the process/app was killed and relaunched. Deliberately flat files rather than a
 * database: the access pattern is "list everything" and "read one log", both of which a
 * handful of small files serve without adding a Room dependency for one feature. Takes a
 * plain [File] rather than a `Context` so it's constructible in a JVM unit test (this
 * project has no Robolectric dependency) via a temp directory.
 */
class HeadlessRunStore(baseDir: File) {
    constructor(context: android.content.Context) : this(context.filesDir)

    private val dir = File(baseDir, "headless_runs").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val lock = Any()

    fun listAll(): List<HeadlessAgentRun> = synchronized(lock) {
        if (!indexFile.exists()) return emptyList()
        val text = runCatching { indexFile.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        (0 until array.length()).mapNotNull { i ->
            runCatching { HeadlessAgentRun.fromJson(array.getJSONObject(i)) }.getOrNull()
        }.sortedByDescending { it.startedAt }
    }

    fun save(run: HeadlessAgentRun) = synchronized(lock) {
        val existing = listAllUnsorted().filterNot { it.id == run.id }
        writeIndex(existing + run)
    }

    fun delete(id: String) = synchronized(lock) {
        writeIndex(listAllUnsorted().filterNot { it.id == id })
        logFile(id).delete()
    }

    fun logFile(id: String): File = File(dir, "$id.log")

    fun readLog(id: String, maxBytes: Int = 512 * 1024): String {
        val file = logFile(id)
        if (!file.exists()) return ""
        val length = file.length()
        if (length <= maxBytes) return file.readText()
        // Tail the file rather than the head - the most recent output (including any
        // final error) is what a user reopening a long run cares about most.
        return file.inputStream().use { input ->
            input.skip(length - maxBytes)
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun listAllUnsorted(): List<HeadlessAgentRun> {
        if (!indexFile.exists()) return emptyList()
        val text = runCatching { indexFile.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching { HeadlessAgentRun.fromJson(array.getJSONObject(i)) }.getOrNull()
        }
    }

    private fun writeIndex(runs: List<HeadlessAgentRun>) {
        val array = JSONArray()
        runs.forEach { array.put(it.toJson()) }
        val temp = File(dir, "index.json.tmp")
        temp.writeText(array.toString())
        // `File.renameTo` can silently fail (returning false, which was previously
        // ignored) when the destination already exists - true on Windows, and not
        // guaranteed elsewhere either - which would leave every save/delete after the
        // first one writing a temp file nobody reads. REPLACE_EXISTING makes the overwrite
        // unconditional and the move atomic.
        Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
