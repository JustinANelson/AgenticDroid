package com.justnels.agenticdroid.env

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ArchiveExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun createApk(name: String, entryName: String, content: String): File {
        val archive = temporaryFolder.newFile(name)
        GzipCompressorOutputStream(archive.outputStream()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                val bytes = content.toByteArray()
                val entry = TarArchiveEntry(entryName).apply { size = bytes.size.toLong() }
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return archive
    }

    private fun rawTar(entryName: String, content: String): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            val bytes = content.toByteArray()
            val entry = TarArchiveEntry(entryName).apply { size = bytes.size.toLong() }
            tar.putArchiveEntry(entry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
        return output.toByteArray()
    }

    private fun gzipTar(entryName: String, content: String): ByteArray {
        val output = ByteArrayOutputStream()
        GzipCompressorOutputStream(output).use { it.write(rawTar(entryName, content)) }
        return output.toByteArray()
    }

    private fun createDeb(name: String, dataEntryName: String, data: ByteArray): File {
        val archive = temporaryFolder.newFile(name)
        val marker = "2.0\n".toByteArray()
        ArArchiveOutputStream(archive.outputStream()).use { ar ->
            ar.putArchiveEntry(ArArchiveEntry("debian-binary", marker.size.toLong()))
            ar.write(marker)
            ar.closeArchiveEntry()
            ar.putArchiveEntry(ArArchiveEntry(dataEntryName, data.size.toLong()))
            ar.write(data)
            ar.closeArchiveEntry()
        }
        return archive
    }

    @Test
    fun extractsRegularFileInsideRoot() {
        val output = temporaryFolder.newFolder("output")
        val archive = createApk("safe.apk", "usr/bin/tool", "safe")

        ArchiveExtractor.extractApk(archive, output, "")

        assertEquals("safe", File(output, "usr/bin/tool").readText())
    }

    @Test(expected = IOException::class)
    fun rejectsTraversalOutsideRoot() {
        val output = temporaryFolder.newFolder("contained")
        val escaped = File(output.parentFile, "escaped.txt")
        val archive = createApk("traversal.apk", "../escaped.txt", "unsafe")

        try {
            ArchiveExtractor.extractApk(archive, output, "")
        } finally {
            assertFalse(escaped.exists())
        }
    }

    @Test
    fun extractsDataFromConcatenatedAlpineSections() {
        val output = temporaryFolder.newFolder("multi-output")
        val archive = temporaryFolder.newFile("realistic.apk")
        archive.outputStream().use { stream ->
            stream.write(gzipTar(".SIGN.RSA.test", "signature"))
            stream.write(gzipTar(".PKGINFO", "pkgname = musl"))
            stream.write(gzipTar("lib/ld-musl-aarch64.so.1", "loader"))
        }

        ArchiveExtractor.extractApk(archive, output, "")

        assertEquals("loader", File(output, "lib/ld-musl-aarch64.so.1").readText())
    }

    @Test
    fun extractsGzipCompressedDebianDataArchive() {
        val output = temporaryFolder.newFolder("deb-output")
        val data = gzipTar("usr/lib/libexample.so", "library")
        val archive = createDeb("package.deb", "data.tar.gz", data)

        ArchiveExtractor.extractDeb(archive, output, "usr/")

        assertEquals("library", File(output, "lib/libexample.so").readText())
    }

    @Test
    fun extractDebReturnsManifestOfWrittenFiles() {
        // NodeBootstrapper's per-group uninstall relies on this list being exactly the
        // set of files a package's install wrote, so it can be removed later without
        // touching files any other still-installed group's packages wrote.
        val output = temporaryFolder.newFolder("deb-manifest-output")
        val rawOutput = ByteArrayOutputStream()
        TarArchiveOutputStream(rawOutput).use { tar ->
            for ((entryName, content) in listOf("usr/bin/tool" to "a", "usr/lib/libtool.so" to "b")) {
                val bytes = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(entryName).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        val gzipped = ByteArrayOutputStream().also { GzipCompressorOutputStream(it).use { g -> g.write(rawOutput.toByteArray()) } }
        val archive = createDeb("multi.deb", "data.tar.gz", gzipped.toByteArray())

        val written = ArchiveExtractor.extractDeb(archive, output, "usr/")

        assertEquals(setOf("bin/tool", "lib/libtool.so"), written.toSet())
        assertEquals("a", File(output, "bin/tool").readText())
        assertEquals("b", File(output, "lib/libtool.so").readText())
    }

    @Test
    fun extractsXzCompressedDebianDataArchive() {
        val compressed = ByteArrayOutputStream().also { output ->
            XZCompressorOutputStream(output).use { it.write(rawTar("usr/bin/tool", "xz")) }
        }.toByteArray()
        val archive = createDeb("package-xz.deb", "data.tar.xz", compressed)
        val output = temporaryFolder.newFolder("deb-xz-output")

        ArchiveExtractor.extractDeb(archive, output, "usr/")

        assertEquals("xz", File(output, "bin/tool").readText())
    }

    @Test
    fun extractsZstdCompressedDebianDataArchive() {
        val compressed = ByteArrayOutputStream().also { output ->
            ZstdCompressorOutputStream(output).use { it.write(rawTar("usr/bin/tool", "zstd")) }
        }.toByteArray()
        val archive = createDeb("package-zstd.deb", "data.tar.zst", compressed)
        val output = temporaryFolder.newFolder("deb-zstd-output")

        ArchiveExtractor.extractDeb(archive, output, "usr/")

        assertEquals("zstd", File(output, "bin/tool").readText())
    }
}
