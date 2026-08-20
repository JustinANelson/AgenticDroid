package com.justnels.agenticdroid.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class PackageIndexTest {

    @Test
    fun debianArchMapsSupportedAbis() {
        assertEquals("arm64", DebianPackageIndex.debianArch("arm64-v8a"))
        assertEquals("amd64", DebianPackageIndex.debianArch("x86_64"))
    }

    @Test(expected = IOException::class)
    fun debianArchRejectsUnsupportedAbi() {
        DebianPackageIndex.debianArch("armeabi-v7a")
    }

    @Test(expected = IOException::class)
    fun debianArchRejectsUnknownAbi() {
        DebianPackageIndex.debianArch("mips")
    }

    @Test
    fun debianDownloadUrlConstructsExpectedMirrorPath() {
        val path = "pool/main/g/glibc/libc6_2.36-9+deb12u7_arm64.deb"
        val url = DebianPackageIndex.downloadUrlFor(path)
        assertEquals("https://deb.debian.org/debian/$path", url)
    }

    @Test
    fun termuxArchMapsSupportedAbis() {
        assertEquals("aarch64", TermuxPackageIndex.termuxArch("arm64-v8a"))
        assertEquals("arm", TermuxPackageIndex.termuxArch("armeabi-v7a"))
        assertEquals("arm", TermuxPackageIndex.termuxArch("armeabi"))
        assertEquals("x86_64", TermuxPackageIndex.termuxArch("x86_64"))
        assertEquals("i686", TermuxPackageIndex.termuxArch("x86"))
    }

    @Test(expected = IOException::class)
    fun termuxArchRejectsUnsupportedAbi() {
        TermuxPackageIndex.termuxArch("riscv64")
    }

    @Test
    fun termuxDownloadUrlConstructsExpectedPath() {
        val path = "dists/stable/main/binary-aarch64/Packages.gz"
        val url = TermuxPackageIndex.downloadUrlFor(path)
        assertEquals("https://packages-cf.termux.dev/apt/termux-main/$path", url)
    }

    @Test
    fun packageArtifactDataClassProperties() {
        val artifact = PackageArtifact(
            filename = "pool/main/n/nodejs/nodejs_20.0.0_aarch64.deb",
            sha256 = "abcdef0123456789",
            size = 12345678L
        )
        assertEquals("pool/main/n/nodejs/nodejs_20.0.0_aarch64.deb", artifact.filename)
        assertEquals("abcdef0123456789", artifact.sha256)
        assertEquals(12345678L, artifact.size)

        val defaultArtifact = PackageArtifact(filename = "pool/main/g/git.deb")
        assertEquals("pool/main/g/git.deb", defaultArtifact.filename)
        assertNull(defaultArtifact.sha256)
        assertNull(defaultArtifact.size)
    }
}
