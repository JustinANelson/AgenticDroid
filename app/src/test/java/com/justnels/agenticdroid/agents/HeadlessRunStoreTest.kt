package com.justnels.agenticdroid.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HeadlessRunStoreTest {
    private fun newStore(): HeadlessRunStore =
        HeadlessRunStore(File.createTempFile("headless-run-store", "").apply { delete(); mkdirs() })

    private fun sampleRun(id: String = "run-1") = HeadlessAgentRun(
        id = id,
        agentId = "claude",
        agentName = "Claude Code",
        prompt = "fix the bug",
        projectPath = "/workspace/project",
        workingDirectory = "/workspace/project",
        environmentLabel = "Local",
        startedAt = 1000L
    )

    @Test
    fun roundTripsRunMetadataThroughJson() {
        val store = newStore()
        store.save(sampleRun())

        val loaded = store.listAll()
        assertEquals(1, loaded.size)
        assertEquals("run-1", loaded[0].id)
        assertEquals(HeadlessRunStatus.RUNNING, loaded[0].status)
        assertNull(loaded[0].exitCode)
    }

    @Test
    fun savingAgainWithTheSameIdReplacesRatherThanDuplicates() {
        val store = newStore()
        store.save(sampleRun())
        store.save(sampleRun().copy(status = HeadlessRunStatus.SUCCEEDED, exitCode = 0, finishedAt = 2000L))

        val loaded = store.listAll()
        assertEquals(1, loaded.size)
        assertEquals(HeadlessRunStatus.SUCCEEDED, loaded[0].status)
        assertEquals(0, loaded[0].exitCode)
    }

    @Test
    fun listsNewestRunsFirst() {
        val store = newStore()
        store.save(sampleRun("older").copy(startedAt = 1000L))
        store.save(sampleRun("newer").copy(startedAt = 2000L))

        assertEquals(listOf("newer", "older"), store.listAll().map { it.id })
    }

    @Test
    fun deleteRemovesBothMetadataAndLogFile() {
        val store = newStore()
        store.save(sampleRun())
        store.logFile("run-1").writeText("some output")

        store.delete("run-1")

        assertTrue(store.listAll().isEmpty())
        assertEquals("", store.readLog("run-1"))
    }

    @Test
    fun readLogTailsRatherThanTruncatingFromTheStart() {
        val store = newStore()
        store.logFile("run-1").writeText("0123456789")

        assertEquals("6789", store.readLog("run-1", maxBytes = 4))
    }
}
