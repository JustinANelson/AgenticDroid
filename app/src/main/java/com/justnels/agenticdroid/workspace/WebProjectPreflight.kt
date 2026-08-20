package com.justnels.agenticdroid.workspace

import org.json.JSONObject
import java.io.File

data class WebDevPreparation(
    val command: String,
    val installRequired: Boolean = false,
    val error: String? = null
)

/**
 * Prepares a web project's dev command without making framework dependencies global.
 * Project-local dependencies keep lockfiles meaningful and allow different projects to
 * use different Vite versions.
 */
object WebProjectPreflight {
    private val npmRunPattern = Regex("""^\s*npm\s+(?:run|run-script)\s+([^\s;&|]+)""")
    private val viteCommandPattern = Regex("""(^|[\s;&|])(?:npx\s+)?vite(?=$|[\s;&|])""")

    fun prepare(projectDir: File, devCommand: String): WebDevPreparation {
        val packageFile = File(projectDir, "package.json")
        if (!packageFile.isFile) return WebDevPreparation(devCommand)
        if (packageFile.length() > MAX_PACKAGE_JSON_BYTES) {
            return WebDevPreparation(devCommand, error = "package.json is too large to inspect safely.")
        }

        val json = runCatching { JSONObject(packageFile.readText()) }.getOrElse {
            return WebDevPreparation(devCommand, error = "package.json is not valid JSON: ${it.message}")
        }
        val npmRunMatch = npmRunPattern.find(devCommand)
        val scriptName = npmRunMatch?.groupValues?.get(1)?.trim('"', '\'')
        val script = scriptName?.let { json.optJSONObject("scripts")?.optString(it) }.orEmpty()
        val invokesVite = viteCommandPattern.containsMatchIn(script.ifBlank { devCommand })
        val viteDeclared = DEPENDENCY_SECTIONS.any { section ->
            json.optJSONObject(section)?.has("vite") == true
        }
        // Use Vite's real project-local entry point so startup does not depend on a global
        // install or on npm's generated .bin wrapper.
        val viteInstalled = File(projectDir, VITE_ENTRYPOINT).isFile

        if (invokesVite && !viteInstalled && !viteDeclared) {
            return WebDevPreparation(
                devCommand,
                error = "This project's dev script uses Vite, but package.json does not declare it. " +
                    "Add Vite to devDependencies (npm install --save-dev vite), then run again."
            )
        }

        val hasDeclaredDependencies = DEPENDENCY_SECTIONS.any { section ->
            (json.optJSONObject(section)?.length() ?: 0) > 0
        }
        val installMarker = File(projectDir, INSTALL_MARKER)
        val installIsCurrent = installMarker.isFile &&
            runCatching { installMarker.readText().trim() == INSTALL_MARKER_VERSION }.getOrDefault(false)
        val installRequired = hasDeclaredDependencies &&
            (!installIsCurrent || (invokesVite && !viteInstalled))

        val runtimeCommand = if (invokesVite) {
            val configuredCommand = script.ifBlank { devCommand }
            val viteMatch = requireNotNull(viteCommandPattern.find(configuredCommand))
            val directViteCommand = configuredCommand.replaceRange(
                viteMatch.range,
                viteMatch.groupValues[1] + "node $VITE_ENTRYPOINT"
            )
            val forwardedArgs = npmRunMatch?.let { match ->
                devCommand.substring(match.range.last + 1).trim().removePrefix("--").trim()
            }.orEmpty()
            if (forwardedArgs.isBlank()) directViteCommand else "$directViteCommand $forwardedArgs"
        } else {
            devCommand
        }
        if (!installRequired) return WebDevPreparation(runtimeCommand)

        // npm ci can retain a lockfile generated for a desktop OS without adding the
        // Android-specific optional Rollup/esbuild package. npm install reconciles those
        // platform packages in the app-private workspace.
        val installAllScript = json.optJSONObject("scripts")?.optString("install:all").orEmpty()
        val installCommand = if (installAllScript.isNotBlank()) {
            "NPM_CONFIG_INCLUDE=optional npm run install:all"
        } else {
            "NPM_CONFIG_INCLUDE=optional npm install"
        }
        return WebDevPreparation(
            command = "echo 'Installing project dependencies on device...'; " +
                "if $installCommand; then printf '$INSTALL_MARKER_VERSION' > $INSTALL_MARKER; $runtimeCommand; " +
                "else echo 'Dependency installation failed.' >&2; exit 1; fi",
            installRequired = true
        )
    }

    private val DEPENDENCY_SECTIONS = listOf("dependencies", "devDependencies", "optionalDependencies")
    private const val VITE_ENTRYPOINT = "node_modules/vite/bin/vite.js"
    private const val INSTALL_MARKER = "node_modules/.agenticdroid-install-ok"
    private const val INSTALL_MARKER_VERSION = "3"
    private const val MAX_PACKAGE_JSON_BYTES = 1024 * 1024L
}
