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
    fun nodeBinary(context: Context): File =
        nativeLibBinary(context, "libnode_native_${qemuArch()}.so") ?: File(binDir(context), "node")
    fun gitBinary(context: Context): File =
        nativeLibBinary(context, "libgit_native_${qemuArch()}.so") ?: File(binDir(context), "git")
    fun npmBinary(context: Context): File =
        nativeLibBinary(context, "libnpm_wrapper.so") ?: File(binDir(context), "npm")
    fun npxBinary(context: Context): File =
        nativeLibBinary(context, "libnpx_wrapper.so") ?: File(binDir(context), "npx")
    fun pythonBinary(context: Context): File {
        nativeLibBinary(context, "libpython_native_${qemuArch()}.so")?.let { return it }
        val py = File(binDir(context), "python")
        return if (py.exists()) py else File(binDir(context), "python3")
    }
    fun pipBinary(context: Context): File {
        nativeLibBinary(context, "libpip_wrapper.so")?.let { return it }
        val pip = File(binDir(context), "pip")
        return if (pip.exists()) pip else File(binDir(context), "pip3")
    }
    fun jdkCommandBinary(context: Context, tool: String): File =
        nativeLibBinary(context, "libjdk_${tool}_wrapper.so") ?: File(binDir(context), tool)
    fun kotlincBinary(context: Context): File =
        nativeLibBinary(context, "libkotlinc_wrapper.so") ?: File(binDir(context), "kotlinc")

    /** Termux's Bionic-native aapt2 build (installed as part of RunnerPackageGroup.JVM) -
     * see [ensureGradleUserHomeProperties] for why AGP needs to be pointed at it. Prefers
     * the native-lib-packaged copy (see [nativeLibBinary]) when bundled for this ABI. */
    fun aapt2Binary(context: Context): File =
        nativeLibBinary(context, "libaapt2_native_${qemuArch()}.so") ?: File(binDir(context), "aapt2")

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
    fun qemuBinary(context: Context): File =
        qemuNativeLibBinary(context) ?: File(binDir(context), "qemu-${qemuArch()}")

    /**
     * PROTOTYPE (see AGENT_RUNTIME_RESEARCH.md): qemu-user shipped as a renamed APK
     * native library (`libqemu_user_<arch>.so` under `jniLibs/<abi>/`) instead of
     * downloaded into app-private storage at runtime. PackageManager extracts native
     * libraries to [android.content.pm.ApplicationInfo.nativeLibraryDir] at install
     * time - a location exempt from the API 29+ W^X policy that currently forces this
     * app's `targetSdk = 28` pin (see build.gradle.kts). Only bundled for arm64-v8a so
     * far, matching the one device this was verified against; falls through to the
     * downloaded copy in [binDir] for any other ABI.
     */
    fun qemuNativeLibBinary(context: Context): File? =
        nativeLibBinary(context, "libqemu_user_${qemuArch()}.so")

    /**
     * PROTOTYPE (see AGENT_RUNTIME_RESEARCH.md Sections 7-9 and 12): resolves any of this
     * app's renamed-as-`lib*.so` native-lib-packaged binaries or shared-library
     * dependencies from [android.content.pm.ApplicationInfo.nativeLibraryDir] - the
     * PackageManager-extracted, W^X-exempt directory every native-lib-packaged binary
     * this app bundles (qemu-user-aarch64, node, git, aapt2, and their combined DT_NEEDED
     * closure, plus the npm/npx native-lib scripts) lives in. Returns null (not just a
     * missing file) when the file doesn't
     * exist there, so every caller falls back to the existing downloaded-storage path -
     * only arm64-v8a is bundled so far, so this is always null on other ABIs.
     */
    fun nativeLibBinary(context: Context, bundledFilename: String): File? {
        val candidate = File(context.applicationInfo.nativeLibraryDir, bundledFilename)
        return candidate.takeIf { it.exists() }
    }

    /**
     * The complete set of versioned-soname aliases needed across every binary this app
     * native-lib-packages (qemu-user-aarch64, node, git, aapt2, and their shared
     * dependency closure - see AGENT_RUNTIME_RESEARCH.md Section 12). Bundled files are
     * renamed to strip each library's versioned soname suffix (Android's native-library
     * extraction only recognizes literal `lib*.so` filenames - a real `libz.so.1` would
     * not be extracted), but the binaries themselves still request the *original*
     * versioned soname at runtime (e.g. `qemu-aarch64` asks for `"libz.so.1"`, not
     * `"libz.so"`) - see [ensureNativeLibAliases] for how that gap is bridged, the same
     * way Acode's `libtalloc.so.2 -> libtalloc.so` symlink does.
     *
     * Derived by parsing the actual ELF DT_NEEDED entries of every bundled file (not
     * Termux's broader, looser apt `Depends:` metadata) - re-run that discovery (see
     * `tools/fetch_native_libs.py`) if any bundled binary changes enough to add, drop, or
     * re-version a dependency.
     */
    private val nativeLibSonameAliases: Map<String, String> = mapOf(
        "libbz2.so.1.0" to "libbz2.so",
        "libcrypto.so.3" to "libcrypto.so",
        "libdw.so.1" to "libdw.so",
        "libelf.so.1" to "libelf.so",
        "libexpat.so.1" to "libexpat.so",
        "libglib-2.0.so.0" to "libglib-2.0.so",
        "libgmodule-2.0.so.0" to "libgmodule-2.0.so",
        "libhogweed.so.6" to "libhogweed.so",
        "libicudata.so.78" to "libicudata.so",
        "libicui18n.so.78" to "libicui18n.so",
        "libicuuc.so.78" to "libicuuc.so",
        "liblzma.so.5" to "liblzma.so",
        "libnettle.so.8" to "libnettle.so",
        "libpython3.14.so" to "libpython3_14.so",
        "libssl.so.3" to "libssl.so",
        "libz.so.1" to "libz.so",
        "libzstd.so.1" to "libzstd.so"
    )

    /** Where the versioned-soname symlinks bridging [nativeLibSonameAliases] live. A
     * plain directory in app-private storage is fine for the symlinks themselves - W^X
     * only governs the bytes the linker actually maps executable, and a symlink's target
     * (resolved to the real file in [android.content.pm.ApplicationInfo.nativeLibraryDir])
     * is what gets mapped, not the writable-storage symlink inode itself. */
    fun nativeLibAliasDir(context: Context): File = File(rootDir(context), "native-lib-aliases")

    /** Idempotently points [link] at [target] via a symlink, only touching the
     * filesystem when the link doesn't already resolve there. Shared by
     * [ensureNativeLibAliases] and [ensureGitRemoteHelperLinks] - both bridge a
     * writable-storage path (a versioned soname, or a fixed `GIT_EXEC_PATH` filename)
     * to a file in the W^X-exempt native-lib directory. Safe under W^X: only the
     * *target's* bytes ever get mapped executable; the symlink inode living in
     * writable storage is never itself treated as executable content. */
    // configureEnvironment() runs on whatever thread spawns a process - the main thread
    // (Terminal PTY launch) and background exec() calls can race to replace the same
    // symlink concurrently. deleteIfExists()+createSymbolicLink() is not atomic, so two
    // racing callers can each delete the other's freshly-created link and then fail with
    // FileAlreadyExistsException (caught on-device: TerminalViewModel's init on the main
    // thread racing a background git-clone diagnostic). Serialize within this process.
    private val symlinkLock = Any()

    private fun ensureSymlink(link: File, target: File) {
        if (!target.exists()) return
        synchronized(symlinkLock) {
            val linkPath = link.toPath()
            if (java.nio.file.Files.isSymbolicLink(linkPath) &&
                java.nio.file.Files.readSymbolicLink(linkPath) == target.toPath()
            ) {
                return
            }
            java.nio.file.Files.deleteIfExists(linkPath)
            java.nio.file.Files.createSymbolicLink(linkPath, target.toPath())
        }
    }

    /** Idempotently (re)creates the versioned-soname symlinks the native-lib dependency
     * closure needs. No-op (per entry) if the target isn't bundled for this ABI. Safe to
     * call before every launch, mirroring `ProcessManager.refreshAxsSymlink()` in
     * Acode's own prototype for this same problem. */
    fun ensureNativeLibAliases(context: Context) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val aliasDir = nativeLibAliasDir(context).also { it.mkdirs() }
        for ((alias, canonical) in nativeLibSonameAliases) {
            ensureSymlink(File(aliasDir, alias), File(nativeDir, canonical))
        }
        // Plain-named aliases (node/git/aapt2/npm/npx, no lib*.so names exposed) so PATH-based lookup -
        // the interactive PTY Terminal's own shell resolves commands this way, a separate
        // code path from NodeExecutionEnvironment.exec()'s explicit binary resolution -
        // finds the W^X-exempt native-lib copy instead of falling through to whatever
        // same-named file the original download left in binDir.
        for ((alias, canonical) in pathAliasTargets) {
            ensureSymlink(File(aliasDir, alias), File(nativeDir, canonical))
        }
    }

    /** Plain command-name -> bundled native-lib filename, for [ensureNativeLibAliases]'s
     * PATH-alias symlinks. Only entries actually bundled for this ABI produce a working
     * symlink ([ensureSymlink] no-ops when the target is missing). */
    private val pathAliasTargets: Map<String, String>
        get() = mapOf(
            "node" to "libnode_native_${qemuArch()}.so",
            "git" to "libgit_native_${qemuArch()}.so",
            "aapt2" to "libaapt2_native_${qemuArch()}.so",
            "npm" to "libnpm_wrapper.so",
            "npx" to "libnpx_wrapper.so",
            "python" to "libpython_native_${qemuArch()}.so",
            "python3" to "libpython_native_${qemuArch()}.so",
            "python3.14" to "libpython_native_${qemuArch()}.so",
            "pip" to "libpip_wrapper.so",
            "pip3" to "libpip3_wrapper.so",
            "java" to "libjdk_java_wrapper.so",
            "javac" to "libjdk_javac_wrapper.so",
            "jar" to "libjdk_jar_wrapper.so",
            "keytool" to "libjdk_keytool_wrapper.so",
            "javap" to "libjdk_javap_wrapper.so",
            "jlink" to "libjdk_jlink_wrapper.so",
            "kotlinc" to "libkotlinc_wrapper.so",
            "kotlinc-jvm" to "libkotlinc_wrapper.so"
        )

    /** Replaces Python's downloaded launcher, libpython names, and every standard-library
     * extension module with symlinks to their PackageManager-installed copies. The mapping
     * is generated and pinned by tools/fetch_python_native_libs.py; keeping it as an asset
     * avoids duplicating roughly 120 version-specific paths in Kotlin. */
    fun ensurePythonNativeLinks(context: Context) {
        // The packaged native closure exists even before the optional Python runner is
        // downloaded. Do not project it into usr/lib while ArchiveExtractor is still
        // populating that same tree: extraction must never write through these symlinks.
        if (!groupReadyMarker(context, RunnerPackageGroup.PYTHON).isFile) return
        ensureMappedNativeLinks(context, "libpython_native_${qemuArch()}.so", "python-native-links.json")
    }

    fun removePythonNativeLinks(context: Context) {
        removeMappedNativeLinks(context, "python-native-links.json")
    }

    /** Replaces the downloaded JDK launchers and native libraries with links to the
     * PackageManager-installed closure. Its patched libjli reads the validated JAVA_HOME
     * supplied by [configureEnvironment], because /proc/self/exe points into the flat
     * native-library directory at a raised target SDK. */
    fun ensureJvmNativeLinks(context: Context) {
        if (!groupReadyMarker(context, RunnerPackageGroup.JVM).isFile) return
        ensureMappedNativeLinks(
            context,
            "libjdk_java_${qemuArch()}.so",
            "jvm-native-links.json",
            excludedPaths = setOf("lib/jvm/java-17-openjdk/lib/server/libjvm.so")
        )
    }

    fun removeJvmNativeLinks(context: Context) {
        removeMappedNativeLinks(context, "jvm-native-links.json")
    }

    /** Removes only symlinks named by a generated native-link manifest. This is called
     * before retrying an interrupted runner install so ArchiveExtractor sees ordinary
     * in-root paths, while regular downloaded files and unrelated user data are preserved. */
    private fun removeMappedNativeLinks(context: Context, assetName: String) {
        val usr = usrDir(context)
        if (!usr.isDirectory) return
        val mappings = runCatching {
            context.assets.open(assetName).bufferedReader().use { reader ->
                org.json.JSONArray(reader.readText())
            }
        }.getOrElse { return }
        for (index in 0 until mappings.length()) {
            val relativePath = java.nio.file.Paths.get(
                mappings.getJSONObject(index).getString("path")
            ).normalize()
            if (relativePath.isAbsolute || relativePath.startsWith("..")) continue
            val path = File(usr, relativePath.toString()).toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) {
                java.nio.file.Files.deleteIfExists(path)
            }
        }
    }

    private fun ensureMappedNativeLinks(
        context: Context,
        requiredNativeFile: String,
        assetName: String,
        excludedPaths: Set<String> = emptySet()
    ) {
        if (nativeLibBinary(context, requiredNativeFile) == null) return
        val usr = usrDir(context)
        if (!usr.isDirectory) return
        val mappings = runCatching {
            context.assets.open(assetName).bufferedReader().use { reader ->
                org.json.JSONArray(reader.readText())
            }
        }.getOrElse { return }
        for (index in 0 until mappings.length()) {
            val item = mappings.getJSONObject(index)
            val relative = item.getString("path")
            if (relative in excludedPaths) continue
            val targetName = item.getString("target")
            val relativePath = java.nio.file.Paths.get(relative).normalize()
            if (relativePath.isAbsolute || relativePath.startsWith("..")) continue
            val link = File(usr, relativePath.toString())
            val target = File(context.applicationInfo.nativeLibraryDir, targetName)
            if (!target.isFile) continue
            // Treat the generated asset as untrusted input at this filesystem boundary:
            // a malformed future mapping must never escape the runtime root.
            link.parentFile?.mkdirs()
            ensureSymlink(link, target)
        }
    }

    /**
     * `git clone`/`fetch`/`push` against an `https://` remote makes `git` (already
     * native-lib-packaged - see [gitBinary]) internally `execve()` a *separate* helper
     * binary, `git-remote-https`, resolved via `$GIT_EXEC_PATH` - a real, independent
     * exec this app's own `ProcessBuilder` never sees or controls. That nested exec hits
     * the exact same W^X restriction as every other binary in this document if the
     * resolved file lives in [libexecGitCoreDir] (app-private writable storage, where
     * the other ~180 git-core files - almost all argv[0]-dispatch hardlinks of `git`
     * itself, not separate execs - correctly still live; only the network remote
     * helpers are ever actually spawned as independent processes by this app's git
     * usage). `git-remote-http`/`-ftp`/`-ftps` are byte-identical to `-https` in every
     * Termux `git` build checked (confirmed: same SHA-256) - git dispatches protocol
     * handling internally by its own argv[0], so one bundled copy covers all four names.
     */
    private val gitRemoteHelperNames = listOf("git-remote-https", "git-remote-http", "git-remote-ftp", "git-remote-ftps")

    /** Idempotently replaces the writable-storage `git-remote-{https,http,ftp,ftps}`
     * entries in [libexecGitCoreDir] with symlinks to the native-lib-packaged copy (see
     * [gitRemoteHelperNames]'s doc). No-op if that copy isn't bundled for this ABI or
     * [libexecGitCoreDir] doesn't exist yet (git not installed). Safe to call before
     * every git invocation. */
    fun ensureGitRemoteHelperLinks(context: Context) {
        val gitCoreDir = libexecGitCoreDir(context)
        if (!gitCoreDir.isDirectory) return

        // On-device evidence (real `git clone https://...`, SELinux denial): git's own
        // internal remote-helper dispatch does not exec a genuinely separate
        // "git-remote-https" file - it execve()s $GIT_EXEC_PATH/git itself (a *second*,
        // distinct-inode writable-storage copy of the main git binary Termux's own layout
        // keeps under libexec/git-core/, separate from usr/bin/git) with argv[0] forced to
        // "git-remote-https". That copy is the one that must be W^X-exempt; symlink it to
        // the same native-lib git binary usr/bin/git already resolves to.
        val nativeGit = nativeLibBinary(context, "libgit_native_${qemuArch()}.so")
        if (nativeGit != null) {
            ensureSymlink(File(gitCoreDir, "git"), nativeGit)
        }

        // Kept as a belt-and-suspenders link in case another git build/version does
        // resolve a literal separate "git-remote-https" file - harmless no-op otherwise.
        val nativeHelper = nativeLibBinary(context, "libgit_remote_https_native_${qemuArch()}.so") ?: return
        for (name in gitRemoteHelperNames) {
            ensureSymlink(File(gitCoreDir, name), nativeHelper)
        }
    }

    /** `LD_LIBRARY_PATH` entries for any native-lib-packaged executable (qemu, node, git,
     * aapt2, npm, npx) to resolve its own dependency closure: the alias-symlink dir first (for
     * versioned sonames), then the native-lib dir itself (for dependencies whose bundled
     * filename already matches the requested soname exactly). Always returns a path -
     * `nativeLibraryDir` exists for every install of this app regardless of ABI (it at
     * minimum holds `terminal-emulator`'s own bundled `libtermux.so`), so there's no
     * "nothing bundled" case worth special-casing; on an ABI with none of this app's own
     * native-lib-packaged binaries, the extra search-path entries are simply never
     * matched by anything. */
    fun nativeLibLdPath(context: Context): String {
        ensureNativeLibAliases(context)
        return listOf(
            nativeLibAliasDir(context).absolutePath,
            context.applicationInfo.nativeLibraryDir
        ).joinToString(":")
    }

    @Deprecated("use ensureNativeLibAliases", ReplaceWith("ensureNativeLibAliases(context)"))
    fun ensureQemuNativeLibAliases(context: Context) = ensureNativeLibAliases(context)

    @Deprecated("use nativeLibLdPath", ReplaceWith("nativeLibLdPath(context)"))
    fun qemuNativeLibLdPath(context: Context): String = nativeLibLdPath(context)

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

        // Native-lib-packaged executables (qemu, node, git, aapt2, npm, npx - see
        // AGENT_RUNTIME_RESEARCH.md Sections 7-9, 12, and 14) need their own dependency closure resolvable from the
        // W^X-exempt native-lib dir; searched first so a native-lib binary never
        // accidentally picks up a same-named but differently-built copy from the
        // downloaded Termux tree. Harmless to prepend unconditionally even when nothing
        // native-lib-packaged is actually invoked - see nativeLibLdPath's own doc.
        environment["LD_LIBRARY_PATH"] = "${nativeLibLdPath(context)}:${libDir(context).absolutePath}"
        ensurePythonNativeLinks(context)
        ensureJvmNativeLinks(context)
        // nativeLibAliasDir first so its plain node/git/aapt2/npm/npx symlinks (see
        // ensureNativeLibAliases) shadow the same-named writable-storage copies still
        // present in binDir from the original download - matters for any PATH-based
        // lookup that doesn't go through NodeExecutionEnvironment.exec()'s own explicit
        // resolution, e.g. the interactive PTY Terminal's shell.
        environment["PATH"] = listOfNotNull(
            nativeLibAliasDir(context).absolutePath,
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
            environment["KOTLIN_HOME"] = File(usrDir(context), "opt/kotlin").absolutePath
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
        ensureGitRemoteHelperLinks(context)
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
        ensureGitRemoteHelperLinks(context)
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
        return android.os.Build.SUPPORTED_ABIS?.firstNotNullOfOrNull {
            when (it) {
                "arm64-v8a" -> "aarch64"
                "x86_64" -> "x86_64"
                else -> null
            }
        } ?: "x86_64"
    }
}
