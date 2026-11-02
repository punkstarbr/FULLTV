package app.opentv

import app.opentv.data.parser.YouTubeLiveSearchUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLiveSearchUrlTest {
    @Test
    fun addsLiveFilterAndKeepsSearchTerm() {
        assertEquals(
            "https://www.youtube.com/results?search_query=fazenda+imagem&sp=EgJAAQ%253D%253D",
            YouTubeLiveSearchUrl.normalize(
                "https://www.youtube.com/results?search_query=fazenda+imagem",
            ),
        )
    }

    @Test
    fun preservesTheSearchFilterAndOrderRequestedByThePlaylist() {
        assertEquals(
            "https://www.youtube.com/results?search_query=live&sp=CAMSAkAB",
            YouTubeLiveSearchUrl.normalize(
                "https://www.youtube.com/results?search_query=live&sp=CAMSAkAB",
            ),
        )
    }

    @Test
    fun rejectsNonSearchPagesAndLookalikeHosts() {
        assertNull(YouTubeLiveSearchUrl.normalize("https://www.youtube.com/@example/live"))
        assertNull(YouTubeLiveSearchUrl.normalize("https://www.youtube.com/results?sp=EgJAAQ%253D%253D"))
        assertNull(YouTubeLiveSearchUrl.normalize("https://youtube.com.example/results?search_query=live"))
        assertFalse(YouTubeLiveSearchUrl.isSearchUrl("not a URL"))
    }

    @Test
    fun acceptsTheUserProvidedLiveSearchUrl() {
        assertTrue(
            YouTubeLiveSearchUrl.isSearchUrl(
                "https://www.youtube.com/results?search_query=A+Fazenda+ao+vivo&sp=CAMSAkAB",
            ),
        )
    }

    @Test
    fun rejectsAmbiguousDuplicateSearchOrFilterParameters() {
        assertNull(
            YouTubeLiveSearchUrl.normalize(
                "https://www.youtube.com/results?search_query=one&search_query=two",
            ),
        )
        assertNull(
            YouTubeLiveSearchUrl.normalize(
                "https://www.youtube.com/results?search_query=live&sp=CAMSAkAB&sp=other",
            ),
        )
    }
}
