/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.data.model.Channel

/** Converts a YouTube /results entry embedded in an M3U into independently playable live channels. */
internal object YouTubeM3uExpander {
    private const val MAX_RESULTS = 5
    private const val MAX_TEXT_CHARS = 256
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    fun expand(
        playlistEntry: Channel,
        videos: List<YouTubeLiveVideo>,
        firstSortIndex: Int,
        isSafeThumbnail: (String) -> Boolean,
    ): List<Channel> = videos.asSequence()
        .take(MAX_RESULTS)
        .mapIndexedNotNull { index, video ->
            if (!videoIdPattern.matches(video.videoId)) return@mapIndexedNotNull null
            val title = video.title.trim().take(MAX_TEXT_CHARS)
            if (title.isBlank()) return@mapIndexedNotNull null
            val broadcaster = video.channelName?.trim()?.takeIf(String::isNotBlank)
                ?.take(MAX_TEXT_CHARS)
            val streamId = "yt-live:${video.videoId}"
            val displayName = listOfNotNull(
                playlistEntry.displayName.takeIf(String::isNotBlank),
                broadcaster,
                title.takeIf { it != broadcaster },
            ).distinct().joinToString(" · ").take(MAX_TEXT_CHARS * 2)
            val thumbnail = video.thumbnailUrl?.takeIf(isSafeThumbnail)

            playlistEntry.copy(
                streamId = streamId,
                name = broadcaster ?: title,
                displayName = displayName.ifBlank { title },
                groupKey = streamId,
                qualityRank = 0,
                qualityLabel = "",
                logoUrl = thumbnail ?: playlistEntry.logoUrl,
                epgChannelId = null,
                number = null,
                streamUrl = video.watchUrl,
                cmd = null,
                sortIndex = firstSortIndex + index,
            )
        }
        .toList()
}
