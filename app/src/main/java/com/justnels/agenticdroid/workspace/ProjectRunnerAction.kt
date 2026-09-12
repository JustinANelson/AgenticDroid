package com.justnels.agenticdroid.workspace

/**
 * Represents an executable action / runner command for a project.
 */
data class ProjectRunnerAction(
    val id: String,
    val label: String,
    val command: String,
    val isBuild: Boolean = false,
    val opensPreview: Boolean = false,
    val previewUrl: String? = null,
    val description: String = "",
    val iconName: String = "play" // "play", "build", "preview", "install", "clean"
) {
    companion object {
        fun defaultActionsFor(type: ProjectType, customConfig: ProjectMetadata? = null): List<ProjectRunnerAction> {
            val actions = mutableListOf<ProjectRunnerAction>()
            
            when (type) {
                ProjectType.ANDROID -> {
                    val buildCmd = customConfig?.customBuildCommand?.takeIf(String::isNotBlank) ?: "./gradlew assembleDebug"
                    actions.add(
                        ProjectRunnerAction(
                            id = "android_build",
                            label = "Build & Sideload APK",
                            command = buildCmd,
                            isBuild = true,
                            description = "Compiles Android APK and prompts for sideload installation",
                            iconName = "build"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "android_clean_build",
                            label = "Clean & Build APK",
                            command = "./gradlew clean assembleDebug",
                            isBuild = true,
                            description = "Cleans previous build outputs and builds APK",
                            iconName = "clean"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "android_test",
                            label = "Run Unit Tests",
                            command = "./gradlew test",
                            isBuild = false,
                            description = "Executes Gradle unit tests",
                            iconName = "play"
                        )
                    )
                }
                ProjectType.WEB -> {
                    val devCmd = customConfig?.customRunCommand?.takeIf(String::isNotBlank) ?: "npm run dev -- --host"
                    val previewUrl = customConfig?.previewUrl ?: type.defaultPreviewUrl
                    actions.add(
                        ProjectRunnerAction(
                            id = "web_dev",
                            label = "Run Dev Server",
                            command = devCmd,
                            isBuild = false,
                            opensPreview = true,
                            previewUrl = previewUrl,
                            description = "Starts web dev server and opens live Web Preview",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "web_install",
                            label = "Install NPM Packages",
                            command = "npm install",
                            isBuild = false,
                            description = "Installs dependencies declared in package.json",
                            iconName = "install"
                        )
                    )
                    val buildCmd = customConfig?.customBuildCommand?.takeIf(String::isNotBlank) ?: "npm run build"
                    actions.add(
                        ProjectRunnerAction(
                            id = "web_build",
                            label = "Build Production Bundle",
                            command = buildCmd,
                            isBuild = false,
                            description = "Builds production assets with npm run build",
                            iconName = "build"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "web_serve",
                            label = "Serve Static Web",
                            command = "npx serve .",
                            isBuild = false,
                            opensPreview = true,
                            previewUrl = "http://localhost:3000",
                            description = "Serves current directory with auto-selected port",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "web_preview",
                            label = "Open Web Preview",
                            command = "",
                            isBuild = false,
                            opensPreview = true,
                            previewUrl = previewUrl,
                            description = "Opens in-app Web Preview panel",
                            iconName = "preview"
                        )
                    )
                }
                ProjectType.NODE_JS -> {
                    val runCmd = customConfig?.customRunCommand?.takeIf(String::isNotBlank) ?: "node index.js"
                    actions.add(
                        ProjectRunnerAction(
                            id = "node_run",
                            label = "Run Node.js Script",
                            command = runCmd,
                            isBuild = false,
                            description = "Executes the entry point script with node",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "node_install",
                            label = "Install NPM Packages",
                            command = "npm install",
                            isBuild = false,
                            description = "Installs dependencies from package.json",
                            iconName = "install"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "node_test",
                            label = "Run Tests",
                            command = "npm test",
                            isBuild = false,
                            description = "Runs test suite using npm test",
                            iconName = "play"
                        )
                    )
                }
                ProjectType.PYTHON -> {
                    val runCmd = customConfig?.customRunCommand?.takeIf(String::isNotBlank) ?: "python main.py"
                    val previewUrl = customConfig?.previewUrl ?: type.defaultPreviewUrl
                    actions.add(
                        ProjectRunnerAction(
                            id = "python_run",
                            label = "Run Python Script",
                            command = runCmd,
                            isBuild = false,
                            description = "Executes the main Python script in terminal",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "python_http_server",
                            label = "Start HTTP Server (Port 8000)",
                            command = "python -m http.server 8000",
                            isBuild = false,
                            opensPreview = true,
                            previewUrl = "http://localhost:8000",
                            description = "Runs Python built-in HTTP server on port 8000",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "python_pip_install",
                            label = "Install Requirements",
                            command = "pip install -r requirements.txt",
                            isBuild = false,
                            description = "Installs dependencies from requirements.txt",
                            iconName = "install"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "python_preview",
                            label = "Open Web Preview",
                            command = "",
                            isBuild = false,
                            opensPreview = true,
                            previewUrl = previewUrl,
                            description = "Opens in-app Web Preview panel",
                            iconName = "preview"
                        )
                    )
                }
                ProjectType.JVM -> {
                    // The bundled JVM runner group is just OpenJDK + kotlinc (no Gradle/Maven
                    // distribution is downloaded for a project) - default commands build
                    // directly with kotlinc rather than assuming a Gradle wrapper the project
                    // may not have.
                    val runCmd = customConfig?.customRunCommand?.takeIf(String::isNotBlank) ?: "java -jar app.jar"
                    actions.add(
                        ProjectRunnerAction(
                            id = "jvm_run",
                            label = "Run JVM App",
                            command = runCmd,
                            isBuild = false,
                            description = "Executes the compiled Java/Kotlin application",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "jvm_build",
                            label = "Build (kotlinc)",
                            command = customConfig?.customBuildCommand?.takeIf(String::isNotBlank) ?: "kotlinc Main.kt -include-runtime -d app.jar",
                            isBuild = false,
                            description = "Compiles Main.kt into a runnable app.jar with kotlinc",
                            iconName = "build"
                        )
                    )
                }
                ProjectType.RUST -> {
                    actions.add(
                        ProjectRunnerAction(
                            id = "rust_run",
                            label = "Cargo Run",
                            command = "cargo run",
                            isBuild = false,
                            description = "Compiles and runs the Rust project",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "rust_build",
                            label = "Cargo Build",
                            command = "cargo build",
                            isBuild = false,
                            description = "Compiles the Rust project",
                            iconName = "build"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "rust_test",
                            label = "Cargo Test",
                            command = "cargo test",
                            isBuild = false,
                            description = "Runs Rust unit and integration tests",
                            iconName = "play"
                        )
                    )
                }
                ProjectType.GOLANG -> {
                    actions.add(
                        ProjectRunnerAction(
                            id = "go_run",
                            label = "Go Run",
                            command = "go run .",
                            isBuild = false,
                            description = "Compiles and runs the Go application",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "go_build",
                            label = "Go Build",
                            command = "go build .",
                            isBuild = false,
                            description = "Compiles the Go application",
                            iconName = "build"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "go_test",
                            label = "Go Test",
                            command = "go test ./...",
                            isBuild = false,
                            description = "Runs Go tests",
                            iconName = "play"
                        )
                    )
                }
                ProjectType.SSG -> {
                    val serveCmd = customConfig?.customRunCommand?.takeIf(String::isNotBlank) ?: "hugo serve"
                    val previewUrl = customConfig?.previewUrl ?: type.defaultPreviewUrl
                    actions.add(
                        ProjectRunnerAction(
                            id = "ssg_serve",
                            label = "Serve Site",
                            command = serveCmd,
                            isBuild = false,
                            opensPreview = true,
                            previewUrl = previewUrl,
                            description = "Starts the SSG development server",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "ssg_build",
                            label = "Build Static Site",
                            command = customConfig?.customBuildCommand?.takeIf(String::isNotBlank) ?: "hugo",
                            isBuild = false,
                            description = "Generates the static site production build",
                            iconName = "build"
                        )
                    )
                }
                ProjectType.CPP -> {
                    val buildCmd = customConfig?.customBuildCommand?.takeIf(String::isNotBlank) ?: "make"
                    val runCmd = customConfig?.customRunCommand?.takeIf(String::isNotBlank) ?: "./a.out"
                    actions.add(
                        ProjectRunnerAction(
                            id = "cpp_build",
                            label = "Build (make)",
                            command = buildCmd,
                            isBuild = false,
                            description = "Compiles the project using Makefile",
                            iconName = "build"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "cpp_run",
                            label = "Run Binary",
                            command = runCmd,
                            isBuild = false,
                            description = "Executes the compiled binary",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "cpp_clean",
                            label = "Clean",
                            command = "make clean",
                            isBuild = false,
                            description = "Removes build artifacts",
                            iconName = "clean"
                        )
                    )
                }
                ProjectType.CUSTOM -> {
                    val runCmd = customConfig?.customRunCommand?.takeIf(String::isNotBlank) ?: "npm start"
                    val buildCmd = customConfig?.customBuildCommand?.takeIf(String::isNotBlank) ?: "make"
                    val previewUrl = customConfig?.previewUrl ?: type.defaultPreviewUrl
                    actions.add(
                        ProjectRunnerAction(
                            id = "custom_run",
                            label = "Run Command",
                            command = runCmd,
                            isBuild = false,
                            description = "Runs the configured project command",
                            iconName = "play"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "custom_build",
                            label = "Build Project",
                            command = buildCmd,
                            isBuild = false,
                            description = "Executes the configured build command",
                            iconName = "build"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "custom_preview",
                            label = "Open Web Preview",
                            command = "",
                            isBuild = false,
                            opensPreview = true,
                            previewUrl = previewUrl,
                            description = "Opens in-app Web Preview panel",
                            iconName = "preview"
                        )
                    )
                }
            }

            return actions
        }
    }
}
