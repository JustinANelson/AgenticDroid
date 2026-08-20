package com.justnels.agenticdroid.ui.env

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.justnels.agenticdroid.env.DoctorResult
import com.justnels.agenticdroid.env.EnvironmentConfig
import com.justnels.agenticdroid.env.EnvironmentManager
import com.justnels.agenticdroid.env.RunnerPackageGroup
import com.justnels.agenticdroid.env.SSHConfig
import com.justnels.agenticdroid.env.SSHAuthType
import com.justnels.agenticdroid.util.NetworkUtil
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
            title = "Development Toolchains",
            text = "AI agents and web apps require the Core Toolchain. Python and other language runners are optional installs shown after Core is ready.",
            hintsShown = viewModel.hintsShown,
            onDismiss = { viewModel.markHintShown(it) }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Wi-Fi only downloads", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Toolchain downloads can be several hundred MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = viewModel.wifiOnlyDownloads,
                onCheckedChange = { viewModel.setWifiOnlyDownloads(it) }
            )
        }

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
                            else -> "Setting up toolchains..."
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
                    isInstalled = if (config is EnvironmentConfig.Node) viewModel.isCoreToolchainInstalled else true,
                    onClick = { manager.activateEnvironment(config) },
                    onBootstrap = { viewModel.startBootstrap() },
                    onClear = { viewModel.clearBootstrap() },
                    onRemove = { manager.removeEnvironment(config) },
                    onTogglePersistentSession = { enabled ->
                        if (config is EnvironmentConfig.SSH) {
                            val wasActive = manager.activeEnvironment == config
                            val updated = config.config.copy(usePersistentSession = enabled)
                            manager.addSSHEnvironment(updated)
                            // addSSHEnvironment replaces the profile with a new
                            // EnvironmentConfig.SSH value (data class equality means it's a
                            // different key than the old one) - if this was the active
                            // profile, re-activate the new value so activeEnvironment and
                            // the terminal/agent screens don't keep pointing at the
                            // now-removed old one.
                            if (wasActive) manager.activateEnvironment(EnvironmentConfig.SSH(updated))
                        }
                    }
                )
            }

            if (viewModel.isCoreToolchainInstalled && !isBootstrapping) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Runners", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Install support for other project types only when you need it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(RunnerPackageGroup.optional) { group ->
                    RunnerGroupCard(
                        group = group,
                        isInstalled = group in viewModel.installedRunnerGroups,
                        sizeBytes = viewModel.runnerGroupSizeBytes(group),
                        onInstall = { viewModel.startBootstrap(viewModel.installedRunnerGroups + group) },
                        onUninstall = { viewModel.uninstallRunnerGroup(group) },
                        onRefresh = { viewModel.refreshRunnerGroup(group) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Diagnostics", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { viewModel.runDiagnostics() }, enabled = !viewModel.isRunningDiagnostics) {
                            Text(if (viewModel.isRunningDiagnostics) "Running..." else "Run Diagnostics")
                        }
                    }
                    Text(
                        text = "Actually runs each installed group's binaries - a bootstrap can finish successfully and still be broken on this specific device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(viewModel.doctorResults) { result ->
                    DoctorResultCard(result)
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(text = "Connectivity Helpers", style = MaterialTheme.typography.titleMedium)
                    WirelessAdbCard(viewModel)
                }
            }
        }
    }

    if (showAddSSHDialog) {
        AddSSHDialog(
            onDismiss = { showAddSSHDialog = false },
            onConfirm = { config ->
                manager.addSSHEnvironment(config)
                showAddSSHDialog = false
            },
            onScan = { onResult -> viewModel.scanForSshServers(onResult) }
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
    onRemove: () -> Unit,
    onTogglePersistentSession: (Boolean) -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { if (isInstalled) onClick() else onBootstrap() },
        colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                 else CardDefaults.cardColors()
    ) {
      Column {
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
                        is EnvironmentConfig.SSH -> {
                            val tunnelSuffix = if (config.config.useCloudflareTunnel) " (Tunnel)" else ""
                            val persistentSuffix = if (config.config.usePersistentSession) " (Persistent)" else ""
                            "Remote SSH: ${config.config.host}$tunnelSuffix$persistentSuffix"
                        }
                        is EnvironmentConfig.Node -> "Core Toolchain"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (isActive) {
                    Text(text = "Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else if (!isInstalled) {
                    Text(text = "Tap to Download & Setup (~100MB)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } else if (config is EnvironmentConfig.Node) {
                    Text(text = "Installed (Node, Git, NPM, curl, gh, ripgrep, jq, fd)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
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
        if (config is EnvironmentConfig.SSH) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Persistent session (tmux/screen)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = config.config.usePersistentSession,
                    onCheckedChange = onTogglePersistentSession
                )
            }
        }
      }
    }
}

@Composable
fun WirelessAdbCard(viewModel: MainViewModel) {
    var port by remember { mutableStateOf("5555") }
    val preferredIp = NetworkUtil.getPreferredAddress()
    val allIps = NetworkUtil.getLocalIpv4Addresses()
    val isSSH = viewModel.environmentManager.activeEnvironment is EnvironmentConfig.SSH

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wifi, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Wireless ADB", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connect a remote dev machine to this phone via ADB. Ensure Wireless Debugging is enabled in Android Developer Options.",
                style = MaterialTheme.typography.bodySmall
            )
            
            if (allIps.isNotEmpty()) {
                Text(
                    text = "Current IPs: ${allIps.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Wireless ADB Port") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.connectWirelessAdb(port.toIntOrNull() ?: 5555) },
                    enabled = isSSH && preferredIp != null
                ) {
                    Text("Connect Remote")
                }
            }
            
            if (!isSSH) {
                Text(
                    text = "Activate an SSH environment to use this helper.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            viewModel.adbConnectionStatus?.let { status ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    onClick = { viewModel.dismissAdbStatus() }
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun RunnerGroupCard(
    group: RunnerPackageGroup,
    isInstalled: Boolean,
    sizeBytes: Long,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onRefresh: () -> Unit
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = !isInstalled) { onInstall() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isInstalled) Icons.Default.Check else Icons.Default.Download,
                contentDescription = null,
                tint = if (isInstalled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = group.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (isInstalled) "Installed" + (formatBytes(sizeBytes)?.let { " ($it)" } ?: "") else group.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isInstalled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isInstalled) {
                TextButton(onClick = onInstall) {
                    Text("Install")
                }
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Check for updates to ${group.displayName}")
                }
                IconButton(onClick = { showRemoveConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove ${group.displayName}", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove ${group.displayName}?") },
            text = { Text("Frees up the space it uses. You can reinstall it any time.") },
            confirmButton = {
                Button(
                    onClick = { showRemoveConfirm = false; onUninstall() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DoctorResultCard(result: DoctorResult) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.healthy) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (result.healthy) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (result.healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = result.group.displayName, style = MaterialTheme.typography.titleSmall)
            }
            if (!result.healthy && result.output.isNotBlank()) {
                Text(
                    text = result.output.take(500),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String? {
    if (bytes <= 0) return null
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "%.1fGB".format(mb / 1024.0) else "%.0fMB".format(mb)
}

@Composable
fun AddSSHDialog(
    onDismiss: () -> Unit,
    onConfirm: (SSHConfig) -> Unit,
    onScan: ((List<String>) -> Unit) -> Unit
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
    var useCloudflareTunnel by remember { mutableStateOf(false) }
    var usePersistentSession by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var scannedHosts by remember { mutableStateOf(emptyList<String>()) }
    var showScanResults by remember { mutableStateOf(false) }

    val validFingerprint = hostKeyFingerprint.matches(Regex("^SHA256:[A-Za-z0-9+/]{43}=?$"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add SSH Environment") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(if (useCloudflareTunnel) "Cloudflare Hostname" else "Host") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            isScanning = true
                            onScan {
                                scannedHosts = it
                                isScanning = false
                                showScanResults = true
                            }
                        },
                        enabled = !isScanning && !useCloudflareTunnel
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Scan")
                        }
                    }
                }

                if (showScanResults && scannedHosts.isNotEmpty()) {
                    Text(
                        "Discovered local hosts:",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        scannedHosts.forEach { scannedHost ->
                            AssistChip(
                                onClick = {
                                    host = scannedHost
                                    showScanResults = false
                                },
                                label = { Text(scannedHost) },
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text("Use Cloudflare Tunnel", modifier = Modifier.weight(1f))
                    Switch(
                        checked = useCloudflareTunnel,
                        onCheckedChange = { useCloudflareTunnel = it }
                    )
                }

                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Persistent terminal session", modifier = Modifier.weight(1f))
                        Switch(
                            checked = usePersistentSession,
                            onCheckedChange = { usePersistentSession = it }
                        )
                    }
                    Text(
                        "Reattaches to a tmux/screen session on the remote host, so a dropped mobile connection doesn't kill what's running there. Requires tmux or screen and a POSIX shell (Linux/macOS/WSL) on the remote.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!useCloudflareTunnel) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                        label = { Text("Port") }
                    )
                }

                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
                OutlinedTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = { Text("Remote workspace") },
                    supportingText = { Text("Use . for the SSH account's home directory") }
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
                    (useCloudflareTunnel || port.toIntOrNull() in 1..65535) && validFingerprint &&
                    ((authType == SSHAuthType.PASSWORD && password.isNotBlank()) ||
                        (authType == SSHAuthType.PRIVATE_KEY && (privateKeyPath.isNotBlank() || privateKeyContent.isNotBlank()))),
                onClick = {
                    onConfirm(
                        SSHConfig(
                            host = host.trim(),
                            port = if (useCloudflareTunnel) 22 else port.toInt(),
                            username = username.trim(),
                            password = password.takeIf { authType == SSHAuthType.PASSWORD },
                            hostKeyFingerprint = hostKeyFingerprint,
                            workingDirectory = workingDirectory.trim(),
                            authType = authType,
                            privateKeyPath = privateKeyPath.trim().takeIf { authType == SSHAuthType.PRIVATE_KEY },
                            privateKeyPassphrase = privateKeyPassphrase.takeIf { it.isNotEmpty() },
                            privateKeyContent = privateKeyContent.trim().takeIf { it.isNotEmpty() },
                            useCloudflareTunnel = useCloudflareTunnel,
                            usePersistentSession = usePersistentSession
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
