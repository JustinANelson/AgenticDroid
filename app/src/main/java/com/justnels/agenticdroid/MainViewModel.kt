package com.justnels.agenticdroid

import android.app.Application
import android.content.Context
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
import com.justnels.agenticdroid.agents.AgentManager
import com.justnels.agenticdroid.env.EnvironmentManager
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
    
    var currentScreen by mutableStateOf(Screen.Workspace)
    var openedFile by mutableStateOf<File?>(null)
    var fileContent by mutableStateOf("")
    var isCreatingFile by mutableStateOf(false)

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

    var githubUsername by mutableStateOf("")
        private set
    var githubToken by mutableStateOf("")
        private set
    var githubRepos by mutableStateOf<List<GithubRepo>>(emptyList())
        private set

    var githubDeviceFlowState by mutableStateOf<GithubDeviceFlowState?>(null)
        private set

    // OAuth 2.0 Web Flow Constants
    private val CLIENT_ID = BuildConfig.GH_CLIENT_ID
    private val CLIENT_SECRET = BuildConfig.GH_CLIENT_SECRET
    private val REDIRECT_URI = "agenticdroid://github-auth"

    var hintsShown by mutableStateOf<Set<String>>(emptySet())
        private set

    var isNodeInstalled by mutableStateOf(false)
        private set

    private val prefs = application.getSharedPreferences("agentic_prefs", Context.MODE_PRIVATE)

    fun markHintShown(hintId: String) {
        val newSet = hintsShown + hintId
        hintsShown = newSet
        prefs.edit().putStringSet("hints_shown", newSet).apply()
    }

    fun wipeAppData() {
        prefs.edit().clear().apply()
        workspaceRoot.deleteRecursively()
        environmentManager.bootstrapper.clear()
        isNodeInstalled = false
        // The app will likely need a restart to fully reset state, 
        // but this clears the persistent storage.
        System.exit(0) 
    }

    val isNodeEnvironment: Boolean
        get() = environmentManager.activeEnvironment is com.justnels.agenticdroid.env.EnvironmentConfig.Node

    fun refreshNodeInstalledStatus() {
        isNodeInstalled = environmentManager.bootstrapper.isInstalled()
    }

    private val gitManager: GitManager
        get() {
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val path = selectedProject?.path ?: workspaceRoot.absolutePath
            val sslPath = com.justnels.agenticdroid.env.NodeRuntime.usrDir(getApplication()).let { usr ->
                File(usr, "etc/tls/cert.pem").absolutePath
            }
            return GitManager(env, path, sslPath)
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
        refreshCurrentProject()
    }

    fun createProject(name: String) {
        if (workspaceManager.createProject(name)) {
            refreshProjects()
        }
    }

    fun deleteProject(project: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete the directory locally.
            // In a real multi-environment app, we'd check if the project is remote.
            // For now, assuming local project management as per WorkspaceManager.
            if (File(project.path).deleteRecursively()) {
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
            val authenticatedUrl = if (githubToken.isNotBlank() && url.startsWith("https://github.com/")) {
                url.replace("https://github.com/", "https://$githubToken@github.com/")
            } else {
                url
            }
            
            val destination = File(workspaceRoot, name).absolutePath
            val result = gitManager.clone(authenticatedUrl, destination)
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
        val file = File(project.path, name)
        if (!file.exists()) {
            file.createNewFile()
            refreshCurrentProject()
        }
    }

    fun deleteFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            if (env.filesystem().deleteFile(path)) {
                refreshCurrentProject()
            }
        }
    }

    fun renameFile(oldPath: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val newPath = File(File(oldPath).parent, newName).absolutePath
            if (env.filesystem().renameFile(oldPath, newPath)) {
                refreshCurrentProject()
            }
        }
    }

    fun copyFile(path: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val newPath = File(File(path).parent, newName).absolutePath
            if (env.filesystem().copyFile(path, newPath)) {
                refreshCurrentProject()
            }
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
            gitManager.addRemote(name, url)
            refreshGitStatus()
        }
    }

    fun gitSetConfig(key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            gitManager.setConfig(key, value)
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
                manager.addAll()
                val commitResult = manager.commit("Initial commit from AgenticDroid")
                if (commitResult is com.justnels.agenticdroid.git.GitResult.Failure) {
                    gitError = "Failed to perform initial commit: ${commitResult.message}"
                    return@launch
                }
            }
            
            // Ensure branch is named 'main' for GitHub compatibility
            manager.renameBranch("main")
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
            val url = "https://$githubToken@github.com/$cleanUsername/$encodedName.git"
            manager.addRemote("origin", url)
            
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
        prefs.edit().putString("github_username", username).apply()
    }

    fun updateGithubToken(token: String) {
        githubToken = token
        prefs.edit().putString("github_token", token).apply()
        // Automatically fetch username when token is set
        fetchGithubUsername(token)
        fetchGithubRepos()
    }

    private fun fetchGithubUsername(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/user"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("Authorization", "token $token")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "AgenticDroid/1.0")
                
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val login = json.getString("login")
                
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
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("Authorization", "token $githubToken")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "AgenticDroid/1.0")
                
                val response = connection.inputStream.bufferedReader().use { it.readText() }
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
                // 1. Request device code
                // Reverting to the standard 20-character Client ID for GitHub CLI
                val clientID = "178ee327b43477bc2214" 
                // The correct endpoint for the initial code request
                val url = "https://github.com/login/device/code"
                
                // Constructing the body manually to ensure no double-encoding issues
                val body = "client_id=$clientID&scope=repo%20read:user"
                
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setRequestProperty("User-Agent", "gh/2.0.0")
                
                connection.outputStream.use { it.write(body.toByteArray()) }
                
                val code = connection.responseCode
                if (code !in 200..299) {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } 
                                    ?: connection.inputStream?.bufferedReader()?.use { it.readText() }
                                    ?: "No response body"
                    Log.e("MainViewModel", "GitHub API Error: $code - $errorBody")
                    throw IOException("GitHub Error $code: $errorBody")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
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
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setRequestProperty("User-Agent", "gh/2.0.0")
                
                connection.outputStream.use { it.write(body.toByteArray()) }
                
                val code = connection.responseCode
                if (code !in 200..299) continue // Retry on next interval

                val response = connection.inputStream.bufferedReader().use { it.readText() }
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
                // Log and maybe retry or stop
            }
        }
    }

    fun cancelGithubDeviceFlow() {
        githubDeviceFlowState = null
    }

    fun startGithubWebLogin(context: Context) {
        val url = "https://github.com/login/oauth/authorize" +
                  "?client_id=$CLIENT_ID" +
                  "&scope=repo%20read:user" +
                  "&redirect_uri=$REDIRECT_URI"
        
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        context.startActivity(intent)
    }

    fun handleGithubCallback(uri: android.net.Uri) {
        val code = uri.getQueryParameter("code") ?: return
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = "https://github.com/login/oauth/access_token"
                val body = "client_id=$CLIENT_ID" +
                          "&client_secret=$CLIENT_SECRET" +
                          "&code=$code" +
                          "&redirect_uri=$REDIRECT_URI"
                
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                
                connection.outputStream.use { it.write(body.toByteArray()) }
                
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                if (json.has("access_token")) {
                    val token = json.getString("access_token")
                    withContext(Dispatchers.Main) {
                        updateGithubToken(token)
                    }
                    refreshGitStatus()
                } else {
                    val error = json.optString("error_description", "Unknown error")
                    withContext(Dispatchers.Main) {
                        gitError = "GitHub Auth Failed: $error"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    gitError = "Error exchanging code for token: ${e.message}"
                }
            }
        }
    }

    var isBuilding by mutableStateOf(false)
    var buildStatus by mutableStateOf<String?>(null)

    fun buildAndInstall(buildCommand: String = "./gradlew assembleDebug") {
        viewModelScope.launch(Dispatchers.IO) {
            isBuilding = true
            buildStatus = "Running build..."
            
            val env = environmentManager.getExecutionEnvironment(environmentManager.activeEnvironment)
            val path = selectedProject?.path ?: workspaceRoot.absolutePath
            
            try {
                val session = env.exec(buildCommand, path)
                val exitCode = session.waitFor()
                
                if (exitCode == 0) {
                    buildStatus = "Build successful. Searching for APK..."
                    // Try to find the APK. Usually in app/build/outputs/apk/debug/app-debug.apk
                    // We'll search for .apk files in the project
                    val fs = env.filesystem()
                    val apkFile = findApk(fs, path)
                    
                    if (apkFile != null) {
                        buildStatus = "Downloading APK..."
                        val localApk = File(getApplication<Application>().getExternalFilesDir("downloads"), "latest_build.apk")
                        fs.downloadFile(apkFile.absolutePath, localApk)
                        
                        buildStatus = "Installing..."
                        withContext(Dispatchers.Main) {
                            ApkInstaller.installApk(getApplication(), localApk)
                        }
                        buildStatus = "Ready to install!"
                    } else {
                        buildStatus = "Error: Could not find generated APK."
                    }
                } else {
                    buildStatus = "Build failed with exit code $exitCode"
                }
            } catch (e: Exception) {
                buildStatus = "Error: ${e.message}"
            } finally {
                isBuilding = false
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
        
        // Fallback: try to find any .apk in common build folders
        return try {
            // This is a bit expensive but robust
            null // For now stay with common locations
        } catch (e: Exception) {
            null
        }
    }

    private val bootstrapWorkId = MutableLiveData<java.util.UUID?>()
    val bootstrapWorkInfo: LiveData<WorkInfo?> = bootstrapWorkId.switchMap { id ->
        if (id == null) MutableLiveData(null)
        else androidx.work.WorkManager.getInstance(application).getWorkInfoByIdLiveData(id)
    }

    fun startBootstrap() {
        environmentManager.startBootstrap()
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
        environmentManager.bootstrapWorkId = null
    }

    fun clearBootstrap() {
        environmentManager.bootstrapper.clear()
        isNodeInstalled = false
        dismissBootstrap()
    }

    init {
        isNodeInstalled = environmentManager.bootstrapper.isInstalled()
        githubUsername = prefs.getString("github_username", "") ?: ""
        githubToken = prefs.getString("github_token", "") ?: ""
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
