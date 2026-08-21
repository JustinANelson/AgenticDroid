package com.justnels.agenticdroid.ui.git

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
    hasGithubToken: Boolean,
    githubDeviceFlow: com.justnels.agenticdroid.GithubDeviceFlowState?,
    hintsShown: Set<String>,
    preferSshGitRemote: Boolean = false,
    confirmDestructiveGitActions: Boolean = true,
    modifier: Modifier = Modifier,
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
    onSetPreferSshGitRemote: (Boolean) -> Unit = {},
    onStartGithubDeviceFlow: () -> Unit,
    onCancelGithubDeviceFlow: () -> Unit,
    onRenameToMain: () -> Unit,
    onReviewChanges: () -> Unit,
    onDismissHint: (String) -> Unit,
    onDismissError: () -> Unit,
    onDismissOutput: () -> Unit
) {
    var commitMessage by remember { mutableStateOf("") }
    var showRemoteDialog by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showCreateRepoDialog by remember { mutableStateOf(false) }
    var showForcePushConfirm by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(true) }
    
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
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
                            if (!hasGithubToken) showConfigDialog = true
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(text = "Recent History (Branch: $currentBranch)", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { historyExpanded = !historyExpanded }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (historyExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (historyExpanded) "Collapse History" else "Expand History"
                        )
                    }
                }
                if (historyExpanded) {
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            history.forEach { log ->
                                Text(text = log, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = "Changes",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (changes.isNotEmpty()) {
                        TextButton(onClick = onReviewChanges) {
                            Text("Review Diff")
                        }
                    }
                }
            }
        }

        if (error == null) {
            items(changes) { change ->
                Text(
                    text = change,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp)
                )
            }
        }

        item {
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onPull(false) }, modifier = Modifier.weight(1f), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text("Pull", maxLines = 1)
                }
                Button(
                    onClick = {
                        if (commitMessage.isNotBlank()) {
                            onCommit(commitMessage)
                            commitMessage = ""
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text("Commit", maxLines = 1)
                }
                Button(onClick = { onPush(false) }, modifier = Modifier.weight(1f), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text("Push", maxLines = 1)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { onPull(true) }, modifier = Modifier.weight(1f)) {
                    Text("Pull (Rebase)", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                TextButton(
                    onClick = {
                        if (confirmDestructiveGitActions) showForcePushConfirm = true else onPush(true)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Force Push", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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
                        label = { Text("URL (HTTPS or SSH)") },
                        placeholder = { Text("https://github.com/user/repo.git or git@github.com:user/repo.git") },
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
        var githubTokenValue by remember { mutableStateOf("") }
        var sshPreferenceValue by remember { mutableStateOf(preferSshGitRemote) }
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
                        placeholder = { Text(if (hasGithubToken) "Configured — leave blank to keep" else "github_pat_...") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    
                    if (githubDeviceFlow == null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { onStartGithubDeviceFlow() }) {
                                Text("Sign in with GitHub Device Flow")
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

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Checkbox(
                            checked = sshPreferenceValue,
                            onCheckedChange = { sshPreferenceValue = it }
                        )
                        Text("Use SSH (git@github.com) remotes")
                    }
                    Text(
                        text = "Auto-linked and cloned remotes will use SSH instead of HTTPS. " +
                            "Requires an SSH key for GitHub already set up wherever this project's " +
                            "commands run (this device, or the remote machine when connected over SSH) " +
                            "— AgenticDroid does not manage that key.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (userName.isNotBlank()) onSetConfig("user.name", userName)
                    if (userEmail.isNotBlank()) onSetConfig("user.email", userEmail)
                    onUpdateGithubUsername(githubUser)
                    if (githubTokenValue.isNotBlank()) onUpdateGithubToken(githubTokenValue)
                    onSetPreferSshGitRemote(sshPreferenceValue)
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

    if (showForcePushConfirm) {
        AlertDialog(
            onDismissRequest = { showForcePushConfirm = false },
            title = { Text("Force Push?") },
            text = {
                Text(
                    "This overwrites the remote branch's history with your local branch. " +
                        "Anything on the remote that isn't in your local history will be lost " +
                        "and cannot be undone from here."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showForcePushConfirm = false
                        onPush(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Force Push")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForcePushConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
