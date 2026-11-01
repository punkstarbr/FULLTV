/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import app.opentv.data.parser.YouTubeLiveSearchUrl
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Metadata for one currently-live YouTube search result; no expiring media URL is stored. */
data class YouTubeLiveVideo(
    val videoId: String,
    val title: String,
    val channelName: String?,
    val thumbnailUrl: String?,
) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
}

/** Runs yt-dlp's bundled YouTube search extractor on-device; no API key or FullTV server is used. */
class YouTubeLiveSearchResolver(context: Context) {
    private val appContext = context.applicationContext

    suspend fun search(url: String, maxResults: Int = MAX_RESULTS): List<YouTubeLiveVideo> {
        val liveUrl = YouTubeLiveSearchUrl.normalize(url)
            ?: throw IllegalArgumentException("Expected a YouTube /results URL with search_query.")
        require(maxResults in 1..MAX_RESULTS) { "At most $MAX_RESULTS results can be shown." }

        return withContext(Dispatchers.IO) {
            val python: Python = PythonRuntime.get(appContext)
            val response = python.getModule("youtube_search_bridge")
                .callAttr("search_live", liveUrl, maxResults)
                .toString()
            val items = JSONObject(response).optJSONArray("items") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { VIDEO_ID.matches(it) } ?: continue
                    val title = item.optString("title").trim().takeIf(String::isNotEmpty) ?: continue
                    val thumbnail = item.optString("thumbnail").takeIf {
                        it.startsWith("https://")
                    }
                    add(
                        YouTubeLiveVideo(
                            videoId = id,
                            title = title,
                            channelName = item.optString("channel").trim().takeIf(String::isNotEmpty),
                            thumbnailUrl = thumbnail,
                        ),
                    )
                    if (size == maxResults) break
                }
            }
        }
    }

    private companion object {
        const val MAX_RESULTS = 5
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
