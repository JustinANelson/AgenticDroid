package com.justnels.agenticdroid.util

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * SSH downloads land in app-private external storage (getExternalFilesDir), which the
 * system Files app and other apps generally can't browse into directly. Routing a
 * completed download through the system share sheet is the one path guaranteed to work
 * regardless of which file managers/cloud apps are installed - "Save to Files" in the
 * chooser lets the user copy it into public Downloads (or anywhere else) themselves.
 */
object FileShare {
    fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Save or share \"${file.name}\"").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
