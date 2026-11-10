/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.ProgrammeDao
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias

/** Keeps programme rows whose XMLTV channel is identified by the current playlist catalogue. */
internal object EpgProgrammeChannelFilter {
    private const val DELETE_CHUNK_SIZE = 500

    /** Uses direct playlist IDs first and the existing ambiguity-safe name matcher as a fallback. */
    fun matchingEpgIds(
        playlistChannels: List<Channel>,
        aliases: Iterable<EpgChannelAlias>,
    ): Set<String> {
        val index = EpgMatcher.buildIndex(
            aliases.asSequence()
                .filter { it.epgId.isNotBlank() }
                .map { it.epgId to it.displayName }
                .asIterable(),
        )
        val retained = LinkedHashSet<String>()
        for (channel in playlistChannels) {
            listOfNotNull(channel.epgOverrideId, channel.epgChannelId)
                .filter(String::isNotBlank)
                .forEach(retained::add)
            if (channel.groupKey.isNotBlank()) {
                index.match(channel.groupKey)?.let(retained::add)
            }
        }
        return retained
    }

    /** Removes unrelated channel IDs without exceeding SQLite's bound-variable limit. */
    suspend fun retainOnly(
        programmeDao: ProgrammeDao,
        feedId: Long,
        retainedEpgIds: Set<String>,
    ): Int {
        val unwantedIds = programmeDao.channelIdsForFeed(feedId)
            .filterNot(retainedEpgIds::contains)
        unwantedIds.chunked(DELETE_CHUNK_SIZE).forEach { batch ->
            programmeDao.deleteChannelsForFeed(feedId, batch)
        }
        return programmeDao.countForFeed(feedId)
    }
}
