package com.justnels.agenticdroid.env

import android.content.Context
import java.io.File

/**
 * Paths and process-environment setup for the bundled, natively-executing Node.js
 * toolchain (Termux's Bionic-built node/git/qemu-user). No chroot/proot/ptrace is
 * involved anywhere here - everything runs as a normal Android child process.
 *
 * Termux packages are extracted preserving their own usr/ layout (bin/, lib/,
 * libexec/, lib/node_modules/npm/), merged from every package into one directory -
 * this is exactly how Termux's own prefix works, just relocated.
 */
object NodeRuntime {
    fun rootDir(context: Context): File = File(context.filesDir, "node-runtime")
    fun usrDir(context: Context): File = File(rootDir(context), "usr")
    fun binDir(context: Context): File = File(usrDir(context), "bin")
    fun libDir(context: Context): File = File(usrDir(context), "lib")
    fun libexecGitCoreDir(context: Context): File = File(usrDir(context), "libexec/git-core")
    fun npmCli(context: Context): File = File(libDir(context), "node_modules/npm/bin/npm-cli.js")
    fun globalDir(context: Context): File = File(rootDir(context), "global")
    fun globalBinDir(context: Context): File = File(globalDir(context), "bin")
    fun homeDir(context: Context): File = File(rootDir(context), "home")
    fun nodeBinary(context: Context): File = File(binDir(context), "node")
    fun gitBinary(context: Context): File = File(binDir(context), "git")
    fun pythonBinary(context: Context): File {
        val py = File(binDir(context), "python")
        return if (py.exists()) py else File(binDir(context), "python3")
    }
    fun pipBinary(context: Context): File {
        val pip = File(binDir(context), "pip")
        return if (pip.exists()) pip else File(binDir(context), "pip3")
    }

    /** Termux's Bionic-native aapt2 build (installed as part of RunnerPackageGroup.JVM) -
     * see [ensureGradleUserHomeProperties] for why AGP needs to be pointed at it. */
    fun aapt2Binary(context: Context): File = File(binDir(context), "aapt2")

    fun gradleUserHomeDir(context: Context): File = File(homeDir(context), ".gradle")

    /**
     * Points AGP at Termux's own Bionic-native aapt2 instead of AGP's own Maven-resolved
     * aapt2 (a glibc binary AGP execs directly with no wrapper hook this app controls,
     * which trips the same Zygote seccomp-bpf block this app's QEMU-user machinery
     * exists to work around in the first place).
     *
     * Must land as a real Gradle property in $GRADLE_USER_HOME/gradle.properties, not an
     * ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride environment variable: empirically,
     * the env-var form (a literal "." in the variable name) was honored by a project's own
     * main resource-processing tasks but not by AarResourcesCompilerTransform, the
     * detached-configuration artifact transform AGP uses to precompile library AARs'
     * resources (e.g. androidx.core) - that transform kept trying to launch AGP's own
     * glibc aapt2 and failing ("Daemon startup failed"). This can't be a checked-in
     * project property either (unlike org.gradle.jvmargs - see this repo's own
     * gradle.properties): the aapt2 path is per-installation (app-private storage), not a
     * fixed value.
     *
     * Idempotent - cheap to call before every on-device build. Silently does nothing if
     * RunnerPackageGroup.JVM (which provides aapt2) isn't installed; AGP will just fail
     * with its own ordinary "aapt2 not found" error in that case.
     */
    fun ensureGradleUserHomeProperties(context: Context) {
        val aapt2 = aapt2Binary(context)
        if (!aapt2.canExecute()) return
        val propsFile = File(gradleUserHomeDir(context).also { it.mkdirs() }, "gradle.properties")
        val existingLines = if (propsFile.isFile) propsFile.readLines() else emptyList()
        val merged = mergeProperty(existingLines, "android.aapt2FromMavenOverride", aapt2.absolutePath)
        if (merged != existingLines) propsFile.writeText(merged.joinToString("\n") + "\n")
    }

    /** Sets [key]=[value] in a gradle.properties-style line list, replacing any existing
     * line for [key] and leaving every other line untouched - pulled out of
     * [ensureGradleUserHomeProperties] so this merge logic is unit-testable without a
     * real Context. */
    internal fun mergeProperty(existingLines: List<String>, key: String, value: String): List<String> {
        val desiredLine = "$key=$value"
        if (existingLines.any { it.trim() == desiredLine }) return existingLines
        return existingLines.filterNot { it.trim().startsWith("$key=") } + desiredLine
    }

    fun readyMarker(context: Context): File = File(rootDir(context), ".agenticdroid-ready")

    /** Per-group readiness marker for every group beyond [RunnerPackageGroup.CORE]. */
    fun groupReadyMarker(context: Context, group: RunnerPackageGroup): File =
        File(rootDir(context), ".agenticdroid-ready-${group.name.lowercase()}")

    /**
     * Records exactly which files (relative to [usrDir]) a given Termux package's
     * install wrote, so an unused package can later be removed without touching files a
     * still-installed group also needs (see NodeBootstrapper.uninstallGroup). Persists
     * across bootstrap() calls - unlike the per-call extraction-resume markers, which are
     * swept after every successful bootstrap().
     */
    fun manifestFile(context: Context, termuxPackage: String): File =
        File(File(rootDir(context), ".agenticdroid-manifests"), termuxPackage)

    /** Tracks each installed group's on-disk footprint, in bytes - shown in the Runners UI. */
    fun groupSizesFile(context: Context): File = File(rootDir(context), ".agenticdroid-group-sizes")

    /**
     * Bionic-built QEMU user-mode emulator, used to run musl-linked native binaries
     * some npm-distributed agent CLIs ship. Android's Zygote seccomp filter kills a
     * syscall musl's own dynamic loader needs during relocation when invoked directly
     * as a real app child - QEMU-user doesn't ptrace and does its own ELF loading, so
     * it never trips that filter (confirmed empirically). This also blocks *statically*
     * linked musl/Rust binaries with no dynamic loader involved at all (confirmed with
     * Codex's static-pie binary: runs fine invoked directly under `run-as`, but a real
     * Zygote child gets killed with SIGSYS/"Bad system call") - QEMU-user sidesteps
     * that too, since none of the guest's syscalls reach the host seccomp filter
     * directly.
     */
    fun qemuBinary(context: Context): File = File(binDir(context), "qemu-${qemuArch()}")

    /**
     * A second, separate sysroot holding a glibc build (Debian's libc6 + libgcc-s1),
     * for agent binaries that ship glibc-linked rather than musl-linked (e.g.
     * Antigravity CLI, which publishes no musl build). Kept apart from [usrDir] so
     * glibc's libc.so.6 et al never shadow anything in the Termux/musl tree sharing
     * the same LD_LIBRARY_PATH.
     */
    fun glibcSysrootDir(context: Context): File = File(rootDir(context), "glibc-sysroot")

    fun isInstalled(context: Context, expectedVersion: String): Boolean {
        val marker = readyMarker(context)
        return marker.isFile &&
            marker.readText().trim() == expectedVersion &&
            nodeBinary(context).canExecute()
    }

    /** Whether [group] (CORE included) has been fully installed at [expectedVersion]. */
    fun isGroupInstalled(context: Context, expectedVersion: String, group: RunnerPackageGroup): Boolean {
        if (!isInstalled(context, expectedVersion)) return false
        if (group == RunnerPackageGroup.CORE) return true
        val marker = groupReadyMarker(context, group)
        return marker.isFile && marker.readText().trim() == expectedVersion
    }

    /** Applies the shared PATH/LD_LIBRARY_PATH/HOME setup any spawned process needs. */
    fun configureEnvironment(context: Context, environment: MutableMap<String, String>) {
        val userHome = homeDir(context).also { it.mkdirs() }
        val userLocalBin = File(userHome, ".local/bin").also { it.mkdirs() }
        val tmpDir = File(usrDir(context), "tmp").also { it.mkdirs() }
        val androidUserHome = File(userHome, ".android").also { it.mkdirs() }
        val gradleUserHome = gradleUserHomeDir(context).also { it.mkdirs() }
        val javaHome = File(usrDir(context), "lib/jvm/java-17-openjdk")
        val javaBin = File(javaHome, "bin")

        environment["LD_LIBRARY_PATH"] = libDir(context).absolutePath
        environment["PATH"] = listOfNotNull(
            globalBinDir(context).absolutePath,
            userLocalBin.absolutePath,
            binDir(context).absolutePath,
            javaBin.takeIf { it.exists() }?.absolutePath,
            "/system/bin"
        ).joinToString(":")
        environment["HOME"] = userHome.absolutePath
        // node's Bionic build has Termux's own /data/data/com.termux/... path compiled in
        // as its default OpenSSL config location - unreadable from this app's sandbox
        // (different app UID), which openssl treats as a hard error rather than falling
        // back. Point it at our own bundled copy instead.
        environment["OPENSSL_CONF"] = File(usrDir(context), "etc/tls/openssl.cnf").absolutePath
        val certFile = File(usrDir(context), "etc/tls/cert.pem").absolutePath
        environment["SSL_CERT_FILE"] = certFile
        environment["CURL_CA_BUNDLE"] = certFile
        environment["GIT_SSL_CAINFO"] = certFile
        
        // GitHub CLI configuration directory
        environment["GH_CONFIG_DIR"] = File(userHome, ".config/gh").absolutePath
        
        // Python configuration
        environment["PYTHONHOME"] = usrDir(context).absolutePath
        environment["PYTHONUSERBASE"] = File(userHome, ".local").absolutePath
        
        // Java & Gradle JVM configurations
        if (javaHome.exists()) {
            environment["JAVA_HOME"] = javaHome.absolutePath
        }
        environment["JAVA_TOOL_OPTIONS"] = "-Djava.io.tmpdir=${tmpDir.absolutePath}"
        environment["_JAVA_OPTIONS"] = "-Djava.io.tmpdir=${tmpDir.absolutePath}"
        environment["GRADLE_OPTS"] = "-Djava.io.tmpdir=${tmpDir.absolutePath}"
        environment["ANDROID_USER_HOME"] = androidUserHome.absolutePath
        environment["ANDROID_SDK_HOME"] = userHome.absolutePath
        environment["GRADLE_USER_HOME"] = gradleUserHome.absolutePath
        
        // Node reports process.platform === "android" for any Bionic build, including this
        // bundled one - and some npm packages (confirmed: clipboardy, a dependency of
        // Google's Gemini CLI) throw unconditionally at module-load time on that platform
        // unless $TERMUX_VERSION is set, without actually checking Termux is present. Set
        // generically here (not just for one agent) since any future pure-JS agent CLI can
        // hit the same check.
        environment["TERMUX_VERSION"] = "0.118.0"
        environment["NPM_CONFIG_PREFIX"] = globalDir(context).absolutePath
        environment["QEMU_BIN"] = qemuBinary(context).absolutePath
        environment["QEMU_SYSROOT"] = usrDir(context).absolutePath
        environment["GLIBC_SYSROOT"] = glibcSysrootDir(context).absolutePath
        environment["NPM_CLI"] = npmCli(context).absolutePath
        environment["GIT_EXEC_PATH"] = libexecGitCoreDir(context).absolutePath
        environment["TMPDIR"] = tmpDir.absolutePath
        environment["TEMP"] = tmpDir.absolutePath
        environment["TMP"] = tmpDir.absolutePath
        
        // Git's Bionic build has Termux's /data/data/com.termux/... path compiled in
        // as its system config location, which is unreadable from our sandbox.
        // Tell it to ignore the system config and use our own global config instead.
        environment["GIT_CONFIG_NOSYSTEM"] = "1"
        environment["GIT_ATTR_NOSYSTEM"] = "1"
        val etcDir = File(usrDir(context), "etc")
        if (!etcDir.exists()) etcDir.mkdirs()
        environment["GIT_CONFIG_SYSTEM"] = File(etcDir, "gitconfig").absolutePath
        environment["GIT_CONFIG_GLOBAL"] = File(userHome, ".gitconfig").absolutePath
    }

    /** QEMU's own arch naming for its user-mode binary, matching the device ABI. */
    fun qemuArch(): String {
        return android.os.Build.SUPPORTED_ABIS.firstNotNullOfOrNull {
            when (it) {
                "arm64-v8a" -> "aarch64"
                "x86_64" -> "x86_64"
                else -> null
            }
        } ?: "x86_64"
    }
}
