package com.justnels.agenticdroid.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class DrainToLogTest {
    @Test
    fun writesBothStreamsAndReturnsExitCode() {
        val logFile = File.createTempFile("drain-test", ".log").apply { deleteOnExit() }
        val result = DrainToLog.drain(
            stdout = ByteArrayInputStream("hello stdout\n".toByteArray()),
            stderr = ByteArrayInputStream("hello stderr\n".toByteArray()),
            waitFor = { 0 },
            logFile = logFile
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.truncated)
        val content = logFile.readText()
        assertTrue(content.contains("hello stdout"))
        assertTrue(content.contains("hello stderr"))
    }

    @Test
    fun propagatesNonZeroExitCode() {
        val logFile = File.createTempFile("drain-test", ".log").apply { deleteOnExit() }
        val result = DrainToLog.drain(
            stdout = ByteArrayInputStream(ByteArray(0)),
            stderr = ByteArrayInputStream("boom".toByteArray()),
            waitFor = { 1 },
            logFile = logFile
        )

        assertEquals(1, result.exitCode)
        assertEquals("boom", logFile.readText())
    }

    @Test
    fun truncatesOutputPastMaxBytes() {
        val logFile = File.createTempFile("drain-test", ".log").apply { deleteOnExit() }
        val bigOutput = "x".repeat(10_000)
        val result = DrainToLog.drain(
            stdout = ByteArrayInputStream(bigOutput.toByteArray()),
            stderr = ByteArrayInputStream(ByteArray(0)),
            waitFor = { 0 },
            logFile = logFile,
            maxBytes = 100
        )

        assertTrue(result.truncated)
        assertEquals(100, logFile.length())
    }

    @Test
    fun appendsToAnExistingLogFileRatherThanOverwriting() {
        val logFile = File.createTempFile("drain-test", ".log").apply {
            deleteOnExit()
            writeText("[earlier output]\n")
        }
        DrainToLog.drain(
            stdout = ByteArrayInputStream("new output".toByteArray()),
            stderr = ByteArrayInputStream(ByteArray(0)),
            waitFor = { 0 },
            logFile = logFile
        )

        val content = logFile.readText()
        assertTrue(content.startsWith("[earlier output]"))
        assertTrue(content.contains("new output"))
    }
}
