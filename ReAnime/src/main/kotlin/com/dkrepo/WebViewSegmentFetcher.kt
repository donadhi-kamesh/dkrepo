package com.dkrepo

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.utils.Coroutines.mainWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches segment bytes through a hidden WebView's real Chromium network stack.
 *
 * The real segment CDN (vault94.slopnet.site) is fronted by a Cloudflare managed
 * challenge that blocks OkHttp/curl/Node by TLS fingerprint, while the decoy
 * host (fetch.flixcloud.cc) serves fake image payloads to non-browser clients.
 * A WebView is a real browser engine, so it passes the challenge; fetch() inside
 * the page reuses the WebView's TLS stack, cookie jar and Origin header.
 *
 * The WebView is primed once with the flixcloud embed page (Origin + cookies)
 * and the vault origin (auto-solves the CF challenge and stores cf_clearance).
 * Each segment is then fetched with fetch() and the bytes are streamed back to
 * Kotlin base64-encoded through a JavaScript interface.
 */
object WebViewSegmentFetcher {
    private const val TAG = "FlixWebView"
    private const val PRIME_TIMEOUT_MS = 20_000L
    private const val FETCH_TIMEOUT_MS = 60_000L

    private val mutex = Mutex()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var primedEmbed: String? = null

    @Volatile
    private var primedVault = false

    private class Bridge {
        val deferred = CompletableDeferred<String>()
        private val sb = StringBuilder()

        @JavascriptInterface
        fun part(s: String) {
            sb.append(s)
        }

        @JavascriptInterface
        fun done() {
            deferred.complete(sb.toString())
        }

        @JavascriptInterface
        fun fail(msg: String) {
            deferred.complete("ERR:" + msg)
        }
    }

    /** Fetch [url] through the WebView. Returns raw bytes or null on failure/timeout. */
    suspend fun fetchBytes(url: String, embedUrl: String): ByteArray? = mutex.withLock {
        try {
            Unit.mainWork { ensureWebView() }

            // Prime the flixcloud page once so fetch() runs with the same
            // Origin/cookies a real browser player would have.
            if (primedEmbed != embedUrl) {
                if (!primeUrl(embedUrl)) return@withLock null
                primedEmbed = embedUrl
            }

            // Prime the vault origin once so the CF managed challenge is solved
            // and cf_clearance is stored before we fetch() the segments.
            val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return@withLock null
            if (!primedVault) {
                if (!primeUrl("https://$host/")) return@withLock null
                primedVault = true
            }

            val bridge = Bridge()
            Unit.mainWork {
                try {
                    webView?.addJavascriptInterface(bridge, "FlixBridge")
                    webView?.evaluateJavascript(buildScript(url), null)
                } catch (e: Exception) {
                    bridge.deferred.complete("ERR:" + e.message)
                }
            }

            val raw = withTimeoutOrNull(FETCH_TIMEOUT_MS) { bridge.deferred.await() }
            if (raw == null) {
                Log.w(TAG, "webview fetch timed out for ${url.take(100)}")
                return@withLock null
            }
            if (raw.startsWith("ERR:")) {
                Log.w(TAG, "webview fetch failed: ${raw.take(160)} for ${url.take(100)}")
                return@withLock null
            }
            runCatching { android.util.Base64.decode(raw, android.util.Base64.DEFAULT) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "webview fetch exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
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
    private fun ensureWebView() {
        if (webView != null) return
        val ctx = getContext() as? Context ?: throw IllegalStateException("no app context")
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
        }
        webView = wv
        Log.i(TAG, "webview created")
    }

    private fun buildScript(url: String): String {
        val quoted = url.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        return """
        (function() {
          var url = '$quoted';
          fetch(url, { credentials: 'include', mode: 'cors' }).then(function(r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.arrayBuffer();
          }).then(function(buf) {
            var bytes = new Uint8Array(buf);
            var CH = 0x8000;
            var bin = '';
            for (var i = 0; i < bytes.length; i += CH) {
              bin += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + CH, bytes.length)));
            }
            return btoa(bin);
          }).then(function(b64) {
            var CHUNK = 1000000;
            for (var i = 0; i < b64.length; i += CHUNK) {
              window.FlixBridge.part(b64.substring(i, i + CHUNK));
            }
            window.FlixBridge.done();
          }).catch(function(e) {
            window.FlixBridge.fail(String(e && e.message ? e.message : e));
          });
        })();
        """.trimIndent()
    }
}
