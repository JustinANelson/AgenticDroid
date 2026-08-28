package com.justnels.agenticdroid.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProfileTest {
    @Test
    fun defaultAgentsInstallOnlyWhenTheirCommandIsMissing() {
        DefaultAgents.All.forEach { agent ->
            val launch = agent.launchCommand()
            assertTrue(launch.contains("command -v ${agent.command}"))
            agent.installCommand.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { installLine -> assertTrue(launch.contains(installLine)) }
            assertTrue(launch.contains("exec ${agent.command}"))
        }
    }

    @Test
    fun defaultAgentsUseExpectedUpstreamDistributionEndpoints() {
        assertTrue(DefaultAgents.Codex.installCommand.contains("@openai/codex"))
        assertTrue(DefaultAgents.Claude.installCommand.contains("@anthropic-ai/claude-code"))
        // Antigravity's official installer cannot run directly in the Android/QEMU
        // environment; the profile consumes Google's updater manifest instead.
        assertTrue(DefaultAgents.Antigravity.installCommand.contains("antigravity-cli-auto-updater"))
        assertTrue(DefaultAgents.Antigravity.installCommand.contains("/manifests/"))
    }

    @Test
    fun installedVersionCommandRedirectsStdinAndChecksExitStatus() {
        // A bare `<command> --version` blocks forever on some agents (confirmed: agy)
        // when reading from a live PTY - stdin must always be /dev/null.
        DefaultAgents.All.forEach { agent ->
            val cmd = agent.installedVersionCommand()
            assertTrue(cmd.startsWith("${agent.command} --version"))
            assertTrue(cmd.contains("</dev/null"))
        }
    }

    @Test
    fun latestVersionCommandOnlySetForNpmDistributedAgents() {
        assertEquals(
            """node "${'$'}NPM_CLI" view @openai/codex version 2>&1""",
            DefaultAgents.Codex.latestVersionCommand()
        )
        assertEquals(
            """node "${'$'}NPM_CLI" view @anthropic-ai/claude-code version 2>&1""",
            DefaultAgents.Claude.latestVersionCommand()
        )
        assertEquals(
            """node "${'$'}NPM_CLI" view @google/gemini-cli version 2>&1""",
            DefaultAgents.Gemini.latestVersionCommand()
        )
        // Antigravity isn't npm-distributed and has no separate version-query endpoint.
        assertNull(DefaultAgents.Antigravity.latestVersionCommand())
    }

    @Test
    fun updateCommandForcesTheSameInstallLogicUnconditionally() {
        DefaultAgents.All.forEach { agent ->
            assertEquals(agent.installCommand, agent.updateCommand())
        }
    }
}
