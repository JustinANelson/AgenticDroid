package com.justnels.agenticdroid.mcp

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.concurrent.Executors

class ContextBridgeServer(
    val port: Int,
    private val getActiveTab: () -> JSONObject?,
    private val getAllTabs: () -> JSONArray,
    private val saveActiveTab: () -> Boolean
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false

    fun start() {
        running = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
                Log.i("ContextBridge", "Bridge server started on port $port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (running) Log.e("ContextBridge", "Server error", e)
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        executor.shutdown()
    }

    private fun handleClient(socket: java.net.Socket) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream())
                
                val firstLine = reader.readLine() ?: return@execute
                val parts = firstLine.split(" ")
                if (parts.size < 2) return@execute
                
                val method = parts[0]
                val path = parts[1]
                
                // Consume headers
                while (reader.readLine().isNotEmpty()) { /* skip */ }
                
                when {
                    path == "/active-tab" -> {
                        val tab = getActiveTab()
                        if (tab != null) {
                            sendResponse(writer, 200, tab.toString())
                        } else {
                            sendResponse(writer, 404, JSONObject().put("error", "No active tab").toString())
                        }
                    }
                    path == "/tabs" -> {
                        sendResponse(writer, 200, getAllTabs().toString())
                    }
                    path == "/save" && method == "POST" -> {
                        val success = saveActiveTab()
                        sendResponse(writer, 200, JSONObject().put("status", if (success) "success" else "failed").toString())
                    }
                    else -> {
                        sendResponse(writer, 404, "Not Found")
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.e("ContextBridge", "Client handling error", e)
            }
        }
    }

    private fun sendResponse(writer: PrintWriter, code: Int, body: String) {
        val statusText = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Error"
        }
        writer.println("HTTP/1.1 $code $statusText")
        writer.println("Content-Type: application/json")
        writer.println("Content-Length: ${body.toByteArray().size}")
        writer.println("Connection: close")
        writer.println()
        writer.print(body)
        writer.flush()
    }
}
