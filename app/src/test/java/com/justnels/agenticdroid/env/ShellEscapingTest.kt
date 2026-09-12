package com.justnels.agenticdroid.env

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellEscapingTest {
    @Test
    fun quotesWhitespaceMetacharactersAndSingleQuotes() {
        assertEquals("'plain value; echo nope'", ShellEscaping.quote("plain value; echo nope"))
        assertEquals("'it'\\''s safe'", ShellEscaping.quote("it's safe"))
        assertEquals("''", ShellEscaping.quote(""))
    }
}
