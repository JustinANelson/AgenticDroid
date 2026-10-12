package com.justnels.agenticdroid.workspace

import com.justnels.agenticdroid.agents.DefaultAgents
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentGuidanceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun rendersTypeSpecificGuidanceAndProjectCommands() {
        val android = AgentGuidance.render(
            ProjectType.ANDROID, ProjectRunnerAction.defaultActionsFor(ProjectType.ANDROID)
        )
        assertTrue(android.contains("no sudo"))
        assertTrue(android.contains("testDebugUnitTest"))
        assertTrue(android.contains("./gradlew assembleDebug"))
        val web = AgentGuidance.render(ProjectType.WEB, emptyList())
        assertTrue(web.contains("npm"))
        assertFalse(web.contains("gradlew"))
    }

    @Test
    fun seedFilesNeverOverwritesExistingGuidance() {
        val dir = tmp.newFolder()
        File(dir, "CLAUDE.md").writeText("mine")
        AgentGuidance.seedFiles(dir, ProjectType.PYTHON, emptyList())
        assertEquals("mine", File(dir, "CLAUDE.md").readText())
        assertTrue(File(dir, "AGENTS.md").readText().contains("Python project"))
    }

    @Test
    fun guidanceIsAppendedToClaudeSystemPromptOnly() {
        val guided = DefaultAgents.Claude.withHeadlessGuidance("G")
        assertEquals(
            listOf("claude", "-p", "--output-format", "stream-json", "--verbose", "--permission-mode", "bypassPermissions", "--append-system-prompt", "G", "x"),
            guided.headlessArgv("x", allowShell = true)
        )
        assertEquals(DefaultAgents.Codex, DefaultAgents.Codex.withHeadlessGuidance("G"))
    }
}
