package com.justnels.agenticdroid.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    fun installApk(context: Context, apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        installApk(context, apkUri)
    }

    /** Installs an APK from any readable content:// or file:// Uri, e.g. one returned by a document picker. */
    fun installApk(context: Context, apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Whether this app is currently allowed to prompt for installs of unknown APKs. */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the system screen for granting "install unknown apps" to this app. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Application id declared inside the given APK, or null if it can't be parsed. */
    fun getArchivePackageName(context: Context, apkFile: File): String? =
        context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)?.packageName

    /**
     * Compares the APK's signing certificate(s) against the currently installed app with the
     * same package name. Returns null when either signature can't be determined (e.g. the APK
     * declares a different, not-yet-installed package). Android silently rejects an install
     * whose signature doesn't match an already-installed package, so checking first turns that
     * into a clear in-app message instead of a confusing system-level failure after a long build.
     */
    @Suppress("DEPRECATION")
    fun signatureMatchesInstalled(context: Context, apkFile: File): Boolean? {
        val archiveInfo = context.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            android.content.pm.PackageManager.GET_SIGNATURES
        ) ?: return null
        if (archiveInfo.packageName != context.packageName) return null
        val installedInfo = try {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            return null
        }
        val archiveSigs = archiveInfo.signatures?.toSet() ?: return null
        val installedSigs = installedInfo.signatures?.toSet() ?: return null
        if (archiveSigs.isEmpty() || installedSigs.isEmpty()) return null
        return archiveSigs == installedSigs
    }

    private fun backupDir(context: Context): File =
        File(context.getExternalFilesDir(null), "backups").also { it.mkdirs() }

    private fun lastKnownGoodFile(context: Context): File =
        File(backupDir(context), "last_known_good.apk")

    /** Copies the currently running APK aside so a bad self-update can be rolled back. */
    fun backupCurrentApk(context: Context): File? {
        return try {
            val currentApk = File(context.applicationInfo.sourceDir)
            val backup = lastKnownGoodFile(context)
            currentApk.copyTo(backup, overwrite = true)
            backup
        } catch (e: Exception) {
            null
        }
    }

    /** The last backed-up known-good build, if one exists. */
    fun lastKnownGoodBackup(context: Context): File? =
        lastKnownGoodFile(context).takeIf { it.exists() }

    /** Reinstalls the last backed-up known-good build, if any. */
    fun restoreLastKnownGood(context: Context): Boolean {
        val backup = lastKnownGoodBackup(context) ?: return false
        installApk(context, backup)
        return true
    }
}
