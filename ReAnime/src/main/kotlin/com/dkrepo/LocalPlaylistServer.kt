package com.dkrepo

import android.util.Base64
import com.lagradost.api.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Thread-safe bounded LRU cache for memory safety on Android devices.
 */
private class BoundedLruCache<K : Any, V : Any>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }
    private val lock = Any()

    fun get(key: K): V? = synchronized(lock) { map[key] }
    fun put(key: K, value: V): V? = synchronized(lock) { map.put(key, value) }
    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }
    fun clear() = synchronized(lock) { map.clear() }
    val size: Int get() = synchronized(lock) { map.size }
}

/**
 * Localhost HTTP server that serves unwrapped m3u8 playlists and proxies
 * segment/key fetches so we can strip PNG/WebP/XOR disguises to real MPEG-TS.
 */
object LocalPlaylistServer {
    private const val TAG = "FlixCloudLocal"
    private val playlists = ConcurrentHashMap<String, ByteArray>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val nestedPlaylistUrls = ConcurrentHashMap<String, String>()

    // Bounded LRU caches prevent memory thrashing and GC pauses
    private val segmentCache = BoundedLruCache<String, ByteArray>(64)
    private val playlistBodyCache = BoundedLruCache<String, ByteArray>(16)

    // In-flight deduplication keyed by canonical segment identity
    private val inflight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()

    // Independent playhead and generation tracking for Video and Audio lanes
    private val playHeadVideo = AtomicInteger(-1)
    private val playHeadAudio = AtomicInteger(-1)
    private val videoPrefetchGen = AtomicInteger(0)
    private val audioPrefetchGen = AtomicInteger(0)

    @Volatile
    private var activeVideoPrefetchCall: Call? = null

    @Volatile
    private var activeAudioPrefetchCall: Call? = null

    // 2-thread pool: 1 lane for sequential video prefetch, 1 lane for sequential audio prefetch
    private val prefetchPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "FlixCloud-Prefetch").apply { isDaemon = true }
    }

    // Thread pool for handling incoming client socket requests
    private val clientPool = Executors.newCachedThreadPool { r ->
        Thread(r, "FlixCloud-Client").apply { isDaemon = true }
    }

    /** Last CDN disguise that returned 200, keyed by directory (`.../_v7/uuid` vs `.../audio`). */
    private val workingExtByDir = ConcurrentHashMap<String, String>()

    /**
     * Dedicated OkHttpClient configured with high connection limits, connection reuse,
     * and retry capability.
     */
    private val httpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 16
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
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
                    client.tcpNoDelay = true
                    client.sendBufferSize = 64 * 1024
                    clientPool.execute {
                        handle(client)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        Log.i(TAG, "listening on 127.0.0.1:$port")
        return port
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
        playHeadVideo.set(-1)
        playHeadAudio.set(-1)
        videoPrefetchGen.incrementAndGet()
        audioPrefetchGen.incrementAndGet()
        activeVideoPrefetchCall?.cancel()
        activeAudioPrefetchCall?.cancel()
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val session = Session(headers, xorKey, segmentReferer, allowOpaque, embedUrl)
        sessions[sessionId] = session
        val rewritten = rewritePlaylist(body, sessionId, p)
        val id = UUID.randomUUID().toString().replace("-", "")
        playlists[id] = rewritten.toByteArray(Charsets.UTF_8)
        trimMaps()

        // Pre-warm initial segment in background for instant playback start (<100ms)
        prefetchPool.execute {
            try {
                val lines = body.lines().map { it.trim() }
                val initialSeg = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.contains(".m3u8") }
                if (initialSeg != null) {
                    val raw = fetchBytes(initialSeg, session, isPrefetch = true)
                    if (raw != null) {
                        val cleaned = normalizeSegment(raw, session.xorKey)
                        if (cleaned != null) {
                            rememberSegment(initialSeg, cleaned)
                            Log.i(TAG, "pre-warmed initial segment ${cleaned.size}b")
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return "http://127.0.0.1:$p/$id.m3u8"
    }

    private fun rewritePlaylist(
        body: String,
        sessionId: String,
        p: Int
    ): String {
        val uriTagRegex = Regex("""URI=["']([^"']+)["']""")
        fun mapRemote(uri: String): String {
            val url = rewriteCdnHost(uri)
            return proxyUrl(sessionId, url, p)
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
        if (playlists.size > 64) {
            val keys = playlists.keys().toList()
            keys.take(keys.size - 48).forEach { playlists.remove(it) }
        }
        if (sessions.size > 32) {
            val keys = sessions.keys().toList()
            keys.take(keys.size - 24).forEach { sessions.remove(it) }
        }
        if (nestedPlaylistUrls.size > 64) {
            val keys = nestedPlaylistUrls.keys().toList()
            keys.take(keys.size - 48).forEach { nestedPlaylistUrls.remove(it) }
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
                val out = BufferedOutputStream(s.getOutputStream(), 32 * 1024)
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
     * Serve a media/variant playlist back to ExoPlayer.
     */
    private fun servePlaylist(remote: String, session: Session, sessionId: String, out: java.io.OutputStream) {
        val cacheKey = segmentIdentityKey(remote)
        playlistBodyCache.get(cacheKey)?.let { cached ->
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
        val rewritten = rewritePlaylist(vod, sessionId, port)
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
        playlistBodyCache.put(cacheKey, bytes)
        writeResponse(out, 200, "application/vnd.apple.mpegurl", bytes, cache = true)
    }

    /**
     * Fetch raw playlist bytes from fetch.flixcloud.cc.
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

        // Return cached segment immediately if available (100% cache hit via canonical key)
        cachedSegment(remote)?.let { cached ->
            val mime = detectMimeType(cached)
            writeResponse(out, 200, mime, cached, cache = true)
            onSegmentPlayed(remote, session)
            return
        }

        // Handle nested media playlist
        if (looksLikePlaylistUrl(remote)) {
            servePlaylist(remote, session, sessionId, out)
            return
        }

        try {
            val raw = fetchBytes(remote, session, isPrefetch = false)
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
            val mime = detectMimeType(cleaned)
            Log.i(
                TAG,
                "segment ok len=${cleaned.size} mime=$mime opaque=$opaque " +
                    "head=${cleaned.take(4).joinToString("") { "%02x".format(it) }} " +
                    "from ${remote.substringBefore('?').take(80)}"
            )
            rememberSegment(remote, cleaned)
            try {
                writeResponse(out, 200, mime, cleaned, cache = true)
            } catch (e: Exception) {
                Log.w(TAG, "client gone after fetch: ${e.message}")
            }
            onSegmentPlayed(remote, session)
        } catch (e: Exception) {
            Log.w(TAG, "serveSegment failed: ${e.message}")
            try { writeResponse(out, 502, "text/plain", ByteArray(0)) } catch (_: Exception) {}
        }
    }

    private fun detectMimeType(data: ByteArray): String = when {
        looksLikeFmp4(data) -> "video/mp4"
        looksLikeAdts(data) -> "audio/aac"
        else -> "video/mp2t"
    }

    /**
     * Maps all variations of a segment (slopnet vs fetch, with/without fake .woff2, tokens)
     * to a single canonical identity key.
     */
    private fun segmentIdentityKey(url: String): String {
        val rewritten = rewriteCdnHost(url)
        val noQuery = rewritten.substringBefore('?')
        val path = runCatching { java.net.URL(noQuery).path }.getOrNull() ?: noQuery
        val isAudio = path.contains("/audio/")
        val dir = path.substringBeforeLast('/')
        val segNum = Regex("seg-(\\d+)").find(path)?.groupValues?.get(1) ?: return path
        return if (isAudio) "$dir/audio/seg-$segNum" else "$dir/seg-$segNum"
    }

    private fun cachedSegment(remote: String): ByteArray? {
        return segmentCache.get(segmentIdentityKey(remote))
    }

    private fun rememberSegment(remote: String, cleaned: ByteArray) {
        segmentCache.put(segmentIdentityKey(remote), cleaned)
    }

    /**
     * Vault/lock/rundown CDN hosts 403 OkHttp. The working path is always
     * fetch.flixcloud.cc + JWT. Skip the 403 round-trips.
     */
    private fun v7FetchUrl(url: String, token: String?): String {
        val resolved = rewriteCdnHost(url)
        val path = runCatching { java.net.URL(resolved).path }.getOrNull()
        val base = if (path != null && path.startsWith("/_v7/")) {
            "https://fetch.flixcloud.cc$path"
        } else resolved
        return if (!token.isNullOrEmpty()) withToken(base, token) else base
    }

    private fun fetchBytes(
        url: String,
        session: Session,
        isPrefetch: Boolean = false,
        prefetchGen: Int? = null,
        isAudio: Boolean = false
    ): ByteArray? {
        val token = session.segmentReferer
            ?.substringAfter("token=", "")
            ?.takeIf { it.isNotEmpty() }
        val primary = v7FetchUrl(url, token)
        val identity = segmentIdentityKey(primary)

        if (!isPrefetch) Log.i(TAG, "fetchBytes ${primary.substringBefore('?').take(100)}")

        // Deduplicate in-flight requests using segmentIdentityKey
        inflight[identity]?.let { existing ->
            return try {
                existing.get(25, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "fetchBytes wait failed: ${e.message}")
                null
            }
        }

        val created = CompletableFuture<ByteArray?>()
        val existing = inflight.putIfAbsent(identity, created)
        if (existing != null) {
            return try {
                existing.get(25, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "fetchBytes wait failed: ${e.message}")
                null
            }
        }

        return try {
            var bytes: ByteArray? = null
            for (candidate in segmentUrlVariants(primary)) {
                // Abort candidate search if prefetch generation was cancelled
                if (prefetchGen != null) {
                    val genRef = if (isAudio) audioPrefetchGen else videoPrefetchGen
                    if (genRef.get() != prefetchGen) break
                }

                bytes = fetchViaOkHttp(candidate, session, isPrefetch, isAudio)
                if (bytes != null && bytes.isNotEmpty()) {
                    rememberWorkingExt(candidate)
                    break
                }
            }
            created.complete(bytes)
            bytes
        } catch (e: Exception) {
            created.completeExceptionally(e)
            Log.w(TAG, "fetchBytes failed: ${e.message}")
            null
        } finally {
            inflight.remove(identity, created)
        }
    }

    /** Append `token=...` to [url] if it does not already carry a token. */
    private fun withToken(url: String, token: String): String {
        if (url.contains("token=")) return url
        return if (url.contains('?')) "$url&token=$token" else "$url?token=$token"
    }

    /**
     * Extensions to probe. Empty string (no extension) and .png / .webp are prioritised.
     */
    private val SEGMENT_EXTS = listOf("", ".png", ".webp", ".jpg", ".gif", ".woff2", ".woff", ".ttf")

    private fun pathExt(url: String): String {
        val name = url.substringBefore('?').substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot) else ""
    }

    private fun dirKey(url: String): String =
        url.substringBefore('?').substringBeforeLast('/')

    private fun replaceExt(url: String, ext: String): String {
        val noQuery = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        val name = noQuery.substringAfterLast('/')
        val dir = noQuery.substringBeforeLast('/', missingDelimiterValue = "")
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val next = if (dir.isEmpty()) stem + ext else "$dir/$stem$ext"
        return if (query.isEmpty()) next else "$next?$query"
    }

    private fun rememberWorkingExt(url: String) {
        workingExtByDir[dirKey(url)] = pathExt(url)
    }

    private fun segmentUrlVariants(url: String): List<String> {
        val dir = dirKey(url)
        val known = workingExtByDir[dir]
        val out = ArrayList<String>(SEGMENT_EXTS.size + 2)

        if (known != null) {
            out.add(replaceExt(url, known))
        }

        for (ext in SEGMENT_EXTS) {
            val candidate = replaceExt(url, ext)
            if (!out.contains(candidate)) out.add(candidate)
        }

        if (!out.contains(url)) out.add(url)
        return out
    }

    private fun segNumber(url: String): Int? =
        Regex("seg-(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun nextSegmentUrl(url: String, targetSeg: Int): String =
        url.replaceFirst(Regex("seg-\\d+"), "seg-$targetSeg")

    /**
     * Called whenever a segment is actively played/requested by ExoPlayer.
     * Updates playHead. If user seeked or jumped, cancels stale prefetch and starts fresh;
     * otherwise smoothly extends prefetch ahead without interrupting in-flight downloads.
     */
    private fun onSegmentPlayed(url: String, session: Session) {
        val n = segNumber(url) ?: return
        val isAudio = url.contains("/audio/")
        val headRef = if (isAudio) playHeadAudio else playHeadVideo
        val genRef = if (isAudio) audioPrefetchGen else videoPrefetchGen

        val prev = headRef.getAndSet(n)
        val isSeek = prev < 0 || kotlin.math.abs(n - prev) > 2

        val gen = if (isSeek) {
            // User seeked or jumped: cancel previous stale prefetch HTTP call immediately
            if (isAudio) activeAudioPrefetchCall?.cancel() else activeVideoPrefetchCall?.cancel()
            genRef.incrementAndGet()
        } else {
            genRef.get()
        }

        prefetchPool.execute {
            runPrefetchLoop(url, session, n, isAudio, gen)
        }
    }

    /**
     * Sequentially prefetches upcoming segments (1 at a time) to keep 8-10 segments (~25-30s)
     * of buffer ready in cache without overwhelming the CDN or network.
     */
    private fun runPrefetchLoop(
        baseUrl: String,
        session: Session,
        currentSeg: Int,
        isAudio: Boolean,
        gen: Int
    ) {
        val genRef = if (isAudio) audioPrefetchGen else videoPrefetchGen
        val maxLookahead = if (isAudio) 10 else 8

        for (delta in 1..maxLookahead) {
            if (genRef.get() != gen) return // Abort if seeked or playhead moved

            val targetSeg = currentSeg + delta
            val nextUrl = nextSegmentUrl(baseUrl, targetSeg)

            if (cachedSegment(nextUrl) != null) continue

            try {
                val raw = fetchBytes(
                    nextUrl,
                    session,
                    isPrefetch = true,
                    prefetchGen = gen,
                    isAudio = isAudio
                ) ?: continue

                if (genRef.get() != gen) return
                val cleaned = normalizeSegment(raw, session.xorKey) ?: continue
                rememberSegment(nextUrl, cleaned)
                Log.i(
                    TAG,
                    "prefetched seg-$targetSeg (${if (isAudio) "audio" else "video"}) ${cleaned.size}b"
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Dedicated OkHttp fetch with HTTP/2 multiplexing and active Call tracking. */
    private fun fetchViaOkHttp(
        url: String,
        session: Session,
        isPrefetch: Boolean = false,
        isAudio: Boolean = false
    ): ByteArray? {
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

        val call = httpClient.newCall(req)
        if (isPrefetch) {
            if (isAudio) activeAudioPrefetchCall = call else activeVideoPrefetchCall = call
        }

        return try {
            call.execute().use { res ->
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
            if (!call.isCanceled()) {
                Log.w(TAG, "fetch failed ${resolved.take(80)}: ${e.message}")
            }
            null
        } finally {
            if (isPrefetch) {
                if (isAudio) {
                    if (activeAudioPrefetchCall === call) activeAudioPrefetchCall = null
                } else {
                    if (activeVideoPrefetchCall === call) activeVideoPrefetchCall = null
                }
            }
        }
    }

    /**
     * Recover MPEG-TS / fMP4 / packed ADTS. Matches FlixCloud hls.js:
     * WEBP wrapper is 12 bytes (RIFF+size+WEBP), PNG is 8 bytes; then XOR with
     * a fixed 16-byte key unless the payload already starts with 0x47.
     * Preserves PTS timestamps across all packets to guarantee perfect A/V sync.
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
        if (strictTs(data) || looksLikeFmp4(data) || looksLikeAdts(data)) return data
        return null
    }

    /**
     * High-speed primitive array XOR loop without lambda boxing overhead.
     */
    private fun xorBytes(data: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        val keyLen = key.size
        var i = 0
        val len = data.size
        while (i < len) {
            out[i] = (data[i].toInt() xor key[i % keyLen].toInt()).toByte()
            i++
        }
        return out
    }

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

    private fun stripImageContainer(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        // Fake WEBP: RIFF....WEBP + XOR payload. Always skip exactly 12-byte header
        if (data.size >= 12 && isRiffMagic(data) &&
            data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
            data[10] == 0x42.toByte() && data[11] == 0x50.toByte()
        ) {
            return data.copyOfRange(12, data.size)
        }
        // Fake PNG: Exactly 8 bytes (\x89PNG\r\n\x1a\n)
        if (isPngMagic(data)) {
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
