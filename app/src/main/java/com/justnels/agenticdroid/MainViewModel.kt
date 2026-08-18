package com.justnels.agenticdroid

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.core.content.edit
import com.justnels.agenticdroid.agents.AgentManager
import com.justnels.agenticdroid.auth.CredentialManager
import com.justnels.agenticdroid.env.EnvironmentManager
import com.justnels.agenticdroid.env.ProcessSession
import com.justnels.agenticdroid.env.capture
import com.justnels.agenticdroid.git.GitManager
import com.justnels.agenticdroid.util.ApkInstaller
import com.justnels.agenticdroid.workspace.Project
import com.justnels.agenticdroid.BuildConfig
import com.justnels.agenticdroid.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class GithubRepo(
    val name: String,
    val fullName: String,
    val cloneUrl: String,
    val isPrivate: Boolean
)

data class GithubDeviceFlowState(
    val userCode: String,
    val verificationUri: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val workspaceRoot = File(application.getExternalFilesDir(null), "workspaces")
    
    val workspaceManager = WorkspaceManager(workspaceRoot)
    val agentManager = AgentManager()
    val environmentManager = EnvironmentManager(application)

    // The Terminal screen backs every agent launch with one shared, persistent shell (see
    // TerminalViewModel) - once an agent's TUI owns that shell's foreground, typing another
    // agent's launch script into it would just be interpreted as chat input by the running
    // agent (junk prompts, wasted turns/tokens) rather than executed as shell commands.
    // Tracks which agent currently owns the shell so the launcher can block/guard against
    // that instead of silently corrupting whatever is running.
    var activeAgent by mutableStateOf<com.justnels.agenticdroid.agents.AgentProfile?>(null)
        private set

    fun onAgentLaunched(agent: com.justnels.agenticdroid.agents.AgentProfile) {
        activeAgent = agent
    }

    /** Best-effort: the caller is responsible for actually interrupting/exiting the running
     * agent (e.g. sending Ctrl+C) before or after calling this - this only clears the
     * tracked state so the launcher unblocks other agents again. */
    fun onAgentStopped() {
        activeAgent = null
    }

    var installedAgentIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var isCheckingInstalledAgents by mutableStateOf(false)
        private set

    /** Probes `command -v <agent command>` for every known agent against the active
     * environment, so the launcher can show install status up front instead of only
     * discovering it (with a multi-minute npm/QEMU-wrap install as the consequence)
     * after the user taps Launch. Best-effort: an environment that isn't reachable
     * (e.g. Node toolchain not bootstrapped, SSH host unreachable) just reports
     * everything as not-installed rather than failing loudly here. */
    fun refreshInstalledAgents() {
        if (environmentManager.activeEnvironment == com.justnels.agenticdroid.env.EnvironmentConfig.Node &&
            !environmentManager.bootstrapper.isInstalled()
        ) {
            installedAgentIds = emptySet()
            return
        }
        val env = runCatching { environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment) }
            .getOrNull() ?: run { installedAgentIds = emptySet(); return }
        val agentsToCheck = agentManager.agents
        val path = executionWorkingDirectory
        isCheckingInstalledAgents = true
        viewModelScope.launch(Dispatchers.IO) {
            val installed = agentsToCheck.filter { agent ->
                runCatching {
                    env.exec("command -v ${agent.command} >/dev/null 2>&1", path)
                        .capture(timeoutMillis = 15_000).exitCode == 0
                }.getOrDefault(false)
            }.map { it.id }.toSet()
            withContext(Dispatchers.Main) {
                installedAgentIds = installed
                isCheckingInstalledAgents = false
            }
        }
    }

    var currentScreen by mutableStateOf(Screen.Workspace)
    var openedFile by mutableStateOf<File?>(null)
    private var openedFileEnvironment: com.justnels.agenticdroid.env.EnvironmentConfig? = null
    var fileContent by mutableStateOf("")
    var fileError by mutableStateOf<String?>(null)
    var isCreatingFile by mutableStateOf(false)
    var resetRequested by mutableStateOf(false)
        private set

    var selectedProject by mutableStateOf<Project?>(null)
    var projects by mutableStateOf<List<Project>>(emptyList())
    var projectNodes by mutableStateOf<List<com.justnels.agenticdroid.workspace.FileNode>>(emptyList())
        private set

    var gitBranch by mutableStateOf("loading...")
    var gitChanges by mutableStateOf<List<String>>(emptyList())
    var gitRemotes by mutableStateOf<List<String>>(emptyList())
    var gitRemoteStatuses by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var gitLog by mutableStateOf<List<String>>(emptyList())
    var gitError by mutableStateOf<String?>(null)
    var lastGitOutput by mutableStateOf<String?>(null)
    var gitDiff by mutableStateOf<String?>(null)
        private set
    var gitDiffUntracked by mutableStateOf<List<String>>(emptyList())
        private set
    var isGitDiffLoading by mutableStateOf(false)
        private set
    var showGitDiff by mutableStateOf(false)
        private set

    var githubUsername by mutableStateOf("")
        private set
    private var githubToken = ""
    var hasGithubToken by mutableStateOf(false)
        private set
    var githubRepos by mutableStateOf<List<GithubRepo>>(emptyList())
        private set

    var githubDeviceFlowState by mutableStateOf<GithubDeviceFlowState?>(null)
        private set

    // GitHub OAuth device flow uses a public client ID and does not require a secret.
    private val CLIENT_ID = BuildConfig.GH_CLIENT_ID

    var hintsShown by mutableStateOf<Set<String>>(emptySet())
        private set

    var isNodeInstalled by mutableStateOf(false)
        private set

    var installedRunnerGroups by mutableStateOf<Set<com.justnels.agenticdroid.env.RunnerPackageGroup>>(emptySet())
        private set

    private val prefs = application.getSharedPreferences("agentic_prefs", Context.MODE_PRIVATE)
    private val credentialManager = CredentialManager(application)

    private fun openHttp(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }

    private fun HttpURLConnection.readResponse(limit: Int = 1_000_000): String {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        return stream?.bufferedReader()?.use { reader ->
            val result = StringBuilder(minOf(limit, 16_384))
            val buffer = CharArray(4096)
            while (result.length < limit) {
                val read = reader.read(buffer, 0, minOf(buffer.size, limit - result.length))
                if (read < 0) break
                result.append(buffer, 0, read)
            }
            result.toString()
        }.orEmpty()
    }

    fun markHintShown(hintId: String) {
        val newSet = hintsShown + hintId
        hintsShown = newSet
        prefs.edit { putStringSet("hints_shown", newSet) }
    }

    fun wipeAppData() {
        viewModelScope.launch(Dispatchers.IO) {
            activeBuildSession?.kill()
            environmentManager.close()
            runCatching { environmentManager.cancelBootstrap().result.get() }
            getApplication<Application>().stopService(
                android.content.Intent(getApplication(), com.justnels.agenticdroid.terminal.TerminalService::class.java)
            )
            prefs.edit { clear() }
            getApplication<Application>().getSharedPreferences("environment", Context.MODE_PRIVATE).edit { clear() }
            credentialManager.clearAll()
            workspaceRoot.deleteRecursively()
            environmentManager.bootstrapper.clear()
            withContext(Dispatchers.Main) {
                refreshNodeInstalledStatus()
                resetRequested = true
            }
        }
    }

    val isNodeEnvironment: Boolean
        get() = environmentManager.activeEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.Node

    val executionWorkingDirectory: String
        get() = when (val active = environmentManager.activeEnvironment) {
            is com.justnels.agenticdroid.env.EnvironmentConfig.SSH -> active.config.workingDirectory
            else -> selectedProject?.path ?: workspaceRoot.absolutePath
        }

    fun refreshNodeInstalledStatus() {
        isNodeInstalled = environmentManager.bootstrapper.isInstalled()
        installedRunnerGroups = environmentManager.installedRunnerGroups()
    }

    /** Groups a project of this type needs but that aren't installed on-device yet. */
    fun missingRunnerGroups(type: com.justnels.agenticdroid.workspace.ProjectType): Set<com.justnels.agenticdroid.env.RunnerPackageGroup> =
        com.justnels.agenticdroid.env.RunnerPackageGroup.requiredFor(type) - installedRunnerGroups

    private val gitManager: GitManager
        get() {
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val path = executionWorkingDirectory
            val sslPath = com.justnels.agenticdroid.env.NodeRuntime.usrDir(getApplication()).let { usr ->
                File(usr, "etc/tls/cert.pem").absolutePath
            }
            return GitManager(env, path, sslPath, githubToken.takeIf(String::isNotBlank))
        }

    var webPreviewUrl by mutableStateOf("http://localhost:5173")

    fun openWebPreview(url: String? = null) {
        if (url != null) {
            webPreviewUrl = url
        } else {
            val project = selectedProject
            if (project != null) {
                val meta = workspaceManager.getProjectMetadata(project)
                webPreviewUrl = meta.previewUrl ?: workspaceManager.getProjectType(project).defaultPreviewUrl
            }
        }
        currentScreen = Screen.WebPreview
    }

    fun getProjectType(project: Project): com.justnels.agenticdroid.workspace.ProjectType =
        workspaceManager.getProjectType(project)

    fun getProjectActions(project: Project): List<com.justnels.agenticdroid.workspace.ProjectRunnerAction> =
        workspaceManager.getProjectActions(project)

    fun getProjectMetadata(project: Project): com.justnels.agenticdroid.workspace.ProjectMetadata =
        workspaceManager.getProjectMetadata(project)

    fun saveProjectMetadata(project: Project, metadata: com.justnels.agenticdroid.workspace.ProjectMetadata) {
        workspaceManager.saveProjectMetadata(project, metadata)
        if (metadata.previewUrl != null) {
            webPreviewUrl = metadata.previewUrl
        }
    }

    fun runProjectAction(
        action: com.justnels.agenticdroid.workspace.ProjectRunnerAction,
        terminalViewModel: com.justnels.agenticdroid.ui.terminal.TerminalViewModel? = null
    ) {
        if (action.isBuild) {
            buildAndInstall(action.command)
        } else {
            if (action.command.isNotBlank() && terminalViewModel != null) {
                terminalViewModel.sendCommand(action.command)
            }
            if (action.opensPreview) {
                action.previewUrl?.let { webPreviewUrl = it }
                currentScreen = Screen.WebPreview
            }
        }
    }

    fun refreshProjects() {
        projects = workspaceManager.listProjects()
        refreshCurrentProject()
    }

    fun refreshCurrentProject() {
        val project = selectedProject
        projectNodes = if (project != null) {
            workspaceManager.getFileTree(project)
        } else {
            emptyList()
        }
    }

    fun selectProject(project: Project?) {
        selectedProject = project
        if (project != null) {
            val meta = workspaceManager.getProjectMetadata(project)
            webPreviewUrl = meta.previewUrl ?: workspaceManager.getProjectType(project).defaultPreviewUrl
        }
        refreshCurrentProject()
    }

    fun createProject(
        name: String,
        template: com.justnels.agenticdroid.workspace.ProjectTemplate = com.justnels.agenticdroid.workspace.ProjectTemplate.EMPTY
    ) {
        if (workspaceManager.createProjectFromTemplate(name, template)) {
            refreshProjects()
            val newProject = projects.find { it.name == name }
            if (newProject != null) {
                selectProject(newProject)
            }
        } else if (workspaceManager.createProject(name)) {
            refreshProjects()
        }
    }

    fun deleteProject(project: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            // Projects managed by WorkspaceManager are always local app workspace directories.
            if (workspaceManager.deleteProject(project)) {
                withContext(Dispatchers.Main) {
                    if (selectedProject?.path == project.path) {
                        selectProject(null)
                    }
                    refreshProjects()
                }
            }
        }
    }

    fun cloneProject(url: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val destination = workspaceManager.projectPath(name)
            if (destination == null) {
                gitError = "Project name must be a single safe directory name."
                return@launch
            }
            val result = gitManager.clone(url, destination)
            if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                gitError = result.message
            } else {
                refreshProjects()
                // Auto-select the cloned project
                val newProject = projects.find { it.name == name }
                if (newProject != null) {
                    withContext(Dispatchers.Main) {
                        selectProject(newProject)
                    }
                }
            }
        }
    }

    fun createFile(name: String) {
        val project = selectedProject ?: return
        fileError = null
        runCatching { workspaceManager.createFile(project, name) }
            .onSuccess { created ->
                if (!created) fileError = "Could not create that file inside the selected project."
            }
            .onFailure { fileError = it.localizedMessage }
        if (fileError == null) {
            refreshCurrentProject()
        }
    }

    fun deleteFile(path: String) {
        val project = selectedProject ?: return
        val safePath = workspaceManager.resolveInsideProject(project, path)?.absolutePath ?: run {
            fileError = "Refusing to delete a file outside the selected project."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val deleted = env.filesystem().deleteFile(safePath)
            withContext(Dispatchers.Main) {
                if (deleted) refreshCurrentProject() else fileError = "Could not delete the file."
            }
        }
    }

    fun renameFile(oldPath: String, newName: String) {
        val project = selectedProject ?: return
        val safeOld = workspaceManager.resolveInsideProject(project, oldPath)?.absolutePath
        val safeNew = workspaceManager.safeSibling(project, oldPath, newName)?.absolutePath
        if (safeOld == null || safeNew == null) {
            fileError = "The rename must stay inside the selected project."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val renamed = env.filesystem().renameFile(safeOld, safeNew)
            withContext(Dispatchers.Main) {
                if (renamed) refreshCurrentProject() else fileError = "Could not rename the file."
            }
        }
    }

    fun copyFile(path: String, newName: String) {
        val project = selectedProject ?: return
        val safeSource = workspaceManager.resolveInsideProject(project, path)?.absolutePath
        val safeDestination = workspaceManager.safeSibling(project, path, newName)?.absolutePath
        if (safeSource == null || safeDestination == null) {
            fileError = "The copy must stay inside the selected project."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val copied = env.filesystem().copyFile(safeSource, safeDestination)
            withContext(Dispatchers.Main) {
                if (copied) refreshCurrentProject() else fileError = "Could not copy the file."
            }
        }
    }

    fun openFile(path: String) {
        val project = selectedProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceManager.readTextFile(project, path) }
                .onSuccess { content -> withContext(Dispatchers.Main) {
                    openedFile = workspaceManager.resolveInsideProject(project, path)
                    openedFileEnvironment = null
                    fileContent = content
                    fileError = null
                } }
                .onFailure { error -> withContext(Dispatchers.Main) {
                    fileError = error.localizedMessage ?: "Could not open the file."
                } }
        }
    }

    fun openRemoteFile(path: String) {
        val config = environmentManager.activeEnvironment
        if (config !is com.justnels.agenticdroid.env.EnvironmentConfig.SSH) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { environmentManager.getExecutionEnvironment(config).filesystem().readFile(path) }
                .onSuccess { content -> withContext(Dispatchers.Main) {
                    openedFile = File(path)
                    openedFileEnvironment = config
                    fileContent = content
                    fileError = null
                } }
                .onFailure { error -> withContext(Dispatchers.Main) {
                    fileError = error.localizedMessage ?: "Could not open the remote file."
                } }
        }
    }

    fun saveOpenedFile() {
        val project = selectedProject
        val file = openedFile ?: return
        val content = fileContent
        val fileEnvironment = openedFileEnvironment
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (fileEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.SSH) {
                    environmentManager.getExecutionEnvironment(fileEnvironment).filesystem().writeFile(file.path, content)
                } else {
                    workspaceManager.writeTextFile(requireNotNull(project) { "No local project is selected" }, file.path, content)
                }
            }
                .onSuccess { withContext(Dispatchers.Main) {
                    openedFile = null
                    openedFileEnvironment = null
                    fileError = null
                } }
                .onFailure { error -> withContext(Dispatchers.Main) {
                    fileError = error.localizedMessage ?: "Could not save the file."
                } }
        }
    }

    fun refreshGitStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = gitManager
            
            // Try to fetch remote state if connected
            if (githubToken.isNotBlank()) {
                manager.fetch()
            }

            val branch = manager.getCurrentBranch()
            val status = manager.getStatus()
            val remotes = manager.getRemotes()
            val logResult = manager.getLog()
            
            gitBranch = branch
            gitChanges = status
            gitRemotes = remotes
            gitLog = if (logResult is com.justnels.agenticdroid.git.GitResult.Success) {
                logResult.output.lines().filter { it.isNotBlank() }
            } else {
                emptyList()
            }
            
            // If branch is unknown, it's definitely not a git repo
            if (branch == "unknown") {
                gitError = "Not a git repository (or git not found)"
            } else if (gitError == "Not a git repository (or git not found)") {
                // Clear the error if we are now in a repo
                gitError = null
            }

            // Check connectivity for each remote
            val statuses = mutableMapOf<String, Boolean>()
            remotes.forEach { line ->
                val name = line.split("\t").firstOrNull()
                if (name != null && !statuses.containsKey(name)) {
                    val result = manager.checkRemoteConnectivity(name)
                    statuses[name] = result is com.justnels.agenticdroid.git.GitResult.Success
                }
            }
            gitRemoteStatuses = statuses
        }
    }

    fun showDiffReview() {
        showGitDiff = true
        isGitDiffLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            val manager = gitManager
            val diffResult = manager.getDiff()
            val untracked = manager.getStatus()
                .filter { it.startsWith("??") }
                .map { it.removePrefix("??").trim() }
            withContext(Dispatchers.Main) {
                gitDiff = (diffResult as? com.justnels.agenticdroid.git.GitResult.Success)?.output
                gitDiffUntracked = untracked
                isGitDiffLoading = false
            }
        }
    }

    fun dismissDiffReview() {
        showGitDiff = false
        gitDiff = null
        gitDiffUntracked = emptyList()
    }

    fun gitCommit(message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i("MainViewModel", "Initiating commit: $message")
            val manager = gitManager
            val addResult = manager.addAll()
            if (addResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                withContext(Dispatchers.Main) {
                    gitError = "Failed to stage changes: ${addResult.message}"
                }
                return@launch
            }
            
            val result = manager.commit(message)
            Log.i("MainViewModel", "Commit result: $result")
            
            withContext(Dispatchers.Main) {
                if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                    gitError = result.message
                } else {
                    lastGitOutput = (result as com.justnels.agenticdroid.git.GitResult.Success).output
                }
            }
            refreshGitStatus()
        }
    }

    fun gitPush(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i("MainViewModel", "Initiating push (force=$force)")
            val manager = gitManager
            
            // Ensure we have commits to push
            if (!manager.hasCommits()) {
                withContext(Dispatchers.Main) {
                    gitError = "Nothing to push: repository has no commits yet."
                }
                return@launch
            }

            val currentBranch = manager.getCurrentBranch()
            
            // Always push explicitly: origin <currentBranch>
            Log.i("MainViewModel", "Pushing explicitly: origin $currentBranch")
            var result = manager.push(remote = "origin", branch = currentBranch, force = force)
            Log.i("MainViewModel", "Push result: $result")
            
            // Handle "no upstream branch" or ref reject automatically
            if (result is com.justnels.agenticdroid.git.GitResult.Failure && 
                (result.message.contains("no upstream branch") || result.message.contains("has no upstream"))) {
                Log.i("MainViewModel", "Attempting push with -u (set-upstream)")
                result = manager.push(remote = "origin", branch = currentBranch, setUpstream = true, force = force)
                Log.i("MainViewModel", "Upstream push result: $result")
            }

            withContext(Dispatchers.Main) {
                if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                    gitError = result.message
                } else {
                    val output = (result as com.justnels.agenticdroid.git.GitResult.Success).output
                    lastGitOutput = if (output.contains("up-to-date")) {
                        "Git says: $output\n\nNote: If you don't see your files on GitHub, make sure you are viewing the '$currentBranch' branch on the website."
                    } else {
                        output
                    }
                    if (gitError != "Not a git repository (or git not found)") {
                        gitError = null
                    }
                }
            }
            refreshGitStatus()
        }
    }

    fun gitPull(rebase: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i("MainViewModel", "Initiating pull (rebase=$rebase)")
            val result = gitManager.pull(rebase = rebase)
            Log.i("MainViewModel", "Pull result: $result")
            withContext(Dispatchers.Main) {
                if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                    gitError = result.message
                } else {
                    lastGitOutput = (result as com.justnels.agenticdroid.git.GitResult.Success).output
                }
            }
            refreshGitStatus()
        }
    }

    fun gitInit() {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = gitManager
            // Check if it's already a repo - if so, this acts as a "Full Status" request
            val branch = manager.getCurrentBranch()
            if (branch != "unknown") {
                val statusResult = manager.getStatusRaw()
                withContext(Dispatchers.Main) {
                    lastGitOutput = statusResult
                }
                refreshGitStatus()
                return@launch
            }

            val result = manager.init()
            if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                gitError = result.message
            } else {
                // Default new repos to 'main' immediately
                manager.renameBranch("main")
                refreshGitStatus()
            }
        }
    }

    fun gitRenameToMain() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = gitManager.renameBranch("main")
            if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                gitError = result.message
            } else {
                refreshGitStatus()
            }
        }
    }

    fun gitAutoAddRemote() {
        val username = githubUsername.trim()
        val projectName = selectedProject?.name?.trim()
        if (username.isNotBlank() && !projectName.isNullOrBlank()) {
            val encodedName = java.net.URLEncoder.encode(projectName, "UTF-8").replace("+", "%20")
            val url = "https://github.com/$username/$encodedName.git"
            gitAddRemote("origin", url)
        } else {
            gitError = "Cannot auto-link: GitHub username or Project name is missing."
        }
    }

    fun gitAddRemote(name: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = gitManager.addRemote(name, url)
            if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                withContext(Dispatchers.Main) { gitError = result.message }
            }
            refreshGitStatus()
        }
    }

    fun gitSetConfig(key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = gitManager.setConfig(key, value)
            if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                withContext(Dispatchers.Main) { gitError = result.message }
            }
        }
    }

    fun gitCreateAndShare(name: String, isPrivate: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanName = name.trim()
            val cleanUsername = githubUsername.trim()
            
            Log.i("MainViewModel", "Creating and sharing repo: $cleanName (private=$isPrivate)")
            if (githubToken.isBlank()) {
                gitError = "GitHub Token required to create a repository."
                return@launch
            }
            
            if (cleanUsername.isBlank()) {
                gitError = "GitHub username is unknown. Please login again or set it in settings."
                return@launch
            }
            
            val manager = gitManager
            
            // 0. Ensure we have something to push
            if (!manager.hasCommits()) {
                Log.d("MainViewModel", "Repo has no commits. Performing initial commit.")
                val addResult = manager.addAll()
                if (addResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                    withContext(Dispatchers.Main) { gitError = "Failed to stage initial files: ${addResult.message}" }
                    return@launch
                }
                val commitResult = manager.commit("Initial commit from AgenticDroid")
                if (commitResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                    gitError = "Failed to perform initial commit: ${commitResult.message}"
                    return@launch
                }
            }
            
            // Ensure branch is named 'main' for GitHub compatibility
            val renameResult = manager.renameBranch("main")
            if (renameResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                withContext(Dispatchers.Main) { gitError = renameResult.message }
                return@launch
            }
            refreshGitStatus() // Update gitBranch state
            val currentBranch = manager.getCurrentBranch()

            // 1. Create Repo on GitHub
            Log.d("MainViewModel", "Step 1: Creating repo on GitHub via API")
            val result = manager.createGitHubRepo(githubToken, cleanName, isPrivate)
            if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                Log.e("MainViewModel", "Repo creation failed: ${result.message}")
                gitError = result.message
                return@launch
            }
            
            // 2. Add Remote
            Log.d("MainViewModel", "Step 2: Adding remote 'origin'")
            val encodedName = java.net.URLEncoder.encode(cleanName, "UTF-8").replace("+", "%20")
            val url = "https://github.com/$cleanUsername/$encodedName.git"
            val remoteResult = manager.addRemote("origin", url)
            if (remoteResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                withContext(Dispatchers.Main) {
                    gitError = "Repo created, but adding origin failed: ${remoteResult.message}"
                }
                return@launch
            }
            
            // 3. Initial Push
            Log.d("MainViewModel", "Step 3: Initial push to branch $currentBranch")
            val pushResult = manager.push(remote = "origin", branch = currentBranch, setUpstream = true)
            Log.i("MainViewModel", "Initial push result: $pushResult")
            
            if (pushResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                withContext(Dispatchers.Main) {
                    gitError = "Repo created, but initial push failed: ${pushResult.message}"
                }
            }
            
            refreshGitStatus()
        }
    }

    fun updateGithubUsername(username: String) {
        githubUsername = username
        prefs.edit { putString("github_username", username) }
    }

    fun updateGithubToken(token: String) {
        githubToken = token.trim()
        hasGithubToken = githubToken.isNotBlank()
        if (githubToken.isBlank()) {
            credentialManager.clearCredential(CredentialManager.GITHUB_TOKEN)
        } else {
            credentialManager.saveCredential(CredentialManager.GITHUB_TOKEN, githubToken)
        }
        // Remove any value left by versions that used plaintext SharedPreferences.
        prefs.edit { remove("github_token") }
        // Automatically fetch username when token is set
        fetchGithubUsername(githubToken)
        fetchGithubRepos()
    }

    private fun fetchGithubUsername(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/user"
                val connection = openHttp(url)
                val login = try {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "AgenticDroid/1.0")
                    val response = connection.readResponse()
                    if (connection.responseCode !in 200..299) throw IOException("GitHub returned HTTP ${connection.responseCode}")
                    JSONObject(response).getString("login")
                } finally {
                    connection.disconnect()
                }
                
                withContext(Dispatchers.Main) {
                    updateGithubUsername(login)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to fetch GitHub username", e)
            }
        }
    }

    fun fetchGithubRepos() {
        if (githubToken.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/user/repos?sort=updated&per_page=100"
                val connection = openHttp(url)
                val response = try {
                    connection.setRequestProperty("Authorization", "Bearer $githubToken")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "AgenticDroid/1.0")
                    val body = connection.readResponse()
                    if (connection.responseCode !in 200..299) throw IOException("GitHub returned HTTP ${connection.responseCode}")
                    body
                } finally {
                    connection.disconnect()
                }
                val jsonArray = org.json.JSONArray(response)
                val repos = mutableListOf<GithubRepo>()
                for (i in 0 until jsonArray.length()) {
                    val repoJson = jsonArray.getJSONObject(i)
                    repos.add(GithubRepo(
                        name = repoJson.getString("name"),
                        fullName = repoJson.getString("full_name"),
                        cloneUrl = repoJson.getString("clone_url"),
                        isPrivate = repoJson.getBoolean("private")
                    ))
                }
                withContext(Dispatchers.Main) {
                    githubRepos = repos
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to fetch GitHub repos", e)
            }
        }
    }

    fun startGithubDeviceFlow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (CLIENT_ID.isBlank()) {
                    throw IOException("GitHub OAuth client ID is not configured")
                }
                // 1. Request device code
                val clientID = CLIENT_ID
                // The correct endpoint for the initial code request
                val url = "https://github.com/login/device/code"
                
                // Constructing the body manually to ensure no double-encoding issues
                val body = "client_id=$clientID&scope=repo%20read:user"
                
                val connection = openHttp(url)
                val response = try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.setRequestProperty("User-Agent", "AgenticDroid/1.0")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                    val responseBody = connection.readResponse(64_000)
                    if (connection.responseCode !in 200..299) {
                        throw IOException("GitHub returned HTTP ${connection.responseCode}")
                    }
                    responseBody
                } finally {
                    connection.disconnect()
                }
                val json = JSONObject(response)
                
                val deviceCode = json.getString("device_code")
                val userCode = json.getString("user_code")
                val verificationUri = json.getString("verification_uri")
                val interval = json.getLong("interval")
                
                withContext(Dispatchers.Main) {
                    githubDeviceFlowState = GithubDeviceFlowState(userCode, verificationUri)
                }
                
                // 2. Poll for token
                pollForGithubToken(clientID, deviceCode, interval)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error starting GitHub Device Flow", e)
                withContext(Dispatchers.Main) {
                    gitError = "Failed to start GitHub login: ${e.localizedMessage ?: e.message}"
                }
            }
        }
    }

    private suspend fun pollForGithubToken(clientID: String, deviceCode: String, interval: Long) {
        val url = "https://github.com/login/oauth/access_token"
        val body = "client_id=$clientID&device_code=$deviceCode&grant_type=urn:ietf:params:oauth:grant-type:device_code"
        
        while (githubDeviceFlowState != null) {
            delay(interval * 1000 + 1000)
            try {
                val connection = openHttp(url)
                val response = try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.setRequestProperty("User-Agent", "AgenticDroid/1.0")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                    val responseBody = connection.readResponse(64_000)
                    if (connection.responseCode !in 200..299) {
                        throw IOException("GitHub returned HTTP ${connection.responseCode}")
                    }
                    responseBody
                } finally {
                    connection.disconnect()
                }
                val json = JSONObject(response)
                
                if (json.has("access_token")) {
                    val token = json.getString("access_token")
                    withContext(Dispatchers.Main) {
                        updateGithubToken(token)
                        githubDeviceFlowState = null
                    }
                    refreshGitStatus()
                    return
                } else {
                    val error = json.optString("error")
                    if (error == "authorization_pending") {
                        // Keep polling
                    } else if (error == "slow_down") {
                        // GitHub tells us to wait longer
                        delay(5000) 
                    } else {
                        // Stop on other errors
                        withContext(Dispatchers.Main) {
                            githubDeviceFlowState = null
                            gitError = "GitHub login failed: $error"
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w("MainViewModel", "GitHub device-flow polling failed", e)
                withContext(Dispatchers.Main) {
                    githubDeviceFlowState = null
                    gitError = "GitHub login polling failed: ${e.localizedMessage ?: "network error"}"
                }
                return
            }
        }
    }

    fun cancelGithubDeviceFlow() {
        githubDeviceFlowState = null
    }

    var isBuilding by mutableStateOf(false)
    var buildStatus by mutableStateOf<String?>(null)
    var buildLog by mutableStateOf("")
        private set
    @Volatile private var activeBuildSession: ProcessSession? = null

    fun buildAndInstall(buildCommand: String = "./gradlew assembleDebug") {
        viewModelScope.launch(Dispatchers.IO) {
            isBuilding = true
            buildStatus = "Running build..."
            
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val path = executionWorkingDirectory
            
            try {
                val session = env.exec(buildCommand, path)
                activeBuildSession = session
                val result = session.capture(timeoutMillis = 30 * 60 * 1000L, maxOutputBytes = 4 * 1024 * 1024)
                buildLog = buildString {
                    append(result.stdout)
                    append(result.stderr)
                    if (result.truncated) append("\n[build output truncated]")
                }.trim()

                if (result.exitCode == 0) {
                    buildStatus = "Build successful. Searching for APK..."
                    // Try to find the APK. Usually in app/build/outputs/apk/debug/app-debug.apk
                    // We'll search for .apk files in the project
                    val fs = env.filesystem()
                    val apkFile = findApk(fs, path)
                    
                    if (apkFile != null) {
                        buildStatus = "Downloading APK..."
                        val app = getApplication<Application>()
                        val localApk = File(app.getExternalFilesDir("downloads"), "latest_build.apk")
                        fs.downloadFile(apkFile.absolutePath, localApk)

                        val isSelfUpdate = ApkInstaller.getArchivePackageName(app, localApk) == app.packageName

                        if (!ApkInstaller.canInstallPackages(app)) {
                            buildStatus = "Enable \"Install unknown apps\" for AgenticDroid, then retry."
                            withContext(Dispatchers.Main) { ApkInstaller.requestInstallPermission(app) }
                        } else if (isSelfUpdate && ApkInstaller.signatureMatchesInstalled(app, localApk) == false) {
                            buildStatus = "Error: this build is signed differently than the installed app. " +
                                "Android will reject the install - check the release signing config."
                        } else {
                            if (isSelfUpdate) ApkInstaller.backupCurrentApk(app)
                            buildStatus = "Installing..."
                            withContext(Dispatchers.Main) {
                                ApkInstaller.installApk(app, localApk)
                            }
                            buildStatus = "Ready to install!"
                        }
                    } else {
                        buildStatus = "Error: Could not find generated APK."
                    }
                } else {
                    buildStatus = "Build failed with exit code ${result.exitCode}"
                }
            } catch (e: Exception) {
                buildStatus = "Error: ${e.message}"
            } finally {
                activeBuildSession = null
                isBuilding = false
            }
        }
    }

    fun cancelBuild() {
        activeBuildSession?.kill()
        activeBuildSession = null
        isBuilding = false
        buildStatus = "Build cancelled."
    }

    fun hasLastKnownGoodApk(): Boolean =
        ApkInstaller.lastKnownGoodBackup(getApplication()) != null

    fun restoreLastKnownGoodApk() {
        val restored = ApkInstaller.restoreLastKnownGood(getApplication())
        if (!restored) fileError = "No backed-up build found to restore."
    }

    /** Installs an APK picked from device storage, applying the same self-update safety checks as [buildAndInstall]. */
    fun installApkFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val localApk = File(app.getExternalFilesDir("downloads"), "picked_install.apk")
                val copied = app.contentResolver.openInputStream(uri)?.use { input ->
                    localApk.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false

                if (!copied) {
                    withContext(Dispatchers.Main) { fileError = "Could not read the selected file." }
                    return@launch
                }

                val isSelfUpdate = ApkInstaller.getArchivePackageName(app, localApk) == app.packageName

                when {
                    !ApkInstaller.canInstallPackages(app) -> withContext(Dispatchers.Main) {
                        fileError = "Enable \"Install unknown apps\" for AgenticDroid, then retry."
                        ApkInstaller.requestInstallPermission(app)
                    }
                    isSelfUpdate && ApkInstaller.signatureMatchesInstalled(app, localApk) == false -> {
                        withContext(Dispatchers.Main) {
                            fileError = "This APK is signed differently than the installed app - Android will reject the install."
                        }
                    }
                    else -> {
                        if (isSelfUpdate) ApkInstaller.backupCurrentApk(app)
                        withContext(Dispatchers.Main) { ApkInstaller.installApk(app, localApk) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { fileError = "Install failed: ${e.message}" }
            }
        }
    }

    private fun findApk(fs: com.justnels.agenticdroid.env.FileSystemAccess, path: String): File? {
        val commonLocations = listOf(
            "app/build/outputs/apk/debug/app-debug.apk",
            "build/outputs/apk/debug/app-debug.apk",
            "app/build/outputs/apk/release/app-release-unsigned.apk",
            "outputs/apk/debug/app-debug.apk"
        )
        
        for (loc in commonLocations) {
            val fullPath = if (path.endsWith("/")) "$path$loc" else "$path/$loc"
            if (fs.exists(fullPath)) {
                return File(fullPath)
            }
        }

        if (fs is com.justnels.agenticdroid.env.LocalFileSystemAccess) {
            return File(path).walkTopDown()
                .onEnter { directory -> directory.name !in setOf(".git", ".gradle", "node_modules") }
                .maxDepth(8)
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                .maxByOrNull(File::lastModified)
        }
        return null
    }

    private val bootstrapWorkId = MutableLiveData<java.util.UUID?>()
    val bootstrapWorkInfo: LiveData<WorkInfo?> = bootstrapWorkId.switchMap { id ->
        if (id == null) MutableLiveData(null)
        else androidx.work.WorkManager.getInstance(application).getWorkInfoByIdLiveData(id)
    }

    private var lastRequestedRunnerGroups: Set<com.justnels.agenticdroid.env.RunnerPackageGroup> =
        setOf(com.justnels.agenticdroid.env.RunnerPackageGroup.CORE)

    fun startBootstrap(groups: Set<com.justnels.agenticdroid.env.RunnerPackageGroup> = lastRequestedRunnerGroups) {
        lastRequestedRunnerGroups = groups
        environmentManager.startBootstrap(groups)
        bootstrapWorkId.value = environmentManager.bootstrapWorkId
    }

    /** Installs whatever [type] needs beyond what's already on-device. */
    fun installRunnersFor(type: com.justnels.agenticdroid.workspace.ProjectType) {
        startBootstrap(installedRunnerGroups + com.justnels.agenticdroid.env.RunnerPackageGroup.requiredFor(type))
    }

    val wifiOnlyDownloads: Boolean get() = environmentManager.wifiOnlyDownloads

    fun setWifiOnlyDownloads(enabled: Boolean) = environmentManager.updateWifiOnlyDownloads(enabled)

    fun runnerGroupSizeBytes(group: com.justnels.agenticdroid.env.RunnerPackageGroup): Long =
        environmentManager.runnerGroupSizeBytes(group)

    fun uninstallRunnerGroup(group: com.justnels.agenticdroid.env.RunnerPackageGroup) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentManager.uninstallRunnerGroup(group)
            withContext(Dispatchers.Main) { refreshNodeInstalledStatus() }
        }
    }

    fun refreshRunnerGroup(group: com.justnels.agenticdroid.env.RunnerPackageGroup) {
        environmentManager.refreshRunnerGroup(group)
        bootstrapWorkId.value = environmentManager.bootstrapWorkId
    }

    fun dismissGitError() {
        gitError = null
    }

    fun dismissGitOutput() {
        lastGitOutput = null
    }

    fun dismissBootstrap() {
        bootstrapWorkId.value = null
        environmentManager.dismissBootstrap()
    }

    fun retryBootstrap() {
        dismissBootstrap()
        startBootstrap()
    }

    fun clearBootstrap() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { environmentManager.cancelBootstrap().result.get() }
            environmentManager.bootstrapper.clear()
            withContext(Dispatchers.Main) {
                refreshNodeInstalledStatus()
                dismissBootstrap()
            }
        }
    }

    override fun onCleared() {
        activeBuildSession?.kill()
        environmentManager.close()
    }

    fun dismissFileError() {
        fileError = null
    }

    init {
        refreshNodeInstalledStatus()
        viewModelScope.launch(Dispatchers.IO) {
            environmentManager.reattachBootstrapWork()
            withContext(Dispatchers.Main) {
                bootstrapWorkId.value = environmentManager.bootstrapWorkId
            }
        }
        githubUsername = prefs.getString("github_username", "") ?: ""
        val legacyToken = prefs.getString("github_token", null)
        githubToken = credentialManager.getCredential(CredentialManager.GITHUB_TOKEN)
            ?: legacyToken.orEmpty()
        hasGithubToken = githubToken.isNotBlank()
        if (!legacyToken.isNullOrBlank()) {
            credentialManager.saveCredential(CredentialManager.GITHUB_TOKEN, legacyToken)
            prefs.edit { remove("github_token") }
        }
        hintsShown = prefs.getStringSet("hints_shown", emptySet()) ?: emptySet()
        if (githubToken.isNotBlank()) fetchGithubRepos()
        if (!workspaceRoot.exists()) workspaceRoot.mkdirs()
        refreshProjects()
        if (projects.isEmpty()) {
            val defaultProject = File(workspaceRoot, "DefaultProject")
            if (!defaultProject.exists()) {
                defaultProject.mkdirs()
                File(defaultProject, "README.md").writeText("# AgenticDroid Workspace\n\nWelcome to your mobile development environment.")
            }
            refreshProjects()
        }
    }
}
