package com.justnels.agenticdroid.git

data class DiffFile(
    val oldPath: String,
    val newPath: String,
    val hunks: List<DiffHunk>,
    val isNew: Boolean = false,
    val isDeleted: Boolean = false,
    val isRenamed: Boolean = false
) {
    val fileName: String get() = newPath.substringAfterLast('/').ifEmpty { newPath }
    val displayPath: String get() = if (isRenamed) "$oldPath -> $newPath" else newPath
    val additions: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLine.Type.ADDED } }
    val deletions: Int get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLine.Type.REMOVED } }
}

data class DiffHunk(
    val header: String,
    val lines: List<DiffLine>
)

data class DiffLine(
    val type: Type,
    val content: String,
    val oldLineNo: Int?,
    val newLineNo: Int?
) {
    val text: String get() = content

    enum class Type { 
        ADDED, REMOVED, NEUTRAL;

        companion object {
            val ADD = ADDED
            val REMOVE = REMOVED
            val CONTEXT = NEUTRAL
        }
    }
}

typealias DiffLineType = DiffLine.Type

fun parseUnifiedDiff(rawDiff: String): List<DiffFile> = DiffParser.parse(rawDiff)

object DiffParser {
    fun parse(rawDiff: String): List<DiffFile> {
        if (rawDiff.isBlank()) return emptyList()
        val files = mutableListOf<DiffFile>()
        var currentOldPath: String? = null
        var currentNewPath: String? = null
        var isNewFile = false
        var isDeletedFile = false
        var isRenamedFile = false
        var currentHunks = mutableListOf<DiffHunk>()
        var currentHunkHeader: String? = null
        var currentLines = mutableListOf<DiffLine>()
        
        var oldLineStart = 0
        var newLineStart = 0
        var oldLineCount = 0
        var newLineCount = 0
        
        fun finalizeFile() {
            if (currentOldPath != null || currentNewPath != null) {
                if (currentHunkHeader != null) {
                    currentHunks.add(DiffHunk(currentHunkHeader!!, currentLines.toList()))
                }
                val old = currentOldPath ?: currentNewPath ?: "unknown"
                val new = currentNewPath ?: currentOldPath ?: "unknown"
                files.add(
                    DiffFile(
                        oldPath = old,
                        newPath = new,
                        hunks = currentHunks.toList(),
                        isNew = isNewFile,
                        isDeleted = isDeletedFile,
                        isRenamed = isRenamedFile || (old != new && !isNewFile && !isDeletedFile && old != "unknown")
                    )
                )
            }
        }

        val lines = rawDiff.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            
            when {
                line.startsWith("diff --git") -> {
                    finalizeFile()
                    
                    // Start new file
                    currentHunks = mutableListOf()
                    currentHunkHeader = null
                    currentLines = mutableListOf()
                    isNewFile = false
                    isDeletedFile = false
                    isRenamedFile = false
                    
                    // Parse paths from diff --git a/old b/new
                    val parts = line.split(" ")
                    val oldPath = parts.getOrNull(2)?.removePrefix("a/") ?: "unknown"
                    val newPath = parts.getOrNull(3)?.removePrefix("b/") ?: oldPath
                    currentOldPath = oldPath
                    currentNewPath = newPath
                }
                line.startsWith("new file mode") -> {
                    isNewFile = true
                }
                line.startsWith("deleted file mode") -> {
                    isDeletedFile = true
                }
                line.startsWith("rename from ") -> {
                    isRenamedFile = true
                    currentOldPath = line.removePrefix("rename from ").trim()
                }
                line.startsWith("rename to ") -> {
                    isRenamedFile = true
                    currentNewPath = line.removePrefix("rename to ").trim()
                }
                line.startsWith("--- ") -> {
                    val path = line.removePrefix("--- ").trim()
                    if (path == "/dev/null") {
                        isNewFile = true
                    }
                }
                line.startsWith("+++ ") -> {
                    val path = line.removePrefix("+++ ").trim()
                    if (path == "/dev/null") {
                        isDeletedFile = true
                    }
                }
                line.startsWith("@@") -> {
                    // Finalize previous hunk
                    if (currentHunkHeader != null) {
                        currentHunks.add(DiffHunk(currentHunkHeader, currentLines.toList()))
                    }
                    
                    // Parse @@ -1,3 +1,4 @@
                    currentHunkHeader = line
                    currentLines = mutableListOf()
                    
                    val match = Regex("""@@ -(\d+),?(\d*) \+(\d+),?(\d*) @@""").find(line)
                    if (match != null) {
                        oldLineStart = match.groupValues[1].toIntOrNull() ?: 1
                        oldLineCount = 0
                        newLineStart = match.groupValues[3].toIntOrNull() ?: 1
                        newLineCount = 0
                    }
                }
                currentHunkHeader != null -> {
                    when {
                        line.startsWith("+") && !line.startsWith("+++") -> {
                            currentLines.add(DiffLine(DiffLine.Type.ADDED, line.substring(1), null, newLineStart + newLineCount))
                            newLineCount++
                        }
                        line.startsWith("-") && !line.startsWith("---") -> {
                            currentLines.add(DiffLine(DiffLine.Type.REMOVED, line.substring(1), oldLineStart + oldLineCount, null))
                            oldLineCount++
                        }
                        line.startsWith(" ") || line.isEmpty() -> {
                            currentLines.add(DiffLine(DiffLine.Type.NEUTRAL, if (line.isNotEmpty()) line.substring(1) else "", oldLineStart + oldLineCount, newLineStart + newLineCount))
                            oldLineCount++
                            newLineCount++
                        }
                        // Ignore other headers
                    }
                }
            }
            i++
        }
        
        finalizeFile()
        return files
    }
}

