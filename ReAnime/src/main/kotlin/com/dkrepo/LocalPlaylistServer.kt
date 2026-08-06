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
        val segmentReferer: String?
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
        segmentReferer: String? = null
    ): String {
        val p = ensureStarted()
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        sessions[sessionId] = Session(headers, xorKey, segmentReferer)
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
            val cleaned = normalizeSegment(raw, session.xorKey)
            if (!looksLikeTs(cleaned) && !looksLikeFmp4(cleaned)) {
                Log.w(
                    TAG,
                    "segment still not media after normalize len=${cleaned.size} " +
                        "head=${cleaned.take(8).joinToString("") { "%02x".format(it) }} " +
                        "token=${remote.contains("token=")} url=${remote.take(100)}"
                )
            } else {
                Log.i(TAG, "segment ok len=${cleaned.size} from ${remote.substringBefore('?').take(80)}")
            }
            writeResponse(out, 200, "video/mp2t", cleaned)
        } catch (e: Exception) {
            Log.w(TAG, "serveSegment failed: ${e.message}")
            writeResponse(out, 502, "text/plain", ByteArray(0))
        }
    }

    /**
     * Fetch via Cloudstream's shared NiceHttp client (same stack that unwraps
     * playlists). Bare OkHttp/HttpURLConnection get vault/CDN 403s.
     */
    private fun fetchBytes(url: String, session: Session): ByteArray? {
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

    private fun normalizeSegment(raw: ByteArray, xorKey: ByteArray?): ByteArray {
        var data = stripImageContainer(raw)
        if (looksLikeTs(data) || looksLikeFmp4(data)) return data
        if (xorKey != null && xorKey.isNotEmpty()) {
            val xored = ByteArray(data.size) { i ->
                (data[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte()
            }
            val stripped = stripImageContainer(xored)
            if (looksLikeTs(stripped) || looksLikeFmp4(stripped)) return stripped
            if (looksLikeTs(xored) || looksLikeFmp4(xored)) return xored
            findTsStart(xored)?.let { start ->
                if (start > 0) return xored.copyOfRange(start, xored.size)
            }
        }
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
                var start = iend + 8
                while (start < data.size &&
                    (data[start] == 0xFF.toByte() || data[start] == 0x00.toByte())
                ) {
                    start++
                }
                findTsStart(data, start)?.let { return data.copyOfRange(it, data.size) }
                if (start < data.size) return data.copyOfRange(start, data.size)
            }
        }
        // RIFF / WEBP — skip full RIFF chunk then scan for TS
        if (data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
            data[2] == 0x46.toByte() && data[3] == 0x46.toByte()
        ) {
            val riffSize = (data[4].toInt() and 0xff) or
                ((data[5].toInt() and 0xff) shl 8) or
                ((data[6].toInt() and 0xff) shl 16) or
                ((data[7].toInt() and 0xff) shl 24)
            val after = (8 + riffSize).coerceAtMost(data.size)
            findTsStart(data, after)?.let { return data.copyOfRange(it, data.size) }
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
        val box = String(data, 4, minOf(4, data.size - 4), Charsets.US_ASCII)
        return box == "ftyp" || box == "moof" || box == "mdat" || box == "styp"
    }

    private fun findTsStart(data: ByteArray, from: Int = 0): Int? {
        var i = from.coerceAtLeast(0)
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
