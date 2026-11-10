/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.net.URI

/** Rewrites an explicit public GitHub /raw or /blob file route to its direct raw HTTPS origin. */
internal object GitHubRawResourceUrl {
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

        return "https://raw.githubusercontent.com/$owner/$repository/${revisionAndFile.joinToString("/")}"
    }
}
