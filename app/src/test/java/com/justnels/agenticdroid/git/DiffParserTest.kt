package com.justnels.agenticdroid.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffParserTest {

    @Test
    fun emptyDiffProducesNoFiles() {
        assertTrue(parseUnifiedDiff("").isEmpty())
        assertTrue(parseUnifiedDiff("   \n  ").isEmpty())
    }

    @Test
    fun parsesModifiedFileWithAddedAndRemovedLines() {
        val diff = """
            diff --git a/src/Foo.kt b/src/Foo.kt
            index abc123..def456 100644
            --- a/src/Foo.kt
            +++ b/src/Foo.kt
            @@ -1,3 +1,3 @@
             unchanged line
            -old line
            +new line
             trailing context
        """.trimIndent()

        val files = parseUnifiedDiff(diff)
        assertEquals(1, files.size)
        val file = files[0]
        assertEquals("src/Foo.kt", file.oldPath)
        assertEquals("src/Foo.kt", file.newPath)
        assertFalse(file.isNew)
        assertFalse(file.isDeleted)
        assertEquals(1, file.additions)
        assertEquals(1, file.deletions)

        val lines = file.hunks.single().lines
        assertEquals(DiffLineType.CONTEXT, lines[0].type)
        assertEquals(DiffLineType.REMOVE, lines[1].type)
        assertEquals("old line", lines[1].text)
        assertEquals(DiffLineType.ADD, lines[2].type)
        assertEquals("new line", lines[2].text)
        assertEquals(DiffLineType.CONTEXT, lines[3].type)
    }

    @Test
    fun parsesNewFileMarker() {
        val diff = """
            diff --git a/README.md b/README.md
            new file mode 100644
            index 0000000..abc123
            --- /dev/null
            +++ b/README.md
            @@ -0,0 +1,2 @@
            +line one
            +line two
        """.trimIndent()

        val file = parseUnifiedDiff(diff).single()
        assertTrue(file.isNew)
        assertEquals(2, file.additions)
        assertEquals(0, file.deletions)
    }

    @Test
    fun parsesDeletedFileMarker() {
        val diff = """
            diff --git a/old.txt b/old.txt
            deleted file mode 100644
            index abc123..0000000
            --- a/old.txt
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -gone
        """.trimIndent()

        val file = parseUnifiedDiff(diff).single()
        assertTrue(file.isDeleted)
        assertEquals(1, file.deletions)
    }

    @Test
    fun parsesMultipleFilesInOneDiff() {
        val diff = """
            diff --git a/a.txt b/a.txt
            index 1..2 100644
            --- a/a.txt
            +++ b/a.txt
            @@ -1,1 +1,1 @@
            -a
            +A
            diff --git a/b.txt b/b.txt
            index 3..4 100644
            --- a/b.txt
            +++ b/b.txt
            @@ -1,1 +1,1 @@
            -b
            +B
        """.trimIndent()

        val files = parseUnifiedDiff(diff)
        assertEquals(2, files.size)
        assertEquals("a.txt", files[0].newPath)
        assertEquals("b.txt", files[1].newPath)
    }

    @Test
    fun tracksRenamedFilePaths() {
        val diff = """
            diff --git a/old/name.kt b/new/name.kt
            similarity index 100%
            rename from old/name.kt
            rename to new/name.kt
        """.trimIndent()

        val file = parseUnifiedDiff(diff).single()
        assertTrue(file.isRenamed)
        assertEquals("old/name.kt", file.oldPath)
        assertEquals("new/name.kt", file.newPath)
        assertEquals("old/name.kt -> new/name.kt", file.displayPath)
    }
}
