/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import android.util.Xml
import app.opentv.data.model.Programme
import java.io.InputStream
import java.net.URI
import java.util.Calendar
import java.util.TimeZone
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Streaming XMLTV guide parser.
 *
 * Two properties matter more than anything else here, because between them they account
 * for most of the "my EPG is broken" reports that plague IPTV players:
 *
 * 1. **It streams.** Programmes are emitted to a callback as they are parsed rather than
 *    accumulated into a list. A week of guide data for 5,000 channels is comfortably
 *    150 MB of XML and several million programmes; nothing about that fits in the heap
 *    of a £30 TV stick. The caller batches into the database as they arrive.
 *
 * 2. **Times are parsed to UTC, correctly.** XMLTV timestamps look like
 *    `20260727183000 +0100`. The offset is frequently *not* the device's offset, and is
 *    sometimes absent entirely. Getting this wrong shifts the whole guide by hours —
 *    the single most-reported EPG bug in this category of app. We normalise to epoch
 *    millis at parse time and never store anything else.
 */
object XmltvParser {

    /** Result of a parse run, for logging and for surfacing honest errors to the user. */
    data class Stats(
        val channelCount: Int,
        val programmeCount: Int,
        val skippedProgrammes: Int,
    )

    /**
     * @param onChannelAlias called with (channelId, displayName) for each `<channel>` element.
     * @param onProgramme called for each successfully parsed `<programme>`.
     *
     * [onProgramme] is a suspending callback, and this function is therefore suspending too.
     * That is deliberate: the whole point of streaming programmes out one at a time is that the
     * caller writes them to the database as they arrive, and Room's DAO methods suspend. A
     * plain callback would force the caller into either `runBlocking` on every batch or
     * buffering the entire guide in memory — and a week of data for a large provider is
     * comfortably 150 MB of XML, which is exactly what this design exists to avoid.
     *
     * The XML reading itself is blocking, so call this from a coroutine on an IO dispatcher.
     */
    suspend fun parse(
        input: InputStream,
        feedId: Long,
        onChannelAlias: (id: String, displayName: String?) -> Unit = { _, _ -> },
        onProgramme: suspend (Programme) -> Unit,
    ): Stats {
        var channelCount = 0
        var programmeCount = 0
        var skipped = 0

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(BoundedInputStream(input, MAX_XML_BYTES, "XMLTV guide"), null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.DOCDECL) {
                throw XmlPullParserException("DOCTYPE is not supported in XMLTV guides.")
            }
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val displayName = readChannelDisplayName(parser)
                        if (!id.isNullOrBlank() && id.length <= MAX_ID_CHARS) {
                            channelCount++
                            if (channelCount > MAX_CHANNELS) throw XmlPullParserException("XMLTV has too many channels.")
                            onChannelAlias(id, displayName)
                        }
                    }

                    "programme" -> {
                        val programme = readProgramme(parser, feedId)
                        if (programme != null) {
                            programmeCount++
                            if (programmeCount > MAX_PROGRAMMES) {
                                throw XmlPullParserException("XMLTV has too many programmes.")
                            }
                            onProgramme(programme)
                        } else {
                            skipped++
                        }
                    }
                }
            }
            event = parser.next()
        }

        return Stats(channelCount, programmeCount, skipped)
    }

    private fun readChannelDisplayName(parser: XmlPullParser): String? {
        var displayName: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (parser.name == "display-name" && displayName == null) {
                            displayName = readBoundedText(parser, MAX_DISPLAY_NAME_CHARS)
                                .trim().takeIf { it.isNotEmpty() }
                            depth--
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return displayName
            }
        }
        return displayName
    }

    private fun readProgramme(parser: XmlPullParser, feedId: Long): Programme? {
        val channelId = parser.getAttributeValue(null, "channel")
        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))

        var title: String? = null
        var description: String? = null
        var category: String? = null
        var iconUrl: String? = null
        var season: Int? = null
        var episode: Int? = null

        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "title" -> {
                            if (title == null) { title = readBoundedText(parser, MAX_TITLE_CHARS).trim(); depth-- }
                        }
                        "desc" -> {
                            if (description == null) { description = readBoundedText(parser, MAX_DESCRIPTION_CHARS).trim(); depth-- }
                        }
                        "category" -> {
                            if (category == null) { category = readBoundedText(parser, MAX_CATEGORY_CHARS).trim(); depth-- }
                        }
                        "icon" -> {
                            if (iconUrl == null) {
                                iconUrl = parser.getAttributeValue(null, "src")?.takeIf(::isSafeHttpsUrl)
                            }
                        }
                        "episode-num" -> {
                            val system = parser.getAttributeValue(null, "system")
                            val value = readBoundedText(parser, MAX_EPISODE_NUM_CHARS).trim()
                            depth--
                            if (system == "xmltv_ns") {
                                val parts = value.split('.')
                                // xmltv_ns is zero-based and may use "1/2" for part numbering.
                                season = parts.getOrNull(0)
                                    ?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
                                episode = parts.getOrNull(1)
                                    ?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }

        // A programme with no channel, no start, or a nonsensical span is not recoverable.
        if (channelId.isNullOrBlank() || channelId.length > MAX_ID_CHARS || start == null) return null
        val end = stop ?: (start + DEFAULT_DURATION_MILLIS)
        if (end <= start || end - start > MAX_PROGRAMME_SPAN_MILLIS) return null

        return Programme(
            feedId = feedId,
            epgChannelId = channelId,
            startUtcMillis = start,
            endUtcMillis = end,
            title = title?.takeIf { it.isNotEmpty() } ?: UNTITLED,
            description = description?.takeIf { it.isNotEmpty() },
            category = category?.takeIf { it.isNotEmpty() },
            season = season,
            episode = episode,
            iconUrl = iconUrl?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Parses an XMLTV timestamp to epoch millis (UTC).
     *
     * Accepts `YYYYMMDDHHMMSS`, `YYYYMMDDHHMM`, `YYYYMMDDHH`, `YYYYMMDD`, each optionally
     * followed by whitespace and a `+HHMM` / `-HHMM` offset. When the offset is missing the
     * value is interpreted as UTC — guessing the device's zone instead is what silently
     * shifts guides for users who travel or run a VPN.
     */
    fun parseXmltvTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > MAX_TIMESTAMP_CHARS) return null
        val trimmed = raw.trim()

        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.length < 8) return null

        val remainder = trimmed.drop(digits.length).trim()
        val offsetMinutes = parseOffset(remainder) ?: 0

        return try {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(Calendar.YEAR, digits.substring(0, 4).toInt())
                set(Calendar.MONTH, digits.substring(4, 6).toInt() - 1)
                set(Calendar.DAY_OF_MONTH, digits.substring(6, 8).toInt())
                set(Calendar.HOUR_OF_DAY, digits.segmentOrZero(8))
                set(Calendar.MINUTE, digits.segmentOrZero(10))
                set(Calendar.SECOND, digits.segmentOrZero(12))
            }
            calendar.timeInMillis - offsetMinutes * 60_000L
        } catch (_: NumberFormatException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    private fun String.segmentOrZero(from: Int): Int =
        if (length >= from + 2) substring(from, from + 2).toIntOrNull() ?: 0 else 0

    /** Reads nested text without nextText()'s unbounded String allocation. */
    private fun readBoundedText(parser: XmlPullParser, maxChars: Int): String {
        val text = StringBuilder()
        var nestedDepth = 0
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    nestedDepth++
                    if (nestedDepth > MAX_TEXT_NESTING) {
                        throw XmlPullParserException("XMLTV text nesting exceeds the safety limit.")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (nestedDepth == 0) return text.toString()
                    nestedDepth--
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val value = parser.text.orEmpty()
                    if (text.length + value.length > maxChars) {
                        throw XmlPullParserException("XMLTV text field exceeds the safety limit.")
                    }
                    text.append(value)
                }
                XmlPullParser.DOCDECL -> throw XmlPullParserException("DOCTYPE is not supported in XMLTV guides.")
                XmlPullParser.END_DOCUMENT -> return text.toString()
            }
        }
    }

    private fun isSafeHttpsUrl(raw: String): Boolean {
        if (raw.length !in 9..MAX_ICON_URL_CHARS) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null
    }

    /** `+0100` / `-0530` / `+01:00` / `UTC` / `Z` / empty. Returns minutes east of UTC. */
    private fun parseOffset(raw: String): Int? {
        if (raw.isEmpty()) return null
        if (raw.equals("Z", true) || raw.equals("UTC", true) || raw.equals("GMT", true)) return 0

        val sign = when (raw.first()) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val body = raw.drop(1).replace(":", "")
        if (body.length < 4) return null
        val hours = body.substring(0, 2).toIntOrNull() ?: return null
        val minutes = body.substring(2, 4).toIntOrNull() ?: return null
        return sign * (hours * 60 + minutes)
    }

    private const val DEFAULT_DURATION_MILLIS = 30 * 60 * 1000L
    private const val UNTITLED = "No information"
    private const val MAX_XML_BYTES = 256L * 1024 * 1024
    private const val MAX_CHANNELS = 100_000
    private const val MAX_PROGRAMMES = 2_000_000
    private const val MAX_ID_CHARS = 512
    private const val MAX_DISPLAY_NAME_CHARS = 512
    private const val MAX_TITLE_CHARS = 2_048
    private const val MAX_DESCRIPTION_CHARS = 16 * 1024
    private const val MAX_CATEGORY_CHARS = 512
    private const val MAX_EPISODE_NUM_CHARS = 128
    private const val MAX_ICON_URL_CHARS = 2_048
    private const val MAX_TIMESTAMP_CHARS = 64
    private const val MAX_TEXT_NESTING = 16
    private const val MAX_PROGRAMME_SPAN_MILLIS = 7L * 24 * 60 * 60 * 1000
}
