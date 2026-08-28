package com.justnels.agenticdroid.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainDoctorTest {

    @Test
    fun allRunnerPackageGroupsHaveHealthCheckCommands() {
        for (group in RunnerPackageGroup.entries) {
            val command = ToolchainDoctor.healthCheckCommand(group)
            assertTrue("Health check command for $group should not be blank", command.isNotBlank())
            assertTrue("Commands must redirect stdin from /dev/null", command.contains("</dev/null"))
            assertTrue("Commands must redirect stderr to stdout 2>&1", command.contains("2>&1"))
        }
    }

    @Test
    fun coreGroupChecksAllProvidedBinaries() {
        val cmd = ToolchainDoctor.healthCheckCommand(RunnerPackageGroup.CORE)
        val expectedBinaries = listOf("node", "git", "npm", "curl", "rg", "jq", "fd", "sqlite3")
        for (bin in expectedBinaries) {
            assertTrue("CORE health check should check $bin", cmd.contains("$bin --version </dev/null 2>&1"))
        }
        val subcommands = cmd.split(" && ")
        assertEquals(8, subcommands.size)
    }

    @Test
    fun pythonGroupChecksPythonAndPip() {
        val cmd = ToolchainDoctor.healthCheckCommand(RunnerPackageGroup.PYTHON)
        assertTrue(cmd.contains("python --version </dev/null 2>&1"))
        assertTrue(cmd.contains("pip --version </dev/null 2>&1"))
        val subcommands = cmd.split(" && ")
        assertEquals(2, subcommands.size)
    }

    @Test
    fun jvmGroupChecksJavaAndKotlinc() {
        val cmd = ToolchainDoctor.healthCheckCommand(RunnerPackageGroup.JVM)
        assertTrue(cmd.contains("java -version </dev/null 2>&1"))
        assertTrue(cmd.contains("kotlinc -version </dev/null 2>&1"))
        val subcommands = cmd.split(" && ")
        assertEquals(2, subcommands.size)
    }

    @Test
    fun rustGroupChecksCargoAndRustc() {
        val cmd = ToolchainDoctor.healthCheckCommand(RunnerPackageGroup.RUST)
        assertTrue(cmd.contains("cargo --version </dev/null 2>&1"))
        assertTrue(cmd.contains("rustc --version </dev/null 2>&1"))
        val subcommands = cmd.split(" && ")
        assertEquals(2, subcommands.size)
    }

    @Test
    fun golangGroupChecksGoVersion() {
        val cmd = ToolchainDoctor.healthCheckCommand(RunnerPackageGroup.GOLANG)
        assertEquals("go version </dev/null 2>&1", cmd)
    }

    @Test
    fun cppGroupChecksClangAndMake() {
        val cmd = ToolchainDoctor.healthCheckCommand(RunnerPackageGroup.CPP)
        assertTrue(cmd.contains("clang --version </dev/null 2>&1"))
        assertTrue(cmd.contains("make --version </dev/null 2>&1"))
        val subcommands = cmd.split(" && ")
        assertEquals(2, subcommands.size)
    }

    @Test
    fun ssgGroupChecksHugoVersion() {
        val cmd = ToolchainDoctor.healthCheckCommand(RunnerPackageGroup.SSG)
        assertEquals("hugo version </dev/null 2>&1", cmd)
    }

    @Test
    fun doctorResultDataClassProperties() {
        val result = DoctorResult(
            group = RunnerPackageGroup.CORE,
            healthy = true,
            output = "v20.0.0"
        )
        assertEquals(RunnerPackageGroup.CORE, result.group)
        assertTrue(result.healthy)
        assertEquals("v20.0.0", result.output)

        val unhealthy = result.copy(healthy = false, output = "command not found")
        assertFalse(unhealthy.healthy)
        assertEquals("command not found", unhealthy.output)
    }
}
