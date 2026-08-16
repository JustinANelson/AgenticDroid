package com.justnels.agenticdroid.ui.env

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
import androidx.work.WorkInfo
import com.justnels.agenticdroid.env.EnvironmentConfig
import com.justnels.agenticdroid.env.EnvironmentManager
import com.justnels.agenticdroid.env.SSHConfig
import androidx.compose.runtime.livedata.observeAsState
import com.justnels.agenticdroid.MainViewModel
import com.justnels.agenticdroid.ui.components.HintBox

@Composable
fun EnvironmentScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val manager = viewModel.environmentManager
    var showAddSSHDialog by remember { mutableStateOf(false) }
    
    val workInfo by viewModel.bootstrapWorkInfo.observeAsState()

    val isBootstrapping = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
    val lastError = workInfo?.outputData?.getString("error")
    val bootstrapStatus = workInfo?.progress?.getString("status") ?: "Queued..."
    val bootstrapSuccess = workInfo?.state == WorkInfo.State.SUCCEEDED

    // Refresh installed status when bootstrap finishes
    LaunchedEffect(bootstrapSuccess) {
        if (bootstrapSuccess) {
            viewModel.refreshNodeInstalledStatus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Environments",
                style = MaterialTheme.typography.headlineMedium
            )
            IconButton(onClick = { showAddSSHDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add SSH")
            }
        }

        HintBox(
            hintId = "hint_node_toolchain",
            title = "Agent Requirements",
            text = "AI agents like Claude Code require the 'Node Toolchain'. Tap the card below to download and set it up.",
            hintsShown = viewModel.hintsShown,
            onDismiss = { viewModel.markHintShown(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Integrated Status & Clear Controls
        if (isBootstrapping || lastError != null || bootstrapSuccess) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = when {
                    lastError != null -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    bootstrapSuccess -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = when {
                            lastError != null -> "Setup Failed"
                            bootstrapSuccess -> "Setup Successful!"
                            else -> "Setting up Node Environment..."
                        }, 
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (isBootstrapping) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    
                    Text(
                        text = lastError ?: bootstrapStatus, 
                        style = MaterialTheme.typography.labelSmall, 
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Row(modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                        TextButton(onClick = { viewModel.clearBootstrap() }) {
                            Text("Clear Data", color = MaterialTheme.colorScheme.error)
                        }
                        Button(
                            onClick = { viewModel.dismissBootstrap() },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }

        LazyColumn {
            items(manager.environments) { config ->
                EnvironmentCard(
                    config = config,
                    isActive = manager.activeEnvironment == config,
                    isInstalled = if (config is EnvironmentConfig.Node) viewModel.isNodeInstalled else true,
                    onClick = { manager.activateEnvironment(config) },
                    onBootstrap = { viewModel.startBootstrap() },
                    onClear = { viewModel.clearBootstrap() }
                )
            }
        }
    }

    if (showAddSSHDialog) {
        AddSSHDialog(
            onDismiss = { showAddSSHDialog = false },
            onConfirm = { config ->
                manager.addSSHEnvironment(config)
                showAddSSHDialog = false
            }
        )
    }
}

@Composable
fun EnvironmentCard(
    config: EnvironmentConfig,
    isActive: Boolean,
    isInstalled: Boolean,
    onClick: () -> Unit,
    onBootstrap: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { if (isInstalled) onClick() else onBootstrap() },
        colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) 
                 else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (config) {
                    is EnvironmentConfig.Local -> Icons.Default.PhoneAndroid
                    is EnvironmentConfig.SSH -> Icons.Default.Computer
                    is EnvironmentConfig.Node -> Icons.Default.Terminal
                },
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (config) {
                        is EnvironmentConfig.Local -> "Local Android (Limited)"
                        is EnvironmentConfig.SSH -> "Remote SSH: ${config.config.host}"
                        is EnvironmentConfig.Node -> "Node Toolchain"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (isActive) {
                    Text(text = "Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else if (!isInstalled) {
                    Text(text = "Tap to Download & Setup (~100MB)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } else if (config is EnvironmentConfig.Node) {
                    Text(text = "Installed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            if (isActive) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            
            if (config is EnvironmentConfig.Node && isInstalled && !isActive) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun AddSSHDialog(
    onDismiss: () -> Unit,
    onConfirm: (SSHConfig) -> Unit
) {
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add SSH Environment") },
        text = {
            Column {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") })
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(SSHConfig(host, 22, username, password)) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
