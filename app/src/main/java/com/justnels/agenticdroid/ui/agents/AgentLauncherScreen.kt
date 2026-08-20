package com.justnels.agenticdroid.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.agents.AgentProfile
import com.justnels.agenticdroid.agents.AgentVersionInfo
import com.justnels.agenticdroid.ui.components.HintBox

@Composable
fun AgentLauncherScreen(
    agents: List<AgentProfile>,
    activeAgent: AgentProfile?,
    installedAgentIds: Set<String>,
    isCheckingInstalled: Boolean,
    agentVersions: Map<String, AgentVersionInfo> = emptyMap(),
    checkingVersionForAgentId: String? = null,
    updatingAgentId: String? = null,
    onCheckVersion: (AgentProfile) -> Unit = {},
    onUpdateAgent: (AgentProfile) -> Unit = {},
    hintsShown: Set<String>,
    onLaunchAgent: (AgentProfile) -> Unit,
    onStopAgent: () -> Unit,
    onDismissHint: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "AI Agents",
            style = MaterialTheme.typography.headlineMedium
        )

        HintBox(
            hintId = "hint_agent_launcher",
            title = "Interactive Agents",
            text = "Tap 'Launch' to start an agent. They'll install themselves on the first run and open in an interactive terminal session. Only one agent can run at a time - stop the active one before launching another.",
            hintsShown = hintsShown,
            onDismiss = onDismissHint
        )

        if (activeAgent != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${activeAgent.name} is running", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onStopAgent) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(agents) { agent ->
                AgentCard(
                    agent = agent,
                    isActive = activeAgent?.id == agent.id,
                    isBlocked = activeAgent != null && activeAgent.id != agent.id,
                    isInstalled = agent.id in installedAgentIds,
                    isCheckingInstalled = isCheckingInstalled,
                    versionInfo = agentVersions[agent.id],
                    isCheckingVersion = checkingVersionForAgentId == agent.id,
                    isUpdating = updatingAgentId == agent.id,
                    onLaunch = { onLaunchAgent(agent) },
                    onCheckVersion = { onCheckVersion(agent) },
                    onUpdate = { onUpdateAgent(agent) }
                )
            }
        }
    }
}

@Composable
fun AgentCard(
    agent: AgentProfile,
    isActive: Boolean,
    isBlocked: Boolean,
    isInstalled: Boolean,
    isCheckingInstalled: Boolean,
    versionInfo: AgentVersionInfo? = null,
    isCheckingVersion: Boolean = false,
    isUpdating: Boolean = false,
    onLaunch: () -> Unit,
    onCheckVersion: () -> Unit = {},
    onUpdate: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = agent.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Command: ${agent.command}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            isCheckingInstalled -> "Checking..."
                            isInstalled -> "Installed"
                            else -> "Not installed"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (versionInfo?.installed != null) {
                        Text(
                            text = "Version: ${versionInfo.installed.lineSequence().first().take(60)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (versionInfo?.updateAvailable == true) {
                        Text(
                            text = "Update available (latest: ${versionInfo.latest})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Button(onClick = onLaunch, enabled = !isBlocked) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when {
                            isActive -> "Resume"
                            isInstalled -> "Launch"
                            else -> "Install & Launch"
                        }
                    )
                }
            }

            if (isInstalled) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = onCheckVersion, enabled = !isCheckingVersion && !isUpdating) {
                        Text(if (isCheckingVersion) "Checking..." else "Check for Updates")
                    }
                    if (versionInfo?.updateAvailable == true) {
                        TextButton(onClick = onUpdate, enabled = !isUpdating) {
                            Text(if (isUpdating) "Updating..." else "Update")
                        }
                    }
                    TextButton(onClick = onUpdate, enabled = !isUpdating) {
                        Text(if (isUpdating) "Repairing..." else "Repair")
                    }
                }
            }
        }
    }
}
