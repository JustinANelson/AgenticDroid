package com.justnels.agenticdroid.agents

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Result of draining a process's output to a log file: the exit code (from [waitFor])
 * and whether output was cut off at [DrainToLog.maxBytes]. */
data class DrainResult(val exitCode: Int, val truncated: Boolean)

/**
 * Drains two streams (stdout/stderr) concurrently to one interleaved, size-capped log
 * file rather than buffering in memory, since a headless agent run can produce far more
 * output than this app wants to hold in the process heap across a run that might last
 * hours - see [com.justnels.agenticdroid.env.capture] for the equivalent in-memory
 * version this app already uses for short-lived commands. A plain top-level function
 * (not a private method on [HeadlessAgentRunService]) so it's testable with fake streams
 * and no `ProcessSession`/Android dependency.
 */
object DrainToLog {
    fun drain(
        stdout: InputStream,
        stderr: InputStream,
        waitFor: () -> Int,
        logFile: File,
        maxBytes: Long = 4L * 1024 * 1024
    ): DrainResult {
        val writeLock = Any()
        var written = 0L
        var truncated = false
        FileOutputStream(logFile, true).use { out ->
            fun drainStream(stream: InputStream) = Thread {
                val buffer = ByteArray(8192)
                while (true) {
                    val read = try { stream.read(buffer) } catch (e: Exception) { -1 }
                    if (read < 0) break
                    synchronized(writeLock) {
                        val remaining = maxBytes - written
                        if (remaining > 0) {
                            val allowed = minOf(read.toLong(), remaining).toInt()
                            out.write(buffer, 0, allowed)
                            out.flush()
                            written += allowed
                            if (allowed < read) truncated = true
                        } else {
                            truncated = true
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val stdoutThread = drainStream(stdout)
            val stderrThread = drainStream(stderr)
            val exit = try { waitFor() } finally {
                stdoutThread.join(5000)
                stderrThread.join(5000)
            }
            return DrainResult(exit, truncated)
        }
    }
}
