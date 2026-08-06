package com.dkrepo

import com.lagradost.api.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Tiny localhost HTTP server that serves unwrapped m3u8 playlists.
 *
 * Needed because:
 *  - Cronet rejects `data:` URIs (ERR_UNKNOWN_URL_SCHEME)
 *  - Cloudstream's RepoLinkGenerator drops ExtractorLinkPlayList (url is always blank)
 */
object LocalPlaylistServer {
    private const val TAG = "FlixCloudLocal"
    private val playlists = ConcurrentHashMap<String, ByteArray>()

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

    /** Store playlist body and return a localhost URL ExoPlayer/Cronet can open. */
    fun publish(body: String): String {
        val p = ensureStarted()
        val id = UUID.randomUUID().toString().replace("-", "")
        playlists[id] = body.toByteArray(Charsets.UTF_8)
        // Cap cache size to avoid unbounded growth across many episodes
        if (playlists.size > 32) {
            val keys = playlists.keys().toList()
            keys.take(keys.size - 24).forEach { playlists.remove(it) }
        }
        return "http://127.0.0.1:$p/$id.m3u8"
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
                val id = path.removeSuffix(".m3u8")
                val body = playlists[id]
                val out = s.getOutputStream()
                if (body == null) {
                    val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(resp.toByteArray(Charsets.US_ASCII))
                } else {
                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/vnd.apple.mpegurl\r\n")
                        append("Content-Length: ${body.size}\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Cache-Control: no-store\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.write(body)
                }
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "client handler failed: ${e.message}")
            }
        }
    }
}
