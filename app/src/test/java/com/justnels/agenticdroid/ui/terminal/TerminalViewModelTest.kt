package com.justnels.agenticdroid.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalViewModelTest {

    @Test
    fun extractUrlsFindsStandardHttpUrl() {
        val transcript = "Please visit https://antigravity.google.com/login to authenticate."
        val urls = TerminalViewModel.extractUrls(transcript)
        assertEquals(1, urls.size)
        assertEquals("https://antigravity.google.com/login", urls[0])
    }

    @Test
    fun extractUrlsFindsGoogleOAuthUrlWithQueryParams() {
        val transcript = "To log in to Antigravity CLI, open the following URL in your browser:\n" +
            "https://accounts.google.com/o/oauth2/auth?response_type=code&client_id=123456789.apps.googleusercontent.com&redirect_uri=http%3A%2F%2Flocalhost%3A8085%2Foauth2callback&scope=openid\n" +
            "Enter the authorization code:"
        val urls = TerminalViewModel.extractUrls(transcript)
        assertTrue(urls.isNotEmpty())
        val oauth = urls.first { it.startsWith("https://accounts.google.com/o/oauth2/auth") }
        assertEquals("https://accounts.google.com/o/oauth2/auth?response_type=code&client_id=123456789.apps.googleusercontent.com&redirect_uri=http%3A%2F%2Flocalhost%3A8085%2Foauth2callback&scope=openid", oauth)
    }

    @Test
    fun extractUrlsStripsAnsiColorSequences() {
        val transcript = "Auth link: \u001B[34;4mhttps://github.com/login/oauth/authorize?client_id=xyz\u001B[0m\nReady"
        val urls = TerminalViewModel.extractUrls(transcript)
        assertEquals(1, urls.size)
        assertEquals("https://github.com/login/oauth/authorize?client_id=xyz", urls[0])
    }

    @Test
    fun extractUrlsUnwrapsWrappedTerminalLines() {
        val line1 = "https://accounts.google.com/o/oauth2/auth?client_id=123456789.apps.googleusercont"
        val line2 = "ent.com&redirect_uri=http%3A%2F%2Flocalhost%3A8085%2Fcallback"
        val transcript = line1 + "\n" + line2 + "\nDone"
        val urls = TerminalViewModel.extractUrls(transcript)
        assertTrue(urls.isNotEmpty())
        val fullUrl = urls.first { it.startsWith("https://accounts.google.com") }
        assertEquals("https://accounts.google.com/o/oauth2/auth?client_id=123456789.apps.googleusercontent.com&redirect_uri=http%3A%2F%2Flocalhost%3A8085%2Fcallback", fullUrl)
    }

    @Test
    fun extractUrlsUnwrapsNarrowScreenIndentedOAuthUrl() {
        val transcript = "  Visit the following URL to authenticate:\n" +
            "  https://accounts.google.com/o/oauth2/auth?\n" +
            "  client_id=123456789-abcdefg.apps.googleus\n" +
            "  ercontent.com&redirect_uri=http%3A%2F%2Flo\n" +
            "  calhost%3A8085%2Foauth2callback&response_t\n" +
            "  ype=code&scope=openid%20email%20profile\n" +
            "\n" +
            "  Enter authorization code:"
        val urls = TerminalViewModel.extractUrls(transcript)
        assertTrue(urls.isNotEmpty())
        val url = urls.first()
        assertTrue("Extracted URL should not contain any spaces", !url.contains(" "))
        assertEquals("https://accounts.google.com/o/oauth2/auth?client_id=123456789-abcdefg.apps.googleusercontent.com&redirect_uri=http%3A%2F%2Flocalhost%3A8085%2Foauth2callback&response_type=code&scope=openid%20email%20profile", url)
    }

    @Test
    fun extractUrlsHandlesEmptyAndBlankText() {
        assertTrue(TerminalViewModel.extractUrls(null).isEmpty())
        assertTrue(TerminalViewModel.extractUrls("").isEmpty())
        assertTrue(TerminalViewModel.extractUrls("No urls here at all").isEmpty())
    }
}

