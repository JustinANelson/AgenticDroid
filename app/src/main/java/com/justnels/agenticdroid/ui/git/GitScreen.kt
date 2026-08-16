package com.justnels.agenticdroid.ui.git

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.ui.components.HintBox

@Composable
fun GitScreen(
    projectName: String,
    currentBranch: String,
    changes: List<String>,
    remotes: List<String>,
    remoteStatuses: Map<String, Boolean>,
    history: List<String>,
    githubUsername: String,
    githubToken: String,
    githubDeviceFlow: com.justnels.agenticdroid.GithubDeviceFlowState?,
    hintsShown: Set<String>,
    lastOutput: String? = null,
    error: String? = null,
    onCommit: (String) -> Unit,
    onPush: (Boolean) -> Unit,
    onPull: (Boolean) -> Unit,
    onInit: () -> Unit,
    onAddRemote: (String, String) -> Unit,
    onAutoAddRemote: () -> Unit,
    onCreateRemote: (String, Boolean) -> Unit,
    onSetConfig: (String, String) -> Unit,
    onUpdateGithubUsername: (String) -> Unit,
    onUpdateGithubToken: (String) -> Unit,
    onStartGithubDeviceFlow: () -> Unit,
    onStartGithubWebFlow: (android.content.Context) -> Unit,
    onCancelGithubDeviceFlow: () -> Unit,
    onRenameToMain: () -> Unit,
    onDismissHint: (String) -> Unit,
    onDismissError: () -> Unit,
    onDismissOutput: () -> Unit,
    modifier: Modifier = Modifier
) {
    var commitMessage by remember { mutableStateOf("") }
    var showRemoteDialog by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showCreateRepoDialog by remember { mutableStateOf(false) }
    
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Project: $projectName",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Branch: $currentBranch",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (currentBranch == "master") {
                    TextButton(
                        onClick = onRenameToMain,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Rename to 'main' (Recommended)", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            IconButton(onClick = { showConfigDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Git Config"
                )
            }
        }

        HintBox(
            hintId = "hint_git_config",
            title = "Identity Required",
            text = "Before committing, tap the gear icon to set your name and email. You'll also need a GitHub Token (PAT) for remote actions.",
            hintsShown = hintsShown,
            onDismiss = onDismissHint
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (remotes.isEmpty() && error == null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (githubUsername.isNotBlank()) {
                    Button(
                        onClick = { onAutoAddRemote() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Link to github.com/$githubUsername/$projectName")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = { showRemoteDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Custom Remote")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { 
                        if (githubToken.isBlank()) showConfigDialog = true
                        else showCreateRepoDialog = true 
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("Create Repository on GitHub")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else if (remotes.isNotEmpty()) {
            Text(text = "Remotes", style = MaterialTheme.typography.titleMedium)
            remotes.forEach { remote ->
                val name = remote.split("\t").firstOrNull() ?: remote
                val isConnected = remoteStatuses[name] ?: false
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Check 
                                      else Icons.Default.Warning,
                        contentDescription = if (isConnected) "Connected" else "Disconnected",
                        tint = if (isConnected) androidx.compose.ui.graphics.Color.Green 
                               else androidx.compose.ui.graphics.Color.Red,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = remote, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (history.isNotEmpty()) {
            Text(text = "Recent History (Branch: $currentBranch)", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    history.forEach { log ->
                        Text(text = log, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onInit() }) { // Reuse onInit or add refresh
                Text("Full Status")
            }
        }

        if (error != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text("Git Error", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    if (error.contains("Not a git repository")) {
                        Button(onClick = onInit, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Initialize Git")
                        }
                    }
                }
            }
        }

        if (lastOutput != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text("Git Output", style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = onDismissOutput, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(lastOutput, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (error == null) {
            Text(
                text = "Changes",
                style = MaterialTheme.typography.titleMedium
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(changes) { change ->
                    Text(
                        text = change,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = commitMessage,
            onValueChange = { commitMessage = it },
            label = { Text("Commit Message") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = { onPull(false) }) {
                Text("Pull")
            }
            Button(
                onClick = { 
                    if (commitMessage.isNotBlank()) {
                        onCommit(commitMessage)
                        commitMessage = ""
                    }
                }
            ) {
                Text("Commit")
            }
            Button(onClick = { onPush(false) }) {
                Text("Push")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = { onPull(true) }) {
                Text("Pull (Rebase)", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { onPush(true) }) {
                Text("Force Push", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showRemoteDialog) {
        var remoteName by remember { mutableStateOf("origin") }
        var remoteUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRemoteDialog = false },
            title = { Text("Add Remote") },
            text = {
                Column {
                    OutlinedTextField(
                        value = remoteName,
                        onValueChange = { remoteName = it },
                        label = { Text("Remote Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { remoteUrl = it },
                        label = { Text("URL (HTTPS)") },
                        placeholder = { Text("https://github.com/user/repo.git") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (remoteUrl.isNotBlank()) {
                        onAddRemote(remoteName, remoteUrl)
                        showRemoteDialog = false
                    }
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showConfigDialog) {
        var userName by remember { mutableStateOf("") }
        var userEmail by remember { mutableStateOf("") }
        var githubUser by remember { mutableStateOf(githubUsername) }
        var githubTokenValue by remember { mutableStateOf(githubToken) }
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("Git Configuration") },
            text = {
                Column {
                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = { Text("Git User Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = userEmail,
                        onValueChange = { userEmail = it },
                        label = { Text("Git User Email") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(
                        value = githubUser,
                        onValueChange = { githubUser = it },
                        label = { Text("GitHub Username") },
                        placeholder = { Text("e.g. justnels") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = githubTokenValue,
                        onValueChange = { githubTokenValue = it },
                        label = { Text("GitHub Token (PAT)") },
                        placeholder = { Text("ghp_...") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    
                    if (githubDeviceFlow == null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { onStartGithubDeviceFlow() }) {
                                Text("Device Flow")
                            }
                            TextButton(onClick = { onStartGithubWebFlow(context) }) {
                                Text("Web Flow (Recommended)")
                            }
                        }
                    } else {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text("Go to: ${githubDeviceFlow.verificationUri}", style = MaterialTheme.typography.labelSmall)
                                Text("Enter code: ${githubDeviceFlow.userCode}", style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { onCancelGithubDeviceFlow() }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }

                    Text(
                        text = "A Personal Access Token (PAT) is required to create repositories and push changes.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (userName.isNotBlank()) onSetConfig("user.name", userName)
                    if (userEmail.isNotBlank()) onSetConfig("user.email", userEmail)
                    onUpdateGithubUsername(githubUser)
                    onUpdateGithubToken(githubTokenValue)
                    showConfigDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateRepoDialog) {
        var repoName by remember { mutableStateOf(projectName) }
        var isPrivate by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showCreateRepoDialog = false },
            title = { Text("Create GitHub Repository") },
            text = {
                Column {
                    OutlinedTextField(
                        value = repoName,
                        onValueChange = { repoName = it },
                        label = { Text("Repository Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                        Text("Private Repository")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (repoName.isNotBlank()) {
                        onCreateRemote(repoName, isPrivate)
                        showCreateRepoDialog = false
                    }
                }) {
                    Text("Create & Push")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRepoDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
