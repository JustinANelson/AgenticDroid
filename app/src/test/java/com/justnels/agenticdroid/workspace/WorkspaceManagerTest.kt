package com.justnels.agenticdroid.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectsProjectNamesThatEscapeTheWorkspace() {
        val root = temporaryFolder.newFolder("workspaces")
        val manager = WorkspaceManager(root)

        assertFalse(manager.createProject("../outside"))
        assertFalse(manager.createProject("nested/project"))
        assertNull(manager.projectPath(".."))
    }

    @Test
    fun onlyDeletesProjectMatchingItsCanonicalNameAndPath() {
        val root = temporaryFolder.newFolder("workspaces")
        val outside = temporaryFolder.newFolder("outside")
        val manager = WorkspaceManager(root)
        assertTrue(manager.createProject("safe"))

        assertFalse(manager.deleteProject(Project("safe", outside.absolutePath)))
        assertTrue(outside.exists())
        assertTrue(manager.deleteProject(Project("safe", manager.projectPath("safe")!!)))
    }

    @Test
    fun projectFilesCannotEscapeProjectRoot() {
        val root = temporaryFolder.newFolder("bounded-workspaces")
        val manager = WorkspaceManager(root)
        assertTrue(manager.createProject("safe"))
        val project = Project("safe", manager.projectPath("safe")!!)

        assertFalse(manager.createFile(project, "../outside.txt"))
        assertNull(manager.safeSibling(project, File(project.path, "file.txt").path, "../outside.txt"))
    }

    @Test
    fun replacementCountIsExactForLongerReplacement() {
        val root = temporaryFolder.newFolder("replace-workspaces")
        val manager = WorkspaceManager(root)
        assertTrue(manager.createProject("safe"))
        val file = File(manager.projectPath("safe")!!, "code.txt")
        file.writeText("cat cat")

        assertEquals(2, manager.replaceInFiles("cat", "kitten"))
        assertEquals("kitten kitten", file.readText())
        assertEquals(0, manager.replaceInFiles("", "ignored"))
    }
}
