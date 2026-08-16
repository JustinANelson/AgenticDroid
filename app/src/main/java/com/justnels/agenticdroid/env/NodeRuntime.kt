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
    fun readyMarker(context: Context): File = File(rootDir(context), ".agenticdroid-ready")

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

    /** Applies the shared PATH/LD_LIBRARY_PATH/HOME setup any spawned process needs. */
    fun configureEnvironment(context: Context, environment: MutableMap<String, String>) {
        environment["LD_LIBRARY_PATH"] = libDir(context).absolutePath
        environment["PATH"] = listOf(
            globalBinDir(context).absolutePath,
            binDir(context).absolutePath,
            "/system/bin"
        ).joinToString(":")
        environment["HOME"] = homeDir(context).absolutePath
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
        environment["GH_CONFIG_DIR"] = File(homeDir(context), ".config/gh").absolutePath
        
        environment["NPM_CONFIG_PREFIX"] = globalDir(context).absolutePath
        environment["QEMU_BIN"] = qemuBinary(context).absolutePath
        environment["QEMU_SYSROOT"] = usrDir(context).absolutePath
        environment["GLIBC_SYSROOT"] = glibcSysrootDir(context).absolutePath
        environment["NPM_CLI"] = npmCli(context).absolutePath
        environment["GIT_EXEC_PATH"] = libexecGitCoreDir(context).absolutePath
        environment["TMPDIR"] = context.cacheDir.absolutePath
        
        // Git's Bionic build has Termux's /data/data/com.termux/... path compiled in
        // as its system config location, which is unreadable from our sandbox.
        // Tell it to ignore the system config and use our own global config instead.
        environment["GIT_CONFIG_NOSYSTEM"] = "1"
        environment["GIT_ATTR_NOSYSTEM"] = "1"
        val etcDir = File(usrDir(context), "etc")
        if (!etcDir.exists()) etcDir.mkdirs()
        environment["GIT_CONFIG_SYSTEM"] = File(etcDir, "gitconfig").absolutePath
        environment["GIT_CONFIG_GLOBAL"] = File(homeDir(context), ".gitconfig").absolutePath
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
