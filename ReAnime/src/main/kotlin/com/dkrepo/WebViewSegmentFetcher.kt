package com.dkrepo

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.utils.Coroutines.mainWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener

/**
 * Fetches segment bytes through a hidden WebView's real Chromium network stack.
 *
 * The real segment CDNs (vault*.slopnet.site / vault-*.rundowncdn.top) serve
 * real MPEG-TS only to real browser engines: OkHttp/curl/Node get a Cloudflare
 * challenge (vault94) or a poisoned fake-image payload (rundowncdn/fetch).
 * A WebView is a real Chromium engine, so it passes the challenge and its
 * fetch() reuses the browser TLS stack, cookie jar and Origin header.
 *
 * The result is read back by polling a global `window.__fcState` variable
 * (no addJavascriptInterface bridge — that was unreliable), with a diagnostic
 * log at every step.
 */
object WebViewSegmentFetcher {
    private const val TAG = "FlixWebView"
    private const val PRIME_TIMEOUT_MS = 15_000L
    private const val FETCH_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 150L

    private val mutex = Mutex()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var primedEmbed: String? = null

    /** Fetch [url] through the WebView. Returns raw bytes or null on failure/timeout. */
    suspend fun fetchBytes(url: String, embedUrl: String): ByteArray? = mutex.withLock {
        try {
            val created = Unit.mainWork { ensureWebView() }
            if (!created) {
                Log.w(TAG, "webview creation failed")
                return@withLock null
            }

            val origin = try {
                val u = java.net.URL(url)
                "${u.protocol}://${u.host}/"
            } catch (_: Exception) {
                embedUrl
            }

            // Same-origin prime: fetch() from the segment host avoids CORS, and
            // credentials:include sends cf_clearance. Cross-origin fetch from the
            // flixcloud embed page fails with "Failed to fetch" on rotating CDNs.
            runFetch(url, origin, credentialsInclude = true)?.let { return@withLock it }

            if (!origin.equals(embedUrl, ignoreCase = true) &&
                !embedUrl.startsWith(origin)
            ) {
                Log.i(TAG, "same-origin fetch failed, retrying from embed origin")
                runFetch(url, embedUrl, credentialsInclude = false)?.let { return@withLock it }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "webview fetch exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun runFetch(
        url: String,
        primePage: String,
        credentialsInclude: Boolean
    ): ByteArray? {
        if (primedEmbed != primePage) {
            val ok = primeUrl(primePage)
            Log.i(TAG, "prime $primePage ${if (ok) "ok" else "failed/timed out"}")
            primedEmbed = primePage
            delay(2000)
        }

        val started = Unit.mainWork {
            try {
                val wv = webView ?: return@mainWork false
                wv.evaluateJavascript(buildScript(url, credentialsInclude), null)
                true
            } catch (e: Exception) {
                Log.w(TAG, "evaluateJavascript failed: ${e.message}")
                false
            }
        }
        if (!started) return null

        delay(300)
        val deadline = System.currentTimeMillis() + FETCH_TIMEOUT_MS
        var lastState = "none"
        while (System.currentTimeMillis() < deadline) {
            val state = pollState()
            if (state == null) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            if (state == "P") {
                lastState = "pending"
                delay(POLL_INTERVAL_MS)
                continue
            }
            if (state.startsWith("OK:")) {
                val partCount = state.removePrefix("OK:").toIntOrNull()
                val b64 = if (partCount != null && partCount > 0) readParts(partCount) else null
                if (b64 != null) {
                    val bytes = runCatching {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) {
                        Log.i(TAG, "webview fetch ok ${bytes.size}b for ${url.take(90)}")
                        return bytes
                    }
                    Log.w(TAG, "webview base64 decode failed len=${b64.length} for ${url.take(90)}")
                } else {
                    Log.w(TAG, "webview parts read failed for ${url.take(90)}")
                }
                return null
            }
            lastState = state.take(140)
            Log.w(TAG, "webview script state: $lastState for ${url.take(90)}")
            return null
        }
        Log.w(TAG, "webview fetch timed out, lastState=$lastState for ${url.take(90)}")
        return null
    }

    /** Read the current window.__fcState string from the WebView. */
    private suspend fun pollState(): String? {
        val raw = evalJs("window.__fcState ? window.__fcState : 'P'")
        return raw?.let { r ->
            // evaluateJavascript returns JSON-encoded strings, e.g. "\"P\"" or "\"OK:2\""
            try {
                (JSONTokener(r).nextValue() as? String) ?: r
            } catch (e: Exception) {
                r
            }
        }
    }

    /** Read the base64 parts stored in window.__fcParts and join them. */
    private suspend fun readParts(count: Int): String? {
        val sb = StringBuilder()
        for (i in 0 until count) {
            var part: String? = null
            // Retry transient bridge failures (evaluateJavascript can drop a large
            // reply under memory pressure); a single dropped chunk otherwise aborts
            // the whole segment read ("webview parts read failed").
            for (attempt in 0 until 3) {
                val raw = evalJs("window.__fcParts[$i] ? window.__fcParts[$i] : null")
                    ?: continue
                part = try {
                    (JSONTokener(raw).nextValue() as? String)
                } catch (e: Exception) {
                    raw
                }
                if (part != null) break
            }
            if (part == null) return null
            sb.append(part)
        }
        return sb.toString()
    }

    /** Run a JS expression and return the (JSON-encoded) result string, or null. */
    private suspend fun evalJs(js: String): String? {
        val result = CompletableDeferred<String?>()
        Unit.mainWork {
            try {
                val wv = webView ?: return@mainWork
                wv.evaluateJavascript(js) { r -> result.complete(r) }
            } catch (e: Exception) {
                result.complete(null)
            }
        }
        return withTimeoutOrNull(2000) { result.await() }
    }

    /** Load [page] in the WebView and wait until it finishes loading (or times out). */
    private suspend fun primeUrl(page: String): Boolean {
        val finished = CompletableDeferred<Boolean>()
        Unit.mainWork {
            try {
                val wv = webView ?: return@mainWork
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!finished.isCompleted) finished.complete(true)
                    }
                }
                wv.loadUrl(page)
            } catch (e: Exception) {
                if (!finished.isCompleted) finished.complete(false)
            }
        }
        return withTimeoutOrNull(PRIME_TIMEOUT_MS) { finished.await() } ?: false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): Boolean {
        if (webView != null) return true
        val ctx = getContext() as? Context
        if (ctx == null) {
            Log.w(TAG, "no app context for WebView")
            return false
        }
        return try {
            val wv = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webViewClient = WebViewClient()
            }
            val cookies = android.webkit.CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(wv, true)
            webView = wv
            Log.i(TAG, "webview created")
            true
        } catch (e: Exception) {
            Log.w(TAG, "webview create failed: ${e.message}")
            false
        }
    }

    private fun buildScript(url: String, credentialsInclude: Boolean): String {
        val quoted = url.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        val creds = if (credentialsInclude) "include" else "omit"
        return """
        (function() {
          window.__fcState = 'P';
          window.__fcParts = [];
          var url = '$quoted';
          fetch(url, { credentials: '$creds', mode: 'cors' }).then(function(r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.arrayBuffer();
          }).then(function(buf) {
            var bytes = new Uint8Array(buf);
            var CH = 0x8000;
            var bin = '';
            for (var i = 0; i < bytes.length; i += CH) {
              bin += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + CH, bytes.length)));
            }
            var b64 = btoa(bin);
            var CHUNK = 200000;
            window.__fcParts = [];
            for (var i = 0; i < b64.length; i += CHUNK) {
              window.__fcParts.push(b64.substring(i, i + CHUNK));
            }
            window.__fcState = 'OK:' + window.__fcParts.length;
          }).catch(function(e) {
            window.__fcState = 'ERR:' + String(e && e.message ? e.message : e);
          });
        })();
        """.trimIndent()
    }
}
