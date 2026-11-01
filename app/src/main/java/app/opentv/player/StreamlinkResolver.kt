/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.util.Base64
import android.util.Log
import com.chaquo.python.Python
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
                ?: throw IllegalArgumentException("Expected a YouTube live channel or video URL.")

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
                Log.w(TAG, "Streamlink could not resolve the YouTube live stream", error)
                throw error
            }
        }
    }

    /** Open a Streamlink reader which yields media bytes, not an HLS playlist document. */
    suspend fun openRecording(channelUrl: String): RecordingReader = resolveMutex.withLock {
        val liveUrl = YouTubeChannelUrl.asLiveUrlOrNull(channelUrl)
            ?: throw IllegalArgumentException("Expected a YouTube live channel or video URL.")
        var openedReader: RecordingReader? = null
        val reader = try {
            withContext(NonCancellable + Dispatchers.IO) {
                val response = pythonInstance().getModule("streamlink_bridge")
                    .callAttr("open_recording", liveUrl)
                    .toString()
                val token = JSONObject(response).getString("token")
                RecordingReader(token).also { openedReader = it }
            }
        } catch (cancelled: CancellationException) {
            openedReader?.close()
            throw cancelled
        }
        if (!currentCoroutineContext().isActive) {
            reader.close()
            throw CancellationException("Recording stopped while Streamlink was opening the reader.")
        }
        reader
    }

    inner class RecordingReader internal constructor(private val token: String) {
        /** Read up to [size] media bytes. Empty means Streamlink reached EOF. */
        suspend fun read(size: Int): ByteArray = withContext(Dispatchers.IO) {
            require(size > 0)
            val encoded = pythonInstance().getModule("streamlink_bridge")
                .callAttr("read_recording", token, size)
                .toString()
            if (encoded.isEmpty()) byteArrayOf() else Base64.decode(encoded, Base64.NO_WRAP)
        }

        /** Closes the underlying Streamlink HLS reader and its HTTP requests. */
        suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                pythonInstance().getModule("streamlink_bridge").callAttr("close_recording", token)
            }
        }
    }

    private fun pythonInstance(): Python = PythonRuntime.get(appContext)

    private companion object {
        const val TAG = "StreamlinkResolver"
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
