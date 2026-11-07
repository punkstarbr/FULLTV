/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import app.opentv.data.remote.NetworkDestinationPolicy
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Canonicalizes only the four public Globoplay video pages explicitly requested for FullTV. */
object GloboplayPageUrl {
    private const val HOST = "globoplay.globo.com"
    private const val MAX_URL_CHARS = 2_048
    private val supportedVideoIds = setOf("1328766", "992055", "4064559", "4218681")
    private val videoPath = Regex("^/v/([0-9]{1,12})/?$")

    /** Drops tracking/query parameters and returns one fixed HTTPS route for an approved video ID. */
    fun canonicalize(raw: String): String? {
        if (raw.length !in 1..MAX_URL_CHARS || raw.any { it.code <= 0x20 || it.code == 0x7f }) {
            return null
        }
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)
        ) return null
        if (uri.host?.lowercase(Locale.ROOT) != HOST) return null
        val id = videoPath.matchEntire(uri.rawPath.orEmpty())?.groupValues?.getOrNull(1) ?: return null
        if (id !in supportedVideoIds) return null
        return "https://$HOST/v/$id/"
    }
}

/** Syntax-only allowlist for the public HLS manifests seen on the approved Globoplay pages. */
object GloboplayManifestUrlPolicy {
    private val liveHost = Regex("^live-[a-z0-9-]+\\.video\\.globo\\.com$")
    private val encodedTraversal = Regex("(?i)%(?:2e|2f|5c)")

    fun hasM3u8Path(raw: String): Boolean = runCatching {
        URI(raw).rawPath.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8")
    }.getOrDefault(false)

    fun isAllowed(raw: String): Boolean {
        if (raw.length !in 1..4_096 || raw.any { it.code <= 0x20 || it.code == 0x7f }) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null ||
            uri.port !in setOf(-1, 443)
        ) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (!liveHost.matches(host)) return false
        val path = uri.rawPath ?: return false
        if (path.length !in 1..2_048 ||
            !path.lowercase(Locale.ROOT).endsWith(".m3u8") ||
            path.contains('\\') || encodedTraversal.containsMatchIn(path) ||
            path.split('/').any { it == "." || it == ".." }
        ) return false
        return true
    }
}

data class ResolvedGloboplayStream(
    val url: String,
    val userAgent: String,
)

/**
 * Resolves the public HLS request made by the official page. A temporary WebView is used only to
 * discover the request; the WebView's manifest response is blocked and the native Media3 player
 * receives the HTTPS URL. Cookies and authorization headers are never copied to Media3 or logged.
 */
class GloboplayResolver {
    private val resolutionMutex = Mutex()

    suspend fun resolve(context: Context, rawPageUrl: String): ResolvedGloboplayStream {
        val pageUrl = GloboplayPageUrl.canonicalize(rawPageUrl)
            ?: throw GloboplayResolutionException("Unsupported Globoplay page.")
        return resolutionMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    ResolutionAttempt(context, pageUrl, continuation).start()
                }
            }
        }
    }
}

class GloboplayResolutionException(message: String) : IOException(message)

private class ResolutionAttempt(
    context: Context,
    private val pageUrl: String,
    private val continuation: CancellableContinuation<ResolvedGloboplayStream>,
) {
    private val uiContext = context
    private val handler = Handler(Looper.getMainLooper())
    private val completed = AtomicBoolean(false)
    private var webView: WebView? = null
    private var attachedParent: ViewGroup? = null
    private val timeout = Runnable {
        finishFailure(GloboplayResolutionException("The public Globoplay stream was not found in time."))
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        if (!continuation.isActive) return
        try {
            val view = WebView(uiContext)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            view.alpha = 0f
            view.isClickable = false
            view.isLongClickable = false
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            attachTemporarily(view)
            val fallbackUserAgent = view.settings.userAgentString.takeIf(::isSafeUserAgent)
                ?: FALLBACK_USER_AGENT
            view.webViewClient = createWebViewClient(view, fallbackUserAgent)
            continuation.invokeOnCancellation {
                handler.post {
                    completed.set(true)
                    cleanup()
                }
            }
            handler.postDelayed(timeout, RESOLUTION_TIMEOUT_MILLIS)
            view.loadUrl(pageUrl)
        } catch (_: Exception) {
            finishFailure(GloboplayResolutionException("Could not open the public Globoplay page."))
        }
    }

    private fun createWebViewClient(view: WebView, fallbackUserAgent: String) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            webView: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (!request.isForMainFrame) return false
            return GloboplayPageUrl.canonicalize(request.url.toString()) != pageUrl
        }

        override fun shouldInterceptRequest(
            webView: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val rawUrl = request.url.toString()
            if (GloboplayManifestUrlPolicy.hasM3u8Path(rawUrl)) {
                if (GloboplayManifestUrlPolicy.isAllowed(rawUrl) && isPublicGloboResource(rawUrl)) {
                    val userAgent = request.requestHeaders.entries
                        .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                        ?.value
                        ?.takeIf(::isSafeUserAgent)
                        ?: fallbackUserAgent
                    handler.post {
                        finishSuccess(ResolvedGloboplayStream(rawUrl, userAgent))
                    }
                }
                // Do not let the hidden WebView play or download the manifest. Media3 will fetch it.
                return blockedResponse()
            }
            if (!isPublicGloboResource(rawUrl)) return blockedResponse()
            return null
        }

        override fun onReceivedError(
            webView: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                handler.post {
                    finishFailure(GloboplayResolutionException("Could not load the public Globoplay page."))
                }
            }
        }

        override fun onReceivedHttpError(
            webView: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                handler.post {
                    finishFailure(GloboplayResolutionException("The public Globoplay page returned an error."))
                }
            }
        }
    }

    /** Public HTTPS resources are preflighted; only Globo/Globo-image hosts are loaded in WebView. */
    private fun isPublicGloboResource(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)
        ) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val ownedHost = host == "globo.com" || host.endsWith(".globo.com") ||
            host == "glbimg.com" || host.endsWith(".glbimg.com")
        ownedHost && NetworkDestinationPolicy.isAllowedHttps(raw)
    }.getOrDefault(false)

    private fun attachTemporarily(view: WebView) {
        var current: Context? = uiContext
        while (current != null) {
            if (current is Activity) {
                val root = current.findViewById<ViewGroup>(android.R.id.content)
                if (root != null) {
                    root.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.TOP or Gravity.START,
                        ),
                    )
                    attachedParent = root
                }
                return
            }
            current = (current as? ContextWrapper)?.baseContext
        }
    }

    private fun finishSuccess(result: ResolvedGloboplayStream) {
        if (!completed.compareAndSet(false, true)) return
        cleanup()
        if (continuation.isActive) continuation.resume(result)
    }

    private fun finishFailure(error: Exception) {
        if (!completed.compareAndSet(false, true)) return
        cleanup()
        if (continuation.isActive) continuation.resumeWithException(error)
    }

    private fun cleanup() {
        handler.removeCallbacks(timeout)
        val view = webView ?: return
        webView = null
        runCatching { view.stopLoading() }
        runCatching { attachedParent?.removeView(view) }
        attachedParent = null
        runCatching { view.destroy() }
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun isSafeUserAgent(value: String): Boolean =
        value.isNotBlank() && value.length <= 512 &&
            value.none { it == '\r' || it == '\n' || it.code == 0x7f }

    private companion object {
        const val RESOLUTION_TIMEOUT_MILLIS = 30_000L
        const val FALLBACK_USER_AGENT = "OpenTV/0.1 (Android)"
    }
}
