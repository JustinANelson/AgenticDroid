package com.justnels.agenticdroid.env

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class SSHExecutionEnvironment(private val config: SSHConfig) : ExecutionEnvironment {
    private var client: SSHClient? = null

    private fun getClient(): SSHClient {
        if (client == null || !client!!.isConnected) {
            client = SSHClient().apply {
                addHostKeyVerifier(PromiscuousVerifier())
                connect(config.host, config.port)
                authPassword(config.username, config.password)
            }
        }
        return client!!
    }

    override fun exec(command: String, workingDirectory: String): ProcessSession {
        val session = getClient().startSession()
        session.allocatePTY("xterm", 80, 24, 0, 0, emptyMap())
        val cmd = session.exec("cd $workingDirectory && $command")
        return SSHProcessSession(session, cmd)
    }

    override fun filesystem(): FileSystemAccess {
        return SSHFileSystemAccess(getClient())
    }

    override fun getEnvironmentInfo(): EnvironmentInfo {
        return EnvironmentInfo(
            name = "Remote SSH: ${config.host}",
            os = "Linux (Remote)",
            architecture = "unknown",
            installedTools = emptyList()
        )
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
        return cmd.exitStatus ?: 0
    }
}

class SSHFileSystemAccess(private val client: SSHClient) : FileSystemAccess {
    override fun listFiles(path: String): List<File> {
        // This is tricky as SSHJ's SFTP returns RemoteResourceInfo, not java.io.File
        // For now, returning empty to avoid complex mapping in this snippet
        return emptyList()
    }

    override fun readFile(path: String): String {
        val sftp = client.newSFTPClient()
        return try {
            val file = sftp.open(path)
            IOUtils.readFully(file.RemoteFileInputStream()).toString()
        } finally {
            sftp.close()
        }
    }

    override fun writeFile(path: String, content: String) {
        val sftp = client.newSFTPClient()
        try {
            val file = sftp.open(path, setOf(net.schmizz.sshj.sftp.OpenMode.WRITE, net.schmizz.sshj.sftp.OpenMode.CREAT))
            file.RemoteFileOutputStream().write(content.toByteArray())
        } finally {
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
        return try {
            val session = client.startSession()
            val cmd = session.exec("cp \"$srcPath\" \"$destPath\"")
            cmd.join()
            session.close()
            cmd.exitStatus == 0
        } catch (e: Exception) {
            false
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
    val password: String // Should use key-based auth in production
)
