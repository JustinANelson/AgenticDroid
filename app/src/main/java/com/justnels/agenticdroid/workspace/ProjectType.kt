package com.justnels.agenticdroid.workspace

import java.io.File

/**
 * Categorization of development projects supported by AgenticDroid.
 */
enum class ProjectType(
    val displayName: String,
    val description: String,
    val defaultPreviewUrl: String = "http://localhost:5173"
) {
    ANDROID("Android App", "Gradle & APK deployment", "http://localhost:8080"),
    WEB("Web App", "HTML, JavaScript, React, Vue, Vite, Next.js", "http://localhost:5173"),
    PYTHON("Python", "Python scripts, Flask, FastAPI, web services", "http://localhost:8000"),
    CUSTOM("Custom", "Configurable commands and workflow", "http://localhost:3000");

    companion object {
        /**
         * Automatically detects the project type based on files present in the project directory.
         */
        fun detect(projectDir: File): ProjectType {
            if (!projectDir.isDirectory) return CUSTOM

            // Check for Android project indicators
            if (File(projectDir, "build.gradle").exists() ||
                File(projectDir, "build.gradle.kts").exists() ||
                File(projectDir, "settings.gradle").exists() ||
                File(projectDir, "settings.gradle.kts").exists() ||
                File(projectDir, "app/build.gradle.kts").exists() ||
                File(projectDir, "AndroidManifest.xml").exists() ||
                File(projectDir, "app/src/main/AndroidManifest.xml").exists()
            ) {
                return ANDROID
            }

            // Check for Web project indicators
            if (File(projectDir, "package.json").exists() ||
                File(projectDir, "index.html").exists() ||
                File(projectDir, "vite.config.js").exists() ||
                File(projectDir, "vite.config.ts").exists() ||
                File(projectDir, "next.config.js").exists() ||
                File(projectDir, "next.config.mjs").exists() ||
                File(projectDir, "webpack.config.js").exists()
            ) {
                return WEB
            }

            // Check for Python project indicators
            if (File(projectDir, "requirements.txt").exists() ||
                File(projectDir, "Pipfile").exists() ||
                File(projectDir, "pyproject.toml").exists() ||
                File(projectDir, "setup.py").exists() ||
                File(projectDir, "main.py").exists() ||
                File(projectDir, "app.py").exists() ||
                runCatching {
                    projectDir.walkTopDown()
                        .onEnter { it.name !in setOf(".git", ".gradle", "node_modules", "__pycache__", ".venv", "venv") }
                        .maxDepth(3)
                        .any { it.isFile && it.extension.equals("py", ignoreCase = true) }
                }.getOrDefault(false)
            ) {
                return PYTHON
            }

            return CUSTOM
        }
    }
}
