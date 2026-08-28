package com.justnels.agenticdroid.env

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeRuntimeTest {

    @Test
    fun mergePropertyAppendsWhenKeyIsAbsent() {
        val result = NodeRuntime.mergeProperty(
            existingLines = listOf("org.gradle.jvmargs=-Xmx640m"),
            key = "android.aapt2FromMavenOverride",
            value = "/data/user/0/com.justnels.agenticdroid/files/node-runtime/usr/bin/aapt2"
        )
        assertEquals(
            listOf(
                "org.gradle.jvmargs=-Xmx640m",
                "android.aapt2FromMavenOverride=/data/user/0/com.justnels.agenticdroid/files/node-runtime/usr/bin/aapt2"
            ),
            result
        )
    }

    @Test
    fun mergePropertyReplacesAStalePathFromAPreviousInstall() {
        val result = NodeRuntime.mergeProperty(
            existingLines = listOf(
                "org.gradle.jvmargs=-Xmx640m",
                "android.aapt2FromMavenOverride=/old/stale/path/aapt2",
                "kotlin.code.style=official"
            ),
            key = "android.aapt2FromMavenOverride",
            value = "/new/path/aapt2"
        )
        assertEquals(
            listOf(
                "org.gradle.jvmargs=-Xmx640m",
                "kotlin.code.style=official",
                "android.aapt2FromMavenOverride=/new/path/aapt2"
            ),
            result
        )
    }

    @Test
    fun mergePropertyIsANoOpWhenAlreadyCorrect() {
        val lines = listOf(
            "org.gradle.jvmargs=-Xmx640m",
            "android.aapt2FromMavenOverride=/same/path/aapt2"
        )
        val result = NodeRuntime.mergeProperty(lines, "android.aapt2FromMavenOverride", "/same/path/aapt2")
        assertEquals(lines, result)
    }

    @Test
    fun mergePropertyOnEmptyFileProducesOnlyTheOneLine() {
        val result = NodeRuntime.mergeProperty(emptyList(), "android.aapt2FromMavenOverride", "/path/aapt2")
        assertEquals(listOf("android.aapt2FromMavenOverride=/path/aapt2"), result)
    }
}
