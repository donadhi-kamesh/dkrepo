package com.dkrepo

import com.lagradost.api.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Localhost HTTP server that serves unwrapped m3u8 playlists and proxies
 * segment/key fetches so we can:
 *  - re-apply FlixCloud headers (Referer/Origin/UA)
 *  - strip PNG/WebP disguises and optional XOR wrapping to real MPEG-TS
 *
 * Needed because Cronet rejects `data:` URIs and Cloudstream drops blank
 * ExtractorLinkPlayList urls.
 */
object LocalPlaylistServer {
    private const val TAG = "FlixCloudLocal"
    private val playlists = ConcurrentHashMap<String, ByteArray>()
    private val segments = ConcurrentHashMap<String, ProxiedResource>()

    data class ProxiedResource(
        val remoteUrl: String,
        val headers: Map<String, String>,
        val xorKey: ByteArray?
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

    /**
     * Rewrite remote segment/key URIs to localhost proxy paths, store the
     * playlist, and return a localhost m3u8 URL.
     */
    fun publish(
        body: String,
        headers: Map<String, String>,
        xorKey: ByteArray? = null
    ): String {
        val p = ensureStarted()
        val rewritten = rewritePlaylist(body, headers, xorKey, p)
        val id = UUID.randomUUID().toString().replace("-", "")
        playlists[id] = rewritten.toByteArray(Charsets.UTF_8)
        trimMaps()
        return "http://127.0.0.1:$p/$id.m3u8"
    }

    private fun rewritePlaylist(
        body: String,
        headers: Map<String, String>,
        xorKey: ByteArray?,
        p: Int
    ): String {
        val uriTagRegex = Regex("""URI=["']([^"']+)["']""")
        return body.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> {
                    uriTagRegex.replace(line) { match ->
                        val uri = match.groupValues[1]
                        if (uri.startsWith("http://") || uri.startsWith("https://")) {
                            """URI="${proxyUrl(uri, headers, xorKey, p)}""""
                        } else {
                            match.value
                        }
                    }
                }
                t.startsWith("http://") || t.startsWith("https://") ->
                    proxyUrl(t, headers, xorKey, p)
                else -> line
            }
        }
    }

    private fun proxyUrl(
        remote: String,
        headers: Map<String, String>,
        xorKey: ByteArray?,
        p: Int
    ): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        segments[id] = ProxiedResource(remote, headers, xorKey)
        return "http://127.0.0.1:$p/s/$id"
    }

    private fun trimMaps() {
        if (playlists.size > 32) {
            val keys = playlists.keys().toList()
            keys.take(keys.size - 24).forEach { playlists.remove(it) }
        }
        if (segments.size > 512) {
            val keys = segments.keys().toList()
            keys.take(keys.size - 384).forEach { segments.remove(it) }
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
                val path = requestLine.split(' ').getOrNull(1)
                    ?.substringBefore('?')
                    ?.trimStart('/')
                    ?: ""
                val out = s.getOutputStream()
                when {
                    path.startsWith("s/") -> {
                        val id = path.removePrefix("s/")
                        serveSegment(id, out)
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

    private fun serveSegment(id: String, out: java.io.OutputStream) {
        val res = segments[id]
        if (res == null) {
            writeResponse(out, 404, "text/plain", ByteArray(0))
            return
        }
        try {
            val raw = fetchBytes(res.remoteUrl, res.headers)
            if (raw == null) {
                Log.w(TAG, "segment fetch failed: ${res.remoteUrl.take(90)}")
                writeResponse(out, 502, "text/plain", ByteArray(0))
                return
            }
            val cleaned = normalizeSegment(raw, res.xorKey)
            if (!looksLikeTs(cleaned) && !looksLikeFmp4(cleaned)) {
                Log.w(
                    TAG,
                    "segment still not media after normalize " +
                        "len=${cleaned.size} head=${cleaned.take(8).joinToString("") { "%02x".format(it) }} " +
                        "url=${res.remoteUrl.take(90)}"
                )
            }
            writeResponse(out, 200, "video/mp2t", cleaned)
        } catch (e: Exception) {
            Log.w(TAG, "serveSegment failed: ${e.message}")
            writeResponse(out, 502, "text/plain", ByteArray(0))
        }
    }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
            headers["Referer"]?.let { setRequestProperty("Referer", it) }
            headers["Origin"]?.let { setRequestProperty("Origin", it) }
            // Avoid inheriting image Accept that some CDNs key off of
            setRequestProperty("Accept", "*/*")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() }
            if (code !in 200..299) {
                Log.w(TAG, "upstream HTTP $code for ${url.take(90)}")
                null
            } else {
                bytes
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Strip image disguise / XOR wrap so ExoPlayer sees MPEG-TS or fMP4. */
    private fun normalizeSegment(raw: ByteArray, xorKey: ByteArray?): ByteArray {
        var data = stripImageContainer(raw)
        if (looksLikeTs(data) || looksLikeFmp4(data)) return data
        if (xorKey != null && xorKey.isNotEmpty()) {
            val xored = ByteArray(data.size) { i ->
                (data[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte()
            }
            val stripped = stripImageContainer(xored)
            if (looksLikeTs(stripped) || looksLikeFmp4(stripped) ||
                looksLikeTs(xored) || looksLikeFmp4(xored)
            ) {
                return when {
                    looksLikeTs(stripped) || looksLikeFmp4(stripped) -> stripped
                    else -> xored
                }
            }
        }
        // Last resort: scan for TS sync even if header junk remains
        findTsStart(data)?.let { start ->
            if (start > 0) return data.copyOfRange(start, data.size)
        }
        return data
    }

    private fun stripImageContainer(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        // PNG
        if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        ) {
            val iend = indexOf(data, byteArrayOf(0x49, 0x45, 0x4E, 0x44))
            if (iend >= 0) {
                var start = iend + 8 // IEND + CRC
                while (start < data.size && (data[start] == 0xFF.toByte() || data[start] == 0x00.toByte())) {
                    start++
                }
                findTsStart(data, start)?.let { return data.copyOfRange(it, data.size) }
                if (start < data.size) return data.copyOfRange(start, data.size)
            }
        }
        // RIFF/WEBP
        if (data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte() && data[3] == 0x46.toByte()
        ) {
            findTsStart(data, 12)?.let { return data.copyOfRange(it, data.size) }
        }
        return data
    }

    private fun looksLikeTs(data: ByteArray): Boolean {
        if (data.size < 188) return false
        return data[0] == 0x47.toByte() && data.size >= 376 && data[188] == 0x47.toByte()
    }

    private fun looksLikeFmp4(data: ByteArray): Boolean {
        if (data.size < 8) return false
        // ....ftyp or ....moof / ....mdat
        val box = String(data, 4, minOf(4, data.size - 4), Charsets.US_ASCII)
        return box == "ftyp" || box == "moof" || box == "mdat" || box == "styp"
    }

    private fun findTsStart(data: ByteArray, from: Int = 0): Int? {
        var i = from
        while (i + 188 < data.size) {
            if (data[i] == 0x47.toByte() &&
                (i + 188 >= data.size || data[i + 188] == 0x47.toByte())
            ) {
                return i
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
