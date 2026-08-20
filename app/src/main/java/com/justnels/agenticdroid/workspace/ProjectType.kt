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
    NODE_JS("Node.js", "Node.js backend, CLI tools, npm packages"),
    PYTHON("Python", "Python scripts, Flask, FastAPI, web services", "http://localhost:8000"),
    JVM("Java/Kotlin", "JVM applications, Gradle, Maven"),
    RUST("Rust", "Rust projects, Cargo"),
    GOLANG("Go", "Go modules and applications"),
    SSG("Static Site", "Hugo, Eleventy, static generators", "http://localhost:1313"),
    CPP("C/C++", "Native C/C++ projects, Makefile, CMake"),
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

            // Check for explicit Web framework configs
            val hasExplicitWebFramework = File(projectDir, "vite.config.js").exists() ||
                File(projectDir, "vite.config.ts").exists() ||
                File(projectDir, "vite.config.mjs").exists() ||
                File(projectDir, "next.config.js").exists() ||
                File(projectDir, "next.config.mjs").exists() ||
                File(projectDir, "webpack.config.js").exists()

            // Check for Python project indicators
            val hasPythonIndicators = File(projectDir, "requirements.txt").exists() ||
                File(projectDir, "Pipfile").exists() ||
                File(projectDir, "pyproject.toml").exists() ||
                File(projectDir, "setup.py").exists() ||
                File(projectDir, "main.py").exists() ||
                File(projectDir, "app.py").exists() ||
                File(projectDir, "launcher.py").exists() ||
                File(projectDir, "run.py").exists() ||
                File(projectDir, "server.py").exists() ||
                runCatching {
                    projectDir.walkTopDown()
                        .onEnter { it.name !in setOf(".git", ".gradle", "node_modules", "__pycache__", ".venv", "venv") }
                        .maxDepth(3)
                        .any { it.isFile && it.extension.equals("py", ignoreCase = true) }
                }.getOrDefault(false)

            // Check for Web project indicators
            val hasPackageJson = File(projectDir, "package.json").exists()
            val nestedWebRoots = listOf("frontend", "client", "web").map { File(projectDir, it) }
            val hasNestedWebApp = nestedWebRoots.any { root ->
                root.isDirectory && (
                    File(root, "index.html").isFile ||
                    File(root, "vite.config.js").isFile ||
                    File(root, "vite.config.ts").isFile ||
                    File(root, "vite.config.mjs").isFile ||
                    File(root, "next.config.js").isFile ||
                    File(root, "next.config.mjs").isFile
                )
            }
            if (hasPackageJson ||
                hasNestedWebApp ||
                File(projectDir, "index.html").exists() ||
                hasExplicitWebFramework
            ) {
                // If it has index.html or other strong web signals, it's WEB
                if (File(projectDir, "index.html").exists() ||
                    hasExplicitWebFramework ||
                    hasNestedWebApp
                ) {
                    return WEB
                }
                if (hasPackageJson) return NODE_JS
            }

            // Check for C/C++ project indicators
            if (File(projectDir, "CMakeLists.txt").exists() ||
                runCatching {
                    projectDir.walkTopDown()
                        .onEnter { it.name !in setOf(".git", ".gradle", "node_modules", "build", "out") }
                        .maxDepth(3)
                        .any { it.isFile && (it.extension.equals("cpp", ignoreCase = true) || 
                                            it.extension.equals("c", ignoreCase = true) ||
                                            it.extension.equals("hpp", ignoreCase = true) ||
                                            it.extension.equals("h", ignoreCase = true)) }
                }.getOrDefault(false)
            ) {
                return CPP
            }

            if (hasPythonIndicators) {
                return PYTHON
            }

            // Check for Rust project indicators
            if (File(projectDir, "Cargo.toml").exists()) {
                return RUST
            }

            // Check for Go project indicators
            if (File(projectDir, "go.mod").exists()) {
                return GOLANG
            }

            // Check for SSG project indicators
            if (File(projectDir, "hugo.toml").exists() || 
                File(projectDir, "hugo.yaml").exists() || 
                File(projectDir, "hugo.json").exists() ||
                File(projectDir, "eleventy.config.js").exists() ||
                File(projectDir, "eleventy.config.cjs").exists() ||
                File(projectDir, "eleventy.config.mjs").exists() ||
                File(projectDir, ".eleventy.js").exists()
            ) {
                return SSG
            }

            // Check for JVM project indicators
            if (File(projectDir, "pom.xml").exists() ||
                File(projectDir, "build.gradle").exists() ||
                File(projectDir, "build.gradle.kts").exists() ||
                runCatching {
                    projectDir.walkTopDown()
                        .onEnter { it.name !in setOf(".git", ".gradle", "node_modules", "build", "out", "target") }
                        .maxDepth(3)
                        .any { it.isFile && (it.extension.equals("java", ignoreCase = true) || 
                                            it.extension.equals("kt", ignoreCase = true)) }
                }.getOrDefault(false)
            ) {
                return JVM
            }

            return CUSTOM
        }

        /**
         * Automatically detects the project type based on file names / relative paths
         * (e.g. from a remote tree or list of files).
         */
        fun detectFromPaths(paths: Collection<String>): ProjectType {
            val normalized = paths.map { it.replace('\\', '/').trim().trimStart('/') }.toSet()

            // Check for Android project indicators
            if (normalized.any { path ->
                    path == "build.gradle" || path == "build.gradle.kts" ||
                    path == "settings.gradle" || path == "settings.gradle.kts" ||
                    path == "app/build.gradle.kts" || path == "app/build.gradle" ||
                    path == "AndroidManifest.xml" || path == "app/src/main/AndroidManifest.xml"
                }
            ) {
                return ANDROID
            }

            // Check for explicit Web framework configs
            val hasExplicitWebFramework = normalized.any { path ->
                path == "vite.config.js" || path == "vite.config.ts" ||
                path == "vite.config.mjs" || path == "next.config.js" || path == "next.config.mjs" ||
                path == "webpack.config.js" ||
                path.startsWith("frontend/vite.config") || path.startsWith("client/vite.config")
            }

            // Check for Python project indicators
            val hasPythonIndicators = normalized.any { path ->
                path == "requirements.txt" || path == "Pipfile" || path == "pyproject.toml" ||
                path == "setup.py" || path == "main.py" || path == "app.py" ||
                path == "launcher.py" || path == "run.py" || path == "server.py" ||
                path.endsWith(".py", ignoreCase = true)
            }

            if (hasPythonIndicators && !hasExplicitWebFramework) {
                return PYTHON
            }

            // Check for Web project indicators
            val hasPackageJson = normalized.contains("package.json")
            val hasWebConfig = hasExplicitWebFramework || normalized.any { path ->
                path == "index.html" ||
                path.startsWith("frontend/") || path.startsWith("client/") || path.startsWith("web/")
            }
            if (hasPackageJson || hasWebConfig) {
                if (hasWebConfig) return WEB
                if (hasPackageJson) return NODE_JS
            }

            // Check for C/C++ project indicators
            if (normalized.any { path ->
                    path == "CMakeLists.txt" ||
                    path.endsWith(".cpp", ignoreCase = true) || path.endsWith(".c", ignoreCase = true) ||
                    path.endsWith(".hpp", ignoreCase = true) || path.endsWith(".h", ignoreCase = true)
                }
            ) {
                return CPP
            }

            // Fallback for Python
            if (hasPythonIndicators) {
                return PYTHON
            }

            // Check for Rust project indicators
            if (normalized.contains("Cargo.toml")) {
                return RUST
            }

            // Check for Go project indicators
            if (normalized.contains("go.mod")) {
                return GOLANG
            }

            // Check for SSG project indicators
            if (normalized.any { path ->
                    path == "hugo.toml" || path == "hugo.yaml" || path == "hugo.json" ||
                    path == "eleventy.config.js" || path == "eleventy.config.cjs" ||
                    path == "eleventy.config.mjs" || path == ".eleventy.js"
                }
            ) {
                return SSG
            }

            // Check for JVM project indicators
            if (normalized.any { path ->
                    path == "pom.xml" || path.endsWith(".java", ignoreCase = true) || path.endsWith(".kt", ignoreCase = true)
                }
            ) {
                return JVM
            }

            return CUSTOM
        }
    }
}
