package com.justnels.agenticdroid.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSecretsStoreTest {

    @Test
    fun namePatternAcceptsValidShellIdentifiersOnly() {
        assertTrue(ProjectSecretsStore.NAME_PATTERN.matches("ANTHROPIC_API_KEY"))
        assertTrue(ProjectSecretsStore.NAME_PATTERN.matches("_private"))
        assertTrue(ProjectSecretsStore.NAME_PATTERN.matches("a1"))
        assertFalse(ProjectSecretsStore.NAME_PATTERN.matches("1KEY"))
        assertFalse(ProjectSecretsStore.NAME_PATTERN.matches("KEY-WITH-DASH"))
        assertFalse(ProjectSecretsStore.NAME_PATTERN.matches("KEY WITH SPACE"))
        assertFalse(ProjectSecretsStore.NAME_PATTERN.matches(""))
    }

    @Test
    fun credentialKeyIsStableAndProjectSpecific() {
        val a = ProjectSecretsStore.credentialKeyFor("/workspace/foo")
        val b = ProjectSecretsStore.credentialKeyFor("/workspace/foo")
        val c = ProjectSecretsStore.credentialKeyFor("/workspace/bar")
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test
    fun exportPreludeQuotesValuesSoTheyCannotEscapeIntoShellSyntax() {
        val prelude = ProjectSecretsStore.buildExportPrelude(
            mapOf("API_KEY" to "it's a \$ecret; rm -rf /")
        )
        assertEquals(
            "export API_KEY='it'\\''s a \$ecret; rm -rf /'\n",
            prelude
        )
    }

    @Test
    fun exportPreludeSkipsAnyNameThatWouldNotBeAValidIdentifier() {
        // Defense in depth even though setSecret() already validates on write - a
        // malformed name here must never end up unquoted in shell text.
        val prelude = ProjectSecretsStore.buildExportPrelude(
            mapOf("BAD NAME; rm -rf /" to "x", "GOOD_NAME" to "y")
        )
        assertEquals("export GOOD_NAME='y'\n", prelude)
    }

    @Test
    fun emptySecretsProduceEmptyPrelude() {
        assertEquals("", ProjectSecretsStore.buildExportPrelude(emptyMap()))
    }
}
