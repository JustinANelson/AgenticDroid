package com.justnels.agenticdroid.env

import android.util.Log
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs a natively-executing (no proot/chroot) toolchain: Termux's Bionic-built
 * node + git, npm (pure JS), and a Bionic-built QEMU user-mode emulator - used to run
 * the musl-linked native binaries some npm-distributed agent CLIs ship (e.g. Claude
 * Code). Android can't exec() such a binary directly (its ELF interpreter is
 * /lib/ld-musl-*.so.1, which doesn't exist on Android) and directly invoking musl's own
 * loader gets killed by the seccomp-bpf filter Android's Zygote installs on every real
 * app-spawned process. QEMU-user sidesteps that: it doesn't ptrace and doesn't rely on
 * the guest's own dynamic loader relocating in-process, so it never trips that filter
 * (confirmed empirically - see AgentProfile's install command). QEMU-user still needs
 * musl's own loader present as a real file in its sysroot (it loads the guest ELF
 * interpreter itself), so this also downloads Alpine's musl package directly - not to
 * invoke it, just to give QEMU something to load. A second, separate glibc sysroot
 * (Debian's libc6 + libgcc-s1) is bootstrapped the same way, for agent binaries that
 * ship glibc-linked instead (e.g. Antigravity CLI - see AgentProfile).
 */
class NodeBootstrapper(private val context: Context) {
    private val tag = "NodeBootstrapper"
    private val provisioningVersion = "8"

    // Bionic-native Termux packages, resolved by name against Termux's live index and
    // merged into one usr/ tree (matching Termux's own layout, just relocated).
    private val termuxPackages = listOf(
        "nodejs", "libc++", "openssl", "c-ares", "libicu", "libsqlite", "zlib", "libffi",
        "git", "libcurl", "libexpat", "libiconv", "pcre2", "less",
        "libnghttp2", "libnghttp3", "libngtcp2", "libssh2",
        "curl", "gh",
        "npm",
        // qemu-user-<arch> and its dependency closure (Depends: fields plus libzstd,
        // which libdw needs but doesn't declare) - see qemuPackageName().
        "glib", "libandroid-shmem", "libdw", "libgnutls", "libpixman", "libandroid-support",
        "argp", "libbz2", "liblzma", "libelf", "libgmp", "libnettle", "ca-certificates",
        "libidn2", "libtasn1", "libunbound", "libunistring", "p11-kit", "zstd",
        // used at agent-install time to unpack Antigravity CLI's release archive - see
        // AgentProfile's Antigravity install command.
        "tar", "libacl", "libandroid-glob", "libandroid-selinux"
    )
    private val DEB_PATH_PREFIX = "/data/data/com.termux/files/usr/"

    private fun qemuPackageName(abi: String): String = when (abi) {
        "arm64-v8a" -> "qemu-user-aarch64"
        "x86_64" -> "qemu-user-x86-64"
        else -> throw IOException("Unsupported architecture for qemu-user: $abi")
    }

    // Alpine's musl arch naming, distinct from Termux's and from Android's ABI name.
    private fun muslArch(abi: String): String = when (abi) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> throw IOException("Unsupported architecture for musl: $abi")
    }

    private val ALPINE_APK_INDEX_URL_TEMPLATE =
        "https://dl-cdn.alpinelinux.org/alpine/latest-stable/main/%s/APKINDEX.tar.gz"
    private fun alpinePackagesUrlTemplate(arch: String) =
        "https://dl-cdn.alpinelinux.org/alpine/latest-stable/main/$arch"

    /**
     * QEMU-user needs musl's own dynamic loader (ld-musl-<arch>.so.1) present inside the
     * sysroot it's given via -L: it loads the guest's ELF interpreter itself rather than
     * using any host-side loading, so the interpreter has to exist as a real guest file.
     * This is unrelated to (and doesn't re-enable) direct musl-loader invocation, which
     * stays blocked by Zygote's seccomp filter - see AgentProfile's install command.
     */
    private fun downloadMuslLoader(cacheDir: File, abi: String): File {
        val arch = muslArch(abi)
        val indexFile = File(cacheDir, "APKINDEX.tar.gz")
        downloadFile(ALPINE_APK_INDEX_URL_TEMPLATE.format(arch), indexFile)
        val version = ArchiveExtractor.readApkIndexVersion(indexFile, "musl")
            ?: throw IOException("Could not resolve musl package version from Alpine index")
        indexFile.delete()
        val apkFilename = "musl-$version.apk"
        val apkFile = File(cacheDir, apkFilename)
        downloadFile("${alpinePackagesUrlTemplate(arch)}/$apkFilename", apkFile)
        return apkFile
    }

    // Debian's ELF interpreter path convention differs per arch (lib64 on x86_64,
    // plain lib on arm64) and doesn't match where the .deb itself ships the file
    // (under usr/) - see downloadGlibcSysroot().
    private fun glibcInterpreterPath(abi: String): String = when (abi) {
        "x86_64" -> "lib64/ld-linux-x86-64.so.2"
        "arm64-v8a" -> "lib/ld-linux-aarch64.so.1"
        else -> throw IOException("Unsupported architecture for glibc: $abi")
    }
    private fun glibcInterpreterSourcePath(abi: String): String = when (abi) {
        "x86_64" -> "usr/lib64/ld-linux-x86-64.so.2"
        "arm64-v8a" -> "usr/lib/ld-linux-aarch64.so.1"
        else -> throw IOException("Unsupported architecture for glibc: $abi")
    }

    /**
     * Downloads Debian's libc6 + libgcc-s1 into a standalone glibc sysroot, for agent
     * binaries that ship glibc-linked rather than musl-linked (e.g. Antigravity CLI,
     * which publishes no musl build - see AgentProfile's install command). QEMU-user
     * needs the ELF interpreter present as a real file at the exact absolute path the
     * binary was linked against; Debian's package ships it under usr/ instead, so this
     * copies it into place afterward.
     */
    private fun downloadGlibcSysroot(cacheDir: File, abi: String, onProgress: (String) -> Unit) {
        val glibcRoot = NodeRuntime.glibcSysrootDir(context)
        glibcRoot.mkdirs()
        val debianArch = DebianPackageIndex.debianArch(abi)
        val packages = setOf("libc6", "libgcc-s1")
        onProgress("Resolving glibc package versions...")
        val indexFile = File(cacheDir, "Packages.gz")
        val filenames = DebianPackageIndex.fetchIndex(debianArch, packages, indexFile)
        indexFile.delete()
        for (pkg in packages) {
            onProgress("Installing $pkg (glibc)...")
            val filename = filenames[pkg] ?: throw IOException("Package not found in Debian index: $pkg")
            val deb = File(cacheDir, filename.substringAfterLast('/'))
            downloadFile(DebianPackageIndex.downloadUrlFor(filename), deb)
            ArchiveExtractor.extractDeb(deb, glibcRoot, "")
            deb.delete()
        }
        val interpSource = File(glibcRoot, glibcInterpreterSourcePath(abi))
        val interpDest = File(glibcRoot, glibcInterpreterPath(abi))
        interpDest.parentFile?.mkdirs()
        interpSource.copyTo(interpDest, overwrite = true)
        interpDest.setExecutable(true)
    }

    /**
     * QEMU's `-L` sysroot redirects the guest's absolute-path opens, including musl/glibc's
     * own DNS resolver reading /etc/resolv.conf - and Android has no such file itself (its
     * apps resolve DNS via netd, not resolv.conf), so a QEMU-wrapped agent binary's own
     * getaddrinfo() found nothing and every request timed out (confirmed empirically: this
     * is what "installs but can't reach the cloud provider" turned out to be - Claude Code
     * failed with "Failed to connect to api.anthropic.com: ETIMEOUT" with no resolv.conf
     * present, and connected normally once one existed). Written unconditionally on every
     * bootstrap() call, including the already-installed fast path, so it's not gated behind
     * provisioningVersion and reaches devices that installed before this fix.
     */
    private fun writeResolvConf(sysroot: File) {
        val etcDir = File(sysroot, "etc")
        etcDir.mkdirs()
        File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    private fun ensureResolvConf() {
        val usr = NodeRuntime.usrDir(context)
        if (usr.isDirectory) writeResolvConf(usr)
        val glibcRoot = NodeRuntime.glibcSysrootDir(context)
        if (glibcRoot.isDirectory) writeResolvConf(glibcRoot)
    }

    fun isInstalled(): Boolean = NodeRuntime.isInstalled(context, provisioningVersion)

    fun clear() {
        NodeRuntime.rootDir(context).deleteRecursively()
    }

    private fun abi(): String {
        return android.os.Build.SUPPORTED_ABIS.firstOrNull {
            it in setOf("arm64-v8a", "x86_64")
        } ?: throw IOException(
            "Unsupported device architecture: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
        )
    }

    suspend fun bootstrap(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            ensureResolvConf()
            onProgress("Node environment ready!")
            return@withContext
        }
        clear()
        val usr = NodeRuntime.usrDir(context)
        listOf(usr, NodeRuntime.homeDir(context), NodeRuntime.globalDir(context)).forEach { it.mkdirs() }

        val abi = abi()
        val termuxArch = TermuxPackageIndex.termuxArch(abi)
        val cacheDir = File(context.cacheDir, "node-bootstrap-dl").also { it.mkdirs() }

        try {
            onProgress("Resolving package versions...")
            val index = TermuxPackageIndex.fetchIndex(termuxArch)

            for (pkg in termuxPackages + qemuPackageName(abi)) {
                onProgress("Installing $pkg...")
                val filename = index[pkg] ?: throw IOException("Package not found in Termux index: $pkg")
                val deb = downloadPackage(filename, cacheDir)
                ArchiveExtractor.extractDeb(deb, usr, DEB_PATH_PREFIX)
                deb.delete()
            }
            
            // Thoroughly ensure all binaries and shared libraries have correct permissions.
            // Termux's packages often have subdirectories with more binaries.
            usr.walkBottomUp().forEach { file ->
                if (file.parentFile?.name == "bin" || file.parentFile?.name == "libexec" || file.parentFile?.parentFile?.name == "libexec") {
                    file.setExecutable(true)
                }
                if (file.name.endsWith(".so") || file.name.contains(".so.")) {
                    file.setReadable(true)
                    file.setExecutable(true) // Shared libs need +x on some Android versions/filesystems
                }
            }

            // npm ships as a JS entry point (lib/node_modules/npm/bin/npm-cli.js), not a
            // bin/npm binary - add the usual wrapper so `npm`/`npx` work as plain PATH
            // commands, matching Termux's own convention.
            val nodePath = NodeRuntime.nodeBinary(context).absolutePath
            val npmCli = NodeRuntime.npmCli(context)
            File(NodeRuntime.binDir(context), "npm").writeText(
                "#!/system/bin/sh\nexec \"$nodePath\" \"${npmCli.absolutePath}\" \"\$@\"\n"
            )
            val npxCli = File(npmCli.parentFile, "npx-cli.js")
            File(NodeRuntime.binDir(context), "npx").writeText(
                "#!/system/bin/sh\nexec \"$nodePath\" \"${npxCli.absolutePath}\" \"\$@\"\n"
            )
            File(NodeRuntime.binDir(context), "npm").setExecutable(true)
            File(NodeRuntime.binDir(context), "npx").setExecutable(true)

            NodeRuntime.qemuBinary(context).setExecutable(true)

            onProgress("Installing musl loader (for QEMU-user)...")
            val muslApk = downloadMuslLoader(cacheDir, abi)
            ArchiveExtractor.extractApk(muslApk, usr, "")
            muslApk.delete()

            downloadGlibcSysroot(cacheDir, abi, onProgress)
            ensureResolvConf()

            NodeRuntime.readyMarker(context).writeText("$provisioningVersion\n")
        } catch (e: Exception) {
            Log.e(tag, "Node bootstrap failed", e)
            onProgress("Setup failed: ${e.message}")
            throw e
        } finally {
            cacheDir.deleteRecursively()
        }

        onProgress("Node environment ready!")
    }

    private fun downloadPackage(filename: String, cacheDir: File): File {
        val dest = File(cacheDir, filename.substringAfterLast('/'))
        downloadFile(TermuxPackageIndex.downloadUrlFor(filename), dest)
        return dest
    }


    private fun downloadFile(url: String, destination: File) {
        var lastException: Exception? = null
        repeat(3) { attempt ->
            try {
                val partialFile = File(destination.parentFile, "${destination.name}.part")
                partialFile.delete()
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android AgenticDroid)")
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 120_000
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) throw IOException("HTTP $responseCode downloading $url")
                    connection.inputStream.use { input ->
                        partialFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (partialFile.length() == 0L) throw IOException("Downloaded an empty file from $url")
                    if (destination.exists()) destination.delete()
                    if (!partialFile.renameTo(destination)) {
                        throw IOException("Could not finalize download at ${destination.absolutePath}")
                    }
                    return // Success
                } finally {
                    connection.disconnect()
                    partialFile.delete()
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(tag, "Download attempt ${attempt + 1} failed for $url: ${e.message}")
                Thread.sleep(2000) // Wait before retry
            }
        }
        throw lastException ?: IOException("Failed to download $url after 3 attempts")
    }
}
