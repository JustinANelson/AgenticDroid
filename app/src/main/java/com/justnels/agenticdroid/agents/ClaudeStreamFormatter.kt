package com.justnels.agenticdroid.agents

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Turns Claude Code's `--output-format stream-json` events into a readable transcript, so a
 * background run's log shows progress (text and tool calls) as it happens instead of
 * nothing until exit. Non-JSON lines pass through untouched.
 */
object ClaudeStreamFormatter {
    private const val DETAIL_LIMIT = 200

    /** The formatted text for one output line (without trailing newline), or null to drop it. */
    fun format(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val event = runCatching { JSONObject(trimmed) }.getOrNull() ?: return line
        return when (event.optString("type")) {
            "assistant" -> formatBlocks(event.optJSONObject("message")?.optJSONArray("content"))
            "user" -> formatToolResults(event.optJSONObject("message")?.optJSONArray("content"))
            "result" -> {
                val turns = event.optInt("num_turns", -1).takeIf { it >= 0 }?.let { " ($it turns)" }.orEmpty()
                if (event.optBoolean("is_error")) {
                    "[run ended with an error$turns: ${event.optString("result").take(DETAIL_LIMIT * 2)}]"
                } else {
                    "[run complete$turns]"
                }
            }
            else -> null
        }
    }

    private fun formatBlocks(blocks: JSONArray?): String? {
        if (blocks == null) return null
        val lines = (0 until blocks.length()).mapNotNull { i ->
            val block = blocks.optJSONObject(i) ?: return@mapNotNull null
            when (block.optString("type")) {
                "text" -> block.optString("text").trim().takeIf(String::isNotEmpty)
                "tool_use" -> "> ${block.optString("name")}: ${toolDetail(block.optJSONObject("input"))}"
                else -> null
            }
        }
        return lines.joinToString("\n").takeIf(String::isNotEmpty)
    }

    private fun toolDetail(input: JSONObject?): String {
        if (input == null) return ""
        val key = listOf("command", "file_path", "path", "pattern", "url", "description")
            .firstOrNull { input.has(it) }
        val value = key?.let { input.optString(it) } ?: input.toString()
        return value.replace('\n', ' ').take(DETAIL_LIMIT)
    }

    /** Only failed tool results are shown; successful ones are usually long and noisy. */
    private fun formatToolResults(blocks: JSONArray?): String? {
        if (blocks == null) return null
        val lines = (0 until blocks.length()).mapNotNull { i ->
            val block = blocks.optJSONObject(i) ?: return@mapNotNull null
            if (block.optString("type") != "tool_result" || !block.optBoolean("is_error")) return@mapNotNull null
            val content = block.opt("content")
            val text = if (content is JSONArray) {
                (0 until content.length()).joinToString(" ") { content.optJSONObject(it)?.optString("text").orEmpty() }
            } else content?.toString().orEmpty()
            "  ! ${text.replace('\n', ' ').take(DETAIL_LIMIT)}"
        }
        return lines.joinToString("\n").takeIf(String::isNotEmpty)
    }

    /** Wraps [source] so each complete line is replaced by [format]'s output. */
    fun wrap(source: InputStream): InputStream = object : InputStream() {
        private val reader = source.bufferedReader()
        private var current: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        private var eof = false

        private fun fill(): Boolean {
            while (current.available() == 0) {
                if (eof) return false
                val line = reader.readLine()
                if (line == null) { eof = true; return false }
                format(line)?.let { current = ByteArrayInputStream((it + "\n").toByteArray()) }
            }
            return true
        }

        override fun read(): Int = if (fill()) current.read() else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (len == 0) 0 else if (fill()) current.read(b, off, len) else -1

        override fun close() = source.close()
    }
}
