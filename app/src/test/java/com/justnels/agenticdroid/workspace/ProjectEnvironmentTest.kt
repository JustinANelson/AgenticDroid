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
    fun testDetectPackageJsonOnlyProjectIsNodeJs() {
        // No index.html/vite/next/webpack config alongside it - a bare package.json is
        // NODE_JS, not WEB (see ProjectType.detect's WEB-vs-NODE_JS split).
        val dir = tempFolder.newFolder("MyNodeApp")
        File(dir, "package.json").writeText("{\"name\": \"my-node-app\"}")

        val detected = ProjectType.detect(dir)
        assertEquals(ProjectType.NODE_JS, detected)
    }

    @Test
    fun testDetectWebProjectWithIndexHtml() {
        val dir = tempFolder.newFolder("MyWebApp")
        File(dir, "package.json").writeText("{\"name\": \"my-web-app\"}")
        File(dir, "index.html").writeText("<!doctype html>")

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
        assertTrue(File(dir, "app/src/main/java/com/example/androidstarter/MainActivity.kt").exists())
        assertEquals(ProjectType.ANDROID, ProjectType.detect(dir))

        // Gradle wrapper must be a working, matching set - not just "some files exist".
        val gradlew = File(dir, "gradlew")
        assertTrue(gradlew.exists())
        assertTrue(gradlew.canExecute())
        assertTrue(gradlew.readText().startsWith("#!/bin/sh"))
        assertTrue(gradlew.readBytes().none { it == '\r'.code.toByte() }) // must be LF, not CRLF
        assertTrue(File(dir, "gradlew.bat").exists())
        assertTrue(File(dir, "gradle/wrapper/gradle-wrapper.jar").length() > 0)
        val wrapperProps = File(dir, "gradle/wrapper/gradle-wrapper.properties").readText()
        assertTrue(wrapperProps.contains("distributionUrl"))
        assertEquals("toolchainVersion=17", File(dir, "gradle/gradle-daemon-jvm.properties").readLines().last())
        assertTrue(File(dir, "gradle.properties").readText().contains("org.gradle.jvmargs"))
    }

    @Test
    fun testScaffoldNodeJsStarterTemplate() {
        val dir = tempFolder.newFolder("NodeStarter")
        ProjectTemplate.NODE_JS_STARTER.scaffold(dir, "NodeStarter")

        assertTrue(File(dir, "index.js").exists())
        assertTrue(File(dir, "package.json").exists())
        // No index.html/vite/next config, so this must detect as NODE_JS, not WEB - see
        // ProjectType.detect's WEB-vs-NODE_JS split.
        assertEquals(ProjectType.NODE_JS, ProjectType.detect(dir))
    }

    @Test
    fun testScaffoldJvmStarterTemplate() {
        val dir = tempFolder.newFolder("JvmStarter")
        ProjectTemplate.JVM_STARTER.scaffold(dir, "JvmStarter")

        assertTrue(File(dir, "Main.kt").exists())
        assertEquals(ProjectType.JVM, ProjectType.detect(dir))

        val actions = ProjectRunnerAction.defaultActionsFor(ProjectType.JVM)
        val buildCmd = actions.find { it.id == "jvm_build" }!!.command
        val runCmd = actions.find { it.id == "jvm_run" }!!.command
        // The default commands must reference exactly what this template scaffolds -
        // kotlinc compiling Main.kt into app.jar, no Gradle/Maven wrapper assumed.
        assertTrue(buildCmd.contains("Main.kt"))
        assertTrue(buildCmd.contains("app.jar"))
        assertTrue(runCmd.contains("app.jar"))
    }

    @Test
    fun testScaffoldRustStarterTemplate() {
        val dir = tempFolder.newFolder("RustStarter")
        ProjectTemplate.RUST_STARTER.scaffold(dir, "RustStarter")

        assertTrue(File(dir, "Cargo.toml").exists())
        assertTrue(File(dir, "src/main.rs").exists())
        assertEquals(ProjectType.RUST, ProjectType.detect(dir))
    }

    @Test
    fun testScaffoldGolangStarterTemplate() {
        val dir = tempFolder.newFolder("GoStarter")
        ProjectTemplate.GOLANG_STARTER.scaffold(dir, "GoStarter")

        assertTrue(File(dir, "go.mod").exists())
        assertTrue(File(dir, "main.go").exists())
        assertEquals(ProjectType.GOLANG, ProjectType.detect(dir))
    }

    @Test
    fun testScaffoldCppStarterTemplate() {
        val dir = tempFolder.newFolder("CppStarter")
        ProjectTemplate.CPP_STARTER.scaffold(dir, "CppStarter")

        assertTrue(File(dir, "main.c").exists())
        assertTrue(File(dir, "Makefile").exists())
        assertEquals(ProjectType.CPP, ProjectType.detect(dir))

        // The Makefile's default target must match the "make" / "./a.out" commands
        // ProjectRunnerAction.CPP defaults to.
        val makefile = File(dir, "Makefile").readText()
        assertTrue(makefile.contains("a.out"))
    }

    @Test
    fun testScaffoldSsgStarterTemplate() {
        val dir = tempFolder.newFolder("SsgStarter")
        ProjectTemplate.SSG_STARTER.scaffold(dir, "SsgStarter")

        assertTrue(File(dir, "hugo.toml").exists())
        assertTrue(File(dir, "content/_index.md").exists())
        assertTrue(File(dir, "layouts/index.html").exists())
        assertEquals(ProjectType.SSG, ProjectType.detect(dir))
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
