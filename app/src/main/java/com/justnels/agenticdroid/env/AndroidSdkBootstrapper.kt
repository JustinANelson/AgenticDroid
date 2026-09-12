package com.justnels.agenticdroid.env

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Provisions just enough of a real Android SDK for on-device Android app builds (e.g.
 * self-updating this app): a platform's android.jar and the SDK license-acceptance
 * marker AGP checks for before touching any SDK component. aapt2 itself is *not*
 * handled here - it's installed as an ordinary Bionic-native Termux package by
 * NodeBootstrapper (see its "aapt2" entry), since AGP's own Maven-resolved aapt2 is a
 * glibc binary AGP execs directly with no wrapper hook we control.
 *
 * Provisioned reactively, one platform at a time, driven by parsing the exact SDK
 * component hash (e.g. "android-37") out of AGP's own failure text rather than trying to
 * pre-parse every target project's compileSdk upfront - see
 * [parsePlatformHashFromBuildError] and MainViewModel.buildAndInstall's
 * retry-after-provisioning step.
 */
object AndroidSdkBootstrapper {
    private const val TAG = "AndroidSdkBootstrapper"
    private const val REPO_INDEX_URL = "https://dl.google.com/android/repository/repository2-3.xml"
    private const val DOWNLOAD_BASE_URL = "https://dl.google.com/android/repository/"
    private const val MAX_ARTIFACT_BYTES = 512L * 1024L * 1024L

    // The two historical SHA-1 hashes of the Android SDK license text that Google's own
    // sdkmanager accepts (the license text was revised once; both are still honored).
    // Writing both sidesteps having to fetch and hash the exact current license text
    // ourselves just to reproduce a value this well-known.
    private val acceptedLicenseHashes = listOf(
        "24333f8a63b6825ea9c5514f83c2829b004d1fee",
        "d56f5187479451eabf01fb78af6dfcb131a6481e"
    )

    fun sdkRootDir(context: Context): File = File(context.filesDir, "android-sdk")
    private fun platformsDir(context: Context): File = File(sdkRootDir(context), "platforms")
    private fun platformDir(context: Context, platformHash: String): File =
        File(platformsDir(context), platformHash)
    private fun androidJar(context: Context, platformHash: String): File =
        File(platformDir(context, platformHash), "android.jar")

    fun isPlatformInstalled(context: Context, platformHash: String): Boolean =
        androidJar(context, platformHash).isFile

    /** Scans AGP's own failure output for the SDK component hash it's missing. */
    fun parsePlatformHashFromBuildError(text: String): String? =
        Regex("""platforms[/;](android-[\w.]+)""").find(text)?.groupValues?.get(1)

    /**
     * AGP refuses to touch any SDK component - even one already fully present on disk -
     * without this marker. Written unconditionally and cheaply on every call rather than
     * only-if-missing, matching the same reasoning as NodeRuntime's gradle.properties
     * rewrite: this is solely ours to manage.
     */
    fun ensureLicenseAccepted(context: Context) {
        val licensesDir = File(sdkRootDir(context), "licenses")
        licensesDir.mkdirs()
        File(licensesDir, "android-sdk-license")
            .writeText(acceptedLicenseHashes.joinToString("\n") + "\n")
    }

    /**
     * Installs the requested SDK platform component (e.g. "android-37") by exact
     * "platforms;<platformHash>" match against Google's repository index, falling back to
     * the highest-revision "platforms;android-<major>.<minor>" stable-channel package for
     * the same major API level if no exact match exists - recent Android versions ship
     * fractional API levels (e.g. compileSdk 37 resolves to a package literally named
     * "platforms;android-37.1", not "platforms;android-37"), but the fetched contents
     * still land at platforms/<platformHash>/ on disk regardless of which package
     * actually supplied them, matching whatever directory name AGP's error asked for.
     */
    suspend fun installPlatform(
        context: Context,
        platformHash: String,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (isPlatformInstalled(context, platformHash)) return@withContext
        ensureLicenseAccepted(context)

        onProgress("Resolving Android SDK platform $platformHash...")
        val indexXml = fetchRepositoryIndex()
        val archive = findPlatformArchive(indexXml, platformHash)
            ?: throw IOException("No SDK platform package found matching $platformHash")

        val cacheDir = File(context.cacheDir, "android-sdk-dl").also { it.mkdirs() }
        try {
            val zipFile = File(cacheDir, archive.filename)
            val sizeMb = archive.sizeBytes / 1_000_000
            onProgress("Downloading Android SDK platform ($platformHash, ${sizeMb}MB)...")
            downloadFile(DOWNLOAD_BASE_URL + archive.filename, zipFile, archive.sha1, archive.sizeBytes)

            onProgress("Extracting Android SDK platform...")
            val destDir = platformDir(context, platformHash)
            destDir.deleteRecursively()
            // These zips nest everything under a top-level folder named after the
            // package that actually supplied them (e.g. "android-37.0/android.jar"),
            // which can differ from platformHash when a fractional-version fallback
            // package served a whole-number request - see findPlatformArchive.
            ArchiveExtractor.extractZip(zipFile, destDir, "${archive.internalDirName}/")
            zipFile.delete()

            if (!isPlatformInstalled(context, platformHash)) {
                throw IOException("Extracted $platformHash but android.jar is still missing")
            }
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private data class PlatformArchive(
        val filename: String,
        val sha1: String,
        val sizeBytes: Long,
        // The package's own name (e.g. "android-37.0") - the top-level folder its zip
        // nests everything under, which can differ from the requested platformHash.
        val internalDirName: String
    )

    private fun findPlatformArchive(indexXml: String, platformHash: String): PlatformArchive? {
        findPackageBlock(indexXml, "platforms;$platformHash")?.let { return parseArchive(it, platformHash) }

        val major = platformHash.removePrefix("android-").substringBefore('.').toIntOrNull()
            ?: return null
        val candidates = Regex("""path="platforms;android-$major\.(\d+)"""")
            .findAll(indexXml)
            .map { m ->
                m.value.substringAfter("path=\"").removeSuffix("\"") to m.groupValues[1].toInt()
            }
            .sortedByDescending { it.second }
        for ((pkgPath, _) in candidates) {
            val block = findPackageBlock(indexXml, pkgPath) ?: continue
            // Restricted to the stable channel so a canary/beta build is never silently
            // substituted for what a project actually asked to compile against.
            if (!block.contains("<channelRef ref=\"channel-0\"/>")) continue
            return parseArchive(block, pkgPath.removePrefix("platforms;"))
        }
        return null
    }

    private fun findPackageBlock(indexXml: String, packagePath: String): String? {
        val marker = "path=\"$packagePath\""
        val start = indexXml.indexOf(marker)
        if (start < 0) return null
        val blockStart = indexXml.lastIndexOf("<remotePackage", start)
        val blockEnd = indexXml.indexOf("</remotePackage>", start)
        if (blockStart < 0 || blockEnd < 0) return null
        return indexXml.substring(blockStart, blockEnd)
    }

    private fun parseArchive(block: String, internalDirName: String): PlatformArchive? {
        val filename = Regex("<url>([^<]+)</url>").find(block)?.groupValues?.get(1) ?: return null
        val sha1 = Regex("""<checksum type="sha1">([^<]+)</checksum>""")
            .find(block)?.groupValues?.get(1) ?: return null
        val size = Regex("<size>(\\d+)</size>").find(block)?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        return PlatformArchive(filename, sha1, size, internalDirName)
    }

    private suspend fun fetchRepositoryIndex(): String = withContext(Dispatchers.IO) {
        val connection = URL(REPO_INDEX_URL).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android AgenticDroid)")
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code fetching $REPO_INDEX_URL")
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        expectedSha1: String,
        expectedSize: Long
    ) {
        if (expectedSize !in 1..MAX_ARTIFACT_BYTES) {
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
                    connection.inputStream.use { input ->
                        partialFile.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_ARTIFACT_BYTES) throw IOException("Download too large: ${destination.name}")
                                output.write(buffer, 0, read)
                            }
                            if (total != expectedSize) {
                                throw IOException("Unexpected package size for ${destination.name}")
                            }
                        }
                    }
                    val digest = MessageDigest.getInstance("SHA-1")
                    partialFile.inputStream().buffered().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha1, ignoreCase = true)) {
                        throw IOException("SHA-1 mismatch for ${destination.name}")
                    }
                    if (destination.exists()) destination.delete()
                    if (!partialFile.renameTo(destination)) {
                        throw IOException("Could not finalize download at ${destination.absolutePath}")
                    }
                    return
                } finally {
                    connection.disconnect()
                    partialFile.delete()
                }
            } catch (e: Exception) {
                lastException = e
                android.util.Log.w(TAG, "Download attempt ${attempt + 1} failed for $url: ${e.message}")
                if (e is kotlinx.coroutines.CancellationException) throw e
                delay(2000)
            }
        }
        throw lastException ?: IOException("Failed to download $url after 3 attempts")
    }
}
