package com.justnels.agenticdroid.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.workspace.McpServer
import com.justnels.agenticdroid.workspace.Project

/**
 * Manages a project's `.mcp.json` (see McpConfigStore) - extends what an agent can do
 * without any app-specific plugin plumbing, since Claude Code and Codex both discover this
 * file automatically from the project directory they're launched in.
 */
@Composable
fun ProjectMcpServersDialog(
    project: Project,
    servers: List<McpServer>,
    onSetServer: (McpServer) -> Unit,
    onRemoveServer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    // Adding a name that already exists intentionally replaces that entry (see
    // McpConfigStore.addOrUpdate) rather than being rejected as a duplicate.
    val validName = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MCP Servers for ${project.name}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Written to .mcp.json - read automatically by Claude Code and Codex when launched in this project. Antigravity may not use this file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (servers.isEmpty()) {
                    Text(
                        text = "No MCP servers configured yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(servers, key = { it.name }) { server ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = server.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = (listOf(server.command) + server.args).joinToString(" "),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onRemoveServer(server.name) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove ${server.name}", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("Server name") },
                    placeholder = { Text("filesystem") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command") },
                    placeholder = { Text("npx") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    label = { Text("Arguments") },
                    placeholder = { Text("-y @modelcontextprotocol/server-filesystem .") },
                    supportingText = { Text("Space-separated") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        onSetServer(
                            McpServer(
                                name = name,
                                command = command.trim(),
                                args = argsText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                            )
                        )
                        name = ""
                        command = ""
                        argsText = ""
                    },
                    enabled = validName && command.isNotBlank(),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Add / Update")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
