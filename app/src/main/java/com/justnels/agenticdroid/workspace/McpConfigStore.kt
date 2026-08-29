package com.justnels.agenticdroid.workspace

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One MCP (Model Context Protocol) server entry, matching the `mcpServers` object shape
 * Claude Code and Codex both read from a project-root `.mcp.json` - see McpConfigStore.
 */
data class McpServer(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
)

/**
 * Reads/writes a project's `.mcp.json` - the de-facto standard MCP client config file
 * Claude Code and Codex both discover automatically from a project's root directory with
 * no app-specific plumbing needed (they're launched as ordinary CLI processes with a real
 * project directory - see AgentProfile.launchCommand). Antigravity may use
 * a different config location/format; this only targets the two that share this file.
 *
 * Deliberately a plain (unencrypted) project file, matching how these tools themselves
 * expect it to be found and matching normal MCP project-config convention - not a secret
 * store. A server's own env needs (e.g. an API key its process reads) are usually better
 * satisfied by a project secret (see ProjectSecretsStore): those get `export`-ed into the
 * shell an MCP server's parent agent runs in, and an MCP server process inherits that
 * environment automatically without needing its own copy written to disk here.
 */
object McpConfigStore {
    private const val FILE_NAME = ".mcp.json"
    private const val ROOT_KEY = "mcpServers"

    fun configFile(project: Project): File = File(project.path, FILE_NAME)

    fun read(project: Project): List<McpServer> {
        val file = configFile(project)
        if (!file.isFile) return emptyList()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    fun addOrUpdate(project: Project, server: McpServer) {
        write(project, read(project).filterNot { it.name == server.name } + server)
    }

    fun remove(project: Project, name: String) {
        write(project, read(project).filterNot { it.name == name })
    }

    /**
     * Ensures the internal AgenticDroid Context MCP server is registered in the project.
     * This allows agents to interact with the IDE's UI state (tabs, focus, etc.).
     */
    fun ensureContextServerRegistered(project: Project, serverScriptPath: String) {
        val servers = read(project)
        if (servers.none { it.name == "agenticdroid-context" }) {
            addOrUpdate(project, McpServer(
                name = "agenticdroid-context",
                command = "node",
                args = listOf(serverScriptPath)
            ))
        }
    }

    internal fun parse(json: String): List<McpServer> {
        val root = JSONObject(json)
        val servers = root.optJSONObject(ROOT_KEY) ?: return emptyList()
        return servers.keys().asSequence().mapNotNull { name ->
            val obj = servers.optJSONObject(name) ?: return@mapNotNull null
            val command = obj.optString("command").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val args = obj.optJSONArray("args")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
            val env = obj.optJSONObject("env")?.let { envObj ->
                envObj.keys().asSequence().associateWith { envObj.getString(it) }
            }.orEmpty()
            McpServer(name, command, args, env)
        }.toList()
    }

    internal fun serialize(servers: List<McpServer>): String {
        val serversObj = JSONObject()
        for (server in servers) {
            val obj = JSONObject()
            obj.put("command", server.command)
            obj.put("args", JSONArray(server.args))
            if (server.env.isNotEmpty()) {
                obj.put("env", JSONObject(server.env as Map<*, *>))
            }
            serversObj.put(server.name, obj)
        }
        return JSONObject().put(ROOT_KEY, serversObj).toString(2)
    }

    private fun write(project: Project, servers: List<McpServer>) {
        val file = configFile(project)
        if (servers.isEmpty()) {
            file.delete()
            return
        }
        file.writeText(serialize(servers))
    }
}
