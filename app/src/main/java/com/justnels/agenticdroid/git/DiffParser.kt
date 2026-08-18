package com.justnels.agenticdroid.git

enum class DiffLineType { CONTEXT, ADD, REMOVE }

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLineNo: Int?,
    val newLineNo: Int?
)

data class DiffHunk(val header: String, val lines: List<DiffLine>)

data class FileDiff(
    val oldPath: String,
    val newPath: String,
    val isNew: Boolean,
    val isDeleted: Boolean,
    val isRenamed: Boolean,
    val hunks: List<DiffHunk>
) {
    val displayPath: String get() = if (isRenamed) "$oldPath -> $newPath" else newPath
    val additions: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.type == DiffLineType.ADD } }
    val deletions: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.type == DiffLineType.REMOVE } }
}

private val FILE_HEADER = Regex("^diff --git a/(.*) b/(.*)$")
private val HUNK_HEADER = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$")

/** Parses `git diff`'s unified output into per-file, per-hunk structured lines for review UI. */
fun parseUnifiedDiff(diffText: String): List<FileDiff> {
    if (diffText.isBlank()) return emptyList()
    val lines = diffText.lines()
    val files = mutableListOf<FileDiff>()

    var i = 0
    while (i < lines.size) {
        val headerMatch = FILE_HEADER.find(lines[i])
        if (headerMatch == null) {
            i++
            continue
        }
        var oldPath = headerMatch.groupValues[1]
        var newPath = headerMatch.groupValues[2]
        i++

        var isNew = false
        var isDeleted = false
        var isRenamed = false
        while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git")) {
            val line = lines[i]
            when {
                line.startsWith("new file mode") -> isNew = true
                line.startsWith("deleted file mode") -> isDeleted = true
                line.startsWith("rename from ") -> { isRenamed = true; oldPath = line.removePrefix("rename from ") }
                line.startsWith("rename to ") -> { isRenamed = true; newPath = line.removePrefix("rename to ") }
                line.startsWith("--- ") || line.startsWith("+++ ") -> { /* redundant with a/ b/ header */ }
            }
            i++
        }

        val hunks = mutableListOf<DiffHunk>()
        while (i < lines.size && lines[i].startsWith("@@")) {
            val hunkMatch = HUNK_HEADER.find(lines[i]) ?: break
            var oldLine = hunkMatch.groupValues[1].toInt()
            var newLine = hunkMatch.groupValues[2].toInt()
            val header = lines[i]
            i++
            val hunkLines = mutableListOf<DiffLine>()
            while (i < lines.size && lines[i].startsWith("diff --git").not() && lines[i].startsWith("@@").not()) {
                val raw = lines[i]
                if (raw.isEmpty()) {
                    // A blank line inside a hunk is a context line with empty content.
                    hunkLines += DiffLine(DiffLineType.CONTEXT, "", oldLine, newLine)
                    oldLine++; newLine++
                    i++
                    continue
                }
                when (raw[0]) {
                    '+' -> {
                        hunkLines += DiffLine(DiffLineType.ADD, raw.substring(1), null, newLine)
                        newLine++
                    }
                    '-' -> {
                        hunkLines += DiffLine(DiffLineType.REMOVE, raw.substring(1), oldLine, null)
                        oldLine++
                    }
                    ' ' -> {
                        hunkLines += DiffLine(DiffLineType.CONTEXT, raw.substring(1), oldLine, newLine)
                        oldLine++; newLine++
                    }
                    else -> break
                }
                i++
            }
            hunks += DiffHunk(header, hunkLines)
        }

        files += FileDiff(oldPath, newPath, isNew, isDeleted, isRenamed, hunks)
    }

    return files
}
