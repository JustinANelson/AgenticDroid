package com.justnels.agenticdroid.agents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.justnels.agenticdroid.MainActivity
import com.justnels.agenticdroid.R
import com.justnels.agenticdroid.env.ExecutionEnvironment
import com.justnels.agenticdroid.env.ProcessSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A foreground Service that runs agent CLIs unattended (`claude -p "..."`, `codex exec
 * ...`) to completion, independent of any terminal PTY or activity lifecycle - unlike
 * [com.justnels.agenticdroid.terminal.TerminalService], which owns interactive sessions a
 * user is actively looking at, this owns fire-and-forget runs a user is expected to
 * background the phone during. Each run's process is kept alive by this service (not by
 * the caller holding a reference), so it survives the launching screen being left, and its
 * outcome is both persisted (via [HeadlessRunStore]) and pushed as a notification, so a
 * killed-and-relaunched app still shows what happened.
 */
class HeadlessAgentRunService : Service() {

    private val binder = HeadlessRunBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeSessions = ConcurrentHashMap<String, ProcessSession>()
    private val killedFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private lateinit var store: HeadlessRunStore

    inner class HeadlessRunBinder : Binder() {
        fun startRun(
            agent: AgentProfile,
            prompt: String,
            env: ExecutionEnvironment,
            workingDirectory: String,
            projectPath: String,
            environmentLabel: String,
            environmentVariables: Map<String, String> = emptyMap()
        ): String? = this@HeadlessAgentRunService.startRun(
            agent, prompt, env, workingDirectory, projectPath, environmentLabel, environmentVariables
        )

        fun killRun(id: String) = this@HeadlessAgentRunService.killRun(id)
        fun listRuns(): List<HeadlessAgentRun> = store.listAll()
        fun readLog(id: String): String = store.readLog(id)
        fun deleteRun(id: String) {
            if (activeJobs.containsKey(id)) return
            store.delete(id)
        }
        fun isRunning(id: String): Boolean = activeJobs.containsKey(id)
    }

    override fun onCreate() {
        super.onCreate()
        store = HeadlessRunStore(this)
        createNotificationChannels()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Returns the new run's ID, or null if this [agent] has no headless invocation. */
    private fun startRun(
        agent: AgentProfile,
        prompt: String,
        env: ExecutionEnvironment,
        workingDirectory: String,
        projectPath: String,
        environmentLabel: String,
        environmentVariables: Map<String, String>
    ): String? {
        val argv = agent.headlessArgv(prompt) ?: return null
        val id = UUID.randomUUID().toString()
        val run = HeadlessAgentRun(
            id = id,
            agentId = agent.id,
            agentName = agent.name,
            prompt = prompt,
            projectPath = projectPath,
            workingDirectory = workingDirectory,
            environmentLabel = environmentLabel,
            startedAt = System.currentTimeMillis()
        )
        store.save(run)
        val killedFlag = AtomicBoolean(false)
        killedFlags[id] = killedFlag

        // Registered before startForeground/updateSummaryNotification (rather than after
        // `launch{}` returns) so updateSummaryNotification never sees an empty activeJobs
        // for a run that's actually starting - it would otherwise immediately
        // stopForeground+stopSelf the service, undoing the promotion this whole feature
        // depends on to survive the app being backgrounded.
        val job = serviceScope.launch {
            var timedOut = false
            var exitCode: Int? = null
            var truncated = false
            try {
                val session = env.exec(argv, workingDirectory, agent.environmentVariables + environmentVariables)
                activeSessions[id] = session
                // Never written to - closed immediately so a CLI that checks/reads stdin
                // (confirmed elsewhere in this app: `agy --version` hangs on an unclosed,
                // never-fed stdin) doesn't block forever waiting for input that will never
                // come, since the argv exec overload has no `</dev/null` equivalent.
                runCatching { session.outputStream.close() }
                val watchdog = launch {
                    delay(MAX_RUN_DURATION_MILLIS)
                    if (session.isRunning()) {
                        timedOut = true
                        session.kill()
                    }
                }
                val result = DrainToLog.drain(
                    session.inputStream, session.errorStream, session::waitFor, store.logFile(id), MAX_LOG_BYTES
                )
                watchdog.cancel()
                exitCode = result.exitCode
                truncated = result.truncated
            } catch (e: Exception) {
                store.logFile(id).appendText("\n[agent run failed to start: ${e.message}]\n")
            } finally {
                activeSessions.remove(id)
                val finalStatus = when {
                    killedFlag.get() -> HeadlessRunStatus.KILLED
                    timedOut -> HeadlessRunStatus.TIMED_OUT
                    exitCode == 0 -> HeadlessRunStatus.SUCCEEDED
                    else -> HeadlessRunStatus.FAILED
                }
                val finished = run.copy(
                    finishedAt = System.currentTimeMillis(),
                    status = finalStatus,
                    exitCode = exitCode,
                    truncated = truncated
                )
                store.save(finished)
                activeJobs.remove(id)
                killedFlags.remove(id)
                notifyRunFinished(finished)
                updateSummaryNotification()
            }
        }
        activeJobs[id] = job
        startForeground(SUMMARY_NOTIFICATION_ID, buildSummaryNotification())
        updateSummaryNotification()
        return id
    }

    private fun killRun(id: String) {
        killedFlags[id]?.set(true)
        activeSessions[id]?.kill()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SUMMARY_CHANNEL_ID, "Agent Runs (in progress)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows headless agent runs currently in progress"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL_ID, "Agent Run Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifies when a headless agent run finishes"
            }
        )
    }

    private fun buildSummaryNotification(): Notification {
        val count = activeJobs.size
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (count <= 1) "1 agent run in progress" else "$count agent runs in progress"
        return NotificationCompat.Builder(this, SUMMARY_CHANNEL_ID)
            .setContentTitle("AgenticDroid")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateSummaryNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (activeJobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        runCatching { manager.notify(SUMMARY_NOTIFICATION_ID, buildSummaryNotification()) }
    }

    private fun notifyRunFinished(run: HeadlessAgentRun) {
        val manager = getSystemService(NotificationManager::class.java)
        val title = when (run.status) {
            HeadlessRunStatus.SUCCEEDED -> "${run.agentName} run finished"
            HeadlessRunStatus.TIMED_OUT -> "${run.agentName} run timed out"
            HeadlessRunStatus.KILLED -> "${run.agentName} run stopped"
            else -> "${run.agentName} run failed"
        }
        val contentIntent = PendingIntent.getActivity(
            this, run.id.hashCode(), Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(run.prompt.take(120))
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(run.id.hashCode(), notification) }
    }

    override fun onDestroy() {
        activeSessions.values.forEach { runCatching { it.kill() } }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SUMMARY_NOTIFICATION_ID = 2001
        private const val SUMMARY_CHANNEL_ID = "headless_agent_run"
        private const val RESULT_CHANNEL_ID = "headless_agent_run_result"
        private const val MAX_LOG_BYTES = 4L * 1024 * 1024
        private const val MAX_RUN_DURATION_MILLIS = 6L * 60 * 60 * 1000
    }
}
