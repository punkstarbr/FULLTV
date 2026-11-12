package app.opentv.data.repo

import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgStartupRefreshPolicyTest {
    @Test
    fun emptyGuideForcesRefreshEvenWhenFeedStampIsRecent() {
        assertTrue(
            EpgStartupRefreshPolicy.shouldSyncFeed(
                lastSyncMillis = 9_900L,
                nowUtcMillis = 10_000L,
                refreshIntervalMillis = 60_000L,
                guideSnapshotEmpty = true,
            ),
        )
    }

    @Test
    fun populatedGuideRefreshesOnlyAfterConfiguredAge() {
        assertFalse(
            EpgStartupRefreshPolicy.shouldSyncFeed(
                lastSyncMillis = 9_900L,
                nowUtcMillis = 10_000L,
                refreshIntervalMillis = 60_000L,
                guideSnapshotEmpty = false,
            ),
        )
        assertTrue(
            EpgStartupRefreshPolicy.shouldSyncFeed(
                lastSyncMillis = 1_000L,
                nowUtcMillis = 10_000L,
                refreshIntervalMillis = 8_000L,
                guideSnapshotEmpty = false,
            ),
        )
    }

    @Test
    fun neverSyncedFeedIsDueAndFutureTimestampIsNotTreatedAsExpired() {
        assertTrue(
            EpgStartupRefreshPolicy.shouldSyncFeed(
                lastSyncMillis = 0L,
                nowUtcMillis = 10_000L,
                refreshIntervalMillis = 60_000L,
                guideSnapshotEmpty = false,
            ),
        )
        assertFalse(
            EpgStartupRefreshPolicy.shouldSyncFeed(
                lastSyncMillis = 11_000L,
                nowUtcMillis = 10_000L,
                refreshIntervalMillis = 60_000L,
                guideSnapshotEmpty = false,
            ),
        )
    }

    @Test
    fun startupRefreshSkipsM3uProviderWithoutGuideButKeepsConfiguredSources() {
        val m3uWithoutGuide = Source(
            id = 1,
            name = "Playlist",
            kind = SourceKind.M3U,
            url = "https://example.invalid/list.m3u",
        )
        val m3uWithGuide = m3uWithoutGuide.copy(epgUrl = "https://example.invalid/guide.xml")
        val providerFeed = EpgFeed(name = "Playlist guide", providerSourceId = 1)
        val directFeed = EpgFeed(name = "Manual guide", url = "https://example.invalid/guide.xml")

        assertFalse(EpgStartupRefreshPolicy.hasUsableGuideSource(providerFeed, m3uWithoutGuide))
        assertTrue(EpgStartupRefreshPolicy.hasUsableGuideSource(providerFeed, m3uWithGuide))
        assertTrue(EpgStartupRefreshPolicy.hasUsableGuideSource(directFeed, null))
        assertFalse(EpgStartupRefreshPolicy.hasUsableGuideSource(providerFeed, null))
    }
}
