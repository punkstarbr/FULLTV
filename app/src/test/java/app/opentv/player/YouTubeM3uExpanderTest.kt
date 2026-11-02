/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.data.parser.M3uParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YouTubeM3uExpanderTest {
    @Test
    fun expandsOnlyTheFirstFiveLivesIntoIndependentWatchableAndRecordableChannels() {
        val playlistEntry = M3uParser.parse(
            """#EXTM3U
#EXTINF:-1 tvg-name="A Fazenda ao Vivo" group-title="Reality Shows",A Fazenda 24h
https://www.youtube.com/results?search_query=A+Fazenda+ao+vivo&sp=CAMSAkAB""",
            sourceId = 7,
        ).channels.single()
        val results = (1..7).map { index ->
            YouTubeLiveVideo(
                videoId = "abcde00000$index",
                title = "Live $index",
                channelName = "Canal $index",
                thumbnailUrl = null,
            )
        }

        val channels = YouTubeM3uExpander.expand(
            playlistEntry = playlistEntry,
            videos = results,
            firstSortIndex = 12,
            isSafeThumbnail = { false },
        )

        assertThat(channels).hasSize(5)
        assertThat(channels.map { it.streamUrl }).containsExactlyElementsIn(
            (1..5).map { "https://www.youtube.com/watch?v=abcde00000$it" },
        ).inOrder()
        assertThat(channels.map { YouTubeChannelUrl.asLiveUrlOrNull(it.streamUrl) })
            .containsExactlyElementsIn(channels.map { it.streamUrl }).inOrder()
        assertThat(channels.map { it.streamId }).containsExactlyElementsIn(
            (1..5).map { "yt-live:abcde00000$it" },
        ).inOrder()
        assertThat(channels.map { it.sortIndex }).containsExactly(12, 13, 14, 15, 16).inOrder()
        assertThat(channels.all { it.categoryId == "Reality Shows" && it.number == null }).isTrue()
        assertThat(channels.map { it.groupKey }.distinct()).hasSize(5)
    }
}
