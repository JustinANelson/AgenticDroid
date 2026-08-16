package com.justnels.agenticdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
        
        handleIntent(intent)

        setContent {
            viewModel = viewModel()
            MaterialTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val data = intent?.data
        if (data != null && data.scheme == "agenticdroid" && data.host == "github-auth") {
            // Need to wait until viewModel is initialized if called from onCreate
            // but for now, we'll just check if it's there
            if (::viewModel.isInitialized) {
                viewModel.handleGithubCallback(data)
            } else {
                // If viewModel isn't ready yet (first launch), 
                // MainScreen will pick it up via its own LaunchedEffect
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val environmentManager = viewModel.environmentManager
    
    // Handle deep links when the screen starts
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val activity = context as? MainActivity
        activity?.intent?.data?.let { uri ->
            if (uri.scheme == "agenticdroid" && uri.host == "github-auth") {
                viewModel.handleGithubCallback(uri)
                // Clear the data so we don't handle it twice
                activity.intent.data = null
            }
        }
    }
    
    // Dynamically update terminal when active environment changes
    val activeEnv = remember(environmentManager.activeEnvironment) {
        environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
    }
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val terminalViewModel = remember(activeEnv, viewModel.selectedProject) {
        val path = viewModel.selectedProject?.path ?: viewModel.workspaceRoot.absolutePath
        TerminalViewModel(application, activeEnv, path)
    }
    // TerminalViewModel is manually `remember`'d (not lifecycle-scoped via viewModel()), so
    // switching environments or projects would otherwise leak the previous one's forked shell process.
    DisposableEffect(terminalViewModel) {
        onDispose { terminalViewModel.dispose() }
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
                                    viewModel.openedFile?.writeText(viewModel.fileContent)
                                    viewModel.openedFile = null 
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
                    } else if (viewModel.selectedProject == null) {
                        ProjectList(
                            projects = viewModel.projects,
                            githubRepos = viewModel.githubRepos,
                            hintsShown = viewModel.hintsShown,
                            onProjectSelected = { viewModel.selectProject(it) },
                            onCreateProject = { viewModel.createProject(it) },
                            onCloneProject = { url, name -> viewModel.cloneProject(url, name) },
                            onFetchRepos = { viewModel.fetchGithubRepos() },
                            onDismissHint = { viewModel.markHintShown(it) }
                        )
                    } else {
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
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        IconButton(onClick = { viewModel.selectProject(null) }) {
                                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                        }
                                        Text(viewModel.selectedProject!!.name, style = MaterialTheme.typography.titleMedium)
                                    }
                                    Row {
                                        IconButton(onClick = { viewModel.isCreatingFile = true }) {
                                            Icon(Icons.Default.NoteAdd, contentDescription = "New File")
                                        }
                                        if (viewModel.isBuilding) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp))
                                        } else {
                                            IconButton(onClick = { viewModel.buildAndInstall() }) {
                                                Icon(Icons.Default.Build, contentDescription = "Build & Install")
                                            }
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
                                    Text(
                                        text = viewModel.buildStatus!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.primary
                                )
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
                                        viewModel.openedFile = file
                                        viewModel.fileContent = file.readText()
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
                        githubToken = viewModel.githubToken,
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
                        onStartGithubWebFlow = { viewModel.startGithubWebLogin(it) },
                        onCancelGithubDeviceFlow = { viewModel.cancelGithubDeviceFlow() },
                        onRenameToMain = { viewModel.gitRenameToMain() },
                        onDismissHint = { viewModel.markHintShown(it) },
                        onDismissError = { viewModel.dismissGitError() },
                        onDismissOutput = { viewModel.dismissGitOutput() }
                    )
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
                        AgentLauncherScreen(
                            agents = viewModel.agentManager.agents,
                            hintsShown = viewModel.hintsShown,
                            onLaunchAgent = { agent ->
                                terminalViewModel.sendCommand(agent.launchCommand())
                                viewModel.currentScreen = Screen.AgentSession
                            },
                            onDismissHint = { viewModel.markHintShown(it) }
                        )
                    }
                }
                Screen.AgentSession -> {
                    IntegratedAgentScreen(
                        terminalViewModel = terminalViewModel
                    )
                }
                Screen.Search -> {
                    SearchScreen(
                        onSearch = { query -> viewModel.workspaceManager.searchInFiles(query) },
                        onResultSelected = { result ->
                            viewModel.openedFile = File(result.path)
                            viewModel.fileContent = viewModel.openedFile!!.readText()
                            viewModel.currentScreen = Screen.Workspace
                        }
                    )
                }
                Screen.Settings -> {
                    SettingsScreen(
                        onNavigateToEnvironments = { viewModel.currentScreen = Screen.Environments },
                        onWipeHistory = { viewModel.wipeAppData() }
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
    Workspace, Terminal, Git, Agents, AgentSession, Search, Settings, Environments
}
