package com.justnels.agenticdroid.env

import java.util.zip.GZIPInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Resolves current download filenames from Termux's live apt package index instead of
 * hardcoding versions, which drift as Termux ships updates.
 */
object TermuxPackageIndex {
    // Cloudflare-backed mirror is generally the most reliable and fast
    private const val BASE_URL = "https://packages-cf.termux.dev/apt/termux-main"
    private const val FALLBACK_URL = "https://grimler.se/termux-main"

    private var activeBaseUrl = BASE_URL

    /** Termux's architecture name for a given Android ABI. */
    fun termuxArch(abi: String): String = when (abi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> throw IOException("Unsupported architecture for Termux packages: $abi")
    }

    /** package name -> authenticated artifact metadata from Packages.gz. */
    suspend fun fetchIndex(arch: String): Map<String, PackageArtifact> {
        return try {
            activeBaseUrl = BASE_URL
            fetchFromUrl(activeBaseUrl, arch)
        } catch (e: Exception) {
            android.util.Log.w("TermuxPackageIndex", "Failed to fetch index from primary mirror, trying fallback: ${e.message}")
            activeBaseUrl = FALLBACK_URL
            fetchFromUrl(activeBaseUrl, arch)
        }
    }

    private suspend fun fetchFromUrl(baseUrl: String, arch: String): Map<String, PackageArtifact> {
        // Use Packages.gz for efficiency and to avoid incomplete plain-text downloads
        val url = "$baseUrl/dists/stable/main/binary-$arch/Packages.gz"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android AgenticDroid)")
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code fetching $url")

            val result = mutableMapOf<String, PackageArtifact>()
            GZIPInputStream(connection.inputStream).bufferedReader().use { reader ->
            var currentName: String? = null
            var currentFile: String? = null
            var currentSha256: String? = null
            var currentSize: Long? = null
            var decodedCharacters = 0L
            fun commitEntry() {
                val name = currentName
                val filename = currentFile
                val sha256 = currentSha256
                if (name != null && filename != null && sha256 != null && !result.containsKey(name)) {
                    result[name] = PackageArtifact(filename, sha256, currentSize)
                }
                currentName = null
                currentFile = null
                currentSha256 = null
                currentSize = null
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                decodedCharacters += line.length
                if (decodedCharacters > 128L * 1024L * 1024L) throw IOException("Termux package index is too large")
                when {
                    line.startsWith("Package: ") -> currentName = line.removePrefix("Package: ").trim()
                    line.startsWith("Filename: ") -> currentFile = line.removePrefix("Filename: ").trim()
                    line.startsWith("SHA256: ") -> currentSha256 = line.removePrefix("SHA256: ").trim()
                    line.startsWith("Size: ") -> currentSize = line.removePrefix("Size: ").trim().toLongOrNull()
                    line.isEmpty() -> commitEntry()
                }
            }
            commitEntry()
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    fun downloadUrlFor(filename: String): String {
        return "$activeBaseUrl/$filename"
    }
}
