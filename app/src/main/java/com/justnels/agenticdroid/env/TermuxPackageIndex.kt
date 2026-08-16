package com.justnels.agenticdroid.env

import java.util.zip.GZIPInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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

    /** package name -> Filename (relative to BASE_URL) */
    fun fetchIndex(arch: String): Map<String, String> {
        return try {
            activeBaseUrl = BASE_URL
            fetchFromUrl(activeBaseUrl, arch)
        } catch (e: Exception) {
            android.util.Log.w("TermuxPackageIndex", "Failed to fetch index from primary mirror, trying fallback: ${e.message}")
            activeBaseUrl = FALLBACK_URL
            fetchFromUrl(activeBaseUrl, arch)
        }
    }

    private fun fetchFromUrl(baseUrl: String, arch: String): Map<String, String> {
        // Use Packages.gz for efficiency and to avoid incomplete plain-text downloads
        val url = "$baseUrl/dists/stable/main/binary-$arch/Packages.gz"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android AgenticDroid)")
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("HTTP $code fetching $url")
        
        val result = mutableMapOf<String, String>()
        GZIPInputStream(connection.inputStream).bufferedReader().use { reader ->
            var currentName: String? = null
            reader.forEachLine { line ->
                when {
                    line.startsWith("Package: ") -> {
                        currentName = line.removePrefix("Package: ").trim()
                    }
                    line.startsWith("Filename: ") -> {
                        val currentFile = line.removePrefix("Filename: ").trim()
                        val name = currentName
                        if (name != null && !result.containsKey(name)) {
                            result[name] = currentFile
                        }
                    }
                }
            }
        }
        return result
    }

    fun downloadUrlFor(filename: String): String {
        return "$activeBaseUrl/$filename"
    }
}
