package com.justnels.agenticdroid.agents.compat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.justnels.agenticdroid.MainActivity
import com.justnels.agenticdroid.R
import com.justnels.agenticdroid.agents.AgentManager
import com.justnels.agenticdroid.env.EnvironmentManager
import com.justnels.agenticdroid.env.EnvironmentConfig
import com.justnels.agenticdroid.env.capture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodically re-runs [AgentCompatChecker] for every installed agent, so a QEMU-wrapper
 * regression (the toolchain's dominant failure mode - see AgentProfile.kt's install
 * commands) is caught on a schedule rather than only when a user happens to hit it live.
 * Scoped to the Node environment only: that's the only environment whose agent binaries go
 * through the QEMU/native-wrapper patching this harness exists to watch - a real SSH/LAN
 * machine or the bare Local shell have no equivalent failure mode to detect.
 */
class CompatCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val environmentManager = EnvironmentManager(applicationContext)
        if (environmentManager.activeEnvironment != EnvironmentConfig.Node || !environmentManager.bootstrapper.isInstalled()) {
            return@withContext Result.success()
        }
        val env = runCatching { environmentManager.getExecutionEnvironment(EnvironmentConfig.Node) }
            .getOrNull() ?: return@withContext Result.success()
        val agentManager = AgentManager(applicationContext)
        val store = CompatCheckStore(applicationContext)
        val workingDirectory = applicationContext.filesDir.absolutePath

        agentManager.agents.forEach { agent ->
            val isInstalled = runCatching {
                env.exec("command -v ${agent.command} >/dev/null 2>&1", workingDirectory)
                    .capture(timeoutMillis = 15_000).exitCode == 0
            }.getOrDefault(false)
            if (!isInstalled) return@forEach

            val previous = store.latestFor(agent.id)
            val outcome = runCatching {
                AgentCompatChecker.check(agent, env, workingDirectory, environmentLabel = "On-device toolchain")
            }.getOrNull() ?: return@forEach
            store.record(outcome.result)

            val regressed = outcome.result.status != CompatStatus.OK &&
                (previous == null || previous.status == CompatStatus.OK)
            if (regressed) notifyRegression(agent.name, outcome.result)
        }
        Result.success()
    }

    private fun notifyRegression(agentName: String, result: CompatCheckResult) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent Compatibility", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifies when an installed agent fails its periodic compatibility check"
            }
        )
        val contentIntent = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("$agentName may need repair")
            .setContentText("Compatibility check failed: ${result.outputSnippet.take(120)}")
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(agentName.hashCode(), notification) }
    }

    companion object {
        const val WORK_NAME = "compat_check_periodic"
        private const val CHANNEL_ID = "compat_check_channel"
    }
}
