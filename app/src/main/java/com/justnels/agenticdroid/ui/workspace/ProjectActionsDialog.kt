package com.justnels.agenticdroid.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.env.RunnerPackageGroup
import com.justnels.agenticdroid.workspace.Project
import com.justnels.agenticdroid.workspace.ProjectMetadata
import com.justnels.agenticdroid.workspace.ProjectRunnerAction
import com.justnels.agenticdroid.workspace.ProjectType

@Composable
fun ProjectActionsDialog(
    project: Project,
    projectType: ProjectType,
    actions: List<ProjectRunnerAction>,
    metadata: ProjectMetadata,
    missingRunnerGroups: Set<RunnerPackageGroup> = emptySet(),
    onInstallMissingRunners: () -> Unit = {},
    onManageSecrets: () -> Unit = {},
    onManageMcpServers: () -> Unit = {},
    onDismiss: () -> Unit,
    onExecuteAction: (ProjectRunnerAction) -> Unit,
    onSaveMetadata: (ProjectMetadata) -> Unit
) {
    var isConfiguring by remember { mutableStateOf(false) }

    var selectedType by remember { mutableStateOf(projectType) }
    var customRunCmd by remember { mutableStateOf(metadata.customRunCommand ?: "") }
    var customBuildCmd by remember { mutableStateOf(metadata.customBuildCommand ?: "") }
    var customPreviewUrl by remember { mutableStateOf(metadata.previewUrl ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isConfiguring) "Configure Project" else "${project.name} Actions",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (!isConfiguring) {
                    IconButton(onClick = onManageSecrets) {
                        Icon(Icons.Default.Key, contentDescription = "Manage Secrets")
                    }
                    IconButton(onClick = onManageMcpServers) {
                        Icon(Icons.Default.Extension, contentDescription = "Manage MCP Servers")
                    }
                }
                IconButton(onClick = { isConfiguring = !isConfiguring }) {
                    Icon(
                        imageVector = if (isConfiguring) Icons.Default.List else Icons.Default.Tune,
                        contentDescription = if (isConfiguring) "Show Actions" else "Configure"
                    )
                }
            }
        },
        text = {
            if (isConfiguring) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Project Environment Type", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ProjectType.entries.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                shape = SegmentedButtonDefaults.itemShape(index, ProjectType.entries.size)
                            ) {
                                Text(
                                    text = when (type) {
                                        ProjectType.ANDROID -> "Android"
                                        ProjectType.WEB -> "Web"
                                        ProjectType.NODE_JS -> "Node"
                                        ProjectType.PYTHON -> "Python"
                                        ProjectType.JVM -> "JVM"
                                        ProjectType.RUST -> "Rust"
                                        ProjectType.GOLANG -> "Go"
                                        ProjectType.SSG -> "SSG"
                                        ProjectType.CPP -> "C/C++"
                                        ProjectType.CUSTOM -> "Custom"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customRunCmd,
                        onValueChange = { customRunCmd = it },
                        label = { Text("Run Command") },
                        placeholder = { Text(when (selectedType) {
                            ProjectType.WEB -> "npm run dev -- --host"
                            ProjectType.NODE_JS -> "node index.js"
                            ProjectType.PYTHON -> "python main.py"
                            ProjectType.JVM -> "java -jar app.jar"
                            ProjectType.RUST -> "cargo run"
                            ProjectType.GOLANG -> "go run ."
                            ProjectType.SSG -> "hugo serve"
                            ProjectType.CPP -> "./a.out"
                            ProjectType.ANDROID -> "./gradlew assembleDebug"
                            ProjectType.CUSTOM -> "npm start"
                        }) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customBuildCmd,
                        onValueChange = { customBuildCmd = it },
                        label = { Text("Build Command") },
                        placeholder = { Text(when (selectedType) {
                            ProjectType.WEB -> "npm run build"
                            ProjectType.RUST -> "cargo build"
                            ProjectType.GOLANG -> "go build ."
                            ProjectType.JVM -> "kotlinc Main.kt -include-runtime -d app.jar"
                            ProjectType.SSG -> "hugo"
                            ProjectType.ANDROID -> "./gradlew assembleDebug"
                            else -> "make"
                        }) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customPreviewUrl,
                        onValueChange = { customPreviewUrl = it },
                        label = { Text("Preview URL") },
                        placeholder = { Text(selectedType.defaultPreviewUrl) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = projectType.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = projectType.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (missingRunnerGroups.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Missing runner: ${missingRunnerGroups.joinToString { it.displayName }}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Some of these actions won't work until it's installed.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                TextButton(
                                    onClick = onInstallMissingRunners,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Install Now")
                                }
                            }
                        }
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 340.dp)
                    ) {
                        items(actions) { action ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onExecuteAction(action)
                                        onDismiss()
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (action.opensPreview) MaterialTheme.colorScheme.secondaryContainer
                                    else if (action.isBuild) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (action.iconName) {
                                            "build" -> Icons.Default.Build
                                            "preview" -> Icons.Default.Language
                                            "install" -> Icons.Default.Download
                                            "clean" -> Icons.Default.CleaningServices
                                            else -> Icons.Default.PlayArrow
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = action.label,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        if (action.command.isNotBlank()) {
                                            Text(
                                                text = action.command,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (action.description.isNotBlank()) {
                                            Text(
                                                text = action.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isConfiguring) {
                Button(onClick = {
                    val updated = ProjectMetadata(
                        type = selectedType,
                        customRunCommand = customRunCmd.trim().takeIf(String::isNotBlank),
                        customBuildCommand = customBuildCmd.trim().takeIf(String::isNotBlank),
                        previewUrl = customPreviewUrl.trim().takeIf(String::isNotBlank)
                    )
                    onSaveMetadata(updated)
                    isConfiguring = false
                }) {
                    Text("Save Config")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
        dismissButton = {
            if (isConfiguring) {
                TextButton(onClick = { isConfiguring = false }) {
                    Text("Back to Actions")
                }
            }
        }
    )
}
