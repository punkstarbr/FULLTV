/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Validates public YouTube search-result URLs and forces the built-in Live filter. */
object YouTubeLiveSearchUrl {
    private val allowedHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    private const val LIVE_FILTER = "sp=EgJAAQ%253D%253D"

    /**
     * Returns a canonical HTTPS results URL limited to active live videos, or null if [value]
     * is not a YouTube `/results` URL with a non-empty `search_query` parameter.
     *
     * The `sp` value is intentionally replaced rather than trusted: the catalogue must never
     * advertise ordinary VOD or upcoming broadcasts as currently live channels.
     */
    fun normalize(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        if (uri.userInfo != null) return null
        if (uri.host?.lowercase(Locale.ROOT) !in allowedHosts) return null
        if (uri.rawPath !in setOf("/results", "/results/")) return null

        val parts = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank)
        val hasSearchQuery = parts.any { part ->
            val pair = part.split('=', limit = 2)
            val key = decode(pair.firstOrNull().orEmpty())
            val query = decode(pair.getOrNull(1).orEmpty())
            key == "search_query" && query.isNotBlank()
        }
        if (!hasSearchQuery) return null

        val preserved = parts.filterNot { part ->
            decode(part.substringBefore('=')) == "sp"
        }
        return "https://www.youtube.com/results?${(preserved + LIVE_FILTER).joinToString("&")}"
    }

    fun isSearchUrl(value: String): Boolean = normalize(value) != null

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}
