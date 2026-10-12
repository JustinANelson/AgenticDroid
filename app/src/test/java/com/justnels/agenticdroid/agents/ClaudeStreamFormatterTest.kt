package com.justnels.agenticdroid.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class ClaudeStreamFormatterTest {
    @Test
    fun formatsTextToolUseErrorsAndResult() {
        assertEquals(
            "Looking.\n> Bash: ./gradlew test",
            ClaudeStreamFormatter.format(
                """{"type":"assistant","message":{"content":[{"type":"text","text":"Looking."},{"type":"tool_use","name":"Bash","input":{"command":"./gradlew test"}}]}}"""
            )
        )
        assertEquals(
            "  ! boom",
            ClaudeStreamFormatter.format(
                """{"type":"user","message":{"content":[{"type":"tool_result","is_error":true,"content":"boom"}]}}"""
            )
        )
        assertNull(
            ClaudeStreamFormatter.format(
                """{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}"""
            )
        )
        assertNull(ClaudeStreamFormatter.format("""{"type":"system","subtype":"init"}"""))
        assertEquals(
            "[run complete (3 turns)]",
            ClaudeStreamFormatter.format("""{"type":"result","is_error":false,"num_turns":3}""")
        )
        assertEquals("plain output", ClaudeStreamFormatter.format("plain output"))
    }

    @Test
    fun wrapStreamsFormattedLines() {
        val input = "{\"type\":\"system\"}\nhello\n{\"type\":\"result\",\"num_turns\":1}\n"
        val out = ClaudeStreamFormatter.wrap(ByteArrayInputStream(input.toByteArray())).readBytes().decodeToString()
        assertEquals("hello\n[run complete (1 turns)]\n", out)
    }
}
