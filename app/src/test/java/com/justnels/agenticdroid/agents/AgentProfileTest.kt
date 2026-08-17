package com.justnels.agenticdroid.agents

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
}
