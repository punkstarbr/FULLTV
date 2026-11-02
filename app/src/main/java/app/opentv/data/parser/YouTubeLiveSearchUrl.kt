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

/** Validates public YouTube search-result URLs while preserving their live filter and sort order. */
object YouTubeLiveSearchUrl {
    private val allowedHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    private const val DEFAULT_LIVE_FILTER = "EgJAAQ%253D%253D"
    private val safeFilterValue = Regex("[A-Za-z0-9._~%=-]{1,256}")

    /**
     * Returns a canonical HTTPS results URL, or null if [value] is not a YouTube `/results` URL
     * with exactly one non-empty `search_query`. A caller-provided `sp` filter/order token is
     * preserved; yt-dlp separately filters the extracted entries to broadcasts currently marked live.
     *
     * A default live-only token is added only if the URL has no safe `sp` token. Preserving the
     * input token matters for search URLs which request a particular live ordering, such as
     * `CAMSAkAB` (the URL supplied in a playlist).
     */
    fun normalize(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (value.length > MAX_URL_CHARS) return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        if (uri.userInfo != null) return null
        if (uri.host?.lowercase(Locale.ROOT) !in allowedHosts) return null
        if (uri.rawPath !in setOf("/results", "/results/")) return null

        val parts = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank)
        val searchQueries = parts.filter { part ->
            val pair = part.split('=', limit = 2)
            decode(pair.firstOrNull().orEmpty()) == "search_query"
        }
        if (searchQueries.size != 1 || decode(searchQueries.single().substringAfter('=', "")).isBlank()) return null

        val filterParts = parts.filter { decode(it.substringBefore('=')) == "sp" }
        if (filterParts.size > 1) return null
        val requestedFilter = filterParts.singleOrNull()?.substringAfter('=', "")
        val filter = requestedFilter?.takeIf(::isSafeFilterValue) ?: DEFAULT_LIVE_FILTER

        val preserved = parts.filterNot { decode(it.substringBefore('=')) == "sp" }
        return "https://www.youtube.com/results?${(preserved + "sp=$filter").joinToString("&")}"
    }

    fun isSearchUrl(value: String): Boolean = normalize(value) != null

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun isSafeFilterValue(value: String): Boolean =
        safeFilterValue.matches(value) && runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.isSuccess

    private const val MAX_URL_CHARS = 2_048
}
