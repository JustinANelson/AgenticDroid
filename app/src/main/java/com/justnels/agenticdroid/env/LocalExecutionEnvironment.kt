package com.justnels.agenticdroid.env

import java.io.File
import java.io.InputStream
import java.io.OutputStream

class LocalExecutionEnvironment : ExecutionEnvironment {
    override fun exec(
        command: String,
        workingDirectory: String,
        environment: Map<String, String>
    ): ProcessSession {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(File(workingDirectory))
            .apply { environment().putAll(environment) }
            .start()
        return LocalProcessSession(process)
    }

    override fun filesystem(): FileSystemAccess {
        return LocalFileSystemAccess()
    }

    override fun getEnvironmentInfo(): EnvironmentInfo {
        return EnvironmentInfo(
            name = "Local Android",
            os = System.getProperty("os.name") ?: "Android",
            architecture = System.getProperty("os.arch") ?: "unknown",
            installedTools = listOf("sh")
        )
    }

    override fun ptyShellSpec(workingDirectory: String): PtyShellSpec = PtyShellSpec(
        shellPath = "/system/bin/sh",
        args = emptyArray(),
        cwd = workingDirectory,
        env = arrayOf(
            "HOME=$workingDirectory",
            "PATH=/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "COLORTERM=truecolor"
        )
    )
}

class LocalProcessSession(private val process: Process) : ProcessSession {
    override val pid: Int
        get() = try {
            // This is a bit hacky for older Android versions, but works on modern ones
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.get(process) as Int
        } catch (e: Exception) {
            -1
        }

    override val inputStream: InputStream = process.inputStream
    override val outputStream: OutputStream = process.outputStream
    override val errorStream: InputStream = process.errorStream

    override fun isRunning(): Boolean = process.isAlive

    @Synchronized
    fun sendInput(input: String) {
        outputStream.write(input.toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    override fun kill() {
        process.destroy()
    }

    override fun waitFor(): Int {
        return process.waitFor()
    }

    override fun close() {
        runCatching { outputStream.close() }
        runCatching { inputStream.close() }
        runCatching { errorStream.close() }
    }
}

class LocalFileSystemAccess : FileSystemAccess {
    override fun listEntries(path: String): List<FileSystemEntry> {
        return File(path).listFiles()?.map {
            FileSystemEntry(it.name, it.absolutePath, it.isDirectory, it.length())
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    override fun readFile(path: String): String {
        return File(path).readText()
    }

    override fun writeFile(path: String, content: String) {
        File(path).writeText(content)
    }

    override fun deleteFile(path: String): Boolean {
        return File(path).delete()
    }

    override fun exists(path: String): Boolean {
        return File(path).exists()
    }

    override fun renameFile(oldPath: String, newPath: String): Boolean {
        return File(oldPath).renameTo(File(newPath))
    }

    override fun copyFile(srcPath: String, destPath: String): Boolean {
        return try {
            File(srcPath).copyTo(File(destPath), overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun getFileSize(path: String): Long {
        val f = File(path)
        return if (f.exists()) f.length() else -1L
    }

    override fun downloadFile(
        remotePath: String,
        localDest: File,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?
    ) {
        val src = File(remotePath)
        if (src.absolutePath == localDest.absolutePath) return
        localDest.parentFile?.mkdirs()
        src.inputStream().use { input ->
            localDest.outputStream().use { output ->
                copyStreamWithProgress(input, output, src.length(), onProgress)
            }
        }
    }

    override fun downloadStream(
        remotePath: String,
        outputStream: OutputStream,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?
    ) {
        val src = File(remotePath)
        src.inputStream().use { input ->
            copyStreamWithProgress(input, outputStream, src.length(), onProgress)
        }
    }

    override fun uploadFile(
        localSrc: File,
        remotePath: String,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?
    ) {
        val dest = File(remotePath)
        if (localSrc.absolutePath == dest.absolutePath) return
        dest.parentFile?.mkdirs()
        localSrc.inputStream().use { input ->
            dest.outputStream().use { output ->
                copyStreamWithProgress(input, output, localSrc.length(), onProgress)
            }
        }
    }

    override fun uploadStream(
        inputStream: InputStream,
        remotePath: String,
        totalBytes: Long,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?
    ) {
        val dest = File(remotePath)
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile ?: dest, ".agentic-up-${dest.name}-${System.nanoTime()}.tmp")
        try {
            temp.outputStream().use { output ->
                copyStreamWithProgress(inputStream, output, totalBytes, onProgress)
            }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
