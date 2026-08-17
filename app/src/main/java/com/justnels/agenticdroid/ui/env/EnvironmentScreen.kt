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
import com.justnels.agenticdroid.env.SSHAuthType
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
                        if (lastError != null) {
                            TextButton(onClick = { viewModel.clearBootstrap() }) {
                                Text("Clear Data", color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = { viewModel.dismissBootstrap() }) {
                                Text("Dismiss")
                            }
                            Button(
                                onClick = { viewModel.retryBootstrap() },
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text("Retry")
                            }
                        } else {
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
        }

        LazyColumn {
            items(manager.environments) { config ->
                EnvironmentCard(
                    config = config,
                    isActive = manager.activeEnvironment == config,
                    isInstalled = if (config is EnvironmentConfig.Node) viewModel.isNodeInstalled else true,
                    onClick = { manager.activateEnvironment(config) },
                    onBootstrap = { viewModel.startBootstrap() },
                    onClear = { viewModel.clearBootstrap() },
                    onRemove = { manager.removeEnvironment(config) }
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
    onClear: () -> Unit,
    onRemove: () -> Unit
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
            if (config is EnvironmentConfig.SSH) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove SSH profile", tint = MaterialTheme.colorScheme.error)
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
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var workingDirectory by remember { mutableStateOf(".") }
    var password by remember { mutableStateOf("") }
    var privateKeyPath by remember { mutableStateOf("") }
    var privateKeyContent by remember { mutableStateOf("") }
    var privateKeyPassphrase by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf(SSHAuthType.PASSWORD) }
    var hostKeyFingerprint by remember { mutableStateOf("") }
    val validFingerprint = hostKeyFingerprint.matches(Regex("^SHA256:[A-Za-z0-9+/]{43}=?$"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add SSH Environment") },
        text = {
            Column {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") })
                OutlinedTextField(
                    value = port,
                    onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                    label = { Text("Port") }
                )
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
                OutlinedTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = { Text("Remote workspace") },
                    supportingText = { Text("Use . for the SSH account's home directory") }
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SSHAuthType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = authType == type,
                            onClick = { authType = type },
                            shape = SegmentedButtonDefaults.itemShape(index, SSHAuthType.entries.size)
                        ) { Text(if (type == SSHAuthType.PASSWORD) "Password" else "Private key") }
                    }
                }
                if (authType == SSHAuthType.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                } else {
                    OutlinedTextField(
                        value = privateKeyPath,
                        onValueChange = { privateKeyPath = it },
                        label = { Text("Private key path") },
                        supportingText = { Text("Path to an app-accessible OpenSSH private key") }
                    )
                    OutlinedTextField(
                        value = privateKeyContent,
                        onValueChange = { privateKeyContent = it },
                        label = { Text("Or paste private key") },
                        minLines = 3,
                        supportingText = { Text("Stored encrypted; prefer this when Android cannot access the source path") }
                    )
                    OutlinedTextField(
                        value = privateKeyPassphrase,
                        onValueChange = { privateKeyPassphrase = it },
                        label = { Text("Key passphrase (optional)") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                }
                OutlinedTextField(
                    value = hostKeyFingerprint,
                    onValueChange = { hostKeyFingerprint = it.trim() },
                    label = { Text("Host key fingerprint") },
                    placeholder = { Text("SHA256:...") },
                    isError = hostKeyFingerprint.isNotEmpty() && !validFingerprint,
                    supportingText = { Text("Verify the complete SHA256 fingerprint with the server administrator.") }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = host.isNotBlank() && username.isNotBlank() &&
                    workingDirectory.isNotBlank() &&
                    port.toIntOrNull() in 1..65535 && validFingerprint &&
                    ((authType == SSHAuthType.PASSWORD && password.isNotBlank()) ||
                        (authType == SSHAuthType.PRIVATE_KEY && (privateKeyPath.isNotBlank() || privateKeyContent.isNotBlank()))),
                onClick = {
                    onConfirm(
                        SSHConfig(
                            host = host.trim(),
                            port = port.toInt(),
                            username = username.trim(),
                            password = password.takeIf { authType == SSHAuthType.PASSWORD },
                            hostKeyFingerprint = hostKeyFingerprint,
                            workingDirectory = workingDirectory.trim(),
                            authType = authType,
                            privateKeyPath = privateKeyPath.trim().takeIf { authType == SSHAuthType.PRIVATE_KEY },
                            privateKeyPassphrase = privateKeyPassphrase.takeIf { it.isNotEmpty() },
                            privateKeyContent = privateKeyContent.trim().takeIf { it.isNotEmpty() }
                        )
                    )
                }
            ) {
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
