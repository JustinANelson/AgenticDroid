package com.justnels.agenticdroid.env

import net.schmizz.sshj.SSHClient
import android.content.Context
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream
import java.net.ServerSocket

class SSHExecutionEnvironment(private val context: Context, private val config: SSHConfig) : ExecutionEnvironment {
    private var client: SSHClient? = null
    private var tunnelProcess: Process? = null
    private var tunnelLocalPort: Int = 0

    private fun startTunnel(): Int {
        val port = ServerSocket(0).use { it.localPort }
        val cloudflared = NodeRuntime.binDir(context).resolve("cloudflared")
        if (!cloudflared.exists()) {
            throw IllegalStateException("cloudflared not found. Please install the Core Toolchain in Environments.")
        }

        val pb = ProcessBuilder(
            cloudflared.absolutePath,
            "access", "tcp",
            "--hostname", config.host,
            "--listener", "127.0.0.1:$port"
        ).apply {
            val env = environment()
            NodeRuntime.configureEnvironment(context, env)
        }

        tunnelProcess = pb.start()
        tunnelLocalPort = port

        // Wait a bit for the tunnel to establish. In a production app we'd probe the port
        // or watch the stdout, but for a prototype this is usually enough for cloudflared.
        Thread.sleep(1000)
        return port
    }

    @Synchronized
    private fun getClient(): SSHClient {
        if (client == null || !client!!.isConnected) {
            val host = if (config.useCloudflareTunnel) {
                if (tunnelProcess == null || tunnelProcess?.isAlive == false) {
                    startTunnel()
                }
                "127.0.0.1"
            } else {
                config.host
            }
            val port = if (config.useCloudflareTunnel) tunnelLocalPort else config.port

            client = SSHClient().apply {
                addHostKeyVerifier(FingerprintVerifier.getInstance(config.hostKeyFingerprint))
                connect(host, port)
                when (config.authType) {
                    SSHAuthType.PASSWORD -> authPassword(
                        config.username,
                        requireNotNull(config.password) { "SSH password is missing" }
                    )
                    SSHAuthType.PRIVATE_KEY -> {
                        val temporaryKey = config.privateKeyContent?.let { content ->
                            File.createTempFile("ssh-key-", ".key", context.cacheDir).apply {
                                writeText(content)
                                setReadable(false, false)
                                setReadable(true, true)
                                setWritable(false, false)
                                setWritable(true, true)
                            }
                        }
                        val path = config.privateKeyPath ?: temporaryKey?.absolutePath
                            ?: error("SSH private key is missing")
                        val provider = try {
                            config.privateKeyPassphrase?.takeIf(String::isNotEmpty)?.let {
                                loadKeys(path, it)
                            } ?: loadKeys(path)
                        } finally {
                            temporaryKey?.delete()
                        }
                        authPublickey(config.username, provider)
                    }
                }
            }
        }
        return client!!
    }

    override fun exec(
        command: String,
        workingDirectory: String,
        environment: Map<String, String>
    ): ProcessSession {
        val session = getClient().startSession()
        val environmentPrefix = environment.entries.joinToString(" ") { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid environment variable name" }
            "$key=${ShellEscaping.quote(value)}"
        }
        val remoteCommand = if (environmentPrefix.isBlank()) command else "env $environmentPrefix $command"
        val cmd = session.exec("cd ${ShellEscaping.quote(workingDirectory)} && $remoteCommand")
        return SSHProcessSession(session, cmd)
    }

    override fun filesystem(): FileSystemAccess {
        return SSHFileSystemAccess(::getClient)
    }

    override fun getEnvironmentInfo(): EnvironmentInfo {
        return EnvironmentInfo(
            name = "Remote SSH: ${config.host}",
            os = "Linux (Remote)",
            architecture = "unknown",
            installedTools = emptyList()
        )
    }

    override fun ptyShellSpec(workingDirectory: String): PtyShellSpec? {
        val sshBin = NodeRuntime.binDir(context).resolve("ssh")
        if (!sshBin.exists()) return null

        val envMap = mutableMapOf<String, String>()
        NodeRuntime.configureEnvironment(context, envMap)
        
        // We use the local ssh binary to connect to the remote host.
        // We force a PTY (-t) and immediately CD to the project directory.
        val args = mutableListOf(
            "-p", config.port.toString(),
            "-o", "StrictHostKeyChecking=accept-new",
            "-t",
            "${config.username}@${config.host}",
            "cd ${ShellEscaping.quote(workingDirectory)} ; exec \$SHELL -l"
        )

        return PtyShellSpec(
            shellPath = sshBin.absolutePath,
            args = args.toTypedArray(),
            cwd = context.filesDir.absolutePath,
            env = envMap.map { (key, value) -> "$key=$value" }.toTypedArray()
        )
    }

    @Synchronized
    override fun close() {
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
        client = null
        tunnelProcess?.destroy()
        tunnelProcess = null
    }
}

class SSHProcessSession(private val session: Session, private val cmd: Session.Command) : ProcessSession {
    override val pid: Int = -1 // SSHJ doesn't easily expose remote PID

    override val inputStream: InputStream = cmd.inputStream
    override val errorStream: InputStream = cmd.errorStream
    override val outputStream: OutputStream = cmd.outputStream

    override fun kill() {
        cmd.close()
        session.close()
    }

    override fun waitFor(): Int {
        cmd.join()
        return cmd.exitStatus ?: throw java.io.IOException("Remote command ended without an SSH exit status")
    }

    override fun isRunning(): Boolean = cmd.isOpen

    override fun close() {
        runCatching { cmd.close() }
        runCatching { session.close() }
    }
}

class SSHFileSystemAccess(private val clientProvider: () -> SSHClient) : FileSystemAccess {
    private val client get() = clientProvider()

    override fun listEntries(path: String): List<FileSystemEntry> {
        val sftp = client.newSFTPClient()
        return try {
            sftp.ls(path)
                .asSequence()
                .filterNot { it.name == "." || it.name == ".." }
                .map { FileSystemEntry(it.name, it.path, it.isDirectory, it.attributes.size) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .toList()
        } finally {
            sftp.close()
        }
    }

    override fun readFile(path: String): String {
        val sftp = client.newSFTPClient()
        return try {
            val size = sftp.stat(path).size
            if (size > 5L * 1024L * 1024L) throw java.io.IOException("Remote file is larger than 5 MiB")
            sftp.open(path).use { file ->
                file.RemoteFileInputStream().use { input ->
                    val bytes = ByteArrayOutputStream(minOf(size.toInt().coerceAtLeast(0), 64 * 1024))
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (bytes.size() + read > 5 * 1024 * 1024) throw java.io.IOException("Remote file is larger than 5 MiB")
                        bytes.write(buffer, 0, read)
                    }
                    val data = bytes.toByteArray()
                    if (data.take(4096).any { it == 0.toByte() }) throw java.io.IOException("Remote file appears to be binary")
                    data.toString(StandardCharsets.UTF_8)
                }
            }
        } finally {
            sftp.close()
        }
    }

    override fun writeFile(path: String, content: String) {
        val sftp = client.newSFTPClient()
        val temporaryPath = "$path.agenticdroid-${System.nanoTime()}.tmp"
        try {
            sftp.open(
                temporaryPath,
                setOf(
                    net.schmizz.sshj.sftp.OpenMode.WRITE,
                    net.schmizz.sshj.sftp.OpenMode.CREAT,
                    net.schmizz.sshj.sftp.OpenMode.TRUNC
                )
            ).use { file ->
                file.RemoteFileOutputStream().use { output ->
                    output.write(content.toByteArray(StandardCharsets.UTF_8))
                }
            }
            try {
                sftp.rename(temporaryPath, path)
            } catch (first: Exception) {
                runCatching { sftp.rm(path) }
                sftp.rename(temporaryPath, path)
            }
        } finally {
            runCatching { sftp.rm(temporaryPath) }
            sftp.close()
        }
    }

    override fun deleteFile(path: String): Boolean {
        val sftp = client.newSFTPClient()
        return try {
            sftp.rm(path)
            true
        } catch (e: Exception) {
            false
        } finally {
            sftp.close()
        }
    }

    override fun exists(path: String): Boolean {
        val sftp = client.newSFTPClient()
        return try {
            sftp.stat(path) != null
        } catch (e: Exception) {
            false
        } finally {
            sftp.close()
        }
    }

    override fun renameFile(oldPath: String, newPath: String): Boolean {
        val sftp = client.newSFTPClient()
        return try {
            sftp.rename(oldPath, newPath)
            true
        } catch (e: Exception) {
            false
        } finally {
            sftp.close()
        }
    }

    override fun copyFile(srcPath: String, destPath: String): Boolean {
        // SFTP doesn't have a native 'copy' (server-side). 
        // We'd have to download and re-upload, or use a shell command.
        // Let's use a shell command for efficiency if possible.
        var session: Session? = null
        return try {
            session = client.startSession()
            val cmd = session.exec("cp ${ShellEscaping.quote(srcPath)} ${ShellEscaping.quote(destPath)}")
            cmd.join()
            cmd.exitStatus == 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { session?.close() }
        }
    }

    override fun downloadFile(remotePath: String, localDest: File) {
        val sftp = client.newSFTPClient()
        try {
            sftp.get(remotePath, net.schmizz.sshj.xfer.FileSystemFile(localDest))
        } finally {
            sftp.close()
        }
    }
}

data class SSHConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    /** Complete SHA-256 fingerprint shown by the trusted SSH server administrator. */
    val hostKeyFingerprint: String,
    val workingDirectory: String = ".",
    val authType: SSHAuthType = SSHAuthType.PASSWORD,
    val privateKeyPath: String? = null,
    val privateKeyPassphrase: String? = null,
    val privateKeyContent: String? = null,
    val useCloudflareTunnel: Boolean = false
) {
    init {
        require(host.isNotBlank()) { "SSH host is required" }
        require(port in 1..65535) { "SSH port is invalid" }
        require(username.isNotBlank()) { "SSH username is required" }
        require(workingDirectory.isNotBlank()) { "Remote workspace is required" }
        require(hostKeyFingerprint.matches(Regex("^SHA256:[A-Za-z0-9+/]{43}=?$"))) {
            "A complete SHA-256 host key fingerprint is required"
        }
        require(
            (authType == SSHAuthType.PASSWORD && !password.isNullOrBlank()) ||
                (authType == SSHAuthType.PRIVATE_KEY && (!privateKeyPath.isNullOrBlank() || !privateKeyContent.isNullOrBlank()))
        ) { "SSH authentication material is missing" }
    }
}

enum class SSHAuthType { PASSWORD, PRIVATE_KEY }
