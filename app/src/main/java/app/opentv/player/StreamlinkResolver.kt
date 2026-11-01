/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A fresh Streamlink result; YouTube media URLs are short-lived and must not be persisted. */
data class ResolvedYouTubeStream(
    val url: String,
    val userAgent: String,
)

/** Runs the bundled Streamlink library on-device; no separate app, executable, or server is used. */
class StreamlinkResolver(context: Context) {
    private val appContext = context.applicationContext
    private val resolveMutex = Mutex()

    suspend fun resolveBest(channelUrl: String): ResolvedYouTubeStream = resolveMutex.withLock {
        withContext(Dispatchers.IO) {
            val liveUrl = YouTubeChannelUrl.asLiveUrlOrNull(channelUrl)
                ?: throw IllegalArgumentException("Expected a YouTube @handle channel URL.")

            try {
                val python = pythonInstance()
                val response = python.getModule("streamlink_bridge")
                    .callAttr("resolve_best", liveUrl)
                    .toString()
                val result = JSONObject(response)
                val streamUrl = result.getString("url")
                require(streamUrl.startsWith("https://")) {
                    "Streamlink returned a non-HTTPS URL."
                }
                ResolvedYouTubeStream(
                    url = streamUrl,
                    userAgent = result.optString("user_agent").takeIf(String::isNotBlank)
                        ?: DEFAULT_USER_AGENT,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Streamlink could not resolve the YouTube live channel", error)
                throw error
            }
        }
    }

    private fun pythonInstance(): Python {
        if (!Python.isStarted()) {
            synchronized(PYTHON_START_LOCK) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            }
        }
        return Python.getInstance()
    }

    private companion object {
        const val TAG = "StreamlinkResolver"
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val PYTHON_START_LOCK = Any()
    }
}
