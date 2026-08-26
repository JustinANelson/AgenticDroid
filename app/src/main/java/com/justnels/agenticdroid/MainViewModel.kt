package com.justnels.agenticdroid

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.justnels.agenticdroid.lsp.LspManager
import com.justnels.agenticdroid.mcp.ContextBridgeServer
import org.json.JSONArray
import org.json.JSONObject
import org.eclipse.lsp4j.PublishDiagnosticsParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.justnels.agenticdroid.util.NetworkUtil
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

private fun migrateLegacyWorkspaces(application: Application): File {
    val internalRoot = File(application.filesDir, "workspaces").also { it.mkdirs() }
    val migrationMarker = File(application.filesDir, LEGACY_MIGRATION_MARKER)
    if (migrationMarker.isFile) return internalRoot
    val externalFiles = application.getExternalFilesDir(null) ?: return internalRoot
    val legacyRoot = File(externalFiles, "workspaces")
    var migrationSucceeded = true
    legacyRoot.listFiles()?.filter(File::isDirectory)?.forEach { legacyProject ->
        val destination = File(internalRoot, legacyProject.name)
        if (destination.exists()) return@forEach
        val staging = File(internalRoot, ".migrating-${legacyProject.name}")
        runCatching {
            staging.deleteRecursively()
            copyProjectForMigration(legacyProject, staging)
            check(staging.renameTo(destination)) { "Could not finalize ${legacyProject.name}" }
        }.onFailure { error ->
            migrationSucceeded = false
            staging.deleteRecursively()
            Log.e("WorkspaceMigration", "Could not migrate ${legacyProject.name}", error)
        }
    }
    if (migrationSucceeded) migrationMarker.writeText("complete")
    return internalRoot
}

private fun copyProjectForMigration(source: File, destination: File) {
    if (Files.isSymbolicLink(source.toPath()) || source.name == "node_modules") return
    if (source.isDirectory) {
        check(destination.mkdirs() || destination.isDirectory)
        source.listFiles()?.forEach { child ->
            copyProjectForMigration(child, File(destination, child.name))
        }
    } else if (source.isFile) {
        source.copyTo(destination, overwrite = false)
    }
}

private const val LEGACY_MIGRATION_MARKER = ".legacy-workspaces-migrated"

data class GithubRepo(
    val name: String,
    val fullName: String,
    val cloneUrl: String,
    val sshUrl: String,
    val isPrivate: Boolean
)

data class GithubDeviceFlowState(
    val userCode: String,
    val verificationUri: String
)

data class EditorSession(
    val file: File,
    val environment: com.justnels.agenticdroid.env.EnvironmentConfig?,
    val initialContent: String,
    var content: String,
    var isDirty: Boolean = false
)

data class DiscoveredServer(val name: String, val host: String, val port: Int, val type: ServerType)
enum class ServerType { SSH, LAN }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val workspaceRoot = migrateLegacyWorkspaces(application)
    
    val workspaceManager = WorkspaceManager(workspaceRoot)
    val agentManager = AgentManager(application)
    val headlessRunController = com.justnels.agenticdroid.agents.HeadlessRunController(application)
    val environmentManager = EnvironmentManager(application)
    val transferManager = com.justnels.agenticdroid.util.FileTransferManager(application)
    val transfers get() = transferManager.transfers
    val lspManager = LspManager { ext, params ->
        viewModelScope.launch(Dispatchers.Main) {
            updateDiagnostics(params)
        }
    }

    private val bridgeServer = ContextBridgeServer(
        port = 41337,
        getActiveTab = {
            activeSession?.let { session ->
                JSONObject().apply {
                    put("name", session.file.name)
                    put("path", session.file.absolutePath)
                    put("content", session.content)
                    put("isDirty", session.isDirty)
                }
            }
        },
        getAllTabs = {
            JSONArray().apply {
                editorSessions.forEach { session ->
                    put(JSONObject().apply {
                        put("name", session.file.name)
                        put("path", session.file.absolutePath)
                    })
                }
            }
        },
        saveActiveTab = {
            saveActiveSession()
            true
        }
    )

    var diagnostics by mutableStateOf<Map<String, List<org.eclipse.lsp4j.Diagnostic>>>(emptyMap())
        private set

    private fun updateDiagnostics(params: PublishDiagnosticsParams) {
        diagnostics = diagnostics + (params.uri to params.diagnostics)
    }

    var completionResults by mutableStateOf<List<org.eclipse.lsp4j.CompletionItem>>(emptyList())
        private set

    fun triggerCompletion(line: Int, character: Int) {
        val session = activeSession ?: return
        val ext = session.file.extension
        val uri = "file://${session.file.absolutePath}"
        
        viewModelScope.launch(Dispatchers.IO) {
            val future = lspManager.requestCompletion(ext, uri, line, character)
            val result = runCatching { future?.get() }.getOrNull() ?: return@launch
            
            val items = if (result.isLeft) result.left else result.right.items
            withContext(Dispatchers.Main) {
                completionResults = items
            }
        }
    }

    var activeAgent by mutableStateOf<com.justnels.agenticdroid.agents.AgentProfile?>(null)
        private set

    fun onAgentLaunched(agent: com.justnels.agenticdroid.agents.AgentProfile) {
        activeAgent = agent
    }

    fun onAgentStopped() {
        activeAgent = null
    }

    fun addCustomAgent(profile: com.justnels.agenticdroid.agents.AgentProfile) {
        agentManager.addAgent(profile)
    }

    fun removeCustomAgent(agentId: String) {
        agentManager.removeAgent(agentId)
    }

    fun isCustomAgent(agentId: String): Boolean {
        return com.justnels.agenticdroid.agents.DefaultAgents.All.none { it.id == agentId }
    }

    /** Starts [agent] unattended (see [HeadlessAgentRunService]) against the currently
     * active environment/project rather than the live terminal PTY - returns the new
     * run's ID, or null if [agent] has no headless mode
     * ([com.justnels.agenticdroid.agents.AgentProfile.headlessPromptArgs] unset) or the
     * active environment couldn't be opened. */
    fun startHeadlessAgentRun(agent: com.justnels.agenticdroid.agents.AgentProfile, prompt: String): String? {
        val env = runCatching { environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment) }
            .getOrNull() ?: return null
        val project = selectedProject
        val secrets = project?.let { projectSecretsStore.getSecrets(it) } ?: emptyMap()
        val label = when (val active = environmentManager.activeEnvironment) {
            is com.justnels.agenticdroid.env.EnvironmentConfig.SSH -> "${active.config.username}@${active.config.host}"
            is com.justnels.agenticdroid.env.EnvironmentConfig.LAN -> "LAN Agent: ${active.name}"
            com.justnels.agenticdroid.env.EnvironmentConfig.Node -> "On-device toolchain"
            com.justnels.agenticdroid.env.EnvironmentConfig.Local -> "Local"
        }
        return headlessRunController.startRun(
            agent = agent,
            prompt = prompt,
            env = env,
            workingDirectory = executionWorkingDirectory,
            projectPath = project?.path ?: executionWorkingDirectory,
            environmentLabel = label,
            environmentVariables = secrets
        )
    }

    var installedAgentIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var isCheckingInstalledAgents by mutableStateOf(false)
        private set

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

    var agentVersions by mutableStateOf<Map<String, com.justnels.agenticdroid.agents.AgentVersionInfo>>(emptyMap())
        private set
    var checkingVersionForAgentId by mutableStateOf<String?>(null)
        private set
    var updatingAgentId by mutableStateOf<String?>(null)
        private set

    fun checkAgentVersion(agent: com.justnels.agenticdroid.agents.AgentProfile) {
        val env = runCatching { environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment) }
            .getOrNull() ?: return
        val path = executionWorkingDirectory
        checkingVersionForAgentId = agent.id
        viewModelScope.launch(Dispatchers.IO) {
            val installed = runCatching {
                env.exec(agent.installedVersionCommand(), path).capture(timeoutMillis = 20_000)
            }.getOrNull()?.takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf { it.isNotBlank() }
            val latest = agent.latestVersionCommand()?.let { cmd ->
                runCatching { env.exec(cmd, path).capture(timeoutMillis = 20_000) }
                    .getOrNull()?.takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf { it.isNotBlank() }
            }
            withContext(Dispatchers.Main) {
                agentVersions = agentVersions + (agent.id to com.justnels.agenticdroid.agents.AgentVersionInfo(installed, latest))
                checkingVersionForAgentId = null
            }
        }
    }

    fun updateAgent(agent: com.justnels.agenticdroid.agents.AgentProfile) {
        val env = runCatching { environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment) }
            .getOrNull() ?: return
        val path = executionWorkingDirectory
        updatingAgentId = agent.id
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val scriptFile = File(getApplication<Application>().cacheDir, "agent-update-${agent.id}.sh")
                scriptFile.writeText(agent.updateCommand())
                env.exec(
                    "sh ${com.justnels.agenticdroid.env.ShellEscaping.quote(scriptFile.absolutePath)}",
                    path
                ).capture(timeoutMillis = 10 * 60 * 1000L).also { scriptFile.delete() }
            }
            withContext(Dispatchers.Main) { updatingAgentId = null }
            checkAgentVersion(agent)
            refreshInstalledAgents()
        }
    }

    var currentScreen by mutableStateOf(Screen.Workspace)
    var editorSessions = androidx.compose.runtime.mutableStateListOf<EditorSession>()
    var activeSessionIndex by mutableStateOf(-1)
    val activeSession: EditorSession? get() = editorSessions.getOrNull(activeSessionIndex)

    var fileError by mutableStateOf<String?>(null)
    var isCreatingFile by mutableStateOf(false)
    var resetRequested by mutableStateOf(false)
        private set

    var selectedProject by mutableStateOf<Project?>(null)
    var projects by mutableStateOf<List<Project>>(emptyList())
    var projectNodes by mutableStateOf<List<com.justnels.agenticdroid.workspace.FileNode>>(emptyList())
        private set
    var isProjectTreeLoading by mutableStateOf(false)
        private set
    var projectTreeError by mutableStateOf<String?>(null)
        private set
    private var projectTreeGeneration = 0

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

    var preferSshGitRemote by mutableStateOf(false)
        private set

    fun updatePreferSshGitRemote(enabled: Boolean) {
        preferSshGitRemote = enabled
        prefs.edit { putBoolean("prefer_ssh_git_remote", enabled) }
    }

    var shortenDirectoryNames by mutableStateOf(false)
        private set

    fun updateShortenDirectoryNames(enabled: Boolean) {
        shortenDirectoryNames = enabled
        prefs.edit { putBoolean("shorten_directory_names", enabled) }
    }

    var confirmDestructiveGitActions by mutableStateOf(true)
        private set

    fun updateConfirmDestructiveGitActions(enabled: Boolean) {
        confirmDestructiveGitActions = enabled
        prefs.edit { putBoolean("confirm_destructive_git_actions", enabled) }
    }

    var keepScreenOnDuringTerminal by mutableStateOf(true)
        private set

    fun updateKeepScreenOnDuringTerminal(enabled: Boolean) {
        keepScreenOnDuringTerminal = enabled
        prefs.edit { putBoolean("keep_screen_on_terminal", enabled) }
    }

    var githubDeviceFlowState by mutableStateOf<GithubDeviceFlowState?>(null)
        private set

    private val CLIENT_ID = BuildConfig.GH_CLIENT_ID

    var hintsShown by mutableStateOf<Set<String>>(emptySet())
        private set

    var isNodeInstalled by mutableStateOf(false)
        private set

    val isCoreToolchainInstalled: Boolean
        get() = isNodeInstalled

    var installedRunnerGroups by mutableStateOf<Set<com.justnels.agenticdroid.env.RunnerPackageGroup>>(emptySet())
        private set

    private val prefs = application.getSharedPreferences("agentic_prefs", Context.MODE_PRIVATE)
    private val credentialManager = CredentialManager(application)
    private val projectSecretsStore = com.justnels.agenticdroid.workspace.ProjectSecretsStore(credentialManager)

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
            activeWebDevSession?.kill()
            environmentManager.close()
            runCatching { environmentManager.cancelBootstrap().result.get() }
            getApplication<Application>().stopService(
                android.content.Intent(getApplication(), com.justnels.agenticdroid.terminal.TerminalService::class.java)
            )
            prefs.edit { clear() }
            getApplication<Application>().getSharedPreferences("environment", Context.MODE_PRIVATE).edit { clear() }
            credentialManager.clearAll()
            workspaceRoot.deleteRecursively()
            getApplication<Application>().getExternalFilesDir(null)?.let { externalFiles ->
                File(externalFiles, "workspaces").deleteRecursively()
            }
            File(getApplication<Application>().filesDir, LEGACY_MIGRATION_MARKER).delete()
            environmentManager.bootstrapper.clear()
            withContext(Dispatchers.Main) {
                refreshNodeInstalledStatus()
                resetRequested = true
            }
        }
    }

    val isNodeEnvironment: Boolean
        get() = environmentManager.activeEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.Node ||
                environmentManager.activeEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.SSH ||
                environmentManager.activeEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.LAN

    val executionWorkingDirectory: String
        get() = when (val active = environmentManager.activeEnvironment) {
            is com.justnels.agenticdroid.env.EnvironmentConfig.SSH -> {
                selectedProject?.path?.takeIf { it.isNotBlank() } ?: active.config.workingDirectory
            }
            is com.justnels.agenticdroid.env.EnvironmentConfig.LAN -> {
                selectedProject?.path?.takeIf { it.isNotBlank() } ?: "."
            }
            else -> selectedProject?.path ?: workspaceRoot.absolutePath
        }

    fun refreshNodeInstalledStatus() {
        isNodeInstalled = if (environmentManager.activeEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.SSH ||
            environmentManager.activeEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.LAN
        ) {
            true
        } else {
            environmentManager.bootstrapper.isInstalled()
        }
        installedRunnerGroups = environmentManager.installedRunnerGroups()
    }

    fun missingRunnerGroups(type: com.justnels.agenticdroid.workspace.ProjectType): Set<com.justnels.agenticdroid.env.RunnerPackageGroup> =
        com.justnels.agenticdroid.env.RunnerPackageGroup.requiredFor(type) - installedRunnerGroups

    var doctorResults by mutableStateOf<List<com.justnels.agenticdroid.env.DoctorResult>>(emptyList())
        private set
    var isRunningDiagnostics by mutableStateOf(false)
        private set

    fun runDiagnostics() {
        if (!environmentManager.bootstrapper.isInstalled()) {
            doctorResults = emptyList()
            return
        }
        val env = com.justnels.agenticdroid.env.NodeExecutionEnvironment(getApplication())
        val groups = environmentManager.installedRunnerGroups()
        isRunningDiagnostics = true
        viewModelScope.launch(Dispatchers.IO) {
            val results = groups.map { group ->
                val command = com.justnels.agenticdroid.env.ToolchainDoctor.healthCheckCommand(group)
                val result = runCatching {
                    env.exec(command, workspaceRoot.absolutePath).capture(timeoutMillis = 30_000)
                }.getOrNull()
                com.justnels.agenticdroid.env.DoctorResult(
                    group = group,
                    healthy = result?.exitCode == 0,
                    output = result?.let { (it.stdout + it.stderr).trim() } ?: "Could not run diagnostics"
                )
            }.sortedBy { it.group.displayName }
            withContext(Dispatchers.Main) {
                doctorResults = results
                isRunningDiagnostics = false
            }
        }
    }

    private val gitManager: GitManager
        get() {
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val path = executionWorkingDirectory
            // The bundled cert.pem only exists on-device; passing its path to a remote git
            // process (SSH/LAN) makes http.sslCAInfo point at a nonexistent file there, so
            // only wire it up for environments that actually execute locally.
            val isLocalEnvironment = environmentManager.activeEnvironment == com.justnels.agenticdroid.env.EnvironmentConfig.Local ||
                environmentManager.activeEnvironment == com.justnels.agenticdroid.env.EnvironmentConfig.Node
            val sslPath = if (isLocalEnvironment) {
                com.justnels.agenticdroid.env.NodeRuntime.usrDir(getApplication()).let { usr ->
                    File(usr, "etc/tls/cert.pem").absolutePath
                }
            } else {
                null
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

    fun getProjectType(project: Project): com.justnels.agenticdroid.workspace.ProjectType {
        if (project.isRemote) {
            val allPaths = mutableSetOf<String>()
            fun collectPaths(nodes: List<com.justnels.agenticdroid.workspace.FileNode>) {
                for (node in nodes) {
                    allPaths.add(node.name)
                    val rel = node.path.removePrefix(project.path).trimStart('/')
                    if (rel.isNotBlank()) allPaths.add(rel)
                    if (node.children.isNotEmpty()) {
                        collectPaths(node.children)
                    }
                }
            }
            collectPaths(projectNodes)
            return com.justnels.agenticdroid.workspace.ProjectType.detectFromPaths(allPaths)
        }
        return workspaceManager.getProjectType(project)
    }

    fun getProjectActions(project: Project): List<com.justnels.agenticdroid.workspace.ProjectRunnerAction> {
        val type = getProjectType(project)
        val meta = getProjectMetadata(project)
        return com.justnels.agenticdroid.workspace.ProjectRunnerAction.defaultActionsFor(type, meta)
    }

    fun getProjectMetadata(project: Project): com.justnels.agenticdroid.workspace.ProjectMetadata {
        if (project.isRemote) {
            val detectedType = getProjectType(project)
            return com.justnels.agenticdroid.workspace.ProjectMetadata(type = detectedType)
        }
        return workspaceManager.getProjectMetadata(project)
    }

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
        val project = selectedProject ?: return
        val projectType = getProjectType(project)
        if (action.isBuild && projectType == com.justnels.agenticdroid.workspace.ProjectType.ANDROID) {
            val secrets = projectSecretsStore.getSecrets(project)
            buildAndInstall(action.command, secrets)
        } else {
            if (action.id == "web_dev" && isNodeEnvironment && !project.isRemote) {
                val preparation = com.justnels.agenticdroid.workspace.WebProjectPreflight.prepare(
                    File(project.path),
                    action.command
                )
                if (preparation.error != null) {
                    fileError = preparation.error
                    return
                }
                startWebDevServer(
                    preparation.command,
                    project.path,
                    projectSecretsStore.getSecrets(project),
                    preparation.installRequired
                )
                action.previewUrl?.let { webPreviewUrl = it }
                currentScreen = Screen.WebPreview
                return
            }
            if (action.command.isNotBlank() && terminalViewModel != null) {
                val prelude = projectSecretsStore.exportPrelude(project)
                terminalViewModel.sendCommand(prelude + action.command)
                currentScreen = Screen.Terminal
            } else if (action.opensPreview) {
                action.previewUrl?.let { webPreviewUrl = it }
                currentScreen = Screen.WebPreview
            }
        }
    }

    var projectSecrets by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun refreshProjectSecrets(project: Project) {
        projectSecrets = projectSecretsStore.getSecrets(project)
    }

    fun setProjectSecret(project: Project, name: String, value: String) {
        projectSecretsStore.setSecret(project, name, value)
        refreshProjectSecrets(project)
    }

    fun removeProjectSecret(project: Project, name: String) {
        projectSecretsStore.removeSecret(project, name)
        refreshProjectSecrets(project)
    }

    fun withProjectSecretsPrelude(project: Project?, command: String): String =
        (project?.let { projectSecretsStore.exportPrelude(it) }.orEmpty()) + command

    var projectMcpServers by mutableStateOf<List<com.justnels.agenticdroid.workspace.McpServer>>(emptyList())
        private set

    fun refreshProjectMcpServers(project: Project) {
        projectMcpServers = com.justnels.agenticdroid.workspace.McpConfigStore.read(project)
    }

    fun setProjectMcpServer(project: Project, server: com.justnels.agenticdroid.workspace.McpServer) {
        com.justnels.agenticdroid.workspace.McpConfigStore.addOrUpdate(project, server)
        refreshProjectMcpServers(project)
    }

    fun removeProjectMcpServer(project: Project, name: String) {
        com.justnels.agenticdroid.workspace.McpConfigStore.remove(project, name)
        refreshProjectMcpServers(project)
    }

    fun refreshProjects() {
        projects = workspaceManager.listProjects()
        refreshCurrentProject()
    }

    fun refreshCurrentProject() {
        val generation = ++projectTreeGeneration
        val project = selectedProject
        if (project == null) {
            projectNodes = emptyList()
            isProjectTreeLoading = false
            projectTreeError = null
            return
        }

        if (project.isRemote) {
            projectNodes = emptyList()
            isProjectTreeLoading = true
            projectTreeError = null
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
                    val fs = env.filesystem()
                    val nodes = fetchRemoteTree(fs, project.path)
                    withContext(Dispatchers.Main) {
                        if (generation == projectTreeGeneration && selectedProject == project) {
                            projectNodes = nodes
                            isProjectTreeLoading = false
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to fetch remote tree", e)
                    withContext(Dispatchers.Main) {
                        if (generation == projectTreeGeneration && selectedProject == project) {
                            projectNodes = emptyList()
                            projectTreeError = e.localizedMessage ?: "Failed to list the remote directory"
                            isProjectTreeLoading = false
                        }
                    }
                }
            }
        } else {
            isProjectTreeLoading = false
            projectTreeError = null
            projectNodes = workspaceManager.getFileTree(project)
        }
    }

    private fun fetchRemoteTree(fs: com.justnels.agenticdroid.env.FileSystemAccess, rootPath: String): List<com.justnels.agenticdroid.workspace.FileNode> {
        val excludedDirectories = setOf(
            ".git", ".gradle", "build", "node_modules", "target", "dist",
            ".next", ".nuxt", ".venv", "venv", "__pycache__", ".cache", ".idea", ".vscode"
        )
        return fs.withBatch { batchFs ->
            var count = 0
            val maxEntries = 1000
            val maxDepth = 5

            fun build(path: String, depth: Int, isRoot: Boolean = false): List<com.justnels.agenticdroid.workspace.FileNode> {
                if (depth > maxDepth || count > maxEntries) return emptyList()
                val entries = if (isRoot) {
                    batchFs.listEntries(path)
                } else {
                    try {
                        batchFs.listEntries(path)
                    } catch (e: Exception) {
                        return emptyList()
                    }
                }
                return entries.mapNotNull { entry ->
                    count++
                    if (count > maxEntries) return@mapNotNull null
                    val shouldTraverse = entry.isDirectory && entry.name !in excludedDirectories && depth < maxDepth
                    com.justnels.agenticdroid.workspace.FileNode(
                        name = entry.name,
                        path = entry.path,
                        isDirectory = entry.isDirectory,
                        children = if (shouldTraverse) build(entry.path, depth + 1) else emptyList()
                    )
                }
            }
            build(rootPath, 0, isRoot = true)
        }
    }

    fun selectRemoteProject(path: String) {
        val normalized = path.replace('\\', '/').trimEnd('/')
        val name = normalized.substringAfterLast('/').ifEmpty { "RemoteProject" }
        selectProject(Project(name, path, isRemote = true))
    }

    fun selectProject(project: Project?) {
        if (selectedProject?.path != project?.path) {
            webDevGeneration++
            activeWebDevSession?.kill()
            activeWebDevSession = null
            isWebDevActive = false
            isWebDevReady = false
            webDevStatus = null
        }
        selectedProject = project
        if (project != null) {
            val meta = workspaceManager.getProjectMetadata(project)
            webPreviewUrl = meta.previewUrl ?: workspaceManager.getProjectType(project).defaultPreviewUrl
            prefs.edit {
                putString("last_project_path", project.path)
                putString("last_project_name", project.name)
                putBoolean("last_project_is_remote", project.isRemote)
            }
            
            // Ensure MCP context bridge is registered
            if (!project.isRemote) {
                val serverFile = File(getApplication<Application>().filesDir, "tools/context-server.js")
                if (serverFile.exists()) {
                    com.justnels.agenticdroid.workspace.McpConfigStore.ensureContextServerRegistered(
                        project,
                        serverFile.absolutePath
                    )
                }
            }
        } else {
            prefs.edit {
                remove("last_project_path")
                remove("last_project_name")
                remove("last_project_is_remote")
            }
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

    var isCloning by mutableStateOf(false)
        private set
    var cloneError by mutableStateOf<String?>(null)
        private set

    fun dismissCloneError() {
        cloneError = null
    }

    fun cloneProject(url: String, name: String) {
        cloneError = null
        val destination = workspaceManager.projectPath(name)
        if (destination == null) {
            cloneError = "Project name must be a single safe directory name."
            return
        }
        isCloning = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = gitManager.clone(url, destination)
                if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                    cloneError = result.message
                } else {
                    refreshProjects()
                    val newProject = projects.find { it.name == name }
                    if (newProject != null) {
                        withContext(Dispatchers.Main) {
                            selectProject(newProject)
                        }
                    }
                }
            } finally {
                isCloning = false
            }
        }
    }

    fun createFile(name: String) {
        val project = selectedProject ?: return
        fileError = null
        if (project.isRemote) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
                    val fullPath = if (project.path.endsWith("/")) "${project.path}$name" else "${project.path}/$name"
                    env.filesystem().writeFile(fullPath, "")
                    withContext(Dispatchers.Main) { refreshCurrentProject() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { fileError = "Could not create remote file: ${e.localizedMessage}" }
                }
            }
            return
        }
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
        val isRemote = project.isRemote
        val safePath = if (isRemote) path else {
            workspaceManager.resolveInsideProject(project, path)?.absolutePath ?: run {
                fileError = "Refusing to delete a file outside the selected project."
                return
            }
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
        if (project.isRemote) {
            val newPath = oldPath.substringBeforeLast('/') + "/" + newName
            viewModelScope.launch(Dispatchers.IO) {
                val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
                val renamed = env.filesystem().renameFile(oldPath, newPath)
                withContext(Dispatchers.Main) {
                    if (renamed) refreshCurrentProject() else fileError = "Could not rename the remote file."
                }
            }
            return
        }
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
        if (project.isRemote) {
            val destPath = path.substringBeforeLast('/') + "/" + newName
            viewModelScope.launch(Dispatchers.IO) {
                val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
                val copied = env.filesystem().copyFile(path, destPath)
                withContext(Dispatchers.Main) {
                    if (copied) refreshCurrentProject() else fileError = "Could not copy the remote file."
                }
            }
            return
        }
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

    fun uploadFileToRemote(uri: Uri, remoteDirectory: String) {
        val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
        val fs = env.filesystem()
        transferManager.uploadUri(viewModelScope, uri, remoteDirectory, fs) { success, error ->
            if (success) {
                refreshCurrentProject()
            } else if (error != null && error != "Upload cancelled") {
                fileError = error
            }
        }
    }

    fun uploadLocalFileToRemote(localFile: File, remoteDirectory: String) {
        val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
        val fs = env.filesystem()
        transferManager.uploadLocalFile(viewModelScope, localFile, remoteDirectory, fs) { success, error ->
            if (success) {
                refreshCurrentProject()
            } else if (error != null && error != "Upload cancelled") {
                fileError = error
            }
        }
    }

    fun downloadRemoteFile(remotePath: String, fileName: String, targetDir: File? = null) {
        val app = getApplication<Application>()
        val destinationDir = targetDir ?: File(app.getExternalFilesDir(null), "downloads").also { it.mkdirs() }
        val localDest = File(destinationDir, fileName)
        val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
        val fs = env.filesystem()
        transferManager.downloadFile(viewModelScope, remotePath, localDest, fs) { success, error ->
            if (!success && error != null && error != "Download cancelled") {
                fileError = error
            }
        }
    }

    fun downloadRemoteDirectoryArchive(remoteDirPath: String, targetDir: File? = null) {
        val app = getApplication<Application>()
        val destinationDir = targetDir ?: File(app.getExternalFilesDir(null), "downloads").also { it.mkdirs() }
        val dirName = remoteDirPath.trimEnd('/').substringAfterLast('/').ifEmpty { "remote_archive" }
        val localDest = File(destinationDir, "$dirName.tar.gz")
        val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
        transferManager.downloadDirectoryAsArchive(viewModelScope, remoteDirPath, localDest, env) { success, error ->
            if (!success && error != null && error != "Download cancelled") {
                fileError = error
            }
        }
    }

    fun cancelTransfer(id: String) = transferManager.cancelTransfer(id)
    fun dismissTransfer(id: String) = transferManager.dismissTransfer(id)
    fun clearCompletedTransfers() = transferManager.clearCompleted()

    fun openFile(path: String) {
        val project = selectedProject ?: return
        val resolved = workspaceManager.resolveInsideProject(project, path) ?: return
        val existingIndex = editorSessions.indexOfFirst { it.file.path == resolved.path && it.environment == null }
        if (existingIndex != -1) {
            activeSessionIndex = existingIndex
            return
        }
        if (project.isRemote) {
            openRemoteFile(path)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspaceManager.readTextFile(project, path) }
                .onSuccess { content -> withContext(Dispatchers.Main) {
                    val session = EditorSession(resolved, null, content, content)
                    editorSessions.add(session)
                    activeSessionIndex = editorSessions.size - 1
                    fileError = null
                    val ext = resolved.extension
                    lspManager.startServer(ext, environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment), executionWorkingDirectory)
                    lspManager.onFileOpen(ext, "file://${resolved.absolutePath}", content)
                } }
                .onFailure { error -> withContext(Dispatchers.Main) {
                    fileError = error.localizedMessage ?: "Could not open the file."
                } }
        }
    }

    fun openRemoteFile(path: String) {
        val config = environmentManager.activeEnvironment
        if (config !is com.justnels.agenticdroid.env.EnvironmentConfig.SSH &&
            config !is com.justnels.agenticdroid.env.EnvironmentConfig.LAN) return
        val existingIndex = editorSessions.indexOfFirst { it.file.path == path && it.environment == config }
        if (existingIndex != -1) {
            activeSessionIndex = existingIndex
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { environmentManager.getExecutionEnvironment(config).filesystem().readFile(path) }
                .onSuccess { content -> withContext(Dispatchers.Main) {
                    val session = EditorSession(File(path), config, content, content)
                    editorSessions.add(session)
                    activeSessionIndex = editorSessions.size - 1
                    fileError = null
                    val ext = File(path).extension
                    lspManager.startServer(ext, environmentManager.getExecutionEnvironment(config), executionWorkingDirectory)
                    lspManager.onFileOpen(ext, "file://$path", content)
                } }
                .onFailure { error -> withContext(Dispatchers.Main) {
                    fileError = error.localizedMessage ?: "Could not open the remote file."
                } }
        }
    }

    fun saveActiveSession() {
        val session = activeSession ?: return
        val project = selectedProject
        val content = session.content
        val fileEnvironment = session.environment
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (fileEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.SSH ||
                    fileEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.LAN
                ) {
                    environmentManager.getExecutionEnvironment(fileEnvironment).filesystem().writeFile(session.file.path, content)
                } else {
                    workspaceManager.writeTextFile(requireNotNull(project) { "No local project is selected" }, session.file.path, content)
                }
            }
                .onSuccess { withContext(Dispatchers.Main) {
                    session.isDirty = false
                    fileError = null
                } }
                .onFailure { error -> withContext(Dispatchers.Main) {
                    fileError = error.localizedMessage ?: "Could not save the file."
                } }
        }
    }

    fun closeActiveSession() {
        if (activeSessionIndex != -1) {
            editorSessions.removeAt(activeSessionIndex)
            activeSessionIndex = if (editorSessions.isEmpty()) -1 else (activeSessionIndex).coerceAtMost(editorSessions.size - 1)
        }
    }

    fun closeSession(index: Int) {
        if (index in editorSessions.indices) {
            editorSessions.removeAt(index)
            if (activeSessionIndex >= editorSessions.size) {
                activeSessionIndex = editorSessions.size - 1
            }
        }
    }

    fun updateActiveSessionContent(newContent: String) {
        activeSession?.let {
            if (it.content != newContent) {
                it.content = newContent
                it.isDirty = true
                val index = activeSessionIndex
                editorSessions[index] = it.copy(content = newContent, isDirty = true)
                lspManager.onFileChange(it.file.extension, "file://${it.file.absolutePath}", newContent)
            }
        }
    }

    fun clearCompletion() {
        completionResults = emptyList()
    }

    var pendingScrollToLine by mutableStateOf<Int?>(null)
        private set

    fun onLineScrolled() {
        pendingScrollToLine = null
    }

    fun goToDefinition(line: Int, character: Int) {
        val session = activeSession ?: return
        val ext = session.file.extension
        val uri = "file://${session.file.absolutePath}"

        viewModelScope.launch(Dispatchers.IO) {
            val future = lspManager.requestDefinition(ext, uri, line, character)
            val result = runCatching { future?.get() }.getOrNull() ?: return@launch

            val locations = if (result.isLeft) result.left else result.right.map { it.targetUri to it.targetRange }
            val first = locations.firstOrNull() ?: return@launch

            val targetUri: String
            val targetRange: org.eclipse.lsp4j.Range
            
            if (first is org.eclipse.lsp4j.Location) {
                targetUri = first.uri
                targetRange = first.range
            } else {
                val pair = first as Pair<*, *>
                targetUri = pair.first as String
                targetRange = pair.second as org.eclipse.lsp4j.Range
            }

            val targetPath = targetUri.removePrefix("file://")
            val targetLine = targetRange.start.line

            withContext(Dispatchers.Main) {
                if (targetPath == session.file.absolutePath) {
                    pendingScrollToLine = targetLine
                } else {
                    openFile(targetPath)
                    pendingScrollToLine = targetLine
                }
            }
        }
    }

    var usagesResults by mutableStateOf<List<org.eclipse.lsp4j.Location>>(emptyList())
        private set

    fun findUsages(line: Int, character: Int) {
        val session = activeSession ?: return
        val ext = session.file.extension
        val uri = "file://${session.file.absolutePath}"

        viewModelScope.launch(Dispatchers.IO) {
            val future = lspManager.requestReferences(ext, uri, line, character)
            val results = runCatching { future?.get() }.getOrNull() ?: emptyList()
            
            withContext(Dispatchers.Main) {
                usagesResults = results
            }
        }
    }

    fun clearUsages() {
        usagesResults = emptyList()
    }

    fun refreshGitStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = gitManager
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
            if (branch == "unknown") {
                gitError = "Not a git repository (or git not found)"
            } else if (gitError == "Not a git repository (or git not found)") {
                gitError = null
            }
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
            val manager = gitManager
            val addResult = manager.addAll()
            if (addResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                withContext(Dispatchers.Main) {
                    gitError = "Failed to stage changes: ${addResult.message}"
                }
                return@launch
            }
            val result = manager.commit(message)
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
            val manager = gitManager
            if (!manager.hasCommits()) {
                withContext(Dispatchers.Main) {
                    gitError = "Nothing to push: repository has no commits yet."
                }
                return@launch
            }
            val currentBranch = manager.getCurrentBranch()
            var result = manager.push(remote = "origin", branch = currentBranch, force = force)
            if (result is com.justnels.agenticdroid.git.GitResult.Failure && 
                (result.message.contains("no upstream branch") || result.message.contains("has no upstream"))) {
                result = manager.push(remote = "origin", branch = currentBranch, setUpstream = true, force = force)
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
            val result = gitManager.pull(rebase = rebase)
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
            val url = if (preferSshGitRemote) {
                "git@github.com:$username/$projectName.git"
            } else {
                val encodedName = java.net.URLEncoder.encode(projectName, "UTF-8").replace("+", "%20")
                "https://github.com/$username/$encodedName.git"
            }
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
            if (githubToken.isBlank()) {
                gitError = "GitHub Token required to create a repository."
                return@launch
            }
            if (cleanUsername.isBlank()) {
                gitError = "GitHub username is unknown. Please login again or set it in settings."
                return@launch
            }
            val manager = gitManager
            if (!manager.hasCommits()) {
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
            val renameResult = manager.renameBranch("main")
            if (renameResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                withContext(Dispatchers.Main) { gitError = renameResult.message }
                return@launch
            }
            refreshGitStatus()
            val currentBranch = manager.getCurrentBranch()
            val result = manager.createGitHubRepo(githubToken, cleanName, isPrivate)
            if (result is com.justnels.agenticdroid.git.GitResult.Failure) {
                gitError = result.message
                return@launch
            }
            val url = if (preferSshGitRemote) {
                "git@github.com:$cleanUsername/$cleanName.git"
            } else {
                val encodedName = java.net.URLEncoder.encode(cleanName, "UTF-8").replace("+", "%20")
                "https://github.com/$cleanUsername/$encodedName.git"
            }
            val remoteResult = manager.addRemote("origin", url)
            if (remoteResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                withContext(Dispatchers.Main) {
                    gitError = "Repo created, but adding origin failed: ${remoteResult.message}"
                }
                return@launch
            }
            val pushResult = manager.push(remote = "origin", branch = currentBranch, setUpstream = true)
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
        prefs.edit { remove("github_token") }
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

    var isFetchingGithubRepos by mutableStateOf(false)
        private set
    var githubReposError by mutableStateOf<String?>(null)
        private set

    fun fetchGithubRepos() {
        githubReposError = null
        if (githubToken.isBlank()) {
            githubReposError = "Add a GitHub token in the Git screen first."
            return
        }
        isFetchingGithubRepos = true
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
                        sshUrl = repoJson.getString("ssh_url"),
                        isPrivate = repoJson.getBoolean("private")
                    ))
                }
                withContext(Dispatchers.Main) {
                    githubRepos = repos
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to fetch GitHub repos", e)
                githubReposError = "Could not load your repos: ${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally {
                isFetchingGithubRepos = false
            }
        }
    }

    fun startGithubDeviceFlow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (CLIENT_ID.isBlank()) {
                    throw IOException("GitHub OAuth client ID is not configured")
                }
                val clientID = CLIENT_ID
                val url = "https://github.com/login/device/code"
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
                    } else if (error == "slow_down") {
                        delay(5000) 
                    } else {
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
    @Volatile private var activeWebDevSession: ProcessSession? = null
    @Volatile private var webDevGeneration = 0
    var webDevStatus by mutableStateOf<String?>(null)
        private set
    var webDevLog by mutableStateOf("")
        private set
    var isWebDevActive by mutableStateOf(false)
        private set
    var isWebDevReady by mutableStateOf(false)
        private set

    private fun startWebDevServer(
        command: String,
        workingDirectory: String,
        secrets: Map<String, String>,
        installRequired: Boolean
    ) {
        val generation = ++webDevGeneration
        activeWebDevSession?.kill()
        activeWebDevSession = null
        isWebDevActive = true
        isWebDevReady = false
        webDevStatus = if (installRequired) "Preparing project dependencies..." else "Starting local web server..."
        webDevLog = ""
        viewModelScope.launch(Dispatchers.IO) {
            var startedSession: ProcessSession? = null
            val retainedOutput = StringBuilder()
            fun retain(line: String) = synchronized(retainedOutput) {
                retainedOutput.appendLine(line)
                if (retainedOutput.length > MAX_WEB_DEV_LOG_CHARS) {
                    retainedOutput.delete(0, retainedOutput.length - MAX_WEB_DEV_LOG_CHARS)
                }
            }
            val urlPattern = Regex("""https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?\]):(\d+)""")
            val portPattern = Regex("""(?:listening on|running on|running at|available at|port)\s*(?:port\s*)?:?\s*(\d{2,5})""", RegexOption.IGNORE_CASE)
            fun updateProgressFrom(line: String) {
                val matchedPort = urlPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    ?: portPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
                val nextStatus = when {
                    line.contains("Installing project dependencies", ignoreCase = true) ->
                        "Installing project dependencies on device..."
                    matchedPort != null ->
                        "Local web server is running on port $matchedPort."
                    line.contains("Local:", ignoreCase = true) ||
                        (line.contains("VITE", ignoreCase = true) && line.contains("ready", ignoreCase = true)) ->
                        "Local web server is running."
                    else -> null
                } ?: return
                viewModelScope.launch(Dispatchers.Main) {
                    if (webDevGeneration == generation) {
                        if (matchedPort != null) {
                            webPreviewUrl = "http://localhost:$matchedPort"
                            isWebDevReady = true
                        }
                        webDevStatus = nextStatus
                        if (nextStatus.startsWith("Local web server is running")) isWebDevReady = true
                    }
                }
            }
            try {
                val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
                val session = env.exec(command, workingDirectory, secrets)
                startedSession = session
                if (webDevGeneration != generation) {
                    session.kill()
                    return@launch
                }
                activeWebDevSession = session
                val stdoutJob = launch {
                    session.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            retain(line)
                            updateProgressFrom(line)
                            Log.d("WebDevServer", line)
                        }
                    }
                }
                val stderrJob = launch {
                    session.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            retain(line)
                            updateProgressFrom(line)
                            Log.w("WebDevServer", line)
                        }
                    }
                }
                val exitCode = session.waitFor()
                stdoutJob.join()
                stderrJob.join()
                val output = synchronized(retainedOutput) { retainedOutput.toString().trim() }
                withContext(Dispatchers.Main) {
                    webDevLog = output
                    if (activeWebDevSession === session) {
                        isWebDevActive = false
                        isWebDevReady = false
                        webDevStatus = if (exitCode == 0) "Web server stopped." else "Web server failed."
                        if (exitCode != 0) {
                            fileError = buildString {
                                append("Web server failed with exit code $exitCode.")
                                if (output.isNotBlank()) append("\n\n").append(output.takeLast(4_000))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (startedSession == null || activeWebDevSession === startedSession) {
                    withContext(Dispatchers.Main) {
                        isWebDevActive = false
                        isWebDevReady = false
                        webDevStatus = "Web server failed."
                        fileError = "Could not start the web server: ${e.localizedMessage ?: "unknown error"}"
                    }
                }
            } finally {
                if (activeWebDevSession === startedSession) activeWebDevSession = null
            }
        }
    }

    fun stopWebDevServer() {
        webDevGeneration++
        activeWebDevSession?.kill()
        activeWebDevSession = null
        isWebDevActive = false
        isWebDevReady = false
        webDevStatus = "Web server stopped."
    }

    fun buildAndInstall(buildCommand: String = "./gradlew assembleDebug", secrets: Map<String, String> = emptyMap()) {
        viewModelScope.launch(Dispatchers.IO) {
            isBuilding = true
            buildStatus = "Running build..."
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val path = executionWorkingDirectory
            runCatching { com.justnels.agenticdroid.env.NodeRuntime.ensureGradleUserHomeProperties(getApplication()) }
            try {
                val session = env.exec(buildCommand, path, secrets)
                activeBuildSession = session
                val result = session.capture(timeoutMillis = 30 * 60 * 1000L, maxOutputBytes = 4 * 1024 * 1024)
                buildLog = buildString {
                    append(result.stdout)
                    append(result.stderr)
                    if (result.truncated) append("\n[build output truncated]")
                }.trim()
                if (result.exitCode == 0) {
                    buildStatus = "Build successful. Searching for APK..."
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
                            buildStatus = "Error: this build is signed differently than the installed app."
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
                            fileError = "This APK is signed differently than the installed app."
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
        val found = fs.withBatch { batchFs ->
            commonLocations.firstNotNullOfOrNull { loc ->
                val fullPath = if (path.endsWith("/")) "$path$loc" else "$path/$loc"
                if (batchFs.exists(fullPath)) File(fullPath) else null
            }
        }
        if (found != null) return found
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

    fun scanForRemoteServers(onResult: (List<DiscoveredServer>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val env = environmentManager.getExecutionEnvironment(com.justnels.agenticdroid.env.EnvironmentConfig.Local)
            val session = env.exec("mdns-scan", ".", emptyMap())
            val results = mutableListOf<DiscoveredServer>()
            val reader = session.inputStream.bufferedReader()
            delay(3000)
            session.kill()
            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("_ssh._tcp")) {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.isNotEmpty()) {
                            val host = parts[0].removeSuffix(".")
                            results.add(DiscoveredServer(host, host, 22, ServerType.SSH))
                        }
                    } else if (line.contains("_agenticdroid._tcp")) {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.isNotEmpty()) {
                            val host = parts[0].removeSuffix(".")
                            // For LAN Agents, we assume default port 41338 for now if not in TXT (mdns-scan doesn't show TXT well)
                            results.add(DiscoveredServer("LAN: $host", host, 41338, ServerType.LAN))
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                onResult(results.distinctBy { it.host + it.type })
            }
        }
    }

    var adbConnectionStatus by mutableStateOf<String?>(null)
        private set

    fun connectWirelessAdb(port: Int) {
        val ip = NetworkUtil.getPreferredAddress()
        if (ip == null) {
            adbConnectionStatus = "Could not find device IP address."
            return
        }
        val activeEnv = environmentManager.activeEnvironment
        if (activeEnv !is com.justnels.agenticdroid.env.EnvironmentConfig.SSH) {
            adbConnectionStatus = "Please activate an SSH environment first."
            return
        }
        adbConnectionStatus = "Connecting to $ip:$port..."
        viewModelScope.launch(Dispatchers.IO) {
            val env = environmentManager.getExecutionEnvironment(activeEnv)
            val result = env.exec("adb connect $ip:$port", ".", emptyMap())
                .capture(timeoutMillis = 10_000)
            withContext(Dispatchers.Main) {
                adbConnectionStatus = if (result.exitCode == 0 && result.stdout.contains("connected")) {
                    "Successfully connected to $ip:$port"
                } else {
                    "Failed: ${result.stdout.ifBlank { result.stderr }.take(100)}"
                }
            }
        }
    }

    fun dismissAdbStatus() { adbConnectionStatus = null }
    fun dismissGitError() { gitError = null }
    fun dismissGitOutput() { lastGitOutput = null }
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

    fun dismissFileError() { fileError = null }

    init {
        refreshNodeInstalledStatus()
        viewModelScope.launch {
            snapshotFlow { environmentManager.activeEnvironment }.collect {
                refreshNodeInstalledStatus()
                refreshInstalledAgents()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            environmentManager.reattachBootstrapWork()
            withContext(Dispatchers.Main) {
                bootstrapWorkId.value = environmentManager.bootstrapWorkId
            }
        }
        preferSshGitRemote = prefs.getBoolean("prefer_ssh_git_remote", false)
        shortenDirectoryNames = prefs.getBoolean("shorten_directory_names", false)
        confirmDestructiveGitActions = prefs.getBoolean("confirm_destructive_git_actions", true)
        keepScreenOnDuringTerminal = prefs.getBoolean("keep_screen_on_terminal", true)
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
        
        setupMcpBridge(application)

        if (projects.isEmpty()) {
            val defaultProject = File(workspaceRoot, "DefaultProject")
            if (!defaultProject.exists()) {
                defaultProject.mkdirs()
                File(defaultProject, "README.md").writeText("# AgenticDroid Workspace\n\nWelcome to your mobile development environment.")
            }
            refreshProjects()
        }
        val lastPath = prefs.getString("last_project_path", null)
        val lastName = prefs.getString("last_project_name", null)
        val lastIsRemote = prefs.getBoolean("last_project_is_remote", false)
        if (lastPath != null && lastName != null) {
            val project = Project(lastName, lastPath, lastIsRemote)
            if (lastIsRemote) {
                if (environmentManager.activeEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.SSH) {
                    selectProject(project)
                }
            } else if (File(lastPath).exists()) {
                selectProject(project)
            }
        }
    }

    override fun onCleared() {
        activeBuildSession?.kill()
        activeWebDevSession?.kill()
        environmentManager.close()
        bridgeServer.stop()
        headlessRunController.unbind()
    }

    private fun setupMcpBridge(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val toolsDir = File(context.filesDir, "tools").also { it.mkdirs() }
                val serverFile = File(toolsDir, "context-server.js")
                context.assets.open("tools/context-server.js").use { input ->
                    serverFile.outputStream().use { output -> input.copyTo(output) }
                }
                bridgeServer.start()
                
                // Auto-register in current project if one is selected
                selectedProject?.let { project ->
                    com.justnels.agenticdroid.workspace.McpConfigStore.ensureContextServerRegistered(
                        project,
                        serverFile.absolutePath
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to setup MCP bridge", e)
            }
        }
    }

    companion object {
        private const val MAX_WEB_DEV_LOG_CHARS = 64 * 1024
    }
}
