package com.justnels.agenticdroid

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import android.Manifest
import android.content.pm.PackageManager
import com.justnels.agenticdroid.auth.CredentialManager
import com.justnels.agenticdroid.workspace.Project
import com.justnels.agenticdroid.workspace.WorkspaceManager

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.justnels.agenticdroid", appContext.packageName)
    }

    @Test
    fun manifestDeclaresForegroundServicePermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in permissions)
    }

    @Test
    fun directKeystoreCredentialRoundTripAndClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = CredentialManager(context)
        val key = "instrumentation_round_trip"
        credentials.saveCredential(key, "secret-value")
        assertEquals("secret-value", credentials.getCredential(key))
        credentials.clearCredential(key)
        assertNull(credentials.getCredential(key))
    }

    @Test
    fun workspaceRejectsTraversalOnDeviceFilesystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(context.cacheDir, "workspace-boundary-test-${System.nanoTime()}")
        try {
            val manager = WorkspaceManager(root)
            assertTrue(manager.createProject("safe"))
            val project = Project("safe", manager.projectPath("safe")!!)
            assertFalse(manager.createFile(project, "../escape.txt"))
        } finally {
            root.deleteRecursively()
        }
    }
}
