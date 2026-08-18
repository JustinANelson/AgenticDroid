package com.justnels.agenticdroid.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectEnvironmentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDetectAndroidProject() {
        val dir = tempFolder.newFolder("MyAndroidApp")
        File(dir, "build.gradle.kts").writeText("// gradle")
        File(dir, "settings.gradle.kts").writeText("// settings")

        val detected = ProjectType.detect(dir)
        assertEquals(ProjectType.ANDROID, detected)
    }

    @Test
    fun testDetectWebProject() {
        val dir = tempFolder.newFolder("MyWebApp")
        File(dir, "package.json").writeText("{\"name\": \"my-web-app\"}")

        val detected = ProjectType.detect(dir)
        assertEquals(ProjectType.WEB, detected)
    }

    @Test
    fun testDetectViteWebProject() {
        val dir = tempFolder.newFolder("MyViteApp")
        File(dir, "vite.config.js").writeText("export default {}")

        val detected = ProjectType.detect(dir)
        assertEquals(ProjectType.WEB, detected)
    }

    @Test
    fun testDetectPythonProjectWithRequirements() {
        val dir = tempFolder.newFolder("MyPythonApp")
        File(dir, "requirements.txt").writeText("flask>=3.0.0")

        val detected = ProjectType.detect(dir)
        assertEquals(ProjectType.PYTHON, detected)
    }

    @Test
    fun testDetectPythonProjectWithScript() {
        val dir = tempFolder.newFolder("MyPythonScript")
        File(dir, "main.py").writeText("print('hello')")

        val detected = ProjectType.detect(dir)
        assertEquals(ProjectType.PYTHON, detected)
    }

    @Test
    fun testDetectCustomProject() {
        val dir = tempFolder.newFolder("MyCustomApp")
        File(dir, "Makefile").writeText("all:\n\techo hi")

        val detected = ProjectType.detect(dir)
        assertEquals(ProjectType.CUSTOM, detected)
    }

    @Test
    fun testScaffoldVanillaWebTemplate() {
        val dir = tempFolder.newFolder("VanillaWeb")
        ProjectTemplate.VANILLA_WEB.scaffold(dir, "VanillaWeb")

        assertTrue(File(dir, "index.html").exists())
        assertTrue(File(dir, "style.css").exists())
        assertTrue(File(dir, "app.js").exists())
        assertTrue(File(dir, "package.json").exists())
        assertTrue(File(dir, "README.md").exists())
        assertEquals(ProjectType.WEB, ProjectType.detect(dir))
    }

    @Test
    fun testScaffoldViteReactTemplate() {
        val dir = tempFolder.newFolder("ViteReact")
        ProjectTemplate.VITE_REACT.scaffold(dir, "ViteReact")

        assertTrue(File(dir, "package.json").exists())
        assertTrue(File(dir, "vite.config.js").exists())
        assertTrue(File(dir, "src/App.jsx").exists())
        assertTrue(File(dir, "src/main.jsx").exists())
        assertEquals(ProjectType.WEB, ProjectType.detect(dir))
    }

    @Test
    fun testScaffoldPythonWebTemplate() {
        val dir = tempFolder.newFolder("PyWeb")
        ProjectTemplate.PYTHON_WEB.scaffold(dir, "PyWeb")

        assertTrue(File(dir, "app.py").exists())
        assertTrue(File(dir, "requirements.txt").exists())
        assertEquals(ProjectType.PYTHON, ProjectType.detect(dir))
    }

    @Test
    fun testScaffoldPythonCliTemplate() {
        val dir = tempFolder.newFolder("PyCli")
        ProjectTemplate.PYTHON_CLI.scaffold(dir, "PyCli")

        assertTrue(File(dir, "main.py").exists())
        assertTrue(File(dir, "requirements.txt").exists())
        assertEquals(ProjectType.PYTHON, ProjectType.detect(dir))
    }

    @Test
    fun testScaffoldAndroidStarterTemplate() {
        val dir = tempFolder.newFolder("AndroidStarter")
        ProjectTemplate.ANDROID_STARTER.scaffold(dir, "AndroidStarter")

        assertTrue(File(dir, "build.gradle.kts").exists())
        assertTrue(File(dir, "settings.gradle.kts").exists())
        assertTrue(File(dir, "app/build.gradle.kts").exists())
        assertTrue(File(dir, "app/src/main/AndroidManifest.xml").exists())
        assertEquals(ProjectType.ANDROID, ProjectType.detect(dir))
    }

    @Test
    fun testProjectRunnerActionsForWeb() {
        val actions = ProjectRunnerAction.defaultActionsFor(ProjectType.WEB)
        val devAction = actions.find { it.id == "web_dev" }
        assertNotNull(devAction)
        assertTrue(devAction!!.opensPreview)
        assertEquals("http://localhost:5173", devAction.previewUrl)

        val buildAction = actions.find { it.id == "web_build" }
        assertNotNull(buildAction)
        assertTrue(buildAction!!.isBuild)
    }

    @Test
    fun testProjectRunnerActionsForPython() {
        val actions = ProjectRunnerAction.defaultActionsFor(ProjectType.PYTHON)
        val runAction = actions.find { it.id == "python_run" }
        assertNotNull(runAction)
        assertEquals("python main.py", runAction!!.command)

        val serverAction = actions.find { it.id == "python_http_server" }
        assertNotNull(serverAction)
        assertTrue(serverAction!!.opensPreview)
        assertEquals("http://localhost:8000", serverAction.previewUrl)
    }

    @Test
    fun testProjectMetadataSerialization() {
        val meta = ProjectMetadata(
            type = ProjectType.WEB,
            customRunCommand = "npm run dev:custom",
            customBuildCommand = "npm run build:prod",
            previewUrl = "http://localhost:3000"
        )
        val json = meta.toJson()
        val parsed = ProjectMetadata.fromJson(json)

        assertEquals(ProjectType.WEB, parsed.type)
        assertEquals("npm run dev:custom", parsed.customRunCommand)
        assertEquals("npm run build:prod", parsed.customBuildCommand)
        assertEquals("http://localhost:3000", parsed.previewUrl)
    }
}
