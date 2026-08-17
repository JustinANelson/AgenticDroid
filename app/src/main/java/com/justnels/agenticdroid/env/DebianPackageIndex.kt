package com.justnels.agenticdroid.env

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Resolves a Debian binary package's current pool filename by downloading and scanning
 * the (large, ~13MB) apt Packages.gz index for stable/main - there's no small per-package
 * JSON endpoint for the exact binary .deb filename, and pool paths are versioned so they
 * can't be constructed without knowing the current version. Used only to fetch the small,
 * infrequently-changing glibc sysroot (see NodeBootstrapper.downloadGlibcSysroot), so
 * paying for the index download once per bootstrap is acceptable.
 */
object DebianPackageIndex {
    private const val MIRROR = "https://deb.debian.org/debian"

    fun debianArch(abi: String): String = when (abi) {
        "arm64-v8a" -> "arm64"
        "x86_64" -> "amd64"
        else -> throw IOException("Unsupported architecture for Debian packages: $abi")
    }

    /** Downloads and parses Packages.gz for [arch], returning package name -> pool Filename. */
    suspend fun fetchIndex(
        arch: String,
        packagesOfInterest: Set<String>,
        downloadTo: File
    ): Map<String, PackageArtifact> {
        val url = "$MIRROR/dists/stable/main/binary-$arch/Packages.gz"
        downloadFile(url, downloadTo)
        val result = mutableMapOf<String, PackageArtifact>()
        GzipCompressorInputStream(BufferedInputStream(downloadTo.inputStream())).use { gz ->
            var currentName: String? = null
            var currentFilename: String? = null
            var currentSha256: String? = null
            var currentSize: Long? = null
            fun commitEntry() {
                val name = currentName
                val filename = currentFilename
                val sha256 = currentSha256
                if (name != null && name in packagesOfInterest && filename != null && sha256 != null) {
                    result[name] = PackageArtifact(filename, sha256, currentSize)
                }
                currentName = null
                currentFilename = null
                currentSha256 = null
                currentSize = null
            }
            val reader = InputStreamReader(gz, Charsets.UTF_8).buffered()
            var decodedCharacters = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                decodedCharacters += line.length
                if (decodedCharacters > 256L * 1024L * 1024L) throw IOException("Debian package index is too large")
                when {
                    line.startsWith("Package: ") -> {
                        currentName = line.removePrefix("Package: ").trim()
                        currentFilename = null
                        currentSha256 = null
                    }
                    line.startsWith("Filename: ") -> currentFilename = line.removePrefix("Filename: ").trim()
                    line.startsWith("SHA256: ") -> currentSha256 = line.removePrefix("SHA256: ").trim()
                    line.startsWith("Size: ") -> currentSize = line.removePrefix("Size: ").trim().toLongOrNull()
                    line.isEmpty() -> commitEntry()
                }
            }
            commitEntry()
        }
        return result
    }

    fun downloadUrlFor(filename: String): String = "$MIRROR/$filename"

    private suspend fun downloadFile(url: String, destination: File) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("HTTP $responseCode downloading $url")
            if (connection.contentLengthLong > 64L * 1024L * 1024L) throw IOException("Debian package index is too large")
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 64L * 1024L * 1024L) throw IOException("Debian package index is too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
