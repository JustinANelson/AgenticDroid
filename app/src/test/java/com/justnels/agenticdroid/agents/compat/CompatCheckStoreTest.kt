package com.justnels.agenticdroid.agents.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CompatCheckStoreTest {
    private fun newStore(): CompatCheckStore =
        CompatCheckStore(File.createTempFile("compat-check-store", "").apply { delete(); mkdirs() })

    private fun sampleResult(
        id: String = "check-1",
        agentId: String = "claude",
        checkedAt: Long = 1000L,
        status: CompatStatus = CompatStatus.OK
    ) = CompatCheckResult(
        id = id,
        agentId = agentId,
        agentName = "Claude Code",
        checkedAt = checkedAt,
        status = status,
        exitCode = if (status == CompatStatus.OK) 0 else 1,
        outputSnippet = "1.2.3",
        environmentLabel = "On-device toolchain"
    )

    @Test
    fun roundTripsResultThroughJson() {
        val store = newStore()
        store.record(sampleResult())

        val loaded = store.listAll()
        assertEquals(1, loaded.size)
        assertEquals("check-1", loaded[0].id)
        assertEquals(CompatStatus.OK, loaded[0].status)
    }

    @Test
    fun latestForReturnsTheMostRecentEntryForThatAgentOnly() {
        val store = newStore()
        store.record(sampleResult("older", checkedAt = 1000L))
        store.record(sampleResult("newer", checkedAt = 2000L))
        store.record(sampleResult("other-agent", agentId = "codex", checkedAt = 3000L))

        val latest = store.latestFor("claude")
        assertEquals("newer", latest?.id)
    }

    @Test
    fun latestForReturnsNullWhenAgentHasNoHistory() {
        val store = newStore()
        assertNull(store.latestFor("codex"))
    }

    @Test
    fun recordTrimsHistoryToMaxEntriesPerAgentWithoutAffectingOtherAgents() {
        val store = newStore()
        (1..20).forEach { i -> store.record(sampleResult("claude-$i", checkedAt = i.toLong())) }
        store.record(sampleResult("codex-1", agentId = "codex", checkedAt = 1L))

        val claudeResults = store.listAll().filter { it.agentId == "claude" }
        val codexResults = store.listAll().filter { it.agentId == "codex" }
        assertEquals(15, claudeResults.size)
        assertEquals(1, codexResults.size)
        // Newest entries survive the trim, not the oldest.
        assertEquals("claude-20", claudeResults.maxByOrNull { it.checkedAt }?.id)
    }
}
