/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import java.net.URI
import java.util.Locale

/** URL handling for YouTube channel handles only; all other channel URLs bypass Streamlink. */
object YouTubeChannelUrl {
    private val allowedHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")

    /**
     * Returns Streamlink's live route for a YouTube @handle URL, or null for every other URL.
     * A bare channel URL is deliberately changed to `/live`: Streamlink's bare-channel mode can
     * select an arbitrary video from the channel rather than its current live broadcast.
     */
    fun asLiveUrlOrNull(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host !in allowedHosts) return null

        val segments = uri.rawPath.orEmpty()
            .split('/')
            .filter(String::isNotEmpty)
        if (segments.isEmpty() || segments.size > 2) return null

        val handle = segments.first()
        if (!handle.startsWith('@') || handle.length < 2 || handle.any(Char::isWhitespace)) return null
        if (segments.size == 2 && !segments[1].equals("live", ignoreCase = true)) return null

        return "https://www.youtube.com/$handle/live"
    }
}
