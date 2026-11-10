package app.opentv.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryYoutubeSearchLimitTest {
    @Test
    fun acceptsTheEightSearchEntriesInTheUserPlaylist() {
        assertFalse(exceedsYoutubeSearchEntryLimit(8))
    }

    @Test
    fun acceptsTenEntriesButKeepsTheSafetyLimitAtEleven() {
        assertFalse(exceedsYoutubeSearchEntryLimit(10))
        assertTrue(exceedsYoutubeSearchEntryLimit(11))
    }
}
