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

class SSHExecutionEnvironment(
    private val context: Context,
    private val config: SSHConfig,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val tunnelTimeoutMs: Long = DEFAULT_TUNNEL_TIMEOUT_MS
) : ExecutionEnvironment {
    private var client: SSHClient? = null
    private var tunnelProcess: Process? = null
    private var tunnelLocalPort: Int = 0

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
        const val DEFAULT_TUNNEL_TIMEOUT_MS = 10_000L

        /**
         * Probes the given local port for readiness by attempting TCP socket connections.
         * Returns true if a connection succeeds before [timeoutMs], or false if the timeout
         * expires or [isAlive] returns false.
         */
        internal fun probePortReady(
            port: Int,
            host: String = "127.0.0.1",
            timeoutMs: Long = DEFAULT_TUNNEL_TIMEOUT_MS,
            pollIntervalMs: Long = 50L,
            isAlive: () -> Boolean = { true }
        ): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!isAlive()) {
                    return false
                }
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(host, port), 200)
                        return true
                    }
                } catch (e: Exception) {
                    // Port not ready yet
                }
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return false
        }
    }

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

        val process = pb.start()
        tunnelProcess = process
        tunnelLocalPort = port

        val ready = probePortReady(
            port = port,
            timeoutMs = tunnelTimeoutMs,
            isAlive = { process.isAlive }
        )
        if (!ready) {
            val exitCode = if (!process.isAlive) runCatching { process.exitValue() }.getOrNull() else null
            process.destroy()
            tunnelProcess = null
            if (exitCode != null) {
                throw java.io.IOException("Cloudflare tunnel process exited unexpectedly with code $exitCode")
            } else {
                throw java.io.IOException("Timed out waiting for Cloudflare tunnel to listen on port $port")
            }
        }
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

            val newClient = SSHClient().apply {
                connectTimeout = timeoutMs
                timeout = timeoutMs
                addHostKeyVerifier(FingerprintVerifier.getInstance(config.hostKeyFingerprint))
                try {
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
                } catch (e: Exception) {
                    runCatching { disconnect() }
                    runCatching { close() }
                    throw e
                }
            }
            client = newClient
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
        // SSHJ-backed file/command access does not need the local Termux runtime, but the
        // interactive PTY launches its OpenSSH client from that runtime. Never touch or
        // execute a stale/partially-provisioned tree while BootstrapWorker is replacing it.
        if (!NodeBootstrapper(context).isInstalled()) return null
        val sshBin = NodeRuntime.binDir(context).resolve("ssh")
        if (!sshBin.exists()) return null

        val envMap = mutableMapOf<String, String>()
        NodeRuntime.configureEnvironment(context, envMap)
        envMap["TERM"] = "xterm-256color"
        envMap["COLORTERM"] = "truecolor"

        val userHome = NodeRuntime.homeDir(context).also { it.mkdirs() }
        val sshDir = File(userHome, ".ssh").also { it.mkdirs() }
        val knownHosts = File(sshDir, "known_hosts")

        val args = mutableListOf(
            "-p", config.port.toString(),
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "UserKnownHostsFile=${knownHosts.absolutePath}",
            "-t"
        )

        if (config.useCloudflareTunnel) {
            val cloudflared = NodeRuntime.binDir(context).resolve("cloudflared")
            if (cloudflared.exists()) {
                args.add("-o")
                args.add("ProxyCommand=${cloudflared.absolutePath} access tcp --hostname ${config.host}")
            }
        }

        when (config.authType) {
            SSHAuthType.PRIVATE_KEY -> {
                val keyPath = config.privateKeyPath ?: config.privateKeyContent?.let { content ->
                    val keyFile = File(context.filesDir, "ssh_identity_${config.host.hashCode()}").apply {
                        writeText(content)
                        setReadable(false, false)
                        setReadable(true, true)
                        setWritable(false, false)
                        setWritable(true, true)
                    }
                    keyFile.absolutePath
                }
                if (keyPath != null) {
                    args.add("-i")
                    args.add(keyPath)
                    args.add("-o")
                    args.add("IdentitiesOnly=yes")
                }
            }
            SSHAuthType.PASSWORD -> {
                config.password?.let { pwd ->
                    val askpassFile = File(context.cacheDir, "ssh_askpass.sh").apply {
                        writeText("#!/system/bin/sh\ncat << 'EOF_AGENTICDROID_PASS'\n$pwd\nEOF_AGENTICDROID_PASS\n")
                        setReadable(false, false)
                        setReadable(true, true)
                        setWritable(false, false)
                        setWritable(true, true)
                        setExecutable(true, true)
                    }
                    envMap["SSH_ASKPASS"] = askpassFile.absolutePath
                    envMap["SSH_ASKPASS_REQUIRE"] = "force"
                    envMap["DISPLAY"] = ":0"
                    args.add("-o")
                    args.add("PreferredAuthentications=password,keyboard-interactive")
                }
            }
        }

        args.add("${config.username}@${config.host}")

        val remoteCommand = if (workingDirectory.isNotBlank() && workingDirectory != ".") {
            "cd ${ShellEscaping.quote(workingDirectory)} 2>/dev/null || true ; exec \${SHELL:-/bin/sh} -l"
        } else {
            "exec \${SHELL:-/bin/sh} -l"
        }
        args.add(remoteCommand)

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

class SSHFileSystemAccess(
    private val clientProvider: () -> SSHClient,
    private val existingSftp: net.schmizz.sshj.sftp.SFTPClient? = null
) : FileSystemAccess {
    private val client get() = clientProvider()

    private inline fun <T> useSftp(block: (net.schmizz.sshj.sftp.SFTPClient) -> T): T {
        return if (existingSftp != null) {
            block(existingSftp)
        } else {
            val sftp = client.newSFTPClient()
            try {
                block(sftp)
            } finally {
                runCatching { sftp.close() }
            }
        }
    }

    override fun listEntries(path: String): List<FileSystemEntry> = useSftp { sftp ->
        sftp.ls(path)
            .asSequence()
            .filterNot { it.name == "." || it.name == ".." }
            .map { FileSystemEntry(it.name, it.path, it.isDirectory, it.attributes.size) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .toList()
    }

    override fun readFile(path: String): String = useSftp { sftp ->
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
    }

    override fun writeFile(path: String, content: String) = useSftp { sftp ->
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
        }
    }

    override fun deleteFile(path: String): Boolean = useSftp { sftp ->
        try {
            sftp.rm(path)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun exists(path: String): Boolean = useSftp { sftp ->
        try {
            sftp.stat(path) != null
        } catch (e: Exception) {
            false
        }
    }

    override fun renameFile(oldPath: String, newPath: String): Boolean = useSftp { sftp ->
        try {
            sftp.rename(oldPath, newPath)
            true
        } catch (e: Exception) {
            false
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

    override fun downloadFile(remotePath: String, localDest: File) = useSftp { sftp ->
        sftp.get(remotePath, net.schmizz.sshj.xfer.FileSystemFile(localDest))
    }

    override fun <T> withBatch(block: (FileSystemAccess) -> T): T {
        if (existingSftp != null) {
            return block(this)
        }
        val sftp = client.newSFTPClient()
        return try {
            block(SSHFileSystemAccess(clientProvider, sftp))
        } finally {
            runCatching { sftp.close() }
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
