/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import app.opentv.data.model.Channel
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * Streaming M3U/M3U8 parser with byte, line, channel and URL quotas. Only HTTPS stream, guide and
 * logo URLs are accepted; local/file/custom schemes and URL userinfo cannot turn a playlist into
 * a local-file reader or hide credentials in URI authority fields.
 */
object M3uParser {

    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)="([^"]*)"""")

    data class Result(
        val channels: List<Channel>,
        /** First `url-tvg`/`x-tvg-url` found anywhere in the playlist. */
        val declaredEpgUrl: String?,
        /** All bounded, unique HTTPS guide URLs declared by the playlist, in order. */
        val declaredEpgUrls: List<String> = emptyList(),
        val skippedEntries: Int = 0,
    )

    fun parse(input: InputStream, sourceId: Long): Result =
        BoundedInputStream(input, MAX_PLAYLIST_BYTES, "M3U playlist")
            .bufferedReader().use { parse(it, sourceId) }

    fun parse(text: String, sourceId: Long): Result {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PLAYLIST_BYTES) {
            "M3U playlist exceeds the safety limit."
        }
        return parse(text.reader().buffered(), sourceId)
    }

    fun parse(reader: BufferedReader, sourceId: Long): Result {
        val channels = ArrayList<Channel>()
        val seenStreamIds = HashSet<String>()
        var declaredEpgUrl: String? = null
        val declaredEpgUrls = LinkedHashSet<String>()
        var skipped = 0
        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingNumber: Int? = null
        var index = 0

        while (true) {
            val rawLine = readLineLimited(reader) ?: break
            val line = rawLine.trim()
            val attributes = if (line.startsWith("#")) parseAttributePairs(line) else emptyList()
            for ((key, value) in attributes) {
                if (key != "url-tvg" && key != "x-tvg-url") continue
                for (url in splitGuideUrls(value)) {
                    if (declaredEpgUrls.size >= MAX_DISCOVERED_EPG_URLS) break
                    if (declaredEpgUrls.add(url) && declaredEpgUrl == null) declaredEpgUrl = url
                }
            }

            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttributes = attributes.toMap()
                    pendingName = displayNameOf(line, pendingAttributes)
                        ?.take(MAX_DISPLAY_TEXT_CHARS)
                    pendingNumber = pendingAttributes["tvg-chno"]?.toIntOrNull()
                }
                line.startsWith("#") -> Unit // Keep a pending EXTINF across directives such as #EXTVLCOPT.
                else -> {
                    val name = pendingName
                    if (name.isNullOrBlank() || !isSafeHttpsUrl(line, MAX_STREAM_URL_CHARS)) {
                        skipped++
                    } else {
                        if (channels.size >= MAX_CHANNELS) {
                            throw IOException("M3U playlist contains too many channels.")
                        }
                        val attributes = pendingAttributes
                        val tvgId = attributes["tvg-id"]?.takeIf {
                            it.isNotBlank() && it.length <= MAX_ID_CHARS
                        }
                        // One tvg-id can identify SD/HD variants. Keep distinct HTTPS URLs and
                        // deduplicate only exact repeated stream URLs.
                        val streamId = tvgId
                            ?.let { "tvg:$it:${stableHash(line)}" }
                            ?: "url:${stableHash(line)}"
                        if (seenStreamIds.add(streamId)) {
                            channels += Channel(
                                sourceId = sourceId,
                                streamId = streamId,
                                name = name,
                                categoryId = attributes["group-title"]?.takeIf { it.isNotBlank() }
                                    ?.take(MAX_DISPLAY_TEXT_CHARS),
                                logoUrl = (attributes["tvg-logo"] ?: attributes["logo"])
                                    ?.takeIf { isSafeHttpsUrl(it, MAX_ICON_URL_CHARS) },
                                epgChannelId = tvgId,
                                number = pendingNumber,
                                streamUrl = line,
                                sortIndex = index++,
                            )
                        }
                    }
                    pendingName = null
                    pendingAttributes = emptyMap()
                    pendingNumber = null
                }
            }
        }

        return Result(channels, declaredEpgUrl, declaredEpgUrls.toList(), skipped)
    }

    /** The display name follows the last comma, falling back to tvg-name when it is empty. */
    private fun displayNameOf(line: String, attributes: Map<String, String>): String? {
        val afterComma = line.substringAfterLast(',', missingDelimiterValue = "").trim()
        return afterComma.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    }

    private fun parseAttributePairs(line: String): List<Pair<String, String>> =
        ATTRIBUTE_REGEX.findAll(line).map { match ->
            match.groupValues[1].lowercase() to match.groupValues[2]
        }.toList()

    private fun splitGuideUrls(value: String): List<String> =
        value.split(GUIDE_URL_SEPARATOR)
            .asSequence()
            .map(String::trim)
            .filter { isSafeHttpsUrl(it, MAX_EPG_URL_CHARS) }
            .take(MAX_DISCOVERED_EPG_URLS)
            .toList()

    private fun isSafeHttpsUrl(raw: String, maxLength: Int): Boolean {
        if (raw.length !in 9..maxLength) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null
    }

    /** readLine() can allocate an attacker-sized String before the parser gets a chance to check it. */
    private fun readLineLimited(reader: BufferedReader): String? {
        val line = StringBuilder()
        var seen = false
        while (true) {
            val value = reader.read()
            if (value < 0) return if (!seen) null else line.toString()
            seen = true
            if (value == '\n'.code || value == '\r'.code) return line.toString()
            if (line.length >= MAX_LINE_CHARS) throw IOException("M3U playlist line exceeds the safety limit.")
            line.append(value.toChar())
        }
    }

    /** FNV-1a: stable across processes and platforms, unlike String.hashCode. */
    private fun stableHash(value: String): String {
        var hash = 0xcbf29ce484222325uL.toLong()
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash)
    }

    const val MAX_PLAYLIST_BYTES = 64L * 1024 * 1024
    private const val MAX_LINE_CHARS = 16 * 1024
    private const val MAX_CHANNELS = 100_000
    private const val MAX_DISCOVERED_EPG_URLS = 128
    private const val MAX_STREAM_URL_CHARS = 8_192
    private const val MAX_EPG_URL_CHARS = 8_192
    private const val MAX_ICON_URL_CHARS = 2_048
    private const val MAX_ID_CHARS = 512
    private const val MAX_DISPLAY_TEXT_CHARS = 256
    private val GUIDE_URL_SEPARATOR = Regex("""[\s,]+""")
}
