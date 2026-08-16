package com.justnels.agenticdroid.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.agents.AgentProfile
import com.justnels.agenticdroid.ui.components.HintBox

@Composable
fun AgentLauncherScreen(
    agents: List<AgentProfile>,
    hintsShown: Set<String>,
    onLaunchAgent: (AgentProfile) -> Unit,
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
            text = "Tap 'Launch' to start an agent. They'll install themselves on the first run and open in an interactive terminal session.",
            hintsShown = hintsShown,
            onDismiss = onDismissHint
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(agents) { agent ->
                AgentCard(
                    agent = agent,
                    onLaunch = { onLaunchAgent(agent) }
                )
            }
        }
    }
}

@Composable
fun AgentCard(
    agent: AgentProfile,
    onLaunch: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
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
            }
            
            Button(onClick = onLaunch) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Launch")
            }
        }
    }
}
