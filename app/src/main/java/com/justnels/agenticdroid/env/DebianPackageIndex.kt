package com.justnels.agenticdroid.env

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

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
    fun fetchIndex(arch: String, packagesOfInterest: Set<String>, downloadTo: File): Map<String, String> {
        val url = "$MIRROR/dists/stable/main/binary-$arch/Packages.gz"
        downloadFile(url, downloadTo)
        val result = mutableMapOf<String, String>()
        GzipCompressorInputStream(BufferedInputStream(downloadTo.inputStream())).use { gz ->
            var currentName: String? = null
            var currentFilename: String? = null
            InputStreamReader(gz, Charsets.UTF_8).buffered().forEachLine { line ->
                when {
                    line.startsWith("Package: ") -> {
                        currentName = line.removePrefix("Package: ").trim()
                        currentFilename = null
                    }
                    line.startsWith("Filename: ") -> currentFilename = line.removePrefix("Filename: ").trim()
                    line.isEmpty() -> {
                        val name = currentName
                        val filename = currentFilename
                        if (name != null && name in packagesOfInterest && filename != null) {
                            result[name] = filename
                        }
                        currentName = null
                        currentFilename = null
                    }
                }
            }
        }
        return result
    }

    fun downloadUrlFor(filename: String): String = "$MIRROR/$filename"

    private fun downloadFile(url: String, destination: File) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("HTTP $responseCode downloading $url")
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }
}
