package com.justnels.agenticdroid.env

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SSHExecutionEnvironmentTest {
    private val fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val dummyContext = object : ContextWrapper(null) {}

    @Test
    fun handlesConnectionTimeoutWhenServerDoesNotRespond() {
        val server = ServerSocket(0)
        val port = server.localPort
        val isRunning = AtomicBoolean(true)

        // Accept the TCP connection, but do not send SSH version header / handshake
        val serverThread = thread {
            try {
                val socket = server.accept()
                while (isRunning.get()) {
                    Thread.sleep(50)
                }
                socket.close()
            } catch (_: Exception) {}
        }

        val config = SSHConfig(
            host = "127.0.0.1",
            port = port,
            username = "testuser",
            password = "testpassword",
            hostKeyFingerprint = fingerprint
        )
        val env = SSHExecutionEnvironment(dummyContext, config, timeoutMs = 300)

        val startTime = System.currentTimeMillis()
        var errorThrown = false
        try {
            env.filesystem().listEntries("/tmp")
        } catch (e: Exception) {
            errorThrown = true
        } finally {
            isRunning.set(false)
            env.close()
            server.close()
            serverThread.join(1000)
        }

        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("Expected connection/handshake to timeout and throw an exception", errorThrown)
        assertTrue("Expected timeout to occur promptly, took ${elapsed}ms", elapsed < 2500)
    }

    @Test
    fun handlesConnectionFailureWhenPortIsNotListening() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val config = SSHConfig(
            host = "127.0.0.1",
            port = unusedPort,
            username = "testuser",
            password = "testpassword",
            hostKeyFingerprint = fingerprint
        )
        val env = SSHExecutionEnvironment(dummyContext, config, timeoutMs = 300)

        var errorThrown = false
        try {
            env.exec("echo hello", "/tmp")
        } catch (e: Exception) {
            errorThrown = true
        } finally {
            env.close()
        }

        assertTrue("Expected failure when connecting to closed port", errorThrown)
    }

    @Test
    fun tunnelProbingSucceedsWhenPortIsOpen() {
        val server = ServerSocket(0)
        try {
            val port = server.localPort
            val ready = SSHExecutionEnvironment.probePortReady(
                port = port,
                timeoutMs = 1000,
                pollIntervalMs = 20,
                isAlive = { true }
            )
            assertTrue("Expected probe to succeed on an open port", ready)
        } finally {
            server.close()
        }
    }

    @Test
    fun tunnelProbingSucceedsWhenPortOpensAfterDelay() {
        val targetPort = ServerSocket(0).use { it.localPort }
        var server: ServerSocket? = null

        val serverThread = thread {
            Thread.sleep(100)
            try {
                server = ServerSocket(targetPort)
            } catch (_: Exception) {}
        }

        try {
            val ready = SSHExecutionEnvironment.probePortReady(
                port = targetPort,
                timeoutMs = 2000,
                pollIntervalMs = 20,
                isAlive = { true }
            )
            assertTrue("Expected probe to succeed after port opens with delay", ready)
        } finally {
            serverThread.join(1000)
            server?.close()
        }
    }

    @Test
    fun tunnelProbingFailsImmediatelyWhenProcessDies() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val start = System.currentTimeMillis()
        val ready = SSHExecutionEnvironment.probePortReady(
            port = unusedPort,
            timeoutMs = 5000,
            pollIntervalMs = 50,
            isAlive = { false }
        )
        val elapsed = System.currentTimeMillis() - start

        assertFalse("Expected probe to fail when process is dead", ready)
        assertTrue("Expected fast failure when process is dead, took ${elapsed}ms", elapsed < 500)
    }

    @Test
    fun tunnelProbingTimesOutWhenPortNeverOpens() {
        val unusedPort = ServerSocket(0).use { it.localPort }
        val start = System.currentTimeMillis()
        val ready = SSHExecutionEnvironment.probePortReady(
            port = unusedPort,
            timeoutMs = 250,
            pollIntervalMs = 50,
            isAlive = { true }
        )
        val elapsed = System.currentTimeMillis() - start

        assertFalse("Expected probe to return false after timeout", ready)
        assertTrue("Expected probe to wait until timeout (${elapsed}ms)", elapsed >= 200)
    }

    @Test
    fun fileSystemAccessDefaultWithBatchExecutesBlock() {
        val mockFs = object : FileSystemAccess {
            override fun listEntries(path: String) = emptyList<FileSystemEntry>()
            override fun readFile(path: String) = ""
            override fun writeFile(path: String, content: String) {}
            override fun deleteFile(path: String) = true
            override fun exists(path: String) = true
            override fun renameFile(oldPath: String, newPath: String) = true
            override fun copyFile(srcPath: String, destPath: String) = true
            override fun downloadFile(remotePath: String, localDest: java.io.File) {}
        }

        val result = mockFs.withBatch { batchFs ->
            assertEquals(mockFs, batchFs)
            "batch_result"
        }
        assertEquals("batch_result", result)
    }

    @Test
    fun ptyShellSpecGeneratesCorrectArgsAndEnvForPasswordAuth() {
        val root = java.nio.file.Files.createTempDirectory("test-agentic-ssh").toFile()
        try {
            val nodeRuntime = java.io.File(root, "node-runtime")
            val binDir = java.io.File(nodeRuntime, "usr/bin").apply { mkdirs() }
            val homeDir = java.io.File(nodeRuntime, "home").apply { mkdirs() }
            val node = java.io.File(binDir, "node").apply { writeText(""); setExecutable(true) }
            val ssh = java.io.File(binDir, "ssh").apply { writeText(""); setExecutable(true) }
            val readyMarker = java.io.File(nodeRuntime, ".agenticdroid-ready").apply { writeText("20") }
            val cacheDir = java.io.File(root, "cache").apply { mkdirs() }

            val mockContext = object : ContextWrapper(null) {
                override fun getFilesDir(): java.io.File = root
                override fun getCacheDir(): java.io.File = cacheDir
                override fun getApplicationInfo(): android.content.pm.ApplicationInfo =
                    android.content.pm.ApplicationInfo().apply { nativeLibraryDir = root.absolutePath }
            }

            val config = SSHConfig(
                host = "remote.host",
                port = 2222,
                username = "alice",
                password = "secret_password",
                hostKeyFingerprint = fingerprint,
                authType = SSHAuthType.PASSWORD
            )
            val env = SSHExecutionEnvironment(mockContext, config)
            val spec = env.ptyShellSpec("/home/alice/project")

            assertTrue("Expected ptyShellSpec to not be null", spec != null)
            assertEquals(ssh.absolutePath, spec!!.shellPath)
            assertEquals("ssh", spec.args[0])
            assertEquals("-p", spec.args[1])
            assertEquals("2222", spec.args[2])
            assertTrue(spec.args.contains("-L"))
            assertTrue(spec.args.contains("5173:127.0.0.1:5173"))
            assertTrue(spec.args.contains("3000:127.0.0.1:3000"))
            assertTrue(spec.args.contains("alice@remote.host"))
            assertTrue(spec.args.contains("PreferredAuthentications=password,keyboard-interactive"))
            assertTrue(spec.env.contains("TERM=xterm-256color"))
            assertTrue(spec.env.any { it.startsWith("SSH_ASKPASS=") })
            assertTrue(spec.env.contains("SSH_ASKPASS_REQUIRE=force"))
            assertTrue(spec.env.any { it.startsWith("LD_LIBRARY_PATH=") })
            assertTrue(spec.env.any { it.startsWith("PATH=") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ptyShellSpecGeneratesCorrectArgsForPrivateKeyAndTunnel() {
        val root = java.nio.file.Files.createTempDirectory("test-agentic-ssh").toFile()
        try {
            val nodeRuntime = java.io.File(root, "node-runtime")
            val binDir = java.io.File(nodeRuntime, "usr/bin").apply { mkdirs() }
            val homeDir = java.io.File(nodeRuntime, "home").apply { mkdirs() }
            val node = java.io.File(binDir, "node").apply { writeText(""); setExecutable(true) }
            val ssh = java.io.File(binDir, "ssh").apply { writeText(""); setExecutable(true) }
            val cloudflared = java.io.File(binDir, "cloudflared").apply { writeText(""); setExecutable(true) }
            val readyMarker = java.io.File(nodeRuntime, ".agenticdroid-ready").apply { writeText("20") }
            val cacheDir = java.io.File(root, "cache").apply { mkdirs() }

            val mockContext = object : ContextWrapper(null) {
                override fun getFilesDir(): java.io.File = root
                override fun getCacheDir(): java.io.File = cacheDir
                override fun getApplicationInfo(): android.content.pm.ApplicationInfo =
                    android.content.pm.ApplicationInfo().apply { nativeLibraryDir = root.absolutePath }
            }

            val keyFile = java.io.File(root, "id_rsa").apply { writeText("dummy-key") }
            val config = SSHConfig(
                host = "ssh.tunnel.example.com",
                port = 22,
                username = "bob",
                hostKeyFingerprint = fingerprint,
                authType = SSHAuthType.PRIVATE_KEY,
                privateKeyPath = keyFile.absolutePath,
                useCloudflareTunnel = true
            )
            val env = SSHExecutionEnvironment(mockContext, config)
            val spec = env.ptyShellSpec(".")

            assertTrue("Expected ptyShellSpec to not be null", spec != null)
            assertEquals(ssh.absolutePath, spec!!.shellPath)
            assertTrue(spec.args.contains("-i"))
            assertTrue(spec.args.contains(keyFile.absolutePath))
            assertTrue(spec.args.contains("IdentitiesOnly=yes"))
            assertTrue(spec.args.any { it.startsWith("ProxyCommand=") && it.contains("ssh.tunnel.example.com") })
            assertTrue(spec.args.contains("bob@ssh.tunnel.example.com"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun projectTypeDetectFromPathsCorrectlyIdentifiesProjectTypes() {
        val webPaths = listOf("package.json", "index.html", "src/main.ts", "vite.config.ts")
        assertEquals(com.justnels.agenticdroid.workspace.ProjectType.WEB, com.justnels.agenticdroid.workspace.ProjectType.detectFromPaths(webPaths))

        val nodePaths = listOf("package.json", "index.js", "lib/util.js")
        assertEquals(com.justnels.agenticdroid.workspace.ProjectType.NODE_JS, com.justnels.agenticdroid.workspace.ProjectType.detectFromPaths(nodePaths))

        val pythonPaths = listOf("requirements.txt", "main.py", "app/routes.py")
        assertEquals(com.justnels.agenticdroid.workspace.ProjectType.PYTHON, com.justnels.agenticdroid.workspace.ProjectType.detectFromPaths(pythonPaths))

        val rustPaths = listOf("Cargo.toml", "src/main.rs")
        assertEquals(com.justnels.agenticdroid.workspace.ProjectType.RUST, com.justnels.agenticdroid.workspace.ProjectType.detectFromPaths(rustPaths))

        val goPaths = listOf("go.mod", "main.go")
        assertEquals(com.justnels.agenticdroid.workspace.ProjectType.GOLANG, com.justnels.agenticdroid.workspace.ProjectType.detectFromPaths(goPaths))

        val androidPaths = listOf("build.gradle.kts", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml")
        assertEquals(com.justnels.agenticdroid.workspace.ProjectType.ANDROID, com.justnels.agenticdroid.workspace.ProjectType.detectFromPaths(androidPaths))

        val cppPaths = listOf("CMakeLists.txt", "main.cpp", "include/header.hpp")
        assertEquals(com.justnels.agenticdroid.workspace.ProjectType.CPP, com.justnels.agenticdroid.workspace.ProjectType.detectFromPaths(cppPaths))
    }
}
