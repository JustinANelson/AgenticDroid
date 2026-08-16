package com.justnels.agenticdroid.workspace

import java.io.File

/**
 * Manages development workspaces and project metadata.
 */
class WorkspaceManager(private val rootDir: File) {

    /**
     * Lists all projects in the workspace root.
     */
    fun listProjects(): List<Project> {
        return rootDir.listFiles { file -> file.isDirectory }?.map { 
            Project(it.name, it.absolutePath) 
        } ?: emptyList()
    }

    /**
     * Creates a new project directory.
     */
    fun createProject(name: String): Boolean {
        val projectDir = File(rootDir, name)
        return projectDir.mkdirs()
    }

    /**
     * Gets the file tree for a specific project.
     */
    fun getFileTree(project: Project): List<FileNode> {
        return buildFileTree(File(project.path))
    }

    private fun buildFileTree(file: File): List<FileNode> {
        return file.listFiles()?.map { child ->
            FileNode(
                name = child.name,
                path = child.absolutePath,
                isDirectory = child.isDirectory,
                children = if (child.isDirectory) buildFileTree(child) else emptyList()
            )
        } ?: emptyList()
    }
    /**
     * Searches for a string in all files within the workspace.
     */
    fun searchInFiles(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        rootDir.walkTopDown().forEach { file ->
            if (file.isFile && !file.name.startsWith(".")) {
                try {
                    val lines = file.readLines()
                    lines.forEachIndexed { index, line ->
                        if (line.contains(query, ignoreCase = true)) {
                            results.add(SearchResult(file.absolutePath, index + 1, line.trim()))
                        }
                    }
                } catch (e: Exception) {
                    // Skip files that can't be read
                }
            }
        }
        return results
    }

    /**
     * Replaces all occurrences of a string with another in all files.
     */
    fun replaceInFiles(query: String, replacement: String): Int {
        var replacementCount = 0
        rootDir.walkTopDown().forEach { file ->
            if (file.isFile && !file.name.startsWith(".")) {
                try {
                    val content = file.readText()
                    if (content.contains(query)) {
                        val newContent = content.replace(query, replacement)
                        file.writeText(newContent)
                        replacementCount += (content.length - newContent.length) / query.length // Approximation
                    }
                } catch (e: Exception) {
                    // Skip files that can't be modified
                }
            }
        }
        return replacementCount
    }
}

data class SearchResult(val path: String, val lineNumber: Int, val lineContent: String)

data class Project(val name: String, val path: String)

data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<FileNode> = emptyList()
)
