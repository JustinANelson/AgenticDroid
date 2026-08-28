package com.justnels.agenticdroid.env

/**
 * Self-test for an installed [RunnerPackageGroup]: actually execs its key binaries rather
 * than trusting the ready-marker file, since a lot of this toolchain's behavior is
 * empirically-discovered-per-device (see NodeBootstrapper/NodeRuntime's comments on
 * QEMU-user/musl/glibc quirks) - a bootstrap can complete successfully and still leave a
 * group with a binary that doesn't actually run on a given device.
 */
object ToolchainDoctor {
    /** One `<binary> --version`-style check per group, ANDed together so a single exec
     * call reports whether every binary the group provides actually runs. Exit code and
     * combined output both matter: a binary that's merely missing from PATH still exits
     * non-zero via `command -v`, distinguishing "not installed" from "installed but
     * broken" isn't this object's job - the caller already knows which groups it asked
     * about, [DoctorResult.output] is for a human to read either way. */
    fun healthCheckCommand(group: RunnerPackageGroup): String = when (group) {
        RunnerPackageGroup.CORE -> listOf(
            "node --version", "git --version", "npm --version", "curl --version",
            "rg --version", "jq --version", "fd --version", "sqlite3 --version"
        )
        RunnerPackageGroup.PYTHON -> listOf("python --version", "pip --version")
        RunnerPackageGroup.JVM -> listOf("java -version", "kotlinc -version")
        RunnerPackageGroup.RUST -> listOf("cargo --version", "rustc --version")
        RunnerPackageGroup.GOLANG -> listOf("go version")
        RunnerPackageGroup.CPP -> listOf("clang --version", "make --version")
        RunnerPackageGroup.SSG -> listOf("hugo version")
    }.joinToString(" && ") { "$it </dev/null 2>&1" }
}

data class DoctorResult(
    val group: RunnerPackageGroup,
    val healthy: Boolean,
    val output: String
)
