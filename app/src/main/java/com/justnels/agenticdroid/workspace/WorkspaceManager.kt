package com.justnels.agenticdroid.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages development workspaces and project metadata.
 */
class WorkspaceManager(private val rootDir: File) {
    companion object {
        private const val MAX_TREE_ENTRIES = 50_000
        private const val MAX_TREE_DEPTH = 50
        private const val MAX_SEARCH_FILE_BYTES = 5L * 1024L * 1024L
        private const val MAX_SEARCH_RESULTS = 10_000
        private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "build", "node_modules")
    }
    private val canonicalRoot: File
        get() = rootDir.also(File::mkdirs).canonicalFile

    private fun resolveProject(name: String): File? {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name) return null
        val project = File(canonicalRoot, name).canonicalFile
        return project.takeIf { it.parentFile == canonicalRoot }
    }

    fun projectPath(name: String): String? = resolveProject(name)?.absolutePath

    /**
     * Lists all projects in the workspace root.
     */
    fun listProjects(): List<Project> {
        return canonicalRoot.listFiles { file -> file.isDirectory && !Files.isSymbolicLink(file.toPath()) }?.map {
            Project(it.name, it.absolutePath) 
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    /**
     * Creates a new project directory.
     */
    fun createProject(name: String): Boolean {
        val projectDir = resolveProject(name) ?: return false
        return projectDir.mkdirs()
    }

    fun deleteProject(project: Project): Boolean {
        val expected = resolveProject(project.name) ?: return false
        val actual = File(project.path).canonicalFile
        if (actual != expected || actual.parentFile != canonicalRoot) return false
        return actual.deleteRecursively()
    }

    /**
     * Gets the file tree for a specific project.
     */
    fun getFileTree(project: Project): List<FileNode> {
        val root = validatedProject(project) ?: return emptyList()
        var count = 0
        fun build(file: File, depth: Int): List<FileNode> {
            if (depth > MAX_TREE_DEPTH) return emptyList()
            return file.listFiles()?.asSequence()
                ?.filterNot { Files.isSymbolicLink(it.toPath()) }
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?.mapNotNull { child ->
                    count++
                    if (count > MAX_TREE_ENTRIES) return@mapNotNull null
                    FileNode(
                        name = child.name,
                        path = child.absolutePath,
                        isDirectory = child.isDirectory,
                        children = if (child.isDirectory) build(child, depth + 1) else emptyList()
                    )
                }?.toList() ?: emptyList()
        }
        return build(root, 0)
    }

    private fun validatedProject(project: Project): File? {
        val expected = resolveProject(project.name) ?: return null
        val actual = runCatching { File(project.path).canonicalFile }.getOrNull() ?: return null
        return actual.takeIf { it == expected && it.isDirectory }
    }

    fun resolveInsideProject(project: Project, path: String): File? {
        val projectRoot = validatedProject(project) ?: return null
        val candidate = runCatching {
            val input = File(path)
            (if (input.isAbsolute) input else File(projectRoot, path)).canonicalFile
        }.getOrNull() ?: return null
        val prefix = projectRoot.path + File.separator
        return candidate.takeIf { it == projectRoot || it.path.startsWith(prefix) }
    }

    fun createFile(project: Project, relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        val file = resolveInsideProject(project, relativePath) ?: return false
        if (file == validatedProject(project) || file.exists()) return false
        file.parentFile?.mkdirs()
        return file.createNewFile()
    }

    fun safeSibling(project: Project, oldPath: String, newName: String): File? {
        if (newName.isBlank() || newName == "." || newName == ".." || '/' in newName || '\\' in newName) return null
        val old = resolveInsideProject(project, oldPath) ?: return null
        return resolveInsideProject(project, File(old.parentFile, newName).path)
    }

    fun readTextFile(project: Project, path: String): String {
        val file = resolveInsideProject(project, path) ?: throw IOException("File is outside the selected project")
        if (!isSearchableFile(file)) throw IOException("File is binary, unreadable, or larger than 5 MiB")
        return file.readText()
    }

    fun writeTextFile(project: Project, path: String, content: String) {
        val file = resolveInsideProject(project, path) ?: throw IOException("File is outside the selected project")
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) throw IOException("File is not a regular project file")
        atomicWrite(file, content)
    }
    /**
     * Searches for a string in all files within the workspace.
     */
    fun searchInFiles(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResult>()
        canonicalRoot.walkTopDown()
            .onEnter { directory ->
                directory == canonicalRoot ||
                    (!Files.isSymbolicLink(directory.toPath()) && directory.name !in EXCLUDED_DIRECTORIES && !directory.name.startsWith("."))
            }
            .forEach { file ->
            if (results.size >= MAX_SEARCH_RESULTS) return results
            if (isSearchableFile(file)) {
                try {
                    file.bufferedReader().useLines { lines -> lines.forEachIndexed { index, line ->
                        if (line.contains(query, ignoreCase = true)) {
                            results.add(SearchResult(file.absolutePath, index + 1, line.trim()))
                            if (results.size >= MAX_SEARCH_RESULTS) return@forEachIndexed
                        }
                    } }
                } catch (_: IOException) { /* unreadable files are excluded from results */ }
            }
        }
        return results
    }

    /**
     * Replaces all occurrences of a string with another in all files.
     */
    fun replaceInFiles(query: String, replacement: String): Int {
        if (query.isEmpty()) return 0
        var replacementCount = 0
        canonicalRoot.walkTopDown()
            .onEnter { directory ->
                directory == canonicalRoot ||
                    (!Files.isSymbolicLink(directory.toPath()) && directory.name !in EXCLUDED_DIRECTORIES && !directory.name.startsWith("."))
            }
            .forEach { file ->
            if (isSearchableFile(file)) {
                try {
                    val content = file.readText()
                    val count = Regex(Regex.escape(query)).findAll(content).count()
                    if (count > 0) {
                        val newContent = content.replace(query, replacement)
                        atomicWrite(file, newContent)
                        replacementCount += count
                    }
                } catch (_: IOException) { /* unreadable files are excluded from replacement */ }
            }
        }
        return replacementCount
    }

    private fun isSearchableFile(file: File): Boolean {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.name.startsWith(".") ||
            file.length() > MAX_SEARCH_FILE_BYTES) return false
        return runCatching {
            file.inputStream().buffered().use { input ->
                val sample = ByteArray(4096)
                val read = input.read(sample)
                read <= 0 || sample.take(read).none { it == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    private fun atomicWrite(file: File, content: String) {
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temp.writeText(content)
            runCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
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
