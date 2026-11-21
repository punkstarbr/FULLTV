/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * Safe diagnostics for asynchronous network boundaries.
 *
 * Exception messages are deliberately omitted: HTTP clients may put full URLs, query tokens,
 * provider credentials, or response content in them. Stack frames and exception class names are
 * enough to identify the failing code path without copying that data into Logcat.
 */
internal object SafeNetworkDiagnostics {
    private const val MAX_CAUSES = 8
    private const val MAX_STACK_FRAMES = 24
    private const val MAX_LOG_CHARS = 3_500

    fun isRetryableNetworkFailure(failure: Throwable): Boolean {
        val chain = causeChain(failure)
        if (chain.any { it is CancellationException }) return false
        // Certificate and TLS protocol failures are not fixed by retrying with backoff.
        if (chain.any { it is SSLException }) return false
        return chain.any { it is IOException }
    }

    fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 429 || statusCode in 500..599

    fun error(tag: String, stage: String, failure: Throwable) {
        Log.e(tag, format(stage, failure))
    }

    /** Stable, redacted report suitable for a user to copy from Logcat. */
    fun format(stage: String, failure: Throwable): String {
        val safeStage = stage.lowercase().map { char ->
            if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_'
        }.joinToString("").take(48).ifBlank { "unspecified" }
        val chain = causeChain(failure)
        val exceptionTypes = chain.joinToString(" -> ") { throwable ->
            throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name.substringAfterLast('.') }
        }
        val frames = chain.asSequence()
            .flatMap { it.stackTrace.asSequence() }
            .take(MAX_STACK_FRAMES)
            .map { frame ->
                val source = frame.fileName?.let { "$it:${frame.lineNumber}" } ?: "line:${frame.lineNumber}"
                "  at ${frame.className}.${frame.methodName}($source)"
            }
            .toList()
        return buildString {
            append("event=operation_failed stage=")
            append(safeStage)
            append(" exception_chain=")
            append(exceptionTypes.ifBlank { "unknown" })
            frames.forEach { frame ->
                append('\n')
                append(frame)
            }
        }.take(MAX_LOG_CHARS)
    }

    private fun causeChain(root: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val chain = ArrayList<Throwable>(MAX_CAUSES)
        var current: Throwable? = root
        while (current != null && chain.size < MAX_CAUSES && seen.add(current)) {
            chain += current
            current = current.cause
        }
        return chain
    }
}
