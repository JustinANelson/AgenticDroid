package com.justnels.agenticdroid.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    private val binder = TerminalServiceBinder()

    inner class TerminalServiceBinder : Binder() {
        fun getService(): TerminalService = this@TerminalService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun getOrCreateSession(
        key: String,
        shellPath: String,
        cwd: String,
        args: Array<String>,
        env: Array<String>,
        client: TerminalSessionClient
    ): TerminalSession {
        return sessions.getOrPut(key) {
            TerminalSession(shellPath, cwd, args, env, 2000, client)
        }.apply {
            updateTerminalSessionClient(client)
        }
    }

    fun removeSession(key: String) {
        sessions.remove(key)?.finishIfRunning()
        if (sessions.isEmpty()) {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AgenticDroid")
            .setContentText("Terminal sessions are active in the background")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Placeholder
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        sessions.values.forEach { it.finishIfRunning() }
        sessions.clear()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "terminal_service"
    }
}
