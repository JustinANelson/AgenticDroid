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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.workspace.Project
import com.justnels.agenticdroid.workspace.ProjectSecretsStore

/**
 * Project-scoped secrets (API keys, tokens) an agent or build/run action needs - injected
 * as environment variables rather than requiring them typed into a shell `export` by hand
 * or pasted into an agent's onboarding prompt. Stored encrypted; see ProjectSecretsStore.
 */
@Composable
fun ProjectSecretsDialog(
    project: Project,
    secrets: Map<String, String>,
    onSetSecret: (String, String) -> Unit,
    onRemoveSecret: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    val validName = ProjectSecretsStore.NAME_PATTERN.matches(newName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Secrets for ${project.name}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Exported into the shell before build/run actions and agent launches in this project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (secrets.isEmpty()) {
                    Text(
                        text = "No secrets yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(secrets.keys.sorted()) { name ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = name, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                IconButton(onClick = { onRemoveSecret(name) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove $name", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.trim() },
                    label = { Text("Name") },
                    placeholder = { Text("ANTHROPIC_API_KEY") },
                    isError = newName.isNotEmpty() && !validName,
                    supportingText = {
                        if (newName.isNotEmpty() && !validName) {
                            Text("Must be a valid shell identifier: letters, digits, underscore, not starting with a digit")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newValue,
                    onValueChange = { newValue = it },
                    label = { Text("Value") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        onSetSecret(newName, newValue)
                        newName = ""
                        newValue = ""
                    },
                    enabled = validName && newValue.isNotEmpty(),
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
