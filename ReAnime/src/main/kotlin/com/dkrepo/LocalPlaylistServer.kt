package com.dkrepo

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

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
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        sessions[sessionId] = Session(headers, xorKey, segmentReferer, allowOpaque, embedUrl)
        val rewritten = rewritePlaylist(body, sessionId, p)
        val id = UUID.randomUUID().toString().replace("-", "")
        playlists[id] = rewritten.toByteArray(Charsets.UTF_8)
        trimMaps()
        return "http://127.0.0.1:$p/$id.m3u8"
    }

    private fun rewritePlaylist(body: String, sessionId: String, p: Int): String {
        val uriTagRegex = Regex("""URI=["']([^"']+)["']""")
        return body.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> {
                    uriTagRegex.replace(line) { match ->
                        val uri = match.groupValues[1]
                        if (uri.startsWith("http://") || uri.startsWith("https://")) {
                            """URI="${proxyUrl(sessionId, uri, p)}""""
                        } else {
                            match.value
                        }
                    }
                }
                t.startsWith("http://") || t.startsWith("https://") ->
                    proxyUrl(sessionId, t, p)
                else -> line
            }
        }
    }

    private fun proxyUrl(sessionId: String, remote: String, p: Int): String {
        val enc = Base64.encodeToString(
            remote.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "http://127.0.0.1:$p/s/$sessionId?u=$enc"
    }

    private fun trimMaps() {
        if (playlists.size > 32) {
            val keys = playlists.keys().toList()
            keys.take(keys.size - 24).forEach { playlists.remove(it) }
        }
        if (sessions.size > 32) {
            val keys = sessions.keys().toList()
            keys.take(keys.size - 24).forEach { sessions.remove(it) }
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
                            writeResponse(out, 200, "application/vnd.apple.mpegurl", body)
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

    private fun serveSegment(sessionId: String, enc: String?, out: java.io.OutputStream) {
        val session = sessions[sessionId]
        if (session == null || enc.isNullOrBlank()) {
            writeResponse(out, 404, "text/plain", ByteArray(0))
            return
        }
        val remote = try {
            String(
                Base64.decode(enc, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
        } catch (e: Exception) {
            writeResponse(out, 400, "text/plain", ByteArray(0))
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
            val mime = if (looksLikeFmp4(cleaned)) "video/mp4" else "video/mp2t"
            Log.i(
                TAG,
                "segment ok len=${cleaned.size} mime=$mime opaque=$opaque " +
                    "head=${cleaned.take(4).joinToString("") { "%02x".format(it) }} " +
                    "from ${remote.substringBefore('?').take(80)}"
            )
            writeResponse(out, 200, mime, cleaned)
        } catch (e: Exception) {
            Log.w(TAG, "serveSegment failed: ${e.message}")
            writeResponse(out, 502, "text/plain", ByteArray(0))
        }
    }

    /**
     * Fetch a segment from the first source that returns real playable media:
     *  1. vault94/slopnet/rundowncdn (real CDN, no token) via OkHttp — works if
     *     the device's OkHttp fingerprint passes the vault Cloudflare zone.
     *  2. the same vault URL via the hidden WebView (real Chromium TLS + JS, so
     *     the CF managed challenge passes).
     *  3. fetch.flixcloud.cc with the media-playlist JWT as a last resort (its
     *     segments are usually poisoned but occasionally serve real TS).
     */
    private fun fetchBytes(url: String, session: Session): ByteArray? {
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrNull() ?: ""
        val vaultLike = host.contains("vault") || host.contains("slopnet") ||
            host.contains("rundowncdn") || (host.contains("cdn") && !host.contains("flixcloud"))

        if (vaultLike) {
            // 1) OkHttp fast path (real CDN, no token)
            val ok = fetchViaOkHttp(url, session)
            if (ok != null && normalizeSegment(ok, session.xorKey) != null) return ok
            Log.w(TAG, "okhttp vault path yielded no media for ${url.substringBefore('?').take(90)}")

            // 2) WebView path (real browser TLS passes the Cloudflare challenge)
            val wv = runBlocking {
                WebViewSegmentFetcher.fetchBytes(url, session.embedUrl ?: "https://flixcloud.cc/")
            }
            if (wv != null && normalizeSegment(wv, session.xorKey) != null) {
                Log.i(TAG, "webview segment ok len=${wv.size} from ${url.substringBefore('?').take(80)}")
                return wv
            }
            Log.w(TAG, "webview vault path yielded no media for ${url.substringBefore('?').take(90)}")

            // 3) Last resort: same path on fetch.flixcloud.cc with the media JWT
            val token = session.segmentReferer
                ?.substringAfter("token=", "")
                ?.takeIf { it.isNotEmpty() }
            val path = runCatching { java.net.URL(url).path }.getOrNull()
            if (token != null && path != null && path.startsWith("/_v7/")) {
                val fetchUrl = "https://fetch.flixcloud.cc$path?token=$token"
                val fb = fetchViaOkHttp(fetchUrl, session)
                if (fb != null && normalizeSegment(fb, session.xorKey) != null) return fb
            }
            return null
        }

        // fetch.flixcloud.cc and other hosts: plain OkHttp path
        return fetchViaOkHttp(url, session)?.takeIf { normalizeSegment(it, session.xorKey) != null }
    }

    /** Plain OkHttp fetch through Cloudstream's shared NiceHttp client. */
    private fun fetchViaOkHttp(url: String, session: Session): ByteArray? {
        val referers = listOfNotNull(
            session.segmentReferer,
            "https://flixcloud.cc/",
            session.headers["Referer"]
        ).distinct()
        // No Origin — some CDNs 403 on non-browser Origin from OkHttp stacks
        val baseHeaders = mapOf(
            "User-Agent" to (session.headers["User-Agent"] ?: "Mozilla/5.0"),
            "Accept" to "*/*"
        )

        for (ref in referers) {
            try {
                val res = runBlocking {
                    app.get(
                        url,
                        referer = ref,
                        headers = baseHeaders,
                        timeout = 60
                    )
                }
                if (!res.isSuccessful) {
                    Log.w(
                        TAG,
                        "upstream HTTP ${res.code} ref=${ref.take(70)} " +
                            "token=${url.contains("token=")} url=${url.take(110)}"
                    )
                    continue
                }
                val bytes = res.body.bytes()
                if (bytes.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "fetched ${bytes.size}b head=${bytes.take(4).joinToString("") { "%02x".format(it) }} " +
                            "url=${url.substringBefore('?').take(80)}"
                    )
                    return bytes
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed ref=${ref.take(70)}: ${e.message}")
            }
        }
        Log.w(TAG, "segment fetch failed: token=${url.contains("token=")} ${url.take(110)}")
        return null
    }

    /**
     * Recover real MPEG-TS / fMP4 from FlixCloud image-disguised (and optionally
     * XOR-wrapped) segment bodies. Returns null if nothing validates — never
     * feed ExoPlayer a false-positive "TS" (that causes Encoding error).
     */
    private fun normalizeSegment(raw: ByteArray, xorKey: ByteArray?): ByteArray? {
        // 1) raw / xor(raw)
        extractMedia(raw)?.let { return it }
        if (xorKey != null && xorKey.isNotEmpty()) {
            extractMedia(xorBytes(raw, xorKey))?.let { return it }
        }

        // 2) strip image container, then optional XOR
        val stripped = stripImageContainer(raw)
        if (stripped !== raw) {
            extractMedia(stripped)?.let { return it }
            if (xorKey != null && xorKey.isNotEmpty()) {
                extractMedia(xorBytes(stripped, xorKey))?.let { return it }
            }
        }

        // 3) XOR then strip (image magic itself may be XOR'd)
        if (xorKey != null && xorKey.isNotEmpty()) {
            val xored = xorBytes(raw, xorKey)
            val xStrip = stripImageContainer(xored)
            if (xStrip !== xored) {
                extractMedia(xStrip)?.let { return it }
            }
        }
        return null
    }

    private fun extractMedia(data: ByteArray): ByteArray? {
        if (strictTs(data)) return data
        if (looksLikeFmp4(data)) return data
        findStrictTsStart(data)?.let { start ->
            val sliced = if (start == 0) data else data.copyOfRange(start, data.size)
            if (strictTs(sliced)) return sliced
        }
        val stripped = stripImageContainer(data)
        if (stripped !== data) {
            if (strictTs(stripped)) return stripped
            if (looksLikeFmp4(stripped)) return stripped
            findStrictTsStart(stripped)?.let { start ->
                val sliced = if (start == 0) stripped else stripped.copyOfRange(start, stripped.size)
                if (strictTs(sliced)) return sliced
            }
        }
        return null
    }

    private fun xorBytes(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

    /** Walk RIFF chunks and try AES-CBC; used when simple strip/XOR fails. */
    private fun deepRecover(raw: ByteArray, xorKey: ByteArray?): ByteArray? {
        for (base in listOfNotNull(raw, xorKey?.let { xorBytes(raw, it) })) {
            extractRiffChunks(base).forEach { chunk ->
                extractMedia(chunk)?.let { return it }
                if (xorKey != null) extractMedia(xorBytes(chunk, xorKey))?.let { return it }
            }
            if (xorKey != null) {
                tryAesDecrypt(base, xorKey)?.let { dec ->
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

    private fun stripImageContainer(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        // PNG — payload after IEND+CRC (+ padding)
        if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        ) {
            val iend = indexOf(data, byteArrayOf(0x49, 0x45, 0x4E, 0x44))
            if (iend >= 0) {
                var start = iend + 8
                while (start < data.size &&
                    (data[start] == 0xFF.toByte() || data[start] == 0x00.toByte())
                ) {
                    start++
                }
                if (start < data.size) return data.copyOfRange(start, data.size)
            }
        }
        // RIFF / WEBP — payload after full RIFF chunk
        if (data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte() && data[3] == 0x46.toByte()
        ) {
            val riffSize = (data[4].toInt() and 0xff) or
                ((data[5].toInt() and 0xff) shl 8) or
                ((data[6].toInt() and 0xff) shl 16) or
                ((data[7].toInt() and 0xff) shl 24)
            val after = 8 + riffSize
            if (after in 1 until data.size) return data.copyOfRange(after, data.size)
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
        body: ByteArray
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
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
