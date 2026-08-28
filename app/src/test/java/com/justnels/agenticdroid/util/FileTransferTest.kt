package com.justnels.agenticdroid.util

import com.justnels.agenticdroid.env.LocalFileSystemAccess
import com.justnels.agenticdroid.env.copyStreamWithProgress
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class FileTransferTest {

    @Test
    fun copyStreamWithProgressReportsByteUpdatesAccurately() {
        val data = ByteArray(256 * 1024) { (it % 128).toByte() }
        val input = ByteArrayInputStream(data)
        val output = ByteArrayOutputStream()

        val progressReports = mutableListOf<Pair<Long, Long>>()
        val totalTransferred = copyStreamWithProgress(
            input = input,
            output = output,
            totalBytes = data.size.toLong(),
            onProgress = { transferred, total ->
                progressReports.add(transferred to total)
            },
            bufferSize = 32 * 1024
        )

        assertEquals(data.size.toLong(), totalTransferred)
        assertArrayEquals(data, output.toByteArray())
        assertTrue("Expected multiple progress updates", progressReports.size >= 8)
        assertEquals(0L, progressReports.first().first)
        assertEquals(data.size.toLong(), progressReports.last().first)
        assertEquals(data.size.toLong(), progressReports.last().second)
    }

    @Test
    fun localFileSystemAccessUploadAndDownloadStreamsCorrectly() {
        val tempDir = Files.createTempDirectory("transfer-test").toFile()
        try {
            val fs = LocalFileSystemAccess()
            val largeData = ByteArray(512 * 1024) { (it % 256).toByte() }

            val remotePath = File(tempDir, "remote_large_file.bin").absolutePath
            var uploadProgressCalled = false
            fs.uploadStream(
                inputStream = ByteArrayInputStream(largeData),
                remotePath = remotePath,
                totalBytes = largeData.size.toLong(),
                onProgress = { transferred, total ->
                    if (transferred > 0) uploadProgressCalled = true
                    assertEquals(largeData.size.toLong(), total)
                }
            )

            assertTrue("Expected upload progress callback", uploadProgressCalled)
            assertEquals(largeData.size.toLong(), fs.getFileSize(remotePath))

            val localDest = File(tempDir, "downloaded_file.bin")
            var downloadProgressCalled = false
            fs.downloadFile(
                remotePath = remotePath,
                localDest = localDest,
                onProgress = { transferred, total ->
                    if (transferred > 0) downloadProgressCalled = true
                    assertEquals(largeData.size.toLong(), total)
                }
            )

            assertTrue("Expected download progress callback", downloadProgressCalled)
            assertTrue("Downloaded file should exist", localDest.exists())
            assertEquals(largeData.size.toLong(), localDest.length())
            assertArrayEquals(largeData, localDest.readBytes())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun formatBytesFormatsProperUnits() {
        assertEquals("0 B", FileTransferManager.formatBytes(0))
        assertEquals("512 B", FileTransferManager.formatBytes(512))
        assertEquals("1.0 KB", FileTransferManager.formatBytes(1024))
        assertEquals("1.5 KB", FileTransferManager.formatBytes(1536))
        assertEquals("1.0 MB", FileTransferManager.formatBytes(1024 * 1024))
        assertEquals("128.5 MB", FileTransferManager.formatBytes((128.5 * 1024 * 1024).toLong()))
        assertEquals("1.50 GB", FileTransferManager.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatSpeedFormatsCorrectly() {
        assertEquals("0 KB/s", FileTransferManager.formatSpeed(0))
        assertEquals("500.0 KB/s", FileTransferManager.formatSpeed(500 * 1024))
        assertEquals("2.5 MB/s", FileTransferManager.formatSpeed((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatEtaFormatsSecondsMinutesHours() {
        assertEquals("", FileTransferManager.formatEta(null))
        assertEquals("", FileTransferManager.formatEta(0))
        assertEquals("45s", FileTransferManager.formatEta(45))
        assertEquals("2m 15s", FileTransferManager.formatEta(135))
        assertEquals("1h 30m", FileTransferManager.formatEta(5400))
    }

    @Test
    fun transferProgressCalculationsAreAccurate() {
        val progress = TransferProgress(
            name = "dataset.zip",
            direction = TransferDirection.UPLOAD,
            remotePath = "/home/user/dataset.zip",
            localDescription = "/sdcard/dataset.zip",
            bytesTransferred = 25 * 1024 * 1024,
            totalBytes = 100 * 1024 * 1024,
            bytesPerSecond = 5 * 1024 * 1024,
            status = TransferStatus.IN_PROGRESS
        )

        assertEquals(0.25f, progress.fraction, 0.001f)
        assertEquals(25, progress.percentage)
        assertEquals(15L, progress.etaSeconds)
        assertFalse(progress.isTerminal)
    }

    @Test
    fun transferProgressTerminalStatusCheck() {
        val completed = TransferProgress(
            name = "done.txt",
            direction = TransferDirection.DOWNLOAD,
            remotePath = "done.txt",
            localDescription = "done.txt",
            status = TransferStatus.COMPLETED
        )
        assertTrue(completed.isTerminal)

        val failed = TransferProgress(
            name = "failed.txt",
            direction = TransferDirection.DOWNLOAD,
            remotePath = "failed.txt",
            localDescription = "failed.txt",
            status = TransferStatus.FAILED
        )
        assertTrue(failed.isTerminal)

        val cancelled = TransferProgress(
            name = "cancel.txt",
            direction = TransferDirection.DOWNLOAD,
            remotePath = "cancel.txt",
            localDescription = "cancel.txt",
            status = TransferStatus.CANCELLED
        )
        assertTrue(cancelled.isTerminal)

        val active = TransferProgress(
            name = "active.txt",
            direction = TransferDirection.DOWNLOAD,
            remotePath = "active.txt",
            localDescription = "active.txt",
            status = TransferStatus.IN_PROGRESS
        )
        assertFalse(active.isTerminal)
    }
}
