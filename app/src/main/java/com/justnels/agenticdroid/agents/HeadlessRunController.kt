package com.justnels.agenticdroid.agents

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.justnels.agenticdroid.env.ExecutionEnvironment

/**
 * Owns the app's binding to [HeadlessAgentRunService] and exposes its state as Compose
 * state, so [com.justnels.agenticdroid.MainViewModel] can start/inspect/kill headless
 * agent runs without any screen needing to know about service binding itself. Falls back
 * to reading [HeadlessRunStore] directly when unbound (e.g. right at startup, before the
 * bind callback lands) so the run list is never just empty because of a timing gap.
 */
class HeadlessRunController(private val application: Application) {
    private val fallbackStore = HeadlessRunStore(application)
    private var binder: HeadlessAgentRunService.HeadlessRunBinder? = null
    private var bound = false
    private val pendingStarts = mutableListOf<PendingStart>()

    private data class PendingStart(
        val agent: AgentProfile,
        val prompt: String,
        val env: ExecutionEnvironment,
        val workingDirectory: String,
        val projectPath: String,
        val environmentLabel: String,
        val environmentVariables: Map<String, String>,
        val allowShell: Boolean,
        val onResult: (Result<String>) -> Unit
    )

    var runs by mutableStateOf<List<HeadlessAgentRun>>(emptyList())
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as HeadlessAgentRunService.HeadlessRunBinder
            val queued = pendingStarts.toList()
            pendingStarts.clear()
            queued.forEach(::startWithBinder)
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    init {
        refresh()
        bound = application.bindService(
            Intent(application, HeadlessAgentRunService::class.java), connection, Context.BIND_AUTO_CREATE
        )
    }

    fun refresh() {
        runs = binder?.listRuns() ?: fallbackStore.listAll()
    }

    /** Starts immediately when bound, otherwise queues until Android connects the service.
     * This avoids silently dropping a launch tapped during the normal asynchronous binding
     * window at app startup. */
    fun startRun(
        agent: AgentProfile,
        prompt: String,
        env: ExecutionEnvironment,
        workingDirectory: String,
        projectPath: String,
        environmentLabel: String,
        environmentVariables: Map<String, String> = emptyMap(),
        allowShell: Boolean = false,
        onResult: (Result<String>) -> Unit = {}
    ) {
        val request = PendingStart(
            agent, prompt, env, workingDirectory, projectPath, environmentLabel,
            environmentVariables, allowShell, onResult
        )
        val currentBinder = binder
        if (currentBinder != null) {
            startWithBinder(request)
            return
        }
        pendingStarts += request
        if (!bound) {
            bound = application.bindService(
                Intent(application, HeadlessAgentRunService::class.java), connection, Context.BIND_AUTO_CREATE
            )
            if (!bound) {
                pendingStarts.remove(request)
                onResult(Result.failure(IllegalStateException("Agent run service could not be started")))
            }
        }
    }

    private fun startWithBinder(request: PendingStart) {
        val service = binder
        if (service == null) {
            request.onResult(Result.failure(IllegalStateException("Agent run service disconnected")))
            return
        }
        val result = runCatching {
            service.startRun(
                request.agent,
                request.prompt,
                request.env,
                request.workingDirectory,
                request.projectPath,
                request.environmentLabel,
                request.environmentVariables,
                request.allowShell
            ) ?: error("${request.agent.name} does not support background runs")
        }
        refresh()
        request.onResult(result)
    }

    fun killRun(id: String) {
        binder?.killRun(id)
        refresh()
    }

    fun deleteRun(id: String) {
        val bound = binder
        if (bound != null) bound.deleteRun(id) else fallbackStore.delete(id)
        refresh()
    }

    fun readLog(id: String): String = binder?.readLog(id) ?: fallbackStore.readLog(id)

    fun isRunning(id: String): Boolean = binder?.isRunning(id) ?: false

    fun unbind() {
        pendingStarts.toList().also { pendingStarts.clear() }.forEach {
            it.onResult(Result.failure(IllegalStateException("Agent run controller closed before launch")))
        }
        if (bound) {
            runCatching { application.unbindService(connection) }
            bound = false
        }
    }
}
