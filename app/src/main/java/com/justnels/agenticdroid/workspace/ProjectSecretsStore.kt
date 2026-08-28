package com.justnels.agenticdroid.workspace

import com.justnels.agenticdroid.auth.CredentialManager
import com.justnels.agenticdroid.env.ShellEscaping
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Per-project secrets (API keys, tokens) - the app previously only had encrypted storage
 * for SSH credentials and the GitHub token (see CredentialManager), so every other secret
 * an agent needs had to be typed into a shell `export` by hand or pasted straight into an
 * agent's onboarding prompt. Stored as one encrypted JSON blob per project (rather than
 * one CredentialManager entry per secret) so listing/renaming/deleting doesn't need to
 * track a growing set of keys.
 */
class ProjectSecretsStore(private val credentials: CredentialManager) {
    companion object {
        /** Same constraint as a POSIX shell identifier - required so a secret's name can
         * always be used as a literal `export NAME=...` without any escaping of its own. */
        val NAME_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        fun credentialKeyFor(projectPath: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(projectPath.toByteArray(Charsets.UTF_8))
            return "project_secrets_" + digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * Builds the shell prelude that exports [secrets] before a command runs. Safe
         * against shell injection: names are restricted to [NAME_PATTERN] (validated in
         * [setSecret], defended again here), and every value is single-quoted via
         * [ShellEscaping] regardless of content.
         */
        fun buildExportPrelude(secrets: Map<String, String>): String =
            secrets.entries
                .filter { (name, _) -> NAME_PATTERN.matches(name) }
                .joinToString("") { (name, value) -> "export $name=${ShellEscaping.quote(value)}\n" }
    }

    fun getSecrets(project: Project): Map<String, String> {
        val raw = credentials.getCredential(credentialKeyFor(project.path)) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun setSecret(project: Project, name: String, value: String) {
        require(NAME_PATTERN.matches(name)) { "Secret name must be a valid shell identifier: $name" }
        saveAll(project, getSecrets(project) + (name to value))
    }

    fun removeSecret(project: Project, name: String) {
        val current = getSecrets(project)
        if (name in current) saveAll(project, current - name)
    }

    /** The `export`-lines prelude for [project]'s secrets - see [buildExportPrelude]. */
    fun exportPrelude(project: Project): String = buildExportPrelude(getSecrets(project))

    private fun saveAll(project: Project, secrets: Map<String, String>) {
        val key = credentialKeyFor(project.path)
        if (secrets.isEmpty()) {
            credentials.clearCredential(key)
            return
        }
        val obj = JSONObject()
        secrets.forEach { (name, value) -> obj.put(name, value) }
        credentials.saveCredential(key, obj.toString())
    }
}
