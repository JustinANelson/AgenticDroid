package com.justnels.agenticdroid.env

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts Termux .deb packages (ar archive containing data.tar.xz) and Alpine .apk
 * packages (gzip'd tar) into a shared output tree, preserving relative directory
 * structure below [pathPrefix] exactly as the package intends it - this lets multiple
 * packages merge into one directory (e.g. every package's shared libraries under
 * usr/lib land together) and lets a symlink in one package resolve against a file a previously-extracted
 * package already placed on disk.
 */
object ArchiveExtractor {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L
    private const val TAR_RECORD_SIZE = 512

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private data class ExtractionBudget(var entries: Int = 0, var bytes: Long = 0)

    private fun resolveInside(root: File, relativePath: String): File {
        if (relativePath.isBlank() || File(relativePath).isAbsolute) {
            throw IOException("Unsafe archive path: $relativePath")
        }
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        if (candidate != canonicalRoot && !candidate.path.startsWith(rootPrefix)) {
            throw IOException("Archive path escapes extraction root: $relativePath")
        }
        return candidate
    }

    private fun InputStream.copyEntryTo(outFile: File, declaredSize: Long): Long {
        if (declaredSize != -1L && (declaredSize < 0 || declaredSize > MAX_ENTRY_BYTES)) {
            throw IOException("Archive entry has invalid or excessive size: $declaredSize")
        }
        var copied = 0L
        outFile.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                copied += read
                if (copied > MAX_ENTRY_BYTES) throw IOException("Archive entry exceeds size limit")
                output.write(buffer, 0, read)
            }
        }
        return copied
    }

    /** Returns the relative paths (below [outDir]) of every file this package wrote - used to
     *  later remove exactly this package's files when uninstalling a [RunnerPackageGroup]. */
    fun extractDeb(debFile: File, outDir: File, pathPrefix: String): List<String> {
        outDir.mkdirs()
        ArArchiveInputStream(BufferedInputStream(debFile.inputStream())).use { ar ->
            var entry = ar.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("data.tar")) {
                    val entryStream = NonClosingInputStream(ar)
                    val decompressed = when {
                        entry.name.endsWith(".xz") -> XZCompressorInputStream(entryStream)
                        entry.name.endsWith(".gz") -> GzipCompressorInputStream(entryStream)
                        entry.name.endsWith(".zst") || entry.name.endsWith(".zstd") ->
                            ZstdCompressorInputStream(entryStream)
                        entry.name == "data.tar" -> entryStream
                        else -> throw IOException("Unsupported Debian data archive: ${entry.name}")
                    }
                    return extractTar(decompressed, outDir, pathPrefix, ExtractionBudget())
                }
                entry = ar.nextEntry
            }
        }
        throw IllegalStateException("No supported data.tar archive found in ${debFile.name}")
    }

    fun extractApk(apkFile: File, outDir: File, pathPrefix: String) {
        outDir.mkdirs()
        GzipCompressorInputStream.builder()
            .setInputStream(BufferedInputStream(apkFile.inputStream()))
            .setDecompressConcatenated(true)
            .get().use { gzip ->
            val input = PushbackInputStream(gzip, TAR_RECORD_SIZE)
            val budget = ExtractionBudget()
            val record = ByteArray(TAR_RECORD_SIZE)
            while (true) {
                var read = 0
                while (read < record.size) {
                    val count = input.read(record, read, record.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read == 0) break
                if (read != record.size) throw IOException("Truncated Alpine package tar header")
                if (record.all { it == 0.toByte() }) continue
                input.unread(record)
                extractTar(NonClosingInputStream(input), outDir, pathPrefix, budget)
            }
        }
    }

    fun extractZip(zipFile: File, outDir: File, pathPrefix: String) {
        outDir.mkdirs()
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val idx = entry.name.indexOf(pathPrefix)
                if (!entry.isDirectory && idx >= 0) {
                    val relPath = entry.name.substring(idx + pathPrefix.length).trimStart('/')
                    if (relPath.isNotEmpty()) {
                        val outFile = resolveInside(outDir, relPath)
                        outFile.parentFile?.mkdirs()
                        zip.copyEntryTo(outFile, entry.size)

                        val isBinaryDir = relPath.startsWith("bin/") ||
                                         relPath.contains("/bin/") ||
                                         relPath.contains("/libexec/")
                        if (isBinaryDir) {
                            outFile.setExecutable(true)
                        }
                    }
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun extractTar(
        rawStream: InputStream,
        outDir: File,
        pathPrefix: String,
        budget: ExtractionBudget
    ): List<String> {
        data class PendingLink(val relPath: String, val linkName: String)
        val pending = mutableListOf<PendingLink>()
        val written = mutableListOf<String>()
        TarArchiveInputStream(rawStream, TAR_RECORD_SIZE).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                budget.entries++
                if (budget.entries > MAX_ENTRIES) throw IOException("Archive contains too many entries")
                val idx = entry.name.indexOf(pathPrefix)
                if (!entry.isDirectory && idx >= 0) {
                    val relPath = entry.name.substring(idx + pathPrefix.length).trimStart('/')
                    if (relPath.isNotEmpty()) {
                        val outFile = resolveInside(outDir, relPath)
                        if (entry.isSymbolicLink) {
                            if (!File(entry.linkName).isAbsolute) {
                                pending.add(PendingLink(relPath, entry.linkName))
                            } // absolute symlinks outside this tree aren't resolvable; skip
                        } else if (entry.isFile) {
                            outFile.parentFile?.mkdirs()
                            budget.bytes += tar.copyEntryTo(outFile, entry.size)
                            if (budget.bytes > MAX_TOTAL_BYTES) {
                                outFile.delete()
                                throw IOException("Archive exceeds total extraction size limit")
                            }
                            written.add(relPath)

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
                entry = tar.nextEntry
            }
        }
        var remaining = pending
        repeat(remaining.size + 1) {
            if (remaining.isEmpty()) return written
            val stillPending = mutableListOf<PendingLink>()
            for (link in remaining) {
                val outFile = resolveInside(outDir, link.relPath)
                val targetRelative = File(link.relPath).parentFile?.let { File(it, link.linkName).path }
                    ?: link.linkName
                val targetFile = resolveInside(outDir, targetRelative)
                if (targetFile.isFile) {
                    outFile.parentFile?.mkdirs()
                    targetFile.copyTo(outFile, overwrite = true)
                    written.add(link.relPath)
                } else {
                    stillPending.add(link)
                }
            }
            remaining = stillPending
        }
        return written
    }

}
