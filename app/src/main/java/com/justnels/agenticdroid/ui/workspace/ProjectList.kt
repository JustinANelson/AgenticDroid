package com.justnels.agenticdroid.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.ui.components.HintBox
import com.justnels.agenticdroid.workspace.Project

@Composable
fun ProjectList(
    projects: List<Project>,
    githubRepos: List<com.justnels.agenticdroid.GithubRepo>,
    preferSshGitRemote: Boolean = false,
    hintsShown: Set<String>,
    onProjectSelected: (Project) -> Unit,
    onCreateProject: (String, com.justnels.agenticdroid.workspace.ProjectTemplate) -> Unit,
    onCloneProject: (String, String) -> Unit,
    onDeleteProject: (Project) -> Unit,
    onFetchRepos: () -> Unit,
    onDismissHint: (String) -> Unit,
    isCloning: Boolean = false,
    cloneError: String? = null,
    onDismissCloneError: () -> Unit = {},
    isFetchingRepos: Boolean = false,
    reposError: String? = null,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }
    var showGitHubRepoDialog by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<Project?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Projects", style = MaterialTheme.typography.headlineMedium)
            Row {
                IconButton(onClick = { 
                    onFetchRepos()
                    showCloneDialog = true 
                }) {
                    Icon(Icons.Default.Download, contentDescription = "Clone Project")
                }
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New Project")
                }
            }
        }

        HintBox(
            hintId = "hint_workspace_projects",
            title = "Welcome to Workspaces",
            text = "Create a project from starter templates (Web, Python, Android) or clone an existing repository from GitHub.",
            hintsShown = hintsShown,
            onDismiss = onDismissHint
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (projects.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No projects found")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(projects) { project ->
                    val detectedType = com.justnels.agenticdroid.workspace.ProjectType.detect(java.io.File(project.path))
                    ProjectItem(
                        project = project,
                        projectType = detectedType,
                        onProjectSelected = onProjectSelected,
                        onDeleteClick = { projectToDelete = it }
                    )
                }
            }
        }
    }

    if (projectToDelete != null) {
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Delete Project") },
            text = { Text("Are you sure you want to delete '${projectToDelete?.name}'? This will permanently remove all files in this project directory.") },
            confirmButton = {
                Button(
                    onClick = {
                        projectToDelete?.let { onDeleteProject(it) }
                        projectToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var selectedTemplate by remember { mutableStateOf(com.justnels.agenticdroid.workspace.ProjectTemplate.VANILLA_WEB) }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Project") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Project Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Starter Template",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(com.justnels.agenticdroid.workspace.ProjectTemplate.entries) { tmpl ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTemplate = tmpl },
                                colors = if (selectedTemplate == tmpl) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedTemplate == tmpl,
                                        onClick = { selectedTemplate = tmpl }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = tmpl.title,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = tmpl.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        onCreateProject(name, selectedTemplate)
                        showCreateDialog = false
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCloneDialog) {
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        // Tracks "a clone was actually requested from this dialog", so the auto-close
        // effect below can't fire just because isCloning/cloneError happen to already be
        // false/null when the dialog first opens (e.g. left over from a previous clone).
        var attempted by remember { mutableStateOf(false) }

        LaunchedEffect(isCloning, cloneError) {
            if (attempted && !isCloning && cloneError == null) {
                showCloneDialog = false
            }
        }

        AlertDialog(
            onDismissRequest = { if (!isCloning) { showCloneDialog = false; onDismissCloneError() } },
            title = { Text("Clone Project") },
            text = {
                Column {
                    if (isFetchingRepos) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading your GitHub repos...", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (reposError != null) {
                        Text(
                            text = reposError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (githubRepos.isNotEmpty()) {
                        Button(
                            onClick = {
                                showGitHubRepoDialog = true
                                showCloneDialog = false
                            },
                            enabled = !isCloning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Browse Your GitHub Repos")
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    }

                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            if (name.isEmpty() && it.contains("/")) {
                                name = it.substringAfterLast("/").removeSuffix(".git")
                            }
                        },
                        label = { Text("Repository URL") },
                        placeholder = { Text("https://github.com/user/repo.git") },
                        enabled = !isCloning,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Project Name (Directory)") },
                        enabled = !isCloning,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isCloning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cloning... this can take a while over a slow connection.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (cloneError != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = cloneError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (url.isNotBlank() && name.isNotBlank()) {
                            attempted = true
                            onDismissCloneError()
                            onCloneProject(url, name)
                        }
                    },
                    enabled = !isCloning
                ) {
                    Text(if (isCloning) "Cloning..." else "Clone")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCloneDialog = false; onDismissCloneError() },
                    enabled = !isCloning
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showGitHubRepoDialog) {
        var attempted by remember { mutableStateOf(false) }

        LaunchedEffect(isCloning, cloneError) {
            if (attempted && !isCloning && cloneError == null) {
                showGitHubRepoDialog = false
            }
        }

        AlertDialog(
            onDismissRequest = { if (!isCloning) showGitHubRepoDialog = false },
            title = { Text("Your GitHub Repositories") },
            text = {
                Column {
                    if (isCloning) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cloning...", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (cloneError != null) {
                        Text(
                            text = cloneError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Box(modifier = Modifier.heightIn(max = 400.dp)) {
                        LazyColumn {
                            items(githubRepos) { repo ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable(enabled = !isCloning) {
                                            attempted = true
                                            onDismissCloneError()
                                            val cloneUrl = if (preferSshGitRemote) repo.sshUrl else repo.cloneUrl
                                            onCloneProject(cloneUrl, repo.name)
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(repo.fullName, style = MaterialTheme.typography.titleSmall)
                                        if (repo.isPrivate) {
                                            Text("Private", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showGitHubRepoDialog = false; onDismissCloneError() },
                    enabled = !isCloning
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProjectItem(
    project: Project,
    projectType: com.justnels.agenticdroid.workspace.ProjectType,
    onProjectSelected: (Project) -> Unit,
    onDeleteClick: (Project) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onProjectSelected(project) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (projectType) {
                    com.justnels.agenticdroid.workspace.ProjectType.ANDROID -> Icons.Default.PhoneAndroid
                    com.justnels.agenticdroid.workspace.ProjectType.WEB -> Icons.Default.Language
                    com.justnels.agenticdroid.workspace.ProjectType.NODE_JS -> Icons.Default.Terminal
                    com.justnels.agenticdroid.workspace.ProjectType.PYTHON -> Icons.Default.Terminal
                    com.justnels.agenticdroid.workspace.ProjectType.JVM -> Icons.Default.Terminal
                    com.justnels.agenticdroid.workspace.ProjectType.RUST -> Icons.Default.Terminal
                    com.justnels.agenticdroid.workspace.ProjectType.GOLANG -> Icons.Default.Terminal
                    com.justnels.agenticdroid.workspace.ProjectType.SSG -> Icons.Default.Language
                    com.justnels.agenticdroid.workspace.ProjectType.CPP -> Icons.Default.Terminal
                    com.justnels.agenticdroid.workspace.ProjectType.CUSTOM -> Icons.Default.Folder
                },
                contentDescription = null,
                tint = when (projectType) {
                    com.justnels.agenticdroid.workspace.ProjectType.ANDROID -> MaterialTheme.colorScheme.primary
                    com.justnels.agenticdroid.workspace.ProjectType.WEB -> MaterialTheme.colorScheme.secondary
                    com.justnels.agenticdroid.workspace.ProjectType.NODE_JS -> MaterialTheme.colorScheme.primary
                    com.justnels.agenticdroid.workspace.ProjectType.PYTHON -> MaterialTheme.colorScheme.tertiary
                    com.justnels.agenticdroid.workspace.ProjectType.JVM -> MaterialTheme.colorScheme.primary
                    com.justnels.agenticdroid.workspace.ProjectType.RUST -> MaterialTheme.colorScheme.tertiary
                    com.justnels.agenticdroid.workspace.ProjectType.GOLANG -> MaterialTheme.colorScheme.secondary
                    com.justnels.agenticdroid.workspace.ProjectType.SSG -> MaterialTheme.colorScheme.secondary
                    com.justnels.agenticdroid.workspace.ProjectType.CPP -> MaterialTheme.colorScheme.secondary
                    com.justnels.agenticdroid.workspace.ProjectType.CUSTOM -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Surface(
                    color = when (projectType) {
                        com.justnels.agenticdroid.workspace.ProjectType.ANDROID -> MaterialTheme.colorScheme.primaryContainer
                        com.justnels.agenticdroid.workspace.ProjectType.WEB -> MaterialTheme.colorScheme.secondaryContainer
                        com.justnels.agenticdroid.workspace.ProjectType.NODE_JS -> MaterialTheme.colorScheme.primaryContainer
                        com.justnels.agenticdroid.workspace.ProjectType.PYTHON -> MaterialTheme.colorScheme.tertiaryContainer
                        com.justnels.agenticdroid.workspace.ProjectType.JVM -> MaterialTheme.colorScheme.primaryContainer
                        com.justnels.agenticdroid.workspace.ProjectType.RUST -> MaterialTheme.colorScheme.tertiaryContainer
                        com.justnels.agenticdroid.workspace.ProjectType.GOLANG -> MaterialTheme.colorScheme.secondaryContainer
                        com.justnels.agenticdroid.workspace.ProjectType.SSG -> MaterialTheme.colorScheme.secondaryContainer
                        com.justnels.agenticdroid.workspace.ProjectType.CPP -> MaterialTheme.colorScheme.secondaryContainer
                        com.justnels.agenticdroid.workspace.ProjectType.CUSTOM -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = projectType.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Project options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            showMenu = false
                            onDeleteClick(project)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}
