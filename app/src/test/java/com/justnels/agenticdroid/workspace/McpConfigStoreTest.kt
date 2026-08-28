package com.justnels.agenticdroid.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpConfigStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun serializeThenParseRoundTrips() {
        val servers = listOf(
            McpServer(
                name = "filesystem",
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
                env = mapOf("LOG_LEVEL" to "debug")
            ),
            McpServer(name = "git", command = "npx", args = listOf("-y", "mcp-server-git"))
        )

        val parsed = McpConfigStore.parse(McpConfigStore.serialize(servers))

        assertEquals(servers.toSet(), parsed.toSet())
    }

    @Test
    fun readReturnsEmptyForMissingFile() {
        val project = Project("empty", tempFolder.newFolder("empty-proj").absolutePath)
        assertTrue(McpConfigStore.read(project).isEmpty())
    }

    @Test
    fun addOrUpdateThenRemoveRoundTripsThroughDisk() {
        val dir = tempFolder.newFolder("mcp-proj")
        val project = Project("mcp-proj", dir.absolutePath)

        McpConfigStore.addOrUpdate(project, McpServer("git", "npx", listOf("-y", "mcp-server-git")))
        assertEquals(1, McpConfigStore.read(project).size)
        assertTrue(File(dir, ".mcp.json").isFile)

        McpConfigStore.addOrUpdate(project, McpServer("filesystem", "npx", listOf("-y", "server-fs")))
        assertEquals(2, McpConfigStore.read(project).size)

        // Updating an existing name replaces it rather than duplicating.
        McpConfigStore.addOrUpdate(project, McpServer("git", "node", listOf("git-server.js")))
        val afterUpdate = McpConfigStore.read(project)
        assertEquals(2, afterUpdate.size)
        assertEquals("node", afterUpdate.first { it.name == "git" }.command)

        McpConfigStore.remove(project, "git")
        assertEquals(listOf("filesystem"), McpConfigStore.read(project).map { it.name })

        McpConfigStore.remove(project, "filesystem")
        assertTrue(McpConfigStore.read(project).isEmpty())
        // Removing the last server deletes the file rather than leaving an empty shell.
        assertTrue(!File(dir, ".mcp.json").exists())
    }

    @Test
    fun ignoresEntriesMissingARequiredCommand() {
        val json = """{"mcpServers": {"broken": {"args": ["x"]}, "ok": {"command": "npx"}}}"""
        val servers = McpConfigStore.parse(json)
        assertEquals(listOf("ok"), servers.map { it.name })
    }
}
