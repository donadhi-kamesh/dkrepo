package com.dkrepo

import android.util.Base64
import com.lagradost.api.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Localhost HTTP server that serves unwrapped m3u8 playlists and proxies
 * segment/key fetches so we can strip PNG/WebP/XOR disguises to real MPEG-TS.
 *
 * Segment remote URLs are embedded (base64) in the request so we never lose
 * mappings when many HD-1/HD-2 playlists are published at once.
 */
object LocalPlaylistServer {
    private const val TAG = "FlixCloudLocal"
    private val playlists = ConcurrentHashMap<String, ByteArray>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val nestedPlaylistUrls = ConcurrentHashMap<String, String>()
    private val segmentCache = ConcurrentHashMap<String, ByteArray>()
    private val playlistBodyCache = ConcurrentHashMap<String, ByteArray>()
    private val inflight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()
    private val fetchJobs = PriorityBlockingQueue<FetchJob>()
    private val jobSeq = AtomicLong()
    private val lowInFlight = AtomicInteger(0)

    @Volatile private var audioHead = -1
    @Volatile private var videoHead = -1
    @Volatile private var workersStarted = false

    /**
     * Own OkHttp client so preview/catalog traffic on Cloudstream's shared
     * NiceHttp dispatcher cannot starve playback.
     */
    private val httpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 4
            maxRequestsPerHost = 4
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private class FetchJob(
        val priority: Int,
        val seq: Long,
        val url: String,
        val session: Session,
        val future: CompletableFuture<ByteArray?>
    ) : Comparable<FetchJob> {
        override fun compareTo(other: FetchJob): Int {
            val p = priority.compareTo(other.priority)
            if (p != 0) return p
            return seq.compareTo(other.seq)
        }
    }

    /**
     * Fixed 16-byte repeating XOR key from FlixCloud's forked hls.js
     * (`artplayer-new/hls.js`). WASM `_c()` is only for playlist unwrap.
     */
    private val SEGMENT_XOR_KEY = byteArrayOf(
        157.toByte(), 42, 241.toByte(), 71, 179.toByte(), 142.toByte(), 92, 112,
        166.toByte(), 25, 228.toByte(), 59, 216.toByte(), 98, 15, 197.toByte()
    )

    data class Session(
        val headers: Map<String, String>,
        val xorKey: ByteArray?,
        val segmentReferer: String?,
        /** When playlist has #EXT-X-KEY, stripped bytes may still be AES ciphertext. */
        val allowOpaque: Boolean = false,
        /** flixcloud embed page, used to prime the WebView segment fetcher. */
        val embedUrl: String? = null
    )

    @Volatile
    private var port: Int = -1

    @Volatile
    private var server: ServerSocket? = null

    @Synchronized
    fun ensureStarted(): Int {
        if (server != null && port > 0) return port
        val ss = ServerSocket(0)
        port = ss.localPort
        server = ss
        thread(isDaemon = true, name = "FlixCloud-M3U8-Server") {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    thread(isDaemon = true, name = "FlixCloud-M3U8-Client") {
                        handle(client)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        startFetchWorkers()
        Log.i(TAG, "listening on 127.0.0.1:$port")
        return port
    }

    private fun startFetchWorkers() {
        if (workersStarted) return
        workersStarted = true
        repeat(2) { i ->
            thread(isDaemon = true, name = "FlixCloud-CDN-$i") {
                while (true) {
                    val job = try {
                        fetchJobs.take()
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (job.future.isDone) continue
                    val low = job.priority > 0
                    if (low) {
                        if (lowInFlight.get() >= 1 && fetchJobs.any { it.priority == 0 }) {
                            fetchJobs.put(job)
                            try { Thread.sleep(25) } catch (_: InterruptedException) { break }
                            continue
                        }
                        lowInFlight.incrementAndGet()
                    }
                    try {
                        if (!job.future.isDone) {
                            job.future.complete(fetchViaOkHttp(job.url, job.session))
                        }
                    } catch (e: Exception) {
                        job.future.completeExceptionally(e)
                    } finally {
                        if (low) lowInFlight.decrementAndGet()
                    }
                }
            }
        }
    }

    fun publish(
        body: String,
        headers: Map<String, String>,
        xorKey: ByteArray? = null,
        segmentReferer: String? = null,
        allowOpaque: Boolean = false,
        embedUrl: String? = null
    ): String {
        val p = ensureStarted()
        audioHead = -1
        videoHead = -1
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        sessions[sessionId] = Session(headers, xorKey, segmentReferer, allowOpaque, embedUrl)
        val rewritten = rewritePlaylist(body, sessionId, p, prefetchPlaylists = false)
        val id = UUID.randomUUID().toString().replace("-", "")
        playlists[id] = rewritten.toByteArray(Charsets.UTF_8)
        trimMaps()
        return "http://127.0.0.1:$p/$id.m3u8"
    }

    private fun rewritePlaylist(
        body: String,
        sessionId: String,
        p: Int,
        prefetchPlaylists: Boolean
    ): String {
        val uriTagRegex = Regex("""URI=["']([^"']+)["']""")
        fun mapRemote(uri: String): String {
            val url = rewriteCdnHost(uri)
            return if (prefetchPlaylists && looksLikePlaylistUrl(url)) {
                materializePlaylist(url, sessionId, p)
            } else {
                proxyUrl(sessionId, url, p)
            }
        }
        return body.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> {
                    uriTagRegex.replace(line) { match ->
                        val uri = match.groupValues[1]
                        if (uri.startsWith("http://") || uri.startsWith("https://")) {
                            """URI="${mapRemote(uri)}""""
                        } else {
                            match.value
                        }
                    }
                }
                t.startsWith("http://") || t.startsWith("https://") -> mapRemote(t)
                else -> line
            }
        }
    }

    /**
     * Fetch a nested media playlist once, mark it VOD so ExoPlayer gets a real
     * duration (seek), and serve it from localhost instead of re-hitting the CDN
     * every target-duration (live reload = buffering).
     */
    private fun materializePlaylist(remote: String, sessionId: String, p: Int): String {
        val cacheKey = "$sessionId|$remote"
        nestedPlaylistUrls[cacheKey]?.let { return it }
        val session = sessions[sessionId] ?: return proxyUrl(sessionId, remote, p)
        val raw = fetchPlaylistBytes(remote, session)
        var text = raw?.let { String(it, Charsets.UTF_8) } ?: ""
        if (!text.trimStart().startsWith("#EXTM3U") &&
            session.xorKey != null && session.xorKey.isNotEmpty()
        ) {
            val unwrapped = xorUnwrapText(text, session.xorKey)
            if (unwrapped.trimStart().startsWith("#EXTM3U")) text = unwrapped
        }
        if (!text.trimStart().startsWith("#EXTM3U")) {
            Log.w(TAG, "prefetch playlist failed: ${remote.substringBefore('?').take(90)}")
            return proxyUrl(sessionId, remote, p)
        }
        val vod = ensureVod(absoluteize(text, remote))
        val rewritten = rewritePlaylist(vod, sessionId, p, prefetchPlaylists = false)
        val id = UUID.randomUUID().toString().replace("-", "")
        playlists[id] = rewritten.toByteArray(Charsets.UTF_8)
        val local = "http://127.0.0.1:$p/$id.m3u8"
        nestedPlaylistUrls[cacheKey] = local
        Log.i(TAG, "cached VOD playlist $id from ${remote.substringBefore('?').take(70)}")
        return local
    }

    /** Dead fetchN.flixcloud.cc hosts (UnknownHost) all alias to fetch.flixcloud.cc. */
    private fun rewriteCdnHost(url: String): String =
        url.replace(Regex("(?i)https://fetch\\d+\\.flixcloud\\.cc"), "https://fetch.flixcloud.cc")

    /** Anime episodes are VOD. Missing ENDLIST makes ExoPlayer treat HLS as live: no seek, constant rebuffer. */
    private fun ensureVod(text: String): String {
        if (!text.contains("#EXTINF")) return text
        if (text.contains("#EXT-X-PLAYLIST-TYPE:EVENT") ||
            text.contains("#EXT-X-PLAYLIST-TYPE:LIVE")
        ) return text
        val lines = ArrayList<String>()
        var sawType = false
        for (line in text.lineSequence()) {
            if (line.startsWith("#EXT-X-PLAYLIST-TYPE")) sawType = true
            lines.add(line)
        }
        if (!sawType) {
            val idx = lines.indexOfFirst { it.startsWith("#EXTM3U") }
            if (idx >= 0) lines.add(idx + 1, "#EXT-X-PLAYLIST-TYPE:VOD")
            else lines.add(0, "#EXT-X-PLAYLIST-TYPE:VOD")
        }
        if (lines.none { it.trim() == "#EXT-X-ENDLIST" }) lines.add("#EXT-X-ENDLIST")
        return lines.joinToString("\n")
    }

    private fun proxyUrl(sessionId: String, remote: String, p: Int): String {
        val enc = Base64.encodeToString(
            remote.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "http://127.0.0.1:$p/s/$sessionId?u=$enc"
    }

    private fun trimMaps() {
        if (playlists.size > 128) {
            val keys = playlists.keys().toList()
            keys.take(keys.size - 96).forEach { playlists.remove(it) }
        }
        if (sessions.size > 48) {
            val keys = sessions.keys().toList()
            keys.take(keys.size - 32).forEach { sessions.remove(it) }
        }
        if (nestedPlaylistUrls.size > 128) {
            val keys = nestedPlaylistUrls.keys().toList()
            keys.take(keys.size - 96).forEach { nestedPlaylistUrls.remove(it) }
        }
        if (segmentCache.size > 96) {
            val keys = segmentCache.keys().toList()
            keys.take(keys.size - 72).forEach { segmentCache.remove(it) }
        }
        if (playlistBodyCache.size > 32) {
            val keys = playlistBodyCache.keys().toList()
            keys.take(keys.size - 24).forEach { playlistBodyCache.remove(it) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = input.readLine() ?: return
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val target = requestLine.split(' ').getOrNull(1) ?: ""
                val path = target.substringBefore('?').trimStart('/')
                val query = target.substringAfter('?', "")
                val out = s.getOutputStream()
                when {
                    path.startsWith("s/") -> {
                        val sessionId = path.removePrefix("s/").substringBefore('/')
                        val enc = queryOf(query, "u")
                        serveSegment(sessionId, enc, out)
                    }
                    else -> {
                        val id = path.removeSuffix(".m3u8")
                        val body = playlists[id]
                        if (body == null) {
                            writeResponse(out, 404, "text/plain", ByteArray(0))
                        } else {
                            writeResponse(out, 200, "application/vnd.apple.mpegurl", body, cache = true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "client handler failed: ${e.message}")
            }
        }
    }

    private fun queryOf(query: String, key: String): String? {
        if (query.isEmpty()) return null
        return query.split('&').firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }

    /** True when a proxied URI ends in a playlist extension (.m3u8), ignoring query. */
    private fun looksLikePlaylistUrl(url: String): Boolean {
        val noQuery = url.substringBefore('?')
        val base = noQuery.substringAfterLast('/')
        return base.lowercase().contains("m3u8")
    }

    /**
     * Serve a media/variant playlist (referenced from the master or from an
     * #EXT-X-MEDIA audio group) back to ExoPlayer. These are NOT segments: they
     * must be fetched, unwrapped (fetch7 base64-XOR), absolutized and have their
     * own segment/URI references re-proxied so ExoPlayer can keep resolving the
     * HLS chain. Before this, every referenced playlist was run through
     * normalizeSegment() (which only accepts TS/fMP4), returning an empty 502
     * and making ExoPlayer fail instantly with an "unsupported format" error.
     */
    private fun servePlaylist(remote: String, session: Session, sessionId: String, out: java.io.OutputStream) {
        val cacheKey = remote.substringBefore('?')
        playlistBodyCache[cacheKey]?.let { cached ->
            writeResponse(out, 200, "application/vnd.apple.mpegurl", cached, cache = true)
            return
        }
        val raw = fetchPlaylistBytes(remote, session)
        var text = raw?.let { String(it, Charsets.UTF_8) } ?: ""
        if (!text.trimStart().startsWith("#EXTM3U") &&
            session.xorKey != null && session.xorKey.isNotEmpty()
        ) {
            val unwrapped = xorUnwrapText(text, session.xorKey)
            if (unwrapped.trimStart().startsWith("#EXTM3U")) text = unwrapped
        }
        if (!text.trimStart().startsWith("#EXTM3U")) {
            Log.w(TAG, "media playlist unusable: ${remote.substringBefore('?').take(90)}")
            writeResponse(out, 502, "text/plain", ByteArray(0))
            return
        }
        val vod = ensureVod(absoluteize(text, remote))
        val rewritten = rewritePlaylist(vod, sessionId, port, prefetchPlaylists = false)
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
        playlistBodyCache[cacheKey] = bytes
        writeResponse(out, 200, "application/vnd.apple.mpegurl", bytes, cache = true)
    }

    /**
     * Fetch raw playlist bytes, preferring a real m3u8 over a Cloudflare
     * challenge. Tries the OkHttp path first (covers slopnet/fetch hosts) and
     * falls back to the hidden WebView (real Chromium TLS) which passes the
     * vault*. Cloudflare zone.
     */
    private fun fetchPlaylistBytes(url: String, session: Session): ByteArray? {
        fun usable(b: ByteArray?): Boolean {
            if (b == null || b.isEmpty()) return false
            val t = String(b, Charsets.UTF_8)
            if (t.trimStart().startsWith("#EXTM3U")) return true
            if (session.xorKey != null && session.xorKey.isNotEmpty() &&
                xorUnwrapText(t, session.xorKey).trimStart().startsWith("#EXTM3U")
            ) return true
            return false
        }
        val token = session.segmentReferer
            ?.substringAfter("token=", "")
            ?.takeIf { it.isNotEmpty() }
        val primary = v7FetchUrl(url, token)
        val ok = fetchViaOkHttp(primary, session)
        if (usable(ok)) return ok
        val fallback = rewriteCdnHost(url)
        if (fallback != primary) {
            val ok2 = fetchViaOkHttp(fallback, session)
            if (usable(ok2)) return ok2
        }
        return ok
    }

    /** Absolutize relative segment/URI references in a playlist against baseUrl. */
    private fun absoluteize(text: String, baseUrl: String): String {
        try { java.net.URL(baseUrl) } catch (e: Exception) { return text }
        val uriTagRegex = Regex("""URI=["']([^"']+)["']""")
        fun resolve(ref: String): String {
            val abs = if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("data:")) ref
            else try { java.net.URL(java.net.URL(baseUrl), ref).toString() } catch (e: Exception) { ref }
            return if (abs.startsWith("data:")) abs else rewriteCdnHost(abs)
        }
        return text.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> uriTagRegex.replace(line) { m ->
                    """URI="${resolve(m.groupValues[1])}""""
                }
                else -> resolve(t)
            }
        }
    }

    /** fetch7 playlists are base64( XOR(plaintext, repeating 32-byte key) ). */
    private fun xorUnwrapText(raw: String, key: ByteArray): String {
        val s = raw.trim()
        val blob = try {
            Base64.decode(s, Base64.DEFAULT)
        } catch (e: Exception) {
            try {
                Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (e2: Exception) {
                return ""
            }
        }
        return try {
            String(xorBytes(blob, key), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun serveSegment(sessionId: String, enc: String?, out: java.io.OutputStream) {
        val session = sessions[sessionId]
        if (session == null || enc.isNullOrBlank()) {
            writeResponse(out, 404, "text/plain", ByteArray(0))
            return
        }
        val remote = try {
            rewriteCdnHost(
                String(
                    Base64.decode(enc, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                    Charsets.UTF_8
                )
            )
        } catch (e: Exception) {
            writeResponse(out, 400, "text/plain", ByteArray(0))
            return
        }
        cachedSegment(remote)?.let { cached ->
            val mime = when {
                looksLikeFmp4(cached) -> "video/mp4"
                looksLikeAdts(cached) -> "audio/aac"
                else -> "video/mp2t"
            }
            writeResponse(out, 200, mime, cached, cache = true)
            markServed(remote)
            prefetchAhead(remote, session)
            return
        }
        // A referenced *.m3u8 is itself a media/variant playlist that ExoPlayer
        // must be able to fetch next (variant + #EXT-X-MEDIA audio groups), not a
        // TS segment. Handle it separately so it is served as a valid playlist.
        if (looksLikePlaylistUrl(remote)) {
            servePlaylist(remote, session, sessionId, out)
            return
        }
        try {
            val raw = fetchBytes(remote, session)
            if (raw == null) {
                writeResponse(out, 502, "text/plain", ByteArray(0))
                return
            }
            var cleaned = normalizeSegment(raw, session.xorKey)
            var opaque = false
            if (cleaned == null && session.allowOpaque) {
                // Image disguise around AES-128 HLS ciphertext — ExoPlayer decrypts via EXT-X-KEY
                val stripped = stripImageContainer(raw)
                cleaned = if (stripped !== raw && stripped.isNotEmpty()) stripped else null
                opaque = cleaned != null
            }
            if (cleaned == null) {
                // Last resort: scan RIFF chunks / full buffer for embedded media after XOR
                cleaned = deepRecover(raw, session.xorKey)
            }
            if (cleaned == null) {
                Log.w(
                    TAG,
                    "normalize failed len=${raw.size} " +
                        "head=${raw.take(8).joinToString("") { "%02x".format(it) }} " +
                        "xor=${session.xorKey != null} url=${remote.take(100)}"
                )
                writeResponse(out, 502, "text/plain", ByteArray(0))
                return
            }
            val mime = when {
                looksLikeFmp4(cleaned) -> "video/mp4"
                looksLikeAdts(cleaned) -> "audio/aac"
                else -> "video/mp2t"
            }
            Log.i(
                TAG,
                "segment ok len=${cleaned.size} mime=$mime opaque=$opaque " +
                    "head=${cleaned.take(4).joinToString("") { "%02x".format(it) }} " +
                    "from ${remote.substringBefore('?').take(80)}"
            )
            rememberSegment(remote, cleaned)
            markServed(remote)
            try {
                writeResponse(out, 200, mime, cleaned, cache = true)
            } catch (e: Exception) {
                Log.w(TAG, "client gone after fetch: ${e.message}")
            }
            trimMaps()
            prefetchAhead(remote, session)
        } catch (e: Exception) {
            Log.w(TAG, "serveSegment failed: ${e.message}")
            try { writeResponse(out, 502, "text/plain", ByteArray(0)) } catch (_: Exception) {}
        }
    }

    private fun cacheKey(url: String): String =
        rewriteCdnHost(url).substringBefore('?')

    private fun cachedSegment(remote: String): ByteArray? {
        val key = cacheKey(remote)
        segmentCache[key]?.let { return it }
        segmentCache[remote]?.let { return it }
        val path = runCatching { java.net.URL(key).path }.getOrNull() ?: return null
        return segmentCache["https://fetch.flixcloud.cc$path"]
    }

    private fun rememberSegment(remote: String, cleaned: ByteArray) {
        val key = cacheKey(remote)
        segmentCache[key] = cleaned
        segmentCache[remote] = cleaned
        val path = runCatching { java.net.URL(key).path }.getOrNull()
        if (path != null) segmentCache["https://fetch.flixcloud.cc$path"] = cleaned
    }

    private fun segNumber(url: String): Int? =
        Regex("seg-(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** Playback (audio + nearby video) beats Cloudstream's PreviewImgM3u8 samples. */
    private fun fetchPriority(url: String): Int {
        val n = segNumber(url) ?: return 1
        if (url.contains("/audio/")) return 0
        if (audioHead >= 0 && kotlin.math.abs(n - audioHead) <= 4) return 0
        if (videoHead >= 0 && n in (videoHead - 1)..(videoHead + 6)) return 0
        return 2
    }

    private fun markServed(url: String) {
        val n = segNumber(url) ?: return
        if (url.contains("/audio/")) {
            audioHead = n
        } else if (audioHead >= 0 && kotlin.math.abs(n - audioHead) <= 4) {
            videoHead = n
        } else if (videoHead < 0) {
            videoHead = n
        }
    }

    /**
     * Vault/lock/rundown CDN hosts 403 OkHttp. The working path is always
     * fetch.flixcloud.cc + JWT. Skip the 403 round-trips and skip WebView.
     */
    private fun v7FetchUrl(url: String, token: String?): String {
        val resolved = rewriteCdnHost(url)
        val path = runCatching { java.net.URL(resolved).path }.getOrNull()
        val base = if (path != null && path.startsWith("/_v7/")) {
            "https://fetch.flixcloud.cc$path"
        } else resolved
        return if (!token.isNullOrEmpty()) withToken(base, token) else base
    }

    private fun fetchBytes(url: String, session: Session, priority: Int = fetchPriority(url)): ByteArray? {
        val token = session.segmentReferer
            ?.substringAfter("token=", "")
            ?.takeIf { it.isNotEmpty() }
        val primary = v7FetchUrl(url, token)
        Log.i(TAG, "fetchBytes p=$priority ${primary.substringBefore('?').take(100)}")

        val future = inflight.computeIfAbsent(primary) { key ->
            val f = CompletableFuture<ByteArray?>()
            fetchJobs.put(
                FetchJob(
                    priority = priority,
                    seq = jobSeq.incrementAndGet(),
                    url = key,
                    session = session,
                    future = f
                )
            )
            f.whenComplete { _, _ -> inflight.remove(key, f) }
            f
        }
        return try {
            future.get(25, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "fetchBytes wait failed: ${e.message}")
            null
        }
    }

    private fun prefetchAhead(remote: String, session: Session) {
        if (fetchPriority(remote) != 0) return
        val n = segNumber(remote) ?: return
        if (fetchJobs.size > 3) return
        thread(isDaemon = true, name = "FlixCloud-Prefetch") {
            for (delta in 1..2) {
                val next = remote.replaceFirst("seg-$n", "seg-${n + delta}")
                if (cachedSegment(next) != null) continue
                try {
                    val raw = fetchBytes(next, session, priority = 3) ?: continue
                    val cleaned = normalizeSegment(raw, session.xorKey) ?: continue
                    rememberSegment(next, cleaned)
                } catch (_: Exception) {
                }
            }
            trimMaps()
        }
    }

    /** Append `token=...` to [url] if it does not already carry a token. */
    private fun withToken(url: String, token: String): String {
        if (url.contains("token=")) return url
        return if (url.contains('?')) "$url&token=$token" else "$url?token=$token"
    }

    /** Dedicated OkHttp fetch — not Cloudstream's shared NiceHttp client. */
    private fun fetchViaOkHttp(url: String, session: Session): ByteArray? {
        val resolved = rewriteCdnHost(url)
        val ua = session.headers["User-Agent"] ?: "Mozilla/5.0"
        val req = Request.Builder()
            .url(resolved)
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .header("Referer", "https://flixcloud.cc/")
            .header("Origin", "https://flixcloud.cc")
            .get()
            .build()
        return try {
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "upstream HTTP ${res.code} url=${resolved.take(110)}")
                    return null
                }
                val bytes = res.body?.bytes() ?: return null
                if (bytes.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "fetched ${bytes.size}b head=${bytes.take(4).joinToString("") { "%02x".format(it) }} " +
                            "url=${resolved.substringBefore('?').take(80)}"
                    )
                    bytes
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed ${resolved.take(80)}: ${e.message}")
            null
        }
    }

    /**
     * Recover MPEG-TS / fMP4 / packed ADTS. Matches FlixCloud hls.js:
     * WEBP wrapper is 12 bytes (RIFF+size+WEBP), PNG is 8 bytes; then XOR with
     * a fixed 16-byte key unless the payload already starts with 0x47.
     */
    private fun normalizeSegment(raw: ByteArray, xorKey: ByteArray?): ByteArray? {
        val stripped = stripImageContainer(raw)
        extractMedia(stripped)?.let { return it }

        // Player: skip XOR when the first payload byte is already a TS sync.
        if (stripped.isNotEmpty() && stripped[0] != 0x47.toByte()) {
            extractMedia(xorBytes(stripped, SEGMENT_XOR_KEY))?.let { return it }
        }

        if (xorKey != null && xorKey.isNotEmpty() && xorKey !== SEGMENT_XOR_KEY) {
            extractMedia(xorBytes(stripped, xorKey))?.let { return it }
            if (stripped !== raw) extractMedia(xorBytes(raw, xorKey))?.let { return it }
        }
        if (stripped !== raw) extractMedia(raw)?.let { return it }
        return null
    }

    private fun extractMedia(data: ByteArray): ByteArray? {
        if (strictTs(data)) return data
        if (looksLikeFmp4(data)) return data
        if (looksLikeAdts(data)) return data
        findStrictTsStart(data)?.let { start ->
            val sliced = if (start == 0) data else data.copyOfRange(start, data.size)
            if (strictTs(sliced)) return sliced
        }
        return null
    }

    private fun xorBytes(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

    /** Walk RIFF chunks and try AES-CBC; used when simple strip/XOR fails. */
    private fun deepRecover(raw: ByteArray, xorKey: ByteArray?): ByteArray? {
        val keys = listOfNotNull(SEGMENT_XOR_KEY, xorKey)
        for (base in listOf(raw) + keys.map { xorBytes(raw, it) }) {
            extractRiffChunks(base).forEach { chunk ->
                extractMedia(chunk)?.let { return it }
                for (k in keys) extractMedia(xorBytes(chunk, k))?.let { return it }
            }
            for (k in keys) {
                tryAesDecrypt(base, k)?.let { dec ->
                    extractMedia(dec)?.let { return it }
                    extractMedia(stripImageContainer(dec))?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractRiffChunks(data: ByteArray): List<ByteArray> {
        if (data.size < 12) return emptyList()
        if (!(data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
                data[2] == 0x46.toByte() && data[3] == 0x46.toByte())
        ) return emptyList()
        val out = ArrayList<ByteArray>()
        var pos = 12
        while (pos + 8 <= data.size) {
            val size = (data[pos + 4].toInt() and 0xff) or
                ((data[pos + 5].toInt() and 0xff) shl 8) or
                ((data[pos + 6].toInt() and 0xff) shl 16) or
                ((data[pos + 7].toInt() and 0xff) shl 24)
            if (size < 0 || pos + 8 + size > data.size) break
            out.add(data.copyOfRange(pos + 8, pos + 8 + size))
            pos += 8 + size + (size and 1)
        }
        return out
    }

    private fun tryAesDecrypt(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.size < 32) return null
        val aligned = data.copyOf(data.size - (data.size % 16))
        if (aligned.size < 32) return null
        val keyVariants = buildList {
            if (key.size >= 16) add(key.copyOf(16))
            if (key.size >= 24) add(key.copyOf(24))
            if (key.size >= 32) add(key.copyOf(32))
        }
        val ivs = listOf(ByteArray(16), if (key.size >= 16) key.copyOf(16) else ByteArray(16))
        for (k in keyVariants) {
            for (iv in ivs) {
                for (transform in listOf("AES/CBC/NoPadding", "AES/CBC/PKCS5Padding")) {
                    try {
                        val cipher = javax.crypto.Cipher.getInstance(transform)
                        cipher.init(
                            javax.crypto.Cipher.DECRYPT_MODE,
                            javax.crypto.spec.SecretKeySpec(k, "AES"),
                            javax.crypto.spec.IvParameterSpec(iv)
                        )
                        val out = cipher.doFinal(aligned)
                        if (out.isNotEmpty()) return out
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return null
    }

    private fun isPngMagic(data: ByteArray): Boolean =
        data.size >= 4 &&
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()

    private fun isRiffMagic(data: ByteArray): Boolean =
        data.size >= 4 &&
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte() && data[3] == 0x46.toByte()

    /** Real PNG starts with an IHDR chunk (length 13) immediately after the signature. */
    private fun isRealPng(data: ByteArray): Boolean =
        data.size >= 16 &&
            data[8] == 0x00.toByte() && data[9] == 0x00.toByte() &&
            data[10] == 0x00.toByte() && data[11] == 0x0D.toByte() &&
            data[12] == 0x49.toByte() && data[13] == 0x48.toByte() &&
            data[14] == 0x44.toByte() && data[15] == 0x52.toByte()

    private fun stripImageContainer(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        // Fake WEBP: RIFF....WEBP + XOR payload. Always skip the 12-byte header
        // (hls.js uses slice(12)). A zero/huge RIFF size is not a real image.
        if (data.size >= 12 && isRiffMagic(data) &&
            data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
            data[10] == 0x42.toByte() && data[11] == 0x50.toByte()
        ) {
            return data.copyOfRange(12, data.size)
        }
        if (isPngMagic(data)) {
            if (isRealPng(data)) {
                val iend = indexOf(data, byteArrayOf(0x49, 0x45, 0x4E, 0x44))
                if (iend >= 0 && iend + 8 < data.size) {
                    return data.copyOfRange(iend + 8, data.size)
                }
            }
            return data.copyOfRange(8, data.size)
        }
        if (isRiffMagic(data) && data.size > 12) {
            return data.copyOfRange(12, data.size)
        }
        return data
    }

    /** Require several consecutive 0x47 syncs at 188-byte spacing (avoid WebP false positives). */
    private fun strictTs(data: ByteArray): Boolean {
        if (data.size < 188 * 5) return false
        var i = 0
        var count = 0
        while (i + 188 <= data.size && count < 8) {
            if (data[i] != 0x47.toByte()) return false
            // basic TEI/PID sanity: transport_error_indicator should be 0
            if ((data[i + 1].toInt() and 0x80) != 0) return false
            count++
            i += 188
        }
        return count >= 5
    }

    private fun looksLikeFmp4(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val box = String(data, 4, minOf(4, data.size - 4), Charsets.US_ASCII)
        return box == "ftyp" || box == "moof" || box == "mdat" || box == "styp"
    }

    /** Packed-audio HLS (ADTS AAC) used by some audio rendition groups. */
    private fun looksLikeAdts(data: ByteArray): Boolean {
        if (data.size < 64) return false
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return false
        val frameLen = ((b1 and 0x03) shl 11) or
            ((data[2].toInt() and 0xFF) shl 3) or
            ((data[3].toInt() and 0xFF) ushr 5)
        return frameLen in 7..data.size
    }

    private fun findStrictTsStart(data: ByteArray, from: Int = 0): Int? {
        var i = from.coerceAtLeast(0)
        val limit = data.size - 188 * 5
        while (i <= limit) {
            if (data[i] == 0x47.toByte()) {
                var ok = true
                for (n in 1 until 5) {
                    val p = i + n * 188
                    if (data[p] != 0x47.toByte() || (data[p + 1].toInt() and 0x80) != 0) {
                        ok = false
                        break
                    }
                }
                if (ok) return i
            }
            i++
        }
        return null
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun writeResponse(
        out: java.io.OutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        cache: Boolean = false
    ) {
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            404 -> "404 Not Found"
            502 -> "502 Bad Gateway"
            else -> "$code"
        }
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append(
                if (cache) "Cache-Control: public, max-age=3600\r\n"
                else "Cache-Control: no-store\r\n"
            )
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
