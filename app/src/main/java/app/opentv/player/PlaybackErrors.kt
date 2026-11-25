/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import app.opentv.R
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap

/** Turns player failures into localized, actionable messages and retry decisions. */
@OptIn(UnstableApi::class)
object PlaybackErrors {

    fun describe(context: Context, error: PlaybackException): String {
        val causes = causesOf(error).toList()
        val httpStatus = causes
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.responseCode
        if (httpStatus != null) return describeHttpStatus(context, httpStatus)

        return when {
            causes.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
                context.getString(R.string.player_error_no_signal)
            causes.any { it is SocketTimeoutException || it is SocketException } ->
                context.getString(R.string.player_error_server_timeout)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                context.getString(R.string.player_error_no_signal)
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                context.getString(R.string.player_error_not_a_stream)
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                context.getString(R.string.player_error_malformed_stream)
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                context.getString(R.string.player_error_unsupported_format)
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                context.getString(R.string.player_error_live_window)
            else -> context.getString(R.string.player_error_generic)
        }
    }

    fun describeHttpStatus(context: Context, code: Int): String = when (code) {
        401 -> context.getString(R.string.player_error_http_401)
        403 -> context.getString(R.string.player_error_http_403)
        404, 410 -> context.getString(R.string.player_error_http_404)
        405 -> context.getString(R.string.player_error_http_405)
        408 -> context.getString(R.string.player_error_server_timeout)
        429 -> context.getString(R.string.player_error_http_429)
        451 -> context.getString(R.string.player_error_http_451)
        in 500..599 -> context.getString(R.string.player_error_http_5xx, code)
        else -> context.getString(R.string.player_error_http_generic)
    }

    /** Network failures worth automatically retrying after a short, bounded backoff. */
    fun shouldAutomaticallyRetry(error: PlaybackException): Boolean {
        val causes = causesOf(error).toList()
        val httpStatus = causes
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.responseCode
        if (httpStatus != null) return shouldAutomaticallyRetryHttpStatus(httpStatus)

        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            causes.any {
                it is UnknownHostException || it is SocketTimeoutException ||
                    it is ConnectException || it is NoRouteToHostException || it is SocketException
            }
    }

    /** A bounded restart decision shared by player errors and playback timeouts. */
    fun shouldScheduleAutomaticRetry(
        error: PlaybackException,
        failuresSoFar: Int,
        maximumRestarts: Int,
    ): Boolean =
        shouldRetryAfterTransientFailure(
            isTransient = shouldAutomaticallyRetry(error),
            failuresSoFar = failuresSoFar,
            maximumRestarts = maximumRestarts,
        )

    /** Timeout failures are transient by definition here, but still obey the same finite budget. */
    fun shouldRetryAfterTransientFailure(
        isTransient: Boolean,
        failuresSoFar: Int,
        maximumRestarts: Int,
    ): Boolean = isTransient && maximumRestarts > 0 && failuresSoFar < maximumRestarts

    fun shouldAutomaticallyRetryHttpStatus(code: Int): Boolean =
        code in RETRYABLE_HTTP_STATUS || code in 500..599

    /** Used by the connectivity observer to resume only failures attributable to lost service. */
    fun isNetworkFailure(error: PlaybackException): Boolean {
        val causes = causesOf(error).toList()
        val httpStatus = causes
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.responseCode
        if (httpStatus != null) return isNetworkHttpStatus(httpStatus)

        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            causes.any {
                it is UnknownHostException || it is SocketTimeoutException ||
                    it is ConnectException || it is NoRouteToHostException || it is SocketException ||
                    (it is IOException && it !is java.io.FileNotFoundException)
            }
    }

    fun isNetworkHttpStatus(code: Int): Boolean = code == 408 || code == 429 || code in 500..599

    private fun causesOf(error: Throwable): Sequence<Throwable> = sequence {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            yield(current)
            current = current.cause
        }
    }

    private val RETRYABLE_HTTP_STATUS = setOf(403, 405, 408, 429)
}
