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
    fun defaultAgentsUseOfficialNativeInstallers() {
        assertTrue(DefaultAgents.Codex.installCommand.contains("chatgpt.com/codex/install.sh"))
        assertTrue(DefaultAgents.Claude.installCommand.contains("claude.ai/install.sh"))
        assertTrue(DefaultAgents.Antigravity.installCommand.contains("antigravity.google/cli/install.sh"))
    }
}
