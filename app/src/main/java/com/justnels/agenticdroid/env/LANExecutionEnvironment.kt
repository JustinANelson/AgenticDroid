package com.justnels.agenticdroid.env

import android.content.Context
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.toByteString

class LANExecutionEnvironment(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val token: String? = null
) : ExecutionEnvironment {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (!token.isNullOrBlank()) {
                addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
                }
            }
        }
        .build()

    private val baseUrl = "http://$host:$port"

    override fun exec(
        command: String,
        workingDirectory: String,
        environment: Map<String, String>
    ): ProcessSession {
        val json = JSONObject().apply {
            put("command", command)
            put("cwd", workingDirectory)
            put("env", JSONObject(environment))
        }

        val request = Request.Builder()
            .url("$baseUrl/api/exec")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}: ${response.message}")

        val resultJson = JSONObject(response.body?.string() ?: "{}")
        return LANProcessSession(resultJson)
    }

    override fun filesystem(): FileSystemAccess {
        return LANFileSystemAccess(client, baseUrl)
    }

    override fun getEnvironmentInfo(): EnvironmentInfo {
        return EnvironmentInfo(
            name = "LAN Agent: $host",
            os = "Remote LAN", 
            architecture = "unknown",
            installedTools = emptyList()
        )
    }

    override fun ptyShellSpec(workingDirectory: String): PtyShellSpec? {
        val node = NodeRuntime.binDir(context).resolve("node")
        if (!node.exists()) return null

        val localBridge = LANTerminalBridge(terminalWebSocketUrl(workingDirectory), token)
        val bridgePort = localBridge.start()

        val envMap = mutableMapOf<String, String>()
        NodeRuntime.configureEnvironment(context, envMap)

        return PtyShellSpec(
            shellPath = node.absolutePath,
            args = arrayOf("-e", "const s=require('net').connect($bridgePort);process.stdin.pipe(s);s.pipe(process.stdout)"),
            cwd = context.filesDir.absolutePath,
            env = envMap.map { (k, v) -> "$k=$v" }.toTypedArray()
        )
    }

    private fun terminalWebSocketUrl(workingDirectory: String): String {
        return "ws://$host:$port/terminal?cwd=${URLEncoder.encode(workingDirectory, "UTF-8")}"
    }
}

private class LANTerminalBridge(val wsUrl: String, val token: String? = null) {
    private var serverSocket: ServerSocket? = null
    private val client = OkHttpClient()

    fun start(): Int {
        val ss = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        Thread {
            try {
                val localSocket = ss.accept()
                ss.close()

                val request = Request.Builder().url(wsUrl).apply {
                    if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                }.build()
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        localSocket.outputStream.write(text.toByteArray())
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        localSocket.outputStream.write(bytes.toByteArray())
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        runCatching { localSocket.close() }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        runCatching { localSocket.close() }
                    }
                })

                localSocket.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        ws.send(buffer.toByteString(0, read))
                    }
                }
                ws.close(1000, null)
            } catch (e: Exception) {
                runCatching { ss.close() }
            }
        }.start()
        return ss.localPort
    }
}

class LANProcessSession(json: JSONObject) : ProcessSession {
    override val pid: Int = -1
    override val inputStream: InputStream = json.optString("stdout").byteInputStream()
    override val errorStream: InputStream = json.optString("stderr").byteInputStream()
    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {} 
    }

    private val exitCode = json.optInt("exitCode", 0)

    override fun kill() {}
    override fun waitFor(): Int = exitCode
    override fun isRunning(): Boolean = false
    override fun close() {}
}

class LANFileSystemAccess(
    private val client: OkHttpClient,
    private val baseUrl: String
) : FileSystemAccess {

    override fun listEntries(path: String): List<FileSystemEntry> {
        val url = "$baseUrl/api/files/list".toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
            .build()

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")

        val array = JSONArray(response.body?.string() ?: "[]")
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            FileSystemEntry(
                name = obj.getString("name"),
                path = obj.getString("path"),
                isDirectory = obj.getBoolean("isDirectory"),
                size = obj.optLong("size", 0)
            )
        }
    }

    override fun readFile(path: String): String {
        val url = "$baseUrl/api/files/read".toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
            .build()

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
        return response.body?.string() ?: ""
    }

    override fun writeFile(path: String, content: String) {
        val json = JSONObject().apply {
            put("path", path)
            put("content", content)
        }

        val request = Request.Builder()
            .url("$baseUrl/api/files/write")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
    }

    override fun deleteFile(path: String): Boolean = false

    override fun exists(path: String): Boolean {
        val url = "$baseUrl/api/files/exists".toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
            .build()

        val request = Request.Builder().url(url).build()
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                JSONObject(response.body?.string() ?: "{}").optBoolean("exists", false)
            } else false
        } catch (e: Exception) {
            false
        }
    }

    override fun renameFile(oldPath: String, newPath: String): Boolean = false
    override fun copyFile(srcPath: String, destPath: String): Boolean = false

    // readFile/writeFile round-trip through UTF-8 text, which is right for source files but
    // would corrupt anything binary (a built APK, an image) - route those through the
    // server's raw-bytes /api/files/download+upload endpoints instead. This also breaks what
    // would otherwise be infinite recursion: FileSystemAccess.downloadFile()'s default impl
    // calls downloadStream(), whose default impl calls downloadFile() right back - every
    // implementation must override at least one of the pair, and this app's other
    // ExecutionEnvironments already do (see LocalExecutionEnvironment, SSHExecutionEnvironment).
    override fun downloadFile(
        remotePath: String,
        localDest: File,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?
    ) {
        localDest.parentFile?.mkdirs()
        val url = "$baseUrl/api/files/download".toHttpUrl().newBuilder()
            .addQueryParameter("path", remotePath)
            .build()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty response body")
            val totalBytes = body.contentLength()
            val temp = File(localDest.parentFile ?: localDest, ".agentic-dl-${localDest.name}-${System.nanoTime()}.tmp")
            try {
                temp.outputStream().use { out -> copyStreamWithProgress(body.byteStream(), out, totalBytes, onProgress) }
                if (localDest.exists()) localDest.delete()
                if (!temp.renameTo(localDest)) {
                    temp.copyTo(localDest, overwrite = true)
                    temp.delete()
                }
            } finally {
                temp.delete()
            }
        }
    }

    override fun uploadStream(
        inputStream: InputStream,
        remotePath: String,
        totalBytes: Long,
        onProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?
    ) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = totalBytes
            override fun writeTo(sink: okio.BufferedSink) {
                copyStreamWithProgress(inputStream, sink.outputStream(), totalBytes, onProgress)
            }
        }
        val url = "$baseUrl/api/files/upload".toHttpUrl().newBuilder()
            .addQueryParameter("path", remotePath)
            .build()
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
        }
    }
}
