/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Canonicalizes YouTube live channel/video pages accepted by the bundled Streamlink plugin. */
object YouTubeChannelUrl {
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    private val shortHosts = setOf("youtu.be", "www.youtu.be")
    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    /**
     * Returns Streamlink's canonical live route, or null for unrelated YouTube pages and other hosts.
     * A bare @handle becomes `/live`; watch/video URLs are accepted because search results point to them.
     * Streamlink itself rejects VOD playback, so only active live results resolve successfully.
     */
    fun asLiveUrlOrNull(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        if (uri.userInfo != null) return null

        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val segments = uri.path.orEmpty().split('/').filter(String::isNotEmpty)

        if (host in shortHosts) {
            val id = segments.singleOrNull()?.takeIf(videoId::matches) ?: return null
            return "https://www.youtube.com/watch?v=$id"
        }
        if (host !in youtubeHosts) return null

        if (segments.size == 1 && segments[0].startsWith('@')) {
            val handle = segments[0]
            if (handle.length < 2 || handle.any(Char::isWhitespace)) return null
            return "https://www.youtube.com/$handle/live"
        }
        if (segments.size == 2 && segments[0].startsWith('@') &&
            segments[0].length > 1 && segments[1].equals("live", ignoreCase = true)
        ) {
            return "https://www.youtube.com/${segments[0]}/live"
        }
        if (segments.size == 2 && segments[0].equals("live", ignoreCase = true)) {
            val id = segments[1].takeIf(videoId::matches) ?: return null
            return "https://www.youtube.com/live/$id"
        }
        if (segments.size == 2 && segments[0].equals("v", ignoreCase = true)) {
            val id = segments[1].takeIf(videoId::matches) ?: return null
            return "https://www.youtube.com/watch?v=$id"
        }
        if (segments.size == 2 && segments[0].equals("embed", ignoreCase = true)) {
            val id = segments[1].takeIf(videoId::matches) ?: return null
            return "https://www.youtube.com/watch?v=$id"
        }
        if (segments.size == 2 && segments[0].equals("channel", ignoreCase = true)) {
            val id = segments[1].takeIf { it.matches(Regex("UC[A-Za-z0-9_-]{22}")) } ?: return null
            return "https://www.youtube.com/channel/$id/live"
        }
        if (segments.firstOrNull()?.equals("watch", ignoreCase = true) == true) {
            val id = queryParameter(uri.rawQuery, "v")?.takeIf(videoId::matches) ?: return null
            return "https://www.youtube.com/watch?v=$id"
        }
        return null
    }

    private fun queryParameter(rawQuery: String?, wanted: String): String? =
        rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
            val pair = part.split('=', limit = 2)
            val key = decode(pair.firstOrNull().orEmpty())
            if (key != wanted) null else decode(pair.getOrNull(1).orEmpty())
        }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}
