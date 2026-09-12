package com.justnels.agenticdroid.agents.compat

import com.justnels.agenticdroid.agents.AgentProfile
import com.justnels.agenticdroid.env.EnvironmentInfo
import com.justnels.agenticdroid.env.ExecutionEnvironment
import com.justnels.agenticdroid.env.FileSystemAccess
import com.justnels.agenticdroid.env.ProcessSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

private class FakeProcessSession(
    private val stdout: String,
    private val stderr: String,
    private val exitCode: Int
) : ProcessSession {
    override val pid = 1
    override fun kill() {}
    override fun waitFor() = exitCode
    override fun isRunning() = false
    override val inputStream: InputStream get() = ByteArrayInputStream(stdout.toByteArray())
    override val errorStream: InputStream get() = ByteArrayInputStream(stderr.toByteArray())
    override val outputStream: OutputStream get() = OutputStream.nullOutputStream()
    override fun close() {}
}

private class FakeExecutionEnvironment(private val session: ProcessSession) : ExecutionEnvironment {
    override fun exec(command: String, workingDirectory: String, environment: Map<String, String>) = session
    override fun filesystem(): FileSystemAccess = throw UnsupportedOperationException()
    override fun getEnvironmentInfo() = EnvironmentInfo("fake", "linux", "arm64", emptyList())
}

/** A session whose [waitFor] blocks well past a short capture() timeout, so [capture]'s own
 * timeout-handling path (kill + throw IOException) is what actually runs - rather than
 * faking an IOException directly, which capture() would instead surface wrapped in an
 * ExecutionException, not as a bare IOException. */
private class SlowProcessSession(private val blockMillis: Long) : ProcessSession {
    override val pid = 1
    override fun kill() {}
    override fun waitFor(): Int {
        Thread.sleep(blockMillis)
        return 0
    }
    override fun isRunning() = true
    override val inputStream: InputStream get() = ByteArrayInputStream(ByteArray(0))
    override val errorStream: InputStream get() = ByteArrayInputStream(ByteArray(0))
    override val outputStream: OutputStream get() = OutputStream.nullOutputStream()
    override fun close() {}
}

class AgentCompatCheckerTest {
    private val agent = AgentProfile(
        id = "claude",
        name = "Claude Code",
        command = "claude",
        installCommand = "npm install -g @anthropic-ai/claude-code"
    )

    @Test
    fun exitCodeZeroWithOutputIsClassifiedOk() = runBlocking {
        val env = FakeExecutionEnvironment(FakeProcessSession("1.2.3 (Claude Code)\n", "", 0))

        val outcome = AgentCompatChecker.check(agent, env, "/workspace", "On-device toolchain")

        assertEquals(CompatStatus.OK, outcome.result.status)
        assertEquals(0, outcome.result.exitCode)
        assertEquals("1.2.3 (Claude Code)", outcome.versionText)
    }

    @Test
    fun nonZeroExitCodeIsClassifiedBroken() = runBlocking {
        val env = FakeExecutionEnvironment(
            FakeProcessSession("", "claude: cannot execute binary file: Exec format error", 126)
        )

        val outcome = AgentCompatChecker.check(agent, env, "/workspace", "On-device toolchain")

        assertEquals(CompatStatus.BROKEN, outcome.result.status)
        assertEquals(126, outcome.result.exitCode)
        assertNull(outcome.versionText)
        assertNotNull(outcome.result.outputSnippet)
        assert(outcome.result.outputSnippet.contains("Exec format error"))
    }

    @Test
    fun blankStdoutWithZeroExitIsClassifiedBroken() = runBlocking {
        val env = FakeExecutionEnvironment(FakeProcessSession("", "", 0))

        val outcome = AgentCompatChecker.check(agent, env, "/workspace", "On-device toolchain")

        assertEquals(CompatStatus.BROKEN, outcome.result.status)
        assertNull(outcome.versionText)
    }

    @Test
    fun captureTimeoutIsClassifiedTimeout() = runBlocking {
        val env = FakeExecutionEnvironment(SlowProcessSession(blockMillis = 500))

        val outcome = AgentCompatChecker.check(agent, env, "/workspace", "On-device toolchain", timeoutMillis = 50)

        assertEquals(CompatStatus.TIMEOUT, outcome.result.status)
        assertNull(outcome.result.exitCode)
        assertNull(outcome.versionText)
    }
}
