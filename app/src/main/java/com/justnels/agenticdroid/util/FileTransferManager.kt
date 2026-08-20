package com.justnels.agenticdroid.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import com.justnels.agenticdroid.env.ExecutionEnvironment
import com.justnels.agenticdroid.env.FileSystemAccess
import com.justnels.agenticdroid.env.ShellEscaping
import com.justnels.agenticdroid.env.copyStreamWithProgress
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD
}

enum class TransferStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferProgress(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val direction: TransferDirection,
    val remotePath: String,
    val localDescription: String,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val status: TransferStatus = TransferStatus.PENDING,
    val errorMessage: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val percentage: Int
        get() = (fraction * 100).toInt()

    val etaSeconds: Long?
        get() = if (bytesPerSecond > 0 && totalBytes > bytesTransferred) {
            (totalBytes - bytesTransferred) / bytesPerSecond
        } else null

    val isTerminal: Boolean
        get() = status == TransferStatus.COMPLETED || status == TransferStatus.FAILED || status == TransferStatus.CANCELLED
}

class FileTransferManager(private val context: Context) {
    val transfers = mutableStateListOf<TransferProgress>()
    private val transferJobs = ConcurrentHashMap<String, Job>()

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 0) return "Unknown size"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return "%.1f KB".format(kb)
            val mb = kb / 1024.0
            if (mb < 1024) return "%.1f MB".format(mb)
            val gb = mb / 1024.0
            return "%.2f GB".format(gb)
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) return "0 KB/s"
            return "${formatBytes(bytesPerSec)}/s"
        }

        fun formatEta(seconds: Long?): String {
            if (seconds == null || seconds <= 0) return ""
            if (seconds < 60) return "${seconds}s"
            val minutes = seconds / 60
            val remainingSec = seconds % 60
            return if (minutes < 60) {
                "${minutes}m ${remainingSec}s"
            } else {
                val hours = minutes / 60
                val remainingMin = minutes % 60
                "${hours}h ${remainingMin}m"
            }
        }

        fun resolveUriFileNameAndSize(contentResolver: ContentResolver, uri: Uri): Pair<String, Long> {
            var name = "file_${System.currentTimeMillis()}"
            var size = -1L
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1) {
                                cursor.getString(nameIdx)?.let { if (it.isNotBlank()) name = it }
                            }
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx != -1) {
                                size = cursor.getLong(sizeIdx)
                            }
                        }
                    }
                } catch (_: Exception) {}
            } else if (uri.scheme == "file") {
                uri.path?.let { p ->
                    val f = File(p)
                    name = f.name
                    size = f.length()
                }
            }
            return name to size
        }
    }

    fun cancelTransfer(id: String) {
        val job = transferJobs.remove(id)
        job?.cancel()
        updateTransfer(id) { it.copy(status = TransferStatus.CANCELLED) }
    }

    fun dismissTransfer(id: String) {
        cancelTransfer(id)
        transfers.removeAll { it.id == id }
    }

    fun clearCompleted() {
        transfers.removeAll { it.isTerminal }
    }

    private fun postUpdate(scope: CoroutineScope, id: String, transform: (TransferProgress) -> TransferProgress) {
        scope.launch(Dispatchers.Main) {
            updateTransfer(id, transform)
        }
    }

    fun uploadUri(
        scope: CoroutineScope,
        uri: Uri,
        remoteDirectory: String,
        fs: FileSystemAccess,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ): String {
        val (fileName, fileSize) = resolveUriFileNameAndSize(context.contentResolver, uri)
        val normalizedDir = remoteDirectory.trimEnd('/')
        val remotePath = if (normalizedDir.isEmpty() || normalizedDir == ".") fileName else "$normalizedDir/$fileName"

        val transfer = TransferProgress(
            name = fileName,
            direction = TransferDirection.UPLOAD,
            remotePath = remotePath,
            localDescription = "Picked Document",
            totalBytes = fileSize,
            status = TransferStatus.PENDING
        )
        transfers.add(0, transfer)

        val isCancelled = AtomicBoolean(false)
        val job = scope.launch(Dispatchers.IO) {
            var lastUpdateMs = System.currentTimeMillis()
            var lastBytes = 0L
            var currentSpeed = 0L

            try {
                postUpdate(scope, transfer.id) { it.copy(status = TransferStatus.IN_PROGRESS) }
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Could not open input stream from selected file")

                inputStream.use { input ->
                    fs.uploadStream(
                        inputStream = input,
                        remotePath = remotePath,
                        totalBytes = fileSize,
                        onProgress = { transferred, total ->
                            if (isCancelled.get()) throw CancellationException("Upload cancelled")
                            val now = System.currentTimeMillis()
                            val deltaMs = now - lastUpdateMs
                            if (deltaMs >= 300 || transferred == total) {
                                val deltaBytes = transferred - lastBytes
                                if (deltaMs > 0) {
                                    val instantaneousSpeed = (deltaBytes * 1000L) / deltaMs
                                    currentSpeed = if (currentSpeed == 0L) instantaneousSpeed else (currentSpeed * 7 + instantaneousSpeed * 3) / 10
                                }
                                lastUpdateMs = now
                                lastBytes = transferred
                                postUpdate(scope, transfer.id) {
                                    it.copy(
                                        bytesTransferred = transferred,
                                        totalBytes = if (total > 0) total else fileSize,
                                        bytesPerSecond = currentSpeed,
                                        status = TransferStatus.IN_PROGRESS
                                    )
                                }
                            }
                        }
                    )
                }

                withContext(Dispatchers.Main) {
                    updateTransfer(transfer.id) {
                        it.copy(
                            bytesTransferred = if (fileSize > 0) fileSize else it.bytesTransferred,
                            status = TransferStatus.COMPLETED
                        )
                    }
                    onComplete?.invoke(true, null)
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    updateTransfer(transfer.id) { it.copy(status = TransferStatus.CANCELLED) }
                    onComplete?.invoke(false, "Upload cancelled")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val error = e.localizedMessage ?: "Upload failed"
                    updateTransfer(transfer.id) { it.copy(status = TransferStatus.FAILED, errorMessage = error) }
                    onComplete?.invoke(false, error)
                }
            } finally {
                transferJobs.remove(transfer.id)
            }
        }

        job.invokeOnCompletion { if (job.isCancelled) isCancelled.set(true) }
        transferJobs[transfer.id] = job
        return transfer.id
    }

    fun uploadLocalFile(
        scope: CoroutineScope,
        localFile: File,
        remoteDirectory: String,
        fs: FileSystemAccess,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ): String {
        val fileName = localFile.name
        val fileSize = localFile.length()
        val normalizedDir = remoteDirectory.trimEnd('/')
        val remotePath = if (normalizedDir.isEmpty() || normalizedDir == ".") fileName else "$normalizedDir/$fileName"

        val transfer = TransferProgress(
            name = fileName,
            direction = TransferDirection.UPLOAD,
            remotePath = remotePath,
            localDescription = localFile.absolutePath,
            totalBytes = fileSize,
            status = TransferStatus.PENDING
        )
        transfers.add(0, transfer)

        val isCancelled = AtomicBoolean(false)
        val job = scope.launch(Dispatchers.IO) {
            var lastUpdateMs = System.currentTimeMillis()
            var lastBytes = 0L
            var currentSpeed = 0L

            try {
                postUpdate(scope, transfer.id) { it.copy(status = TransferStatus.IN_PROGRESS) }
                localFile.inputStream().use { input ->
                    fs.uploadStream(
                        inputStream = input,
                        remotePath = remotePath,
                        totalBytes = fileSize,
                        onProgress = { transferred, total ->
                            if (isCancelled.get()) throw CancellationException("Upload cancelled")
                            val now = System.currentTimeMillis()
                            val deltaMs = now - lastUpdateMs
                            if (deltaMs >= 300 || transferred == total) {
                                val deltaBytes = transferred - lastBytes
                                if (deltaMs > 0) {
                                    val instantaneousSpeed = (deltaBytes * 1000L) / deltaMs
                                    currentSpeed = if (currentSpeed == 0L) instantaneousSpeed else (currentSpeed * 7 + instantaneousSpeed * 3) / 10
                                }
                                lastUpdateMs = now
                                lastBytes = transferred
                                postUpdate(scope, transfer.id) {
                                    it.copy(
                                        bytesTransferred = transferred,
                                        totalBytes = if (total > 0) total else fileSize,
                                        bytesPerSecond = currentSpeed,
                                        status = TransferStatus.IN_PROGRESS
                                    )
                                }
                            }
                        }
                    )
                }

                withContext(Dispatchers.Main) {
                    updateTransfer(transfer.id) {
                        it.copy(
                            bytesTransferred = fileSize,
                            status = TransferStatus.COMPLETED
                        )
                    }
                    onComplete?.invoke(true, null)
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    updateTransfer(transfer.id) { it.copy(status = TransferStatus.CANCELLED) }
                    onComplete?.invoke(false, "Upload cancelled")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val error = e.localizedMessage ?: "Upload failed"
                    updateTransfer(transfer.id) { it.copy(status = TransferStatus.FAILED, errorMessage = error) }
                    onComplete?.invoke(false, error)
                }
            } finally {
                transferJobs.remove(transfer.id)
            }
        }

        job.invokeOnCompletion { if (job.isCancelled) isCancelled.set(true) }
        transferJobs[transfer.id] = job
        return transfer.id
    }

    fun downloadFile(
        scope: CoroutineScope,
        remotePath: String,
        localDestFile: File,
        fs: FileSystemAccess,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ): String {
        val fileName = localDestFile.name

        val transfer = TransferProgress(
            name = fileName,
            direction = TransferDirection.DOWNLOAD,
            remotePath = remotePath,
            localDescription = localDestFile.absolutePath,
            totalBytes = -1L,
            status = TransferStatus.PENDING
        )
        transfers.add(0, transfer)

        val isCancelled = AtomicBoolean(false)
        val job = scope.launch(Dispatchers.IO) {
            var lastUpdateMs = System.currentTimeMillis()
            var lastBytes = 0L
            var currentSpeed = 0L
            val knownSize = runCatching { fs.getFileSize(remotePath) }.getOrDefault(-1L)

            try {
                postUpdate(scope, transfer.id) { it.copy(totalBytes = knownSize, status = TransferStatus.IN_PROGRESS) }
                fs.downloadFile(
                    remotePath = remotePath,
                    localDest = localDestFile,
                    onProgress = { transferred, total ->
                        if (isCancelled.get()) throw CancellationException("Download cancelled")
                        val now = System.currentTimeMillis()
                        val deltaMs = now - lastUpdateMs
                        if (deltaMs >= 300 || transferred == total) {
                            val deltaBytes = transferred - lastBytes
                            if (deltaMs > 0) {
                                val instantaneousSpeed = (deltaBytes * 1000L) / deltaMs
                                currentSpeed = if (currentSpeed == 0L) instantaneousSpeed else (currentSpeed * 7 + instantaneousSpeed * 3) / 10
                            }
                            lastUpdateMs = now
                            lastBytes = transferred
                            postUpdate(scope, transfer.id) {
                                it.copy(
                                    bytesTransferred = transferred,
                                    totalBytes = if (total > 0) total else knownSize,
                                    bytesPerSecond = currentSpeed,
                                    status = TransferStatus.IN_PROGRESS
                                )
                            }
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    updateTransfer(transfer.id) {
                        it.copy(
                            bytesTransferred = localDestFile.length(),
                            totalBytes = localDestFile.length(),
                            status = TransferStatus.COMPLETED
                        )
                    }
                    onComplete?.invoke(true, null)
                }
            } catch (e: CancellationException) {
                if (localDestFile.exists()) localDestFile.delete()
                withContext(Dispatchers.Main) {
                    updateTransfer(transfer.id) { it.copy(status = TransferStatus.CANCELLED) }
                    onComplete?.invoke(false, "Download cancelled")
                }
            } catch (e: Exception) {
                if (localDestFile.exists()) localDestFile.delete()
                withContext(Dispatchers.Main) {
                    val error = e.localizedMessage ?: "Download failed"
                    updateTransfer(transfer.id) { it.copy(status = TransferStatus.FAILED, errorMessage = error) }
                    onComplete?.invoke(false, error)
                }
            } finally {
                transferJobs.remove(transfer.id)
            }
        }

        job.invokeOnCompletion { if (job.isCancelled) isCancelled.set(true) }
        transferJobs[transfer.id] = job
        return transfer.id
    }

    fun downloadDirectoryAsArchive(
        scope: CoroutineScope,
        remoteDirPath: String,
        localDestFile: File,
        env: ExecutionEnvironment,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ): String {
        val fileName = localDestFile.name
        val transfer = TransferProgress(
            name = fileName,
            direction = TransferDirection.DOWNLOAD,
            remotePath = remoteDirPath,
            localDescription = localDestFile.absolutePath,
            totalBytes = -1L,
            status = TransferStatus.PENDING
        )
        transfers.add(0, transfer)

        val isCancelled = AtomicBoolean(false)
        val job = scope.launch(Dispatchers.IO) {
            var lastUpdateMs = System.currentTimeMillis()
            var lastBytes = 0L
            var currentSpeed = 0L

            localDestFile.parentFile?.mkdirs()
            val tempDest = File(localDestFile.parentFile ?: localDestFile, ".agentic-dl-${localDestFile.name}-${System.nanoTime()}.tmp")

            try {
                postUpdate(scope, transfer.id) { it.copy(status = TransferStatus.IN_PROGRESS) }

                val normalized = remoteDirPath.trimEnd('/')
                val parent = normalized.substringBeforeLast('/', "/")
                val dirName = normalized.substringAfterLast('/')
                val tarCommand = "tar -czf - -C ${ShellEscaping.quote(parent)} ${ShellEscaping.quote(dirName)}"

                val session = env.exec(tarCommand, ".")
                try {
                    tempDest.outputStream().use { out ->
                        copyStreamWithProgress(
                            input = session.inputStream,
                            output = out,
                            totalBytes = -1L,
                            onProgress = { transferred, _ ->
                                if (isCancelled.get()) {
                                    session.kill()
                                    throw CancellationException("Download cancelled")
                                }
                                val now = System.currentTimeMillis()
                                val deltaMs = now - lastUpdateMs
                                if (deltaMs >= 300) {
                                    val deltaBytes = transferred - lastBytes
                                    if (deltaMs > 0) {
                                        val instantaneousSpeed = (deltaBytes * 1000L) / deltaMs
                                        currentSpeed = if (currentSpeed == 0L) instantaneousSpeed else (currentSpeed * 7 + instantaneousSpeed * 3) / 10
                                    }
                                    lastUpdateMs = now
                                    lastBytes = transferred
                                    postUpdate(scope, transfer.id) {
                                        it.copy(
                                            bytesTransferred = transferred,
                                            totalBytes = -1L,
                                            bytesPerSecond = currentSpeed,
                                            status = TransferStatus.IN_PROGRESS
                                        )
                                    }
                                }
                            }
                        )
                    }
                    val exitCode = session.waitFor()
                    if (exitCode != 0) {
                        throw java.io.IOException("Remote tar command failed with exit code $exitCode")
                    }
                } finally {
                    session.close()
                }

                if (localDestFile.exists()) localDestFile.delete()
                if (!tempDest.renameTo(localDestFile)) {
                    tempDest.copyTo(localDestFile, overwrite = true)
                    tempDest.delete()
                }

                withContext(Dispatchers.Main) {
                    updateTransfer(transfer.id) {
                        it.copy(
                            bytesTransferred = localDestFile.length(),
                            totalBytes = localDestFile.length(),
                            status = TransferStatus.COMPLETED
                        )
                    }
                    onComplete?.invoke(true, null)
                }
            } catch (e: CancellationException) {
                if (tempDest.exists()) tempDest.delete()
                withContext(Dispatchers.Main) {
                    updateTransfer(transfer.id) { it.copy(status = TransferStatus.CANCELLED) }
                    onComplete?.invoke(false, "Download cancelled")
                }
            } catch (e: Exception) {
                if (tempDest.exists()) tempDest.delete()
                withContext(Dispatchers.Main) {
                    val error = e.localizedMessage ?: "Archive download failed"
                    updateTransfer(transfer.id) { it.copy(status = TransferStatus.FAILED, errorMessage = error) }
                    onComplete?.invoke(false, error)
                }
            } finally {
                transferJobs.remove(transfer.id)
            }
        }

        job.invokeOnCompletion { if (job.isCancelled) isCancelled.set(true) }
        transferJobs[transfer.id] = job
        return transfer.id
    }

    private fun updateTransfer(id: String, transform: (TransferProgress) -> TransferProgress) {
        val index = transfers.indexOfFirst { it.id == id }
        if (index != -1) {
            transfers[index] = transform(transfers[index])
        }
    }
}
