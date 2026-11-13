package app.opentv.ui.onboarding

import app.opentv.data.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFormPolicyTest {
    @Test
    fun xtreamRequiresAddressUsernameAndPassword() {
        assertEquals(
            setOf(
                ProviderFormIssue.URL_REQUIRED,
                ProviderFormIssue.USERNAME_REQUIRED,
                ProviderFormIssue.PASSWORD_REQUIRED,
            ),
            ProviderFormPolicy.issues(SourceKind.XTREAM, "", "", "", "", false),
        )
        assertTrue(
            ProviderFormPolicy.issues(SourceKind.XTREAM, "https://host.test", "user", "secret", "", false).isEmpty(),
        )
    }

    @Test
    fun m3uRequiresOnlyItsUrl() {
        assertEquals(
            setOf(ProviderFormIssue.URL_REQUIRED),
            ProviderFormPolicy.issues(SourceKind.M3U, "", "", "", "", false),
        )
        assertTrue(
            ProviderFormPolicy.issues(SourceKind.M3U, "https://host.test/list.m3u", "", "", "", false).isEmpty(),
        )
    }

    @Test
    fun stalkerRequiresPortalAndMac() {
        assertEquals(
            setOf(ProviderFormIssue.URL_REQUIRED, ProviderFormIssue.MAC_REQUIRED),
            ProviderFormPolicy.issues(SourceKind.STALKER, "", "", "", "", false),
        )
        assertTrue(
            ProviderFormPolicy.issues(SourceKind.STALKER, "https://portal.test", "", "", "AA:BB:CC:DD:EE:FF", false).isEmpty(),
        )
    }

    @Test
    fun youtubeSearchExplainsInvalidNonblankUrl() {
        assertEquals(
            setOf(ProviderFormIssue.URL_REQUIRED),
            ProviderFormPolicy.issues(SourceKind.YOUTUBE_LIVE_SEARCH, "", "", "", "", false),
        )
        assertEquals(
            setOf(ProviderFormIssue.YOUTUBE_SEARCH_INVALID),
            ProviderFormPolicy.issues(SourceKind.YOUTUBE_LIVE_SEARCH, "https://youtube.com/watch?v=x", "", "", "", false),
        )
        assertTrue(
            ProviderFormPolicy.issues(SourceKind.YOUTUBE_LIVE_SEARCH, "https://www.youtube.com/results?search_query=live", "", "", "", true).isEmpty(),
        )
    }
}
