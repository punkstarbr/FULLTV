/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.ProgrammeDao
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.parser.ChannelNameNormalizer
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EpgProgrammeChannelFilterTest {
    @Test
    fun `retains direct playlist IDs and unambiguous normalized name matches only`() {
        val channels = listOf(
            channel("direct", "Local Extra", epgId = "m3u-local-id"),
            channel("by-name", "Sports Today", epgId = null).copy(matchedEpgId = "stale-global-match"),
        )
        val aliases = listOf(
            alias("unrelated", "Cartoons"),
            alias("sports-today.xml", "Sports Today HD"),
        )

        val retained = EpgProgrammeChannelFilter.matchingEpgIds(channels, aliases)

        assertThat(retained).containsExactly("m3u-local-id", "sports-today.xml").inOrder()
        assertThat(retained).doesNotContain("unrelated")
        assertThat(retained).doesNotContain("stale-global-match")
    }

    @Test
    fun `refuses ambiguous name-only matches`() {
        val channels = listOf(channel("news", "News", epgId = null))
        val aliases = listOf(
            alias("news-london", "News London"),
            alias("news-wales", "News Wales"),
        )

        assertThat(EpgProgrammeChannelFilter.matchingEpgIds(channels, aliases)).isEmpty()
    }

    @Test
    fun `deletes unrelated programme IDs in SQLite-safe batches`() = runBlocking {
        val feedId = 91L
        val staleIds = (0 until 1_201).map { "unrelated-$it" }
        val storedIds = (staleIds + listOf("m3u-id", "matched-name-id")).toMutableSet()
        val batchSizes = mutableListOf<Int>()
        val programmeDao = fakeProgrammeDao(storedIds, batchSizes)

        val keptRows = EpgProgrammeChannelFilter.retainOnly(
            programmeDao = programmeDao,
            feedId = feedId,
            retainedEpgIds = setOf("m3u-id", "matched-name-id"),
        )

        assertThat(keptRows).isEqualTo(2)
        assertThat(storedIds).containsExactly("m3u-id", "matched-name-id")
        assertThat(batchSizes).containsExactly(500, 500, 201).inOrder()
        Unit
    }

    private fun fakeProgrammeDao(
        storedIds: MutableSet<String>,
        batchSizes: MutableList<Int>,
    ): ProgrammeDao = Proxy.newProxyInstance(
        ProgrammeDao::class.java.classLoader,
        arrayOf(ProgrammeDao::class.java),
        InvocationHandler { _, method, rawArguments ->
            val arguments = rawArguments ?: emptyArray()
            when (method.name) {
                "channelIdsForFeed" -> storedIds.toList()
                "deleteChannelsForFeed" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = arguments[1] as List<String>
                    batchSizes += ids.size
                    storedIds.removeAll(ids.toSet())
                    Unit
                }
                "countForFeed" -> storedIds.size
                "toString" -> "FakeProgrammeDao"
                else -> error("Unexpected DAO call: ${method.name}")
            }
        },
    ) as ProgrammeDao

    private fun channel(streamId: String, name: String, epgId: String?) = Channel(
        sourceId = 1,
        streamId = streamId,
        name = name,
        displayName = name,
        groupKey = ChannelNameNormalizer.normalize(name).groupKey,
        categoryId = null,
        logoUrl = null,
        epgChannelId = epgId,
        number = null,
        streamUrl = "https://media.example/$streamId.m3u8",
    )

    private fun alias(epgId: String, name: String) = EpgChannelAlias(
        feedId = 9,
        epgId = epgId,
        displayName = name,
        normalizedKey = ChannelNameNormalizer.normalize(name).groupKey,
    )
}
