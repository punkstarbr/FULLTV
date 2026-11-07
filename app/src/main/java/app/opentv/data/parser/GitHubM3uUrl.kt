/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.net.URI

/**
 * Converts GitHub's browser-facing `/blob/` and `/raw/` playlist URLs into the direct Raw host.
 * This avoids following GitHub's cross-origin redirect, which the app correctly blocks for
 * arbitrary catalogue requests. Only public HTTPS GitHub links to `.m3u`/`.m3u8` files are
 * rewritten; query strings are intentionally left untouched rather than copied to another host.
 */
internal object GitHubM3uUrl {
    private val ambiguousEncodedPath = Regex("(?i)%2f|%5c|%2e|%25")

    fun toDirectRawUrl(raw: String): String {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return raw
        val host = uri.host ?: return raw
        val rawPath = uri.rawPath ?: return raw
        if (uri.scheme?.equals("https", ignoreCase = true) != true ||
            !host.equals("github.com", ignoreCase = true) ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port != 443)
        ) return raw

        val parts = rawPath.split('/')
        if (parts.size < 6 || parts.firstOrNull() != "") return raw
        val owner = parts[1]
        val repository = parts[2]
        val view = parts[3]
        val revisionAndFile = parts.drop(4)
        if (owner.isBlank() || repository.isBlank() || view !in setOf("raw", "blob") ||
            revisionAndFile.size < 2 ||
            parts.drop(1).any { segment ->
                segment.isBlank() || segment == "." || segment == ".." ||
                    ambiguousEncodedPath.containsMatchIn(segment) || '\\' in segment
            }
        ) return raw

        val filename = revisionAndFile.last().lowercase()
        if (!filename.endsWith(".m3u") && !filename.endsWith(".m3u8")) return raw

        return "https://raw.githubusercontent.com/$owner/$repository/${revisionAndFile.joinToString("/")}"
    }
}
