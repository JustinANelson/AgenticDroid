package com.justnels.agenticdroid.terminal

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
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * A Foreground Service that owns and maintains active [TerminalSession]s.
 * This ensures that terminal processes (and AI agents running inside them) continue
 * running even if the UI is backgrounded or the main activity is destroyed.
 */
class TerminalService : Service() {

    private val sessions = mutableMapOf<String, TerminalSession>()
    private val sessionClients = mutableMapOf<String, TerminalSessionClient>()
    private val binder = TerminalServiceBinder()

    inner class TerminalServiceBinder : Binder() {
        fun getOrCreateSession(
            key: String,
            shellPath: String,
            cwd: String,
            args: Array<String>,
            env: Array<String>,
            client: TerminalSessionClient
        ): TerminalSession = this@TerminalService.getOrCreateSession(key, shellPath, cwd, args, env, client)

        fun removeSession(key: String) = this@TerminalService.removeSession(key)
        fun detachSessionClient(key: String, client: TerminalSessionClient) =
            this@TerminalService.detachSessionClient(key, client)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            sessions.values.toList().forEach { it.finishIfRunning() }
            sessions.clear()
            sessionClients.clear()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    fun getOrCreateSession(
        key: String,
        shellPath: String,
        cwd: String,
        args: Array<String>,
        env: Array<String>,
        client: TerminalSessionClient
    ): TerminalSession {
        val terminalSession = sessions.getOrPut(key) {
            TerminalSession(shellPath, cwd, args, env, 2000, client)
        }
        sessionClients[key] = client
        terminalSession.updateTerminalSessionClient(client)
        return terminalSession
    }

    fun detachSessionClient(key: String, client: TerminalSessionClient) {
        if (sessionClients[key] === client) {
            sessionClients.remove(key)
            sessions[key]?.updateTerminalSessionClient(DetachedTerminalClient(key))
        }
    }

    fun removeSession(key: String) {
        sessions.remove(key)?.finishIfRunning()
        sessionClients.remove(key)
        if (sessions.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Terminal Sessions",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps terminal sessions running in the background"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AgenticDroid")
            .setContentText("Terminal sessions are active in the background")
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_terminal_notification, "Stop terminals", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        sessions.values.toList().forEach { it.finishIfRunning() }
        sessions.clear()
        sessionClients.clear()
        super.onDestroy()
    }

    private inner class DetachedTerminalClient(private val key: String) : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = removeSession(key)
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "terminal_service"
        private const val ACTION_STOP_ALL = "com.justnels.agenticdroid.action.STOP_TERMINALS"
    }
}
