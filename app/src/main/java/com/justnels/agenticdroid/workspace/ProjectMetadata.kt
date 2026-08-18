package com.justnels.agenticdroid.workspace

import org.json.JSONObject

/**
 * Stores custom runner and preview configurations for a specific project.
 */
data class ProjectMetadata(
    val type: ProjectType? = null,
    val customRunCommand: String? = null,
    val customBuildCommand: String? = null,
    val previewUrl: String? = null
) {
    fun toJson(): String {
        return JSONObject().apply {
            type?.let { put("type", it.name) }
            customRunCommand?.let { put("customRunCommand", it) }
            customBuildCommand?.let { put("customBuildCommand", it) }
            previewUrl?.let { put("previewUrl", it) }
        }.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): ProjectMetadata {
            return runCatching {
                val json = JSONObject(jsonStr)
                val type = json.optString("type").takeIf(String::isNotBlank)?.let {
                    runCatching { ProjectType.valueOf(it) }.getOrNull()
                }
                ProjectMetadata(
                    type = type,
                    customRunCommand = json.optString("customRunCommand").takeIf(String::isNotBlank),
                    customBuildCommand = json.optString("customBuildCommand").takeIf(String::isNotBlank),
                    previewUrl = json.optString("previewUrl").takeIf(String::isNotBlank)
                )
            }.getOrDefault(ProjectMetadata())
        }
    }
}
