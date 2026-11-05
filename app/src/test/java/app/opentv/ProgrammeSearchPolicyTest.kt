/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.ui.selectProgrammeSearchMatches
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgrammeSearchPolicyTest {

    @Test
    fun `matches every epg candidate and keeps visible quality variants`() {
        val channels = listOf(
            channel(
                id = 1,
                groupKey = "news",
                epgChannelId = "provider-news",
                matchedEpgId = "matched-news",
            ),
            channel(
                id = 2,
                groupKey = "news",
                epgChannelId = "variant-news",
                qualityRank = 2,
            ),
            channel(
                id = 3,
                groupKey = "news",
                epgChannelId = "hidden-news",
                hidden = true,
                qualityRank = 3,
            ),
        )

        val result = selectProgrammeSearchMatches(
            programmes = listOf(programme("matched-news", 10_000L, "News")),
            channels = channels,
            hiddenCategoryIds = emptySet(),
        )

        assertThat(result).hasSize(1)
        assertThat(result.single().variants.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `parental category and hidden channel matches are excluded`() {
        val hiddenCategory = channel(
            id = 10,
            groupKey = "adult",
            epgChannelId = "adult-news",
            categoryId = "adult-category",
        )
        val hiddenChannel = channel(
            id = 11,
            groupKey = "hidden",
            epgChannelId = "hidden-news",
            hidden = true,
        )

        val result = selectProgrammeSearchMatches(
            programmes = listOf(
                programme("adult-news", 10_000L, "Adult"),
                programme("hidden-news", 20_000L, "Hidden"),
            ),
            channels = listOf(hiddenCategory, hiddenChannel),
            hiddenCategoryIds = setOf("adult-category"),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `orders by start then title and applies a hard cap`() {
        val channel = channel(id = 20, groupKey = "sports", epgChannelId = "sports")
        val programmes = listOf(
            programme("sports", 30_000L, "Later"),
            programme("sports", 10_000L, "Zulu"),
            programme("sports", 10_000L, "Alpha"),
        )

        val result = selectProgrammeSearchMatches(
            programmes = programmes,
            channels = listOf(channel),
            hiddenCategoryIds = emptySet(),
            limit = 2,
        )

        assertThat(result).hasSize(2)
        assertThat(result.map { it.programme.title }).containsExactly("Alpha", "Zulu").inOrder()
    }

    @Test
    fun `never returns more than one hundred results even when caller asks for more`() {
        val channel = channel(id = 21, groupKey = "news", epgChannelId = "news")
        val result = selectProgrammeSearchMatches(
            programmes = List(150) { index -> programme("news", index.toLong(), "News $index") },
            channels = listOf(channel),
            hiddenCategoryIds = emptySet(),
            limit = 10_000,
        )

        assertThat(result).hasSize(100)
    }

    private fun channel(
        id: Long,
        groupKey: String,
        epgChannelId: String?,
        matchedEpgId: String? = null,
        categoryId: String? = "general",
        hidden: Boolean = false,
        qualityRank: Int = 1,
    ) = Channel(
        id = id,
        sourceId = 1,
        streamId = "stream-$id",
        name = "Channel $id",
        displayName = "Channel $id",
        groupKey = groupKey,
        qualityRank = qualityRank,
        qualityLabel = "Q$qualityRank",
        categoryId = categoryId,
        logoUrl = null,
        epgChannelId = epgChannelId,
        matchedEpgId = matchedEpgId,
        epgOverrideId = null,
        tvArchive = false,
        tvArchiveDays = 0,
        number = null,
        streamUrl = "https://example.test/$id",
        hidden = hidden,
    )

    private fun programme(epgChannelId: String, start: Long, title: String) = Programme(
        feedId = 1,
        epgChannelId = epgChannelId,
        startUtcMillis = start,
        endUtcMillis = start + 1_000L,
        title = title,
    )
}
