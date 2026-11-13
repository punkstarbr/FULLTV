package app.opentv.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderClipboardTextTest {
    @Test
    fun trimsOuterWhitespaceForUrls() {
        assertEquals("https://example.test/list.m3u", ProviderClipboardText.normalize("  https://example.test/list.m3u\n", false))
    }

    @Test
    fun preservesCredentialSpacesButRemovesClipboardLineBreaks() {
        assertEquals("  pass word  ", ProviderClipboardText.normalize("\r\n  pass word  \n", true))
    }

    @Test
    fun emptyClipboardOrLineBreakOnlyDoesNotReplaceExistingValue() {
        assertNull(ProviderClipboardText.normalize(null, true))
        assertNull(ProviderClipboardText.normalize("\r\n", true))
        val current = "previous-value"
        assertEquals(current, ProviderClipboardText.normalize("\n", true) ?: current)
    }

    @Test
    fun subsequentPasteReplacesRatherThanAppends() {
        val current = "old username"
        val pasted = ProviderClipboardText.normalize("new username", true)
        assertEquals("new username", pasted ?: current)
    }
}
