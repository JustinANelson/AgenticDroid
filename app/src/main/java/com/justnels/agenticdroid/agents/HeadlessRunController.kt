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

    var runs by mutableStateOf<List<HeadlessAgentRun>>(emptyList())
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as HeadlessAgentRunService.HeadlessRunBinder
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

    /** Returns the new run's ID, or null if [agent] has no headless mode
     * ([AgentProfile.headlessPromptArgs] unset). */
    fun startRun(
        agent: AgentProfile,
        prompt: String,
        env: ExecutionEnvironment,
        workingDirectory: String,
        projectPath: String,
        environmentLabel: String,
        environmentVariables: Map<String, String> = emptyMap()
    ): String? {
        if (!bound) {
            bound = application.bindService(
                Intent(application, HeadlessAgentRunService::class.java), connection, Context.BIND_AUTO_CREATE
            )
        }
        val id = binder?.startRun(agent, prompt, env, workingDirectory, projectPath, environmentLabel, environmentVariables)
        refresh()
        return id
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
        if (bound) {
            runCatching { application.unbindService(connection) }
            bound = false
        }
    }
}
