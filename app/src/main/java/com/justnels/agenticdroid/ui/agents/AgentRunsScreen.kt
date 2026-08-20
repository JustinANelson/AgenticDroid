package com.justnels.agenticdroid.ui.agents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.agents.HeadlessAgentRun
import com.justnels.agenticdroid.agents.HeadlessRunStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Lists past and in-progress headless agent runs ([HeadlessAgentRun]), and lets a user
 * drill into one to read its captured transcript or stop it early. This is the surface
 * that makes background runs (see [com.justnels.agenticdroid.agents.HeadlessAgentRunService])
 * legible after the fact - without it, a completed run would only ever be visible as a
 * notification the user might have already dismissed.
 */
@Composable
fun AgentRunsScreen(
    runs: List<HeadlessAgentRun>,
    isRunning: (String) -> Boolean,
    onKill: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    readLog: (String) -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(2000)
        }
    }

    val selected = runs.firstOrNull { it.id == selectedId }
    if (selected != null) {
        AgentRunDetailView(
            run = selected,
            isRunning = isRunning(selected.id),
            readLog = readLog,
            onBack = { selectedId = null },
            onKill = { onKill(selected.id) }
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(text = "Agent Runs", style = MaterialTheme.typography.headlineMedium)
        }

        if (runs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No background runs yet. Use \"Run in background\" on an agent to send it a\nsingle prompt and let it work unattended.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(runs, key = { it.id }) { run ->
                RunCard(
                    run = run,
                    isRunning = isRunning(run.id),
                    onClick = { selectedId = run.id },
                    onKill = { onKill(run.id) },
                    onDelete = { onDelete(run.id) }
                )
            }
        }
    }
}

@Composable
private fun RunCard(
    run: HeadlessAgentRun,
    isRunning: Boolean,
    onClick: () -> Unit,
    onKill: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(run.agentName, style = MaterialTheme.typography.titleMedium)
                StatusBadge(run.status)
            }
            Text(
                text = run.prompt.take(160),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
            Text(
                text = "${run.environmentLabel} - ${formatTime(run.startedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.padding(top = 6.dp)) {
                if (isRunning) {
                    TextButton(onClick = onKill) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                } else {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRunDetailView(
    run: HeadlessAgentRun,
    isRunning: Boolean,
    readLog: (String) -> String,
    onBack: () -> Unit,
    onKill: () -> Unit
) {
    // A run's log can be up to MAX_LOG_BYTES (4 MB) - reading it off the main thread, and
    // only re-reading on a timer while the run is still active, keeps this screen from
    // doing a large disk read on every 2-second parent recomposition (see
    // AgentRunsScreen's polling LaunchedEffect) for a finished run whose log never changes.
    var log by remember(run.id) { mutableStateOf("") }
    LaunchedEffect(run.id, isRunning) {
        do {
            log = withContext(Dispatchers.IO) { readLog(run.id) }
            if (isRunning) delay(2000)
        } while (isRunning)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(run.agentName, style = MaterialTheme.typography.titleLarge)
                StatusBadge(run.status)
            }
            if (isRunning) {
                TextButton(onClick = onKill) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("Stop")
                }
            }
        }

        Text(
            text = "Prompt",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        SelectionContainer {
            Text(run.prompt, style = MaterialTheme.typography.bodyMedium)
        }

        Text(
            text = "${run.environmentLabel} - ${run.workingDirectory}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (run.exitCode != null) {
            Text(
                text = "Exit code: ${run.exitCode}" + if (run.truncated) " (output truncated)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = "Output",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 4.dp)
        ) {
            SelectionContainer {
                Text(
                    text = log.ifBlank { if (isRunning) "Waiting for output..." else "(no output captured)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: HeadlessRunStatus) {
    val (label, color) = when (status) {
        HeadlessRunStatus.RUNNING -> "Running" to MaterialTheme.colorScheme.primary
        HeadlessRunStatus.SUCCEEDED -> "Succeeded" to MaterialTheme.colorScheme.tertiary
        HeadlessRunStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        HeadlessRunStatus.KILLED -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
        HeadlessRunStatus.TIMED_OUT -> "Timed out" to MaterialTheme.colorScheme.error
    }
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
