package com.justnels.agenticdroid.env

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.Data
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException

class BootstrapWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bootstrapper = NodeBootstrapper(applicationContext)

        // Setup notification channel
        val channelId = "bootstrap_channel"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Node Setup", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Setting up Node Environment")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)

        // Promote to a foreground service for the duration of this network-heavy,
        // multi-minute download. The manifest already declares the dataSync FGS type
        // for WorkManager's SystemForegroundService.
        try {
            setForeground(
                ForegroundInfo(
                    1,
                    notificationBuilder.build(),
                    if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
                )
            )
        } catch (e: Exception) {
            Log.w("BootstrapWorker", "Unable to promote to foreground service; network access may be restricted", e)
        }

        return try {
            bootstrapper.bootstrap { status ->
                setProgressAsync(Data.Builder().putString("status", status).build())
                try {
                    notificationManager.notify(1, notificationBuilder.setContentText(status).build())
                } catch (e: SecurityException) {
                    // Ignore
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Data.Builder().putString("error", e.message).build())
        } finally {
            notificationManager.cancel(1)
        }
    }
}
