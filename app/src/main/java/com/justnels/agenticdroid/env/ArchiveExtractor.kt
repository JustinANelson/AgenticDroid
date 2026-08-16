package com.justnels.agenticdroid.env

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Extracts Termux .deb packages (ar archive containing data.tar.xz) and Alpine .apk
 * packages (gzip'd tar) into a shared output tree, preserving relative directory
 * structure below [pathPrefix] exactly as the package intends it - this lets multiple
 * packages merge into one directory (e.g. every package's shared libraries under
 * usr/lib land together) and lets a symlink in one package resolve against a file a previously-extracted
 * package already placed on disk.
 */
object ArchiveExtractor {

    fun extractDeb(debFile: File, outDir: File, pathPrefix: String) {
        outDir.mkdirs()
        ArArchiveInputStream(BufferedInputStream(debFile.inputStream())).use { ar ->
            var entry = ar.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("data.tar")) {
                    val bytes = ByteArrayOutputStream().also { ar.copyTo(it) }.toByteArray()
                    extractTar(XZCompressorInputStream(ByteArrayInputStream(bytes)), outDir, pathPrefix)
                    return
                }
                entry = ar.nextEntry
            }
        }
        throw IllegalStateException("No data.tar.xz found in ${debFile.name}")
    }

    fun extractApk(apkFile: File, outDir: File, pathPrefix: String) {
        outDir.mkdirs()
        extractTar(GzipCompressorInputStream(BufferedInputStream(apkFile.inputStream()), true), outDir, pathPrefix)
    }

    private fun extractTar(rawStream: InputStream, outDir: File, pathPrefix: String) {
        data class PendingLink(val relPath: String, val linkName: String)
        val pending = mutableListOf<PendingLink>()
        TarArchiveInputStream(rawStream).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val idx = entry.name.indexOf(pathPrefix)
                if (!entry.isDirectory && idx >= 0) {
                    val relPath = entry.name.substring(idx + pathPrefix.length).trimStart('/')
                    if (relPath.isNotEmpty()) {
                        val outFile = File(outDir, relPath)
                        if (entry.isSymbolicLink) {
                            if (!entry.linkName.startsWith("/")) {
                                pending.add(PendingLink(relPath, entry.linkName))
                            } // absolute symlinks outside this tree aren't resolvable; skip
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> tar.copyTo(out) }
                            
                            // On Android, we should be aggressive about setting execute permissions
                            // for anything in bin/ or libexec/ or if the tar entry has it.
                            val isBinaryDir = relPath.startsWith("bin/") || 
                                             relPath.contains("/bin/") || 
                                             relPath.contains("/libexec/")
                            if (isBinaryDir || (entry.mode and 0b001000000) != 0) {
                                outFile.setExecutable(true)
                            }
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        var remaining = pending
        repeat(remaining.size + 1) {
            if (remaining.isEmpty()) return
            val stillPending = mutableListOf<PendingLink>()
            for (link in remaining) {
                val outFile = File(outDir, link.relPath)
                val targetFile = File(outFile.parentFile, link.linkName).canonicalFile
                if (targetFile.isFile) {
                    outFile.parentFile?.mkdirs()
                    targetFile.copyTo(outFile, overwrite = true)
                } else {
                    stillPending.add(link)
                }
            }
            remaining = stillPending
        }
    }

    /** Parses Alpine's APKINDEX.tar.gz for a package's `P:name` / `V:version` stanza. */
    fun readApkIndexVersion(indexTarGz: File, packageName: String): String? {
        var version: String? = null
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(indexTarGz.inputStream()), true)).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                if (entry.name == "APKINDEX") {
                    val text = ByteArrayOutputStream().also { tar.copyTo(it) }.toByteArray()
                        .toString(Charsets.UTF_8)
                    var currentName: String? = null
                    for (line in text.lineSequence()) {
                        when {
                            line.startsWith("P:") -> currentName = line.removePrefix("P:").trim()
                            line.startsWith("V:") && currentName == packageName -> {
                                version = line.removePrefix("V:").trim()
                                return@use
                            }
                            line.isEmpty() -> currentName = null
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        return version
    }
}
