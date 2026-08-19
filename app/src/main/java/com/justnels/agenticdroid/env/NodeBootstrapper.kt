package com.justnels.agenticdroid.env

import android.util.Log
import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
    // Bumped from "14": package installation is now split into independently-installable
    // RunnerPackageGroups instead of one all-or-nothing list, so any prior install (whose
    // on-disk layout the old code assumed) needs to be redone under the new bookkeeping.
    // Bumped again "16"->"17": added openssh to CORE.
    private val provisioningVersion = "17"
    private val maxArtifactBytes = 512L * 1024L * 1024L

    // This is a path *inside Termux package archives*, not an Android filesystem path.
    @Suppress("SdCardPath")
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

    private data class AlpineMuslPin(val version: String, val sha256: String)

    private val alpineMuslPins = mapOf(
        "aarch64" to AlpineMuslPin(
            version = "1.2.6-r2",
            sha256 = "5e9674b7f41152fe2119093b5cb4c13eaaadb19c2d5422b2d7267913e663ee6e"
        ),
        "x86_64" to AlpineMuslPin(
            version = "1.2.6-r2",
            sha256 = "573712e2f49c15bfc20a2699f204acdfc74c772722b15e7353d768057fae0e71"
        )
    )

    private fun alpinePackagesUrl(arch: String) =
        "https://dl-cdn.alpinelinux.org/alpine/v3.24/main/$arch"

    /**
     * QEMU-user needs musl's own dynamic loader (ld-musl-<arch>.so.1) present inside the
     * sysroot it's given via -L: it loads the guest's ELF interpreter itself rather than
     * using any host-side loading, so the interpreter has to exist as a real guest file.
     * This is unrelated to (and doesn't re-enable) direct musl-loader invocation, which
     * stays blocked by Zygote's seccomp filter - see AgentProfile's install command.
     */
    private suspend fun downloadMuslLoader(cacheDir: File, abi: String): File {
        val arch = muslArch(abi)
        val pin = alpineMuslPins[arch] ?: throw IOException("No pinned Alpine musl package for $arch")
        val apkFilename = "musl-${pin.version}.apk"
        val apkFile = File(cacheDir, apkFilename)
        downloadFile("${alpinePackagesUrl(arch)}/$apkFilename", apkFile, pin.sha256)
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
    private suspend fun downloadGlibcSysroot(cacheDir: File, abi: String, onProgress: (String) -> Unit) {
        val glibcRoot = NodeRuntime.glibcSysrootDir(context)
        glibcRoot.mkdirs()
        val debianArch = DebianPackageIndex.debianArch(abi)
        val packages = setOf("libc6", "libgcc-s1")
        val needed = packages.filterNot { isExtracted("glibc:$it") }
        val artifacts = if (needed.isNotEmpty()) {
            onProgress("Resolving glibc package versions...")
            val indexFile = File(cacheDir, "Packages.gz")
            val result = DebianPackageIndex.fetchIndex(debianArch, needed.toSet(), indexFile)
            indexFile.delete()
            result
        } else emptyMap()
        for (pkg in packages) {
            val markerKey = "glibc:$pkg"
            if (isExtracted(markerKey)) {
                onProgress("Already installed $pkg (glibc)")
                continue
            }
            onProgress("Installing $pkg (glibc)...")
            val artifact = artifacts[pkg] ?: throw IOException("Package not found in Debian index: $pkg")
            val deb = File(cacheDir, artifact.filename.substringAfterLast('/'))
            downloadFile(DebianPackageIndex.downloadUrlFor(artifact.filename), deb, artifact.sha256, artifact.size)
            ArchiveExtractor.extractDeb(deb, glibcRoot, "")
            deb.delete()
            markExtracted(markerKey)
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
     * present, and connected normally once one existed).
     *
     * Prefers the network's own resolvers (from ConnectivityManager/LinkProperties) over
     * the hardcoded public ones: some networks (captive portals, some carrier/enterprise
     * setups) don't route to third-party resolvers like 8.8.8.8 at all, which would
     * reproduce the exact same "installs but every request times out" symptom from a
     * different cause. Public resolvers are kept as a fallback/tail entry for networks
     * whose own resolver is unreachable from a plain socket (e.g. transparent-proxy-only
     * setups). Capped at 3 entries - both glibc's and musl's resolvers only consult the
     * first 3 lines of resolv.conf.
     */
    private fun writeResolvConf(sysroot: File) {
        val servers = (deviceDnsServers() + listOf("8.8.8.8", "1.1.1.1")).distinct().take(3)
        val etcDir = File(sysroot, "etc")
        etcDir.mkdirs()
        File(etcDir, "resolv.conf").writeText(servers.joinToString("") { "nameserver $it\n" })
    }

    private fun deviceDnsServers(): List<String> = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val linkProperties = network?.let { cm.getLinkProperties(it) }
        linkProperties?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
    } catch (e: Exception) {
        Log.w(tag, "Could not read device DNS servers", e)
        emptyList()
    }

    /**
     * Refreshes resolv.conf for the bundled sysroots. Unlike the rest of bootstrap(), this
     * is meant to be called on every agent/terminal session launch (not just at install
     * time) since the device's own resolvers - and reachability of the public fallbacks -
     * can change whenever the phone switches between Wi-Fi and mobile data or roams to a
     * new network; a session started on one network shouldn't keep stale resolvers from
     * whatever network was active when the toolchain was installed.
     */
    fun ensureResolvConf() {
        val usr = NodeRuntime.usrDir(context)
        if (usr.isDirectory) {
            writeResolvConf(usr)
            File(usr, "tmp").mkdirs()
        }
        val home = NodeRuntime.homeDir(context)
        if (home.isDirectory) {
            File(home, ".android").mkdirs()
            File(home, ".gradle").mkdirs()
        }
        val glibcRoot = NodeRuntime.glibcSysrootDir(context)
        if (glibcRoot.isDirectory) writeResolvConf(glibcRoot)
    }

    /** Whether the always-required [RunnerPackageGroup.CORE] group is installed. */
    fun isInstalled(): Boolean = NodeRuntime.isInstalled(context, provisioningVersion)

    fun isGroupInstalled(group: RunnerPackageGroup): Boolean =
        NodeRuntime.isGroupInstalled(context, provisioningVersion, group)

    fun installedGroups(): Set<RunnerPackageGroup> =
        RunnerPackageGroup.entries.filterTo(mutableSetOf()) { isGroupInstalled(it) }

    /** Approximate on-disk footprint of [group], as recorded the last time it was installed. */
    fun groupSizeBytes(group: RunnerPackageGroup): Long = readGroupSizes()[group] ?: 0L

    private fun readGroupSizes(): Map<RunnerPackageGroup, Long> {
        val file = NodeRuntime.groupSizesFile(context)
        if (!file.isFile) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val (name, bytes) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val group = runCatching { RunnerPackageGroup.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            group to (bytes.toLongOrNull() ?: 0L)
        }.toMap()
    }

    private fun recordGroupSize(group: RunnerPackageGroup, bytes: Long) {
        val sizes = readGroupSizes().toMutableMap()
        sizes[group] = bytes
        NodeRuntime.groupSizesFile(context).writeText(
            sizes.entries.joinToString("\n") { (g, b) -> "${g.name}=$b" }
        )
    }

    private fun forgetGroupSize(group: RunnerPackageGroup) {
        val sizes = readGroupSizes().toMutableMap()
        if (sizes.remove(group) != null) {
            NodeRuntime.groupSizesFile(context).writeText(
                sizes.entries.joinToString("\n") { (g, b) -> "${g.name}=$b" }
            )
        }
    }

    /**
     * Removes [group]'s files and frees the space it used, leaving every other installed
     * group (CORE included) intact even if they happen to share a package with it (e.g.
     * RUST and CPP both need clang/binutils) - a package is only deleted once no
     * remaining installed group's list still names it. [RunnerPackageGroup.CORE] itself
     * can't be uninstalled; nothing else in this app works without it.
     */
    suspend fun uninstallGroup(group: RunnerPackageGroup) = withContext(Dispatchers.IO) {
        require(group != RunnerPackageGroup.CORE) { "CORE cannot be uninstalled" }
        if (!isGroupInstalled(group)) return@withContext
        val stillNeeded = RunnerPackageGroup.packagesNeededAfterRemoving(group, installedGroups())
        val usr = NodeRuntime.usrDir(context)
        for (pkg in group.termuxPackages) {
            if (pkg in stillNeeded) continue
            val manifest = NodeRuntime.manifestFile(context, pkg)
            if (manifest.isFile) {
                manifest.readLines().forEach { relPath ->
                    if (relPath.isNotBlank()) File(usr, relPath).delete()
                }
                manifest.delete()
            }
        }
        // Tidy up directories a removed package left empty - purely cosmetic/disk-usage
        // hygiene, safe even if some still hold files from other packages.
        usr.walkBottomUp().forEach { dir ->
            if (dir != usr && dir.isDirectory) dir.delete()
        }
        NodeRuntime.groupReadyMarker(context, group).delete()
        forgetGroupSize(group)
    }

    /**
     * Forces [group] to be re-downloaded and re-extracted from Termux's current live
     * index next time [bootstrap] runs, picking up any package updates since it was last
     * installed - existing files are overwritten in place package-by-package rather than
     * deleted upfront, so there's no window where a tool this group provides is missing.
     * CORE is deliberately excluded: clearing its readiness marker would make
     * [isGroupInstalled] report every group as not-installed (all of them require CORE)
     * for the whole span of the re-download, even though CORE's files are still fully
     * present and working on disk the entire time.
     */
    fun markGroupForRefresh(group: RunnerPackageGroup) {
        require(group != RunnerPackageGroup.CORE) { "CORE cannot be refreshed in place" }
        NodeRuntime.groupReadyMarker(context, group).delete()
    }

    fun clear() {
        NodeRuntime.rootDir(context).deleteRecursively()
    }

    // Marks (by name) which packages/steps have already been downloaded and extracted
    // into place, so a bootstrap() that failed partway (a dropped connection on package
    // 18 of 20, say) can pick up where it left off on retry instead of re-downloading
    // and re-extracting everything from scratch - a multi-minute install over cellular is
    // exactly where a transient blip is likely, and previously any single failure there
    // discarded all prior progress via clear(). Cleared as part of clear() itself (same
    // directory tree), and swept away on a successful bootstrap() completion.
    private fun extractionMarkersDir(): File =
        File(NodeRuntime.rootDir(context), ".agenticdroid-extracted").also { it.mkdirs() }

    private fun isExtracted(key: String): Boolean = File(extractionMarkersDir(), key).isFile

    private fun markExtracted(key: String) {
        File(extractionMarkersDir(), key).createNewFile()
    }

    // Recorded once a bootstrap attempt starts populating rootDir, so a later attempt can
    // tell "a partial install of this same provisioningVersion, safe to resume" apart from
    // "a partial or completed install of some other version, must be wiped first" - mixing
    // extracted files across different provisioningVersions could leave an inconsistent
    // toolchain.
    private fun inProgressVersionMarker(): File =
        File(NodeRuntime.rootDir(context), ".agenticdroid-provisioning-version")

    private fun abi(): String {
        return android.os.Build.SUPPORTED_ABIS.firstOrNull {
            it in setOf("arm64-v8a", "x86_64")
        } ?: throw IOException(
            "Unsupported device architecture: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
        )
    }

    /**
     * Installs [groups] (CORE always included implicitly) without re-downloading any
     * group that's already installed at the current [provisioningVersion] - so calling
     * this again later with a different set of groups only fetches what's newly needed,
     * instead of the old all-or-nothing behavior.
     */
    suspend fun bootstrap(
        groups: Set<RunnerPackageGroup>,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val requestedGroups = groups + RunnerPackageGroup.CORE
        val pendingGroups = requestedGroups.filterNot { isGroupInstalled(it) }
        if (pendingGroups.isEmpty()) {
            ensureResolvConf()
            onProgress("Environment ready!")
            return@withContext
        }
        val coreIsPending = RunnerPackageGroup.CORE in pendingGroups

        val root = NodeRuntime.rootDir(context)
        val versionMarker = inProgressVersionMarker()
        // A stale-version root (from a previous provisioningVersion) can't be reused for
        // an incremental install - wipe it. A same-version root is safe to build on,
        // whether it holds a fully-installed CORE (adding a new group) or a
        // partially-downloaded one from an interrupted run (resuming it).
        val existingVersion = when {
            NodeRuntime.readyMarker(context).isFile -> NodeRuntime.readyMarker(context).readText().trim()
            versionMarker.isFile -> versionMarker.readText().trim()
            else -> null
        }
        if (!root.isDirectory || existingVersion != provisioningVersion) {
            clear()
            root.mkdirs()
        }
        if (!versionMarker.isFile || versionMarker.readText().trim() != provisioningVersion) {
            versionMarker.writeText("$provisioningVersion\n")
        }
        val usr = NodeRuntime.usrDir(context)
        listOf(usr, NodeRuntime.homeDir(context), NodeRuntime.globalDir(context)).forEach { it.mkdirs() }

        val abi = abi()
        val termuxArch = TermuxPackageIndex.termuxArch(abi)
        val cacheDir = File(context.cacheDir, "node-bootstrap-dl").also { it.mkdirs() }

        val pendingPackages = pendingGroups.flatMap { it.termuxPackages } +
            (if (coreIsPending) listOf(qemuPackageName(abi)) else emptyList())
        // Total download steps across this bootstrap call, so progress text can show
        // "(n/total)" instead of an unbounded spinner during a multi-minute install.
        val totalSteps = pendingPackages.size +
            (if (coreIsPending) 1 /* musl loader */ + 2 /* glibc: libc6, libgcc-s1 */ else 0)
        var completedSteps = 0

        try {
            onProgress("Resolving package versions... (0/$totalSteps)")
            val index = TermuxPackageIndex.fetchIndex(termuxArch)

            for (pkg in pendingPackages) {
                currentCoroutineContext().ensureActive()
                val markerKey = "termux:$pkg"
                if (isExtracted(markerKey)) {
                    onProgress("$pkg already installed (${completedSteps + 1}/$totalSteps)")
                } else {
                    onProgress("Installing $pkg... (${completedSteps + 1}/$totalSteps)")
                    val artifact = index[pkg] ?: throw IOException("Package not found in Termux index: $pkg")
                    val deb = downloadPackage(artifact, cacheDir)
                    val writtenFiles = ArchiveExtractor.extractDeb(deb, usr, DEB_PATH_PREFIX)
                    deb.delete()
                    NodeRuntime.manifestFile(context, pkg).apply {
                        parentFile?.mkdirs()
                        writeText(writtenFiles.joinToString("\n"))
                    }
                    markExtracted(markerKey)
                }
                completedSteps++
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

            // The wrapper/env setup below is idempotent and only touches files that exist
            // (npm/npx need CORE, python/pip need PYTHON, java/javac need JVM) - safe to
            // always re-run regardless of which groups this particular call installed.

            // npm ships as a JS entry point (lib/node_modules/npm/bin/npm-cli.js), not a
            // bin/npm binary - add the usual wrapper so `npm`/`npx` work as plain PATH
            // commands, matching Termux's own convention.
            val nodePath = NodeRuntime.nodeBinary(context).absolutePath
            val npmCli = NodeRuntime.npmCli(context)
            if (npmCli.exists()) {
                File(NodeRuntime.binDir(context), "npm").writeText(
                    "#!/system/bin/sh\nexec \"$nodePath\" \"${npmCli.absolutePath}\" \"\$@\"\n"
                )
                val npxCli = File(npmCli.parentFile, "npx-cli.js")
                File(NodeRuntime.binDir(context), "npx").writeText(
                    "#!/system/bin/sh\nexec \"$nodePath\" \"${npxCli.absolutePath}\" \"\$@\"\n"
                )
                File(NodeRuntime.binDir(context), "npm").setExecutable(true)
                File(NodeRuntime.binDir(context), "npx").setExecutable(true)
            }

            // Python and Pip wrappers: ensure python, python3, pip, pip3 can be invoked directly
            val python3Bin = File(NodeRuntime.binDir(context), "python3")
            val pythonBin = File(NodeRuntime.binDir(context), "python")
            if (!pythonBin.exists() && python3Bin.exists()) {
                pythonBin.writeText(
                    "#!/system/bin/sh\nexec \"${python3Bin.absolutePath}\" \"\$@\"\n"
                )
                pythonBin.setExecutable(true)
            }
            if (python3Bin.exists()) {
                val pipBin = File(NodeRuntime.binDir(context), "pip")
                val pip3Bin = File(NodeRuntime.binDir(context), "pip3")
                val effectivePyPath = if (pythonBin.exists()) pythonBin.absolutePath else python3Bin.absolutePath
                pipBin.writeText(
                    "#!/system/bin/sh\nexec \"$effectivePyPath\" -m pip \"\$@\"\n"
                )
                pip3Bin.writeText(
                    "#!/system/bin/sh\nexec \"${python3Bin.absolutePath}\" -m pip \"\$@\"\n"
                )
                pipBin.setExecutable(true)
                pip3Bin.setExecutable(true)
            }

            // Java wrappers: ensure java, javac, jar, keytool can be invoked directly from binDir
            val javaHome = File(usr, "lib/jvm/java-17-openjdk")
            if (javaHome.exists()) {
                listOf("java", "javac", "jar", "keytool", "javap", "jlink").forEach { tool ->
                    val toolTarget = File(javaHome, "bin/$tool")
                    val toolBin = File(NodeRuntime.binDir(context), tool)
                    if (toolTarget.exists() && !toolBin.exists()) {
                        toolBin.writeText(
                            "#!/system/bin/sh\nexec \"${toolTarget.absolutePath}\" \"\$@\"\n"
                        )
                        toolBin.setExecutable(true)
                    }
                }
            }

            File(usr, "tmp").mkdirs()
            File(NodeRuntime.homeDir(context), ".android").mkdirs()
            File(NodeRuntime.homeDir(context), ".gradle").mkdirs()

            if (coreIsPending) {
                NodeRuntime.qemuBinary(context).setExecutable(true)

                val muslMarkerKey = "musl-loader"
                if (isExtracted(muslMarkerKey)) {
                    onProgress("Musl loader already installed (${completedSteps + 1}/$totalSteps)")
                } else {
                    onProgress("Installing musl loader (for QEMU-user)... (${completedSteps + 1}/$totalSteps)")
                    val muslApk = downloadMuslLoader(cacheDir, abi)
                    ArchiveExtractor.extractApk(muslApk, usr, "")
                    muslApk.delete()
                    markExtracted(muslMarkerKey)
                }
                completedSteps++

                downloadGlibcSysroot(cacheDir, abi) { status ->
                    if (status.startsWith("Installing") || status.startsWith("Already installed")) {
                        onProgress("$status (${completedSteps + 1}/$totalSteps)")
                        completedSteps++
                    } else {
                        onProgress(status)
                    }
                }
                ensureResolvConf()

                NodeRuntime.readyMarker(context).writeText("$provisioningVersion\n")
            }
            for (group in pendingGroups) {
                if (group != RunnerPackageGroup.CORE) {
                    NodeRuntime.groupReadyMarker(context, group).writeText("$provisioningVersion\n")
                }
                val groupBytes = group.termuxPackages.sumOf { index[it]?.size ?: 0L }
                recordGroupSize(group, groupBytes)
            }
            versionMarker.delete()
            extractionMarkersDir().deleteRecursively()
        } catch (e: Exception) {
            Log.e(tag, "Node bootstrap failed", e)
            onProgress("Setup failed: ${e.message}")
            throw e
        } finally {
            cacheDir.deleteRecursively()
        }

        onProgress("Environment ready!")
    }

    private suspend fun downloadPackage(artifact: PackageArtifact, cacheDir: File): File {
        val dest = File(cacheDir, artifact.filename.substringAfterLast('/'))
        downloadFile(TermuxPackageIndex.downloadUrlFor(artifact.filename), dest, artifact.sha256, artifact.size)
        return dest
    }


    private suspend fun downloadFile(
        url: String,
        destination: File,
        expectedSha256: String? = null,
        expectedSize: Long? = null
    ) {
        if (expectedSize != null && expectedSize !in 1..maxArtifactBytes) {
            throw IOException("Invalid package size for ${destination.name}: $expectedSize")
        }
        var lastException: Exception? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
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
                    val contentLength = connection.contentLengthLong
                    if (contentLength > maxArtifactBytes) throw IOException("Download is too large: ${destination.name}")
                    if (expectedSize != null && contentLength > 0 && contentLength != expectedSize) {
                        throw IOException("Unexpected package size for ${destination.name}")
                    }
                    connection.inputStream.use { input ->
                        partialFile.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxArtifactBytes) throw IOException("Download is too large: ${destination.name}")
                                output.write(buffer, 0, read)
                            }
                            if (expectedSize != null && total != expectedSize) {
                                throw IOException("Unexpected package size for ${destination.name}")
                            }
                        }
                    }
                    if (partialFile.length() == 0L) throw IOException("Downloaded an empty file from $url")
                    if (expectedSha256 != null) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        partialFile.inputStream().buffered().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                            }
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(expectedSha256, ignoreCase = true)) {
                            throw IOException("SHA-256 mismatch for ${destination.name}")
                        }
                    }
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                delay(2000)
            }
        }
        throw lastException ?: IOException("Failed to download $url after 3 attempts")
    }
}
