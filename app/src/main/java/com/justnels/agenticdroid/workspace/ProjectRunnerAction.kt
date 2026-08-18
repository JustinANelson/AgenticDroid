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
                    val devCmd = customConfig?.customRunCommand?.takeIf(String::isNotBlank) ?: "npm run dev"
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
                            isBuild = true,
                            description = "Builds production assets with npm run build",
                            iconName = "build"
                        )
                    )
                    actions.add(
                        ProjectRunnerAction(
                            id = "web_serve",
                            label = "Serve Static (Port 3000)",
                            command = "npx serve -l 3000 .",
                            isBuild = false,
                            opensPreview = true,
                            previewUrl = "http://localhost:3000",
                            description = "Serves current directory with local static HTTP server",
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
                            isBuild = true,
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
