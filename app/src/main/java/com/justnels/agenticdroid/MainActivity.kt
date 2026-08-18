package com.justnels.agenticdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justnels.agenticdroid.ui.terminal.TerminalScreen
import com.justnels.agenticdroid.ui.git.GitScreen
import com.justnels.agenticdroid.ui.workspace.FileTree
import com.justnels.agenticdroid.ui.workspace.SearchScreen
import com.justnels.agenticdroid.ui.workspace.RemoteBrowserScreen
import com.justnels.agenticdroid.env.EnvironmentConfig
import com.justnels.agenticdroid.ui.editor.CodeEditor
import com.justnels.agenticdroid.ui.settings.SettingsScreen
import com.justnels.agenticdroid.ui.env.EnvironmentScreen
import com.justnels.agenticdroid.ui.agents.AgentLauncherScreen
import com.justnels.agenticdroid.ui.agents.IntegratedAgentScreen
import com.justnels.agenticdroid.ui.terminal.TerminalViewModel
import com.justnels.agenticdroid.ui.workspace.ProjectList
import com.justnels.agenticdroid.workspace.Project
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            viewModel = viewModel()
            MaterialTheme {
                MainScreen(viewModel)
            }
        }
    }

}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val environmentManager = viewModel.environmentManager
    
    // Dynamically update terminal when active environment changes
    val activeEnv = remember(environmentManager.activeEnvironment) {
        environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as android.app.Application
    val activity = LocalActivity.current
    val installApkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.installApkFromUri(uri)
    }
    LaunchedEffect(viewModel.resetRequested) {
        if (viewModel.resetRequested) activity?.finishAndRemoveTask()
    }
    val terminalViewModel = remember(activeEnv, viewModel.selectedProject) {
        val path = viewModel.executionWorkingDirectory
        TerminalViewModel(application, activeEnv, path, onSessionEnded = viewModel::onAgentStopped)
    }
    // TerminalViewModel is manually `remember`'d (not lifecycle-scoped via viewModel()), so
    // switching environments or projects would otherwise leak the previous one's forked shell process.
    DisposableEffect(terminalViewModel) {
        // A new TerminalViewModel means a brand new shell - whatever agent was tracked as
        // running belonged to the previous (now-disposed) one.
        viewModel.onAgentStopped()
        onDispose { terminalViewModel.dispose() }
    }

    viewModel.fileError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFileError,
            title = { Text("File operation failed") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = viewModel::dismissFileError) { Text("OK") } }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = viewModel.currentScreen == Screen.Workspace,
                    onClick = { viewModel.currentScreen = Screen.Workspace },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                    label = { Text("Files") }
                )
                NavigationBarItem(
                    selected = viewModel.currentScreen == Screen.Terminal,
                    onClick = { viewModel.currentScreen = Screen.Terminal },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Terminal") },
                    label = { Text("Terminal") }
                )
                NavigationBarItem(
                    selected = viewModel.currentScreen == Screen.WebPreview,
                    onClick = { viewModel.currentScreen = Screen.WebPreview },
                    icon = { Icon(Icons.Default.Language, contentDescription = "Preview") },
                    label = { Text("Preview") }
                )
                NavigationBarItem(
                    selected = viewModel.currentScreen == Screen.Git,
                    onClick = { viewModel.currentScreen = Screen.Git },
                    icon = { Icon(Icons.Default.Source, contentDescription = "Git") },
                    label = { Text("Git") }
                )
                NavigationBarItem(
                    selected = viewModel.currentScreen == Screen.Agents,
                    onClick = { viewModel.currentScreen = Screen.Agents },
                    icon = { Icon(Icons.Default.SmartToy, contentDescription = "Agents") },
                    label = { Text("Agents") }
                )
                NavigationBarItem(
                    selected = viewModel.currentScreen == Screen.Search,
                    onClick = { viewModel.currentScreen = Screen.Search },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    label = { Text("Search") }
                )
                NavigationBarItem(
                    selected = viewModel.currentScreen == Screen.Settings,
                    onClick = { viewModel.currentScreen = Screen.Settings },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            when (viewModel.currentScreen) {
                Screen.Workspace -> {
                    if (viewModel.openedFile != null) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(viewModel.openedFile!!.name, style = MaterialTheme.typography.titleMedium)
                                IconButton(onClick = { 
                                    viewModel.saveOpenedFile()
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                            CodeEditor(
                                content = viewModel.fileContent,
                                onContentChange = { viewModel.fileContent = it },
                                fileName = viewModel.openedFile!!.name
                            )
                        }
                    } else if (environmentManager.activeEnvironment is EnvironmentConfig.SSH) {
                        val ssh = environmentManager.activeEnvironment as EnvironmentConfig.SSH
                        RemoteBrowserScreen(
                            filesystem = activeEnv.filesystem(),
                            rootPath = ssh.config.workingDirectory,
                            onOpenFile = viewModel::openRemoteFile
                        )
                    } else if (viewModel.selectedProject == null) {
                        ProjectList(
                            projects = viewModel.projects,
                            githubRepos = viewModel.githubRepos,
                            hintsShown = viewModel.hintsShown,
                            onProjectSelected = { viewModel.selectProject(it) },
                            onCreateProject = { name, template -> viewModel.createProject(name, template) },
                            onCloneProject = { url, name -> viewModel.cloneProject(url, name) },
                            onDeleteProject = { viewModel.deleteProject(it) },
                            onFetchRepos = { viewModel.fetchGithubRepos() },
                            onDismissHint = { viewModel.markHintShown(it) }
                        )
                    } else {
                        val currentProject = viewModel.selectedProject!!
                        val projectType = viewModel.getProjectType(currentProject)
                        val projectActions = viewModel.getProjectActions(currentProject)
                        val projectMeta = viewModel.getProjectMetadata(currentProject)
                        var showActionsDialog by remember { mutableStateOf(false) }

                        if (showActionsDialog) {
                            com.justnels.agenticdroid.ui.workspace.ProjectActionsDialog(
                                project = currentProject,
                                projectType = projectType,
                                actions = projectActions,
                                metadata = projectMeta,
                                onDismiss = { showActionsDialog = false },
                                onExecuteAction = { action ->
                                    viewModel.runProjectAction(action, terminalViewModel)
                                },
                                onSaveMetadata = { updated ->
                                    viewModel.saveProjectMetadata(currentProject, updated)
                                }
                            )
                        }

                        val nodes = viewModel.projectNodes
                        if (nodes.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                    Text("Project is empty")
                                    Row {
                                        Button(
                                            onClick = {
                                                File(viewModel.selectedProject!!.path, "README.md").writeText("# ${viewModel.selectedProject!!.name}\n\nProject created in AgenticDroid.")
                                                viewModel.refreshCurrentProject() 
                                            },
                                            modifier = Modifier.padding(16.dp)
                                        ) {
                                            Text("Create README")
                                        }
                                        Button(
                                            onClick = { viewModel.selectProject(null) },
                                            modifier = Modifier.padding(16.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                        ) {
                                            Text("Back to Projects")
                                        }
                                    }
                                }
                            }
                        } else {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        IconButton(onClick = { viewModel.selectProject(null) }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                        }
                                        Column {
                                            Text(currentProject.name, style = MaterialTheme.typography.titleMedium)
                                            Surface(
                                                color = when (projectType) {
                                                    com.justnels.agenticdroid.workspace.ProjectType.ANDROID -> MaterialTheme.colorScheme.primaryContainer
                                                    com.justnels.agenticdroid.workspace.ProjectType.WEB -> MaterialTheme.colorScheme.secondaryContainer
                                                    com.justnels.agenticdroid.workspace.ProjectType.PYTHON -> MaterialTheme.colorScheme.tertiaryContainer
                                                    com.justnels.agenticdroid.workspace.ProjectType.CUSTOM -> MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                shape = MaterialTheme.shapes.extraSmall
                                            ) {
                                                Text(
                                                    text = projectType.displayName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        IconButton(onClick = { viewModel.isCreatingFile = true }) {
                                            Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "New File")
                                        }
                                        if (projectType == com.justnels.agenticdroid.workspace.ProjectType.WEB ||
                                            projectType == com.justnels.agenticdroid.workspace.ProjectType.PYTHON ||
                                            projectMeta.previewUrl != null
                                        ) {
                                            IconButton(onClick = { viewModel.openWebPreview() }) {
                                                Icon(Icons.Default.Language, contentDescription = "Web Preview")
                                            }
                                        }
                                        if (viewModel.isBuilding) {
                                            IconButton(onClick = viewModel::cancelBuild) {
                                                Icon(Icons.Default.Stop, contentDescription = "Cancel build")
                                            }
                                        } else {
                                            IconButton(onClick = {
                                                val primary = projectActions.firstOrNull()
                                                if (primary != null) {
                                                    viewModel.runProjectAction(primary, terminalViewModel)
                                                } else {
                                                    showActionsDialog = true
                                                }
                                            }) {
                                                Icon(
                                                    imageVector = when (projectType) {
                                                        com.justnels.agenticdroid.workspace.ProjectType.ANDROID -> Icons.Default.Build
                                                        else -> Icons.Default.PlayArrow
                                                    },
                                                    contentDescription = "Run"
                                                )
                                            }
                                        }
                                        IconButton(onClick = { showActionsDialog = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "Project Actions")
                                        }
                                    }
                                }
                                if (viewModel.isCreatingFile) {
                                    var fileName by remember { mutableStateOf("") }
                                    AlertDialog(
                                        onDismissRequest = { viewModel.isCreatingFile = false },
                                        title = { Text("New File") },
                                        text = {
                                            OutlinedTextField(
                                                value = fileName,
                                                onValueChange = { fileName = it },
                                                label = { Text("File Name") },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        },
                                        confirmButton = {
                                            Button(onClick = {
                                                if (fileName.isNotBlank()) {
                                                    viewModel.createFile(fileName)
                                                    viewModel.isCreatingFile = false
                                                }
                                            }) {
                                                Text("Create")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { viewModel.isCreatingFile = false }) {
                                                Text("Cancel")
                                            }
                                        }
                                    )
                                }
                                if (viewModel.buildStatus != null) {
                                    var showBuildLog by remember { mutableStateOf(false) }
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(
                                            text = viewModel.buildStatus!!,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (viewModel.buildLog.isNotBlank()) {
                                            TextButton(onClick = { showBuildLog = true }) { Text("View log") }
                                        }
                                    }
                                    if (showBuildLog) {
                                        AlertDialog(
                                            onDismissRequest = { showBuildLog = false },
                                            title = { Text("Build output") },
                                            text = {
                                                androidx.compose.foundation.text.selection.SelectionContainer {
                                                    Text(
                                                        viewModel.buildLog,
                                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                        modifier = Modifier.heightIn(max = 420.dp)
                                                            .verticalScroll(rememberScrollState())
                                                    )
                                                }
                                            },
                                            confirmButton = {
                                                TextButton(onClick = { showBuildLog = false }) { Text("Close") }
                                            }
                                        )
                                    }
                                }
                            com.justnels.agenticdroid.ui.components.HintBox(
                                hintId = "hint_build_install",
                                title = "Remote Deployment",
                                text = "Tap the hammer icon above to build and install your app directly on this device, even if the project is on a remote server!",
                                hintsShown = viewModel.hintsShown,
                                onDismiss = { viewModel.markHintShown(it) }
                            )
                            FileTree(
                                nodes = nodes,
                                onFileSelected = { path ->
                                    val file = File(path)
                                    if (file.isFile) {
                                        viewModel.openFile(file.path)
                                    }
                                },
                                onDelete = { viewModel.deleteFile(it) },
                                onRename = { old, new -> viewModel.renameFile(old, new) },
                                onCopy = { src, dest -> viewModel.copyFile(src, dest) }
                            )
                            }
                        }
                    }
                }
                Screen.Terminal -> {
                    TerminalScreen(
                        viewModel = terminalViewModel,
                        hintsShown = viewModel.hintsShown,
                        onDismissHint = { viewModel.markHintShown(it) }
                    )
                }
                Screen.Git -> {
                    LaunchedEffect(Unit) {
                        viewModel.refreshGitStatus()
                    }
                    GitScreen(
                        projectName = viewModel.selectedProject?.name ?: "No Project Selected",
                        currentBranch = viewModel.gitBranch,
                        changes = viewModel.gitChanges,
                        remotes = viewModel.gitRemotes,
                        remoteStatuses = viewModel.gitRemoteStatuses,
                        history = viewModel.gitLog,
                        githubUsername = viewModel.githubUsername,
                        hasGithubToken = viewModel.hasGithubToken,
                        githubDeviceFlow = viewModel.githubDeviceFlowState,
                        hintsShown = viewModel.hintsShown,
                        lastOutput = viewModel.lastGitOutput,
                        error = viewModel.gitError,
                        onCommit = { viewModel.gitCommit(it) },
                        onPush = { viewModel.gitPush(it) },
                        onPull = { viewModel.gitPull(it) },
                        onInit = { viewModel.gitInit() },
                        onAddRemote = { name, url -> viewModel.gitAddRemote(name, url) },
                        onAutoAddRemote = { viewModel.gitAutoAddRemote() },
                        onCreateRemote = { name, isPrivate -> viewModel.gitCreateAndShare(name, isPrivate) },
                        onSetConfig = { key, value -> viewModel.gitSetConfig(key, value) },
                        onUpdateGithubUsername = { viewModel.updateGithubUsername(it) },
                        onUpdateGithubToken = { viewModel.updateGithubToken(it) },
                        onStartGithubDeviceFlow = { viewModel.startGithubDeviceFlow() },
                        onCancelGithubDeviceFlow = { viewModel.cancelGithubDeviceFlow() },
                        onRenameToMain = { viewModel.gitRenameToMain() },
                        onReviewChanges = { viewModel.showDiffReview() },
                        onDismissHint = { viewModel.markHintShown(it) },
                        onDismissError = { viewModel.dismissGitError() },
                        onDismissOutput = { viewModel.dismissGitOutput() }
                    )
                    if (viewModel.showGitDiff) {
                        com.justnels.agenticdroid.ui.git.DiffReviewDialog(
                            rawDiff = viewModel.gitDiff,
                            untrackedFiles = viewModel.gitDiffUntracked,
                            isLoading = viewModel.isGitDiffLoading,
                            onDismiss = { viewModel.dismissDiffReview() }
                        )
                    }
                }
                Screen.Agents -> {
                    if (!viewModel.isNodeEnvironment || !viewModel.isNodeInstalled) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                Text("Agents require the Node Toolchain environment.", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Please activate and setup the Node Toolchain in Settings.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Button(onClick = { viewModel.currentScreen = Screen.Environments }, modifier = Modifier.padding(top = 16.dp)) {
                                    Text("Go to Environments")
                                }
                            }
                        }
                    } else {
                        LaunchedEffect(environmentManager.activeEnvironment) {
                            viewModel.refreshInstalledAgents()
                        }
                        AgentLauncherScreen(
                            agents = viewModel.agentManager.agents,
                            activeAgent = viewModel.activeAgent,
                            installedAgentIds = viewModel.installedAgentIds,
                            isCheckingInstalled = viewModel.isCheckingInstalledAgents,
                            hintsShown = viewModel.hintsShown,
                            onLaunchAgent = { agent ->
                                // Guard against typing a launch script into an already-running
                                // agent's TUI - see MainViewModel.activeAgent. Re-launching the
                                // already-active agent just reattaches to it instead of resending.
                                // KNOWN ISSUE (see sendCommand's doc): a cold-start tap here -
                                // before ever visiting the Terminal tab - can silently fail to
                                // reach the visible session, but this still marks the agent as
                                // launched. Visiting Terminal once first, or retrying Launch,
                                // works around it until the underlying race is found.
                                if (viewModel.activeAgent == null) {
                                    terminalViewModel.sendCommand(agent.launchCommand())
                                    viewModel.onAgentLaunched(agent)
                                }
                                viewModel.currentScreen = Screen.AgentSession
                            },
                            onStopAgent = {
                                terminalViewModel.sendRawInput("\u0003")
                                viewModel.onAgentStopped()
                            },
                            onDismissHint = { viewModel.markHintShown(it) }
                        )
                    }
                }
                Screen.AgentSession -> {
                    IntegratedAgentScreen(
                        terminalViewModel = terminalViewModel,
                        hintsShown = viewModel.hintsShown,
                        onDismissHint = { viewModel.markHintShown(it) }
                    )
                }
                Screen.Search -> {
                    SearchScreen(
                        onSearch = { query -> viewModel.workspaceManager.searchInFiles(query) },
                        onResultSelected = { result ->
                            viewModel.openFile(result.path)
                            viewModel.currentScreen = Screen.Workspace
                        }
                    )
                }
                Screen.Settings -> {
                    SettingsScreen(
                        onNavigateToEnvironments = { viewModel.currentScreen = Screen.Environments },
                        onWipeHistory = { viewModel.wipeAppData() },
                        onInstallApkFromFiles = {
                            installApkLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                        },
                        onRestoreLastKnownGood = { viewModel.restoreLastKnownGoodApk() },
                        hasLastKnownGoodBackup = viewModel.hasLastKnownGoodApk()
                    )
                }
                Screen.WebPreview -> {
                    com.justnels.agenticdroid.ui.preview.WebPreviewScreen(
                        currentUrl = viewModel.webPreviewUrl,
                        onUrlChange = { viewModel.webPreviewUrl = it },
                        onNavigateToTerminal = { viewModel.currentScreen = Screen.Terminal }
                    )
                }
                Screen.Environments -> {
                    EnvironmentScreen(
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

enum class Screen {
    Workspace, Terminal, WebPreview, Git, Agents, AgentSession, Search, Settings, Environments
}
