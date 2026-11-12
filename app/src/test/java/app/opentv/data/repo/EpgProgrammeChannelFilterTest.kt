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
    fun `exact name match keeps priority over a longer prefix candidate`() {
        val channels = listOf(channel("news", "News", epgId = null))
        val matcher = EpgProgrammeChannelFilter.Matcher(channels)

        matcher.addAlias("news-exact", "News")
        matcher.addAlias("news-world", "News World")

        assertThat(matcher.retainedEpgIds()).containsExactly("news-exact")
    }

    @Test
    fun `short unambiguous alias prefixes still match longer playlist names`() {
        val channels = listOf(channel("bbc", "BBC One Extra", epgId = null))
        val matcher = EpgProgrammeChannelFilter.Matcher(channels)

        matcher.addAlias("bbc-guide", "BBC One")

        assertThat(matcher.retainedEpgIds()).containsExactly("bbc-guide")
    }

    @Test
    fun `incremental matcher agrees with EpgMatcher for exact prefix and ambiguous aliases`() {
        val channels = listOf(
            channel("local", "Local Extra", epgId = "playlist-direct-id"),
            channel("news", "News", epgId = null),
            channel("news-world", "News World", epgId = null),
            channel("sports", "Sports Today", epgId = null),
            channel("bbc", "BBC One Extra", epgId = null),
        )
        val aliases = listOf(
            alias("news-a", "News"),
            alias("news-b", "News"),
            alias("world-id", "News World"),
            alias("sports-id", "Sports Today HD"),
            alias("bbc-id", "BBC One"),
            alias("unrelated-id", "Cartoons"),
        )
        val incremental = EpgProgrammeChannelFilter.Matcher(channels).also { matcher ->
            aliases.forEach { matcher.addAlias(it.epgId, it.displayName) }
        }.retainedEpgIds()
        val referenceIndex = EpgMatcher.buildIndex(
            aliases.flatMap { listOf(it.epgId to it.displayName, it.epgId to it.epgId) },
        )
        val expected = buildSet {
            channels.forEach { channel ->
                listOfNotNull(channel.epgOverrideId, channel.epgChannelId)
                    .filter(String::isNotBlank)
                    .forEach(::add)
                referenceIndex.match(channel.groupKey)?.let(::add)
            }
        }

        assertThat(incremental).containsExactlyElementsIn(expected)
    }

    @Test
    fun `ambiguous exact key remains ambiguous when fallback sees a unique shorter alias`() {
        val channels = listOf(channel("news", "News", epgId = null))
        val aliases = listOf(
            alias("news-a", "News"),
            alias("news-b", "News"),
            alias("new-id", "New"),
        )
        val matcher = EpgProgrammeChannelFilter.Matcher(channels).also { state ->
            aliases.forEach { state.addAlias(it.epgId, it.displayName) }
        }
        val reference = EpgMatcher.buildIndex(
            aliases.flatMap { listOf(it.epgId to it.displayName, it.epgId to it.epgId) },
        )

        assertThat(matcher.retainedEpgIds()).isEmpty()
        assertThat(reference.match(channels.single().groupKey)).isNull()
        assertThat(
            EpgProgrammeChannelFilter.matchingEpgIds(channels, aliases),
        ).isEmpty()
    }

    @Test
    fun `programme predicate filters unrelated channels and out-of-window rows before insert`() {
        val retained = setOf("playlist-channel")

        assertThat(
            EpgProgrammeChannelFilter.shouldKeepProgramme(
                epgChannelId = "playlist-channel",
                startUtcMillis = 100,
                endUtcMillis = 200,
                retainedEpgIds = retained,
                fromUtcMillis = 150,
                toUtcMillis = 250,
            ),
        ).isTrue()
        assertThat(
            EpgProgrammeChannelFilter.shouldKeepProgramme(
                epgChannelId = "xmltv-only-channel",
                startUtcMillis = 100,
                endUtcMillis = 200,
                retainedEpgIds = retained,
                fromUtcMillis = 150,
                toUtcMillis = 250,
            ),
        ).isFalse()
        assertThat(
            EpgProgrammeChannelFilter.shouldKeepProgramme(
                epgChannelId = "playlist-channel",
                startUtcMillis = 300,
                endUtcMillis = 400,
                retainedEpgIds = retained,
                fromUtcMillis = 150,
                toUtcMillis = 250,
            ),
        ).isFalse()
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
