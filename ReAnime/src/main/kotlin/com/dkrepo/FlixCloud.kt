package com.dkrepo

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

import android.util.Base64

/**
 * Extractor for flixcloud.cc embeds (the player reanime.to uses).
 *
 * Flow (reverse engineered from the site's player):
 *  1. The embed page is a SvelteKit page whose `kit.start(...)` call carries a
 *     `data:{...}` object with `obfuscation_seed`, `obfuscated_crypto_data`,
 *     `w_payload` (a tiny WASM module) and several dynamically-named fields.
 *  2. SHA-256 chains over the seed produce the dynamic field names which hold
 *     the base64 key fragments + playback token.
 *  3. GET /api/m3u8/{token} returns the AES ciphertext + third fragment under
 *     SHA-256-derived keys.
 *  4. The WASM module's `_r` function merges the three 32-byte fragments into
 *     the PBKDF2 password (its ops/constants rotate per deploy, so the module
 *     is executed with a small built-in interpreter).
 *  5. PBKDF2-HMAC-SHA256(1000) -> XOR with seed -> SHA-256 => AES-256-CBC key
 *     which decrypts the master m3u8 URL.
 */
class FlixCloud : ExtractorApi() {
    override var name = "FlixCloud"
    override var mainUrl = "https://flixcloud.cc"
    override val requiresReferer = true

    @Volatile
    private var wasmKey: ByteArray? = null

    companion object {
        private const val TAG = "FlixCloud"
        const val PARENT_REFERER = "https://reanime.to/"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, subtitleCallback, callback, emitSubtitles = true)
    }

    suspend fun extract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitSubtitles: Boolean,
        serverLabel: String? = null
    ) {
        Log.i(TAG, "extract start for $url")
        val headers = mapOf("User-Agent" to USER_AGENT)
        val html = try {
            app.get(url, referer = PARENT_REFERER, headers = headers).text
        } catch (e: Exception) {
            Log.w(TAG, "embed fetch failed: ${e.message}")
            return
        }
        Log.i(TAG, "embed html length=${html.length}")
        val data = findDataObject(html) ?: run {
            Log.w(TAG, "no kit.start data object on $url (html len ${html.length}, has seed=${html.contains("obfuscation_seed")})")
            return
        }

        if (emitSubtitles) {
            (data["subtitles"] as? List<*>)?.forEach { s ->
                val m = s as? Map<*, *> ?: return@forEach
                val subUrl = m["url"] as? String ?: return@forEach
                val lang = (m["language"] as? String)?.ifBlank { null } ?: "Unknown"
                if (subUrl.startsWith("http")) subtitleCallback(SubtitleFile(lang, subUrl))
            }
        }

        val masterUrl = decryptMasterUrl(data, url) ?: run {
            Log.w(TAG, "decryption failed for $url")
            return
        }
        Log.i(TAG, "decrypted master url: ${masterUrl.take(90)}")

        val masterText = fetchAndUnwrapPlaylist(masterUrl) ?: run {
            Log.w(TAG, "master playlist fetch/unwrap failed for $masterUrl")
            return
        }

        val quality = parseMasterQuality(masterText)
        val mediaUrl: String
        val mediaText: String

        if (masterText.contains("#EXT-X-STREAM-INF")) {
            val variantUrl = extractBestVariant(masterText, masterUrl) ?: run {
                Log.w(TAG, "failed to extract variant from master playlist")
                return
            }
            Log.i(TAG, "selected best variant url: ${variantUrl.take(90)}")
            val variantText = fetchAndUnwrapPlaylist(variantUrl) ?: run {
                Log.w(TAG, "variant playlist fetch/unwrap failed for $variantUrl")
                return
            }
            mediaUrl = variantUrl
            mediaText = variantText
        } else {
            mediaUrl = masterUrl
            mediaText = masterText
        }

        val absolutized = absolutizePlaylist(mediaText, mediaUrl)
        val b64 = Base64.encodeToString(
            absolutized.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val playUrl = "data:application/vnd.apple.mpegurl;base64,$b64"

        val linkName = if (serverLabel.isNullOrBlank()) this.name else "${this.name} $serverLabel"
        callback(
            newExtractorLink(this.name, linkName, playUrl, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
            }
        )
        Log.i(TAG, "emitted link $linkName -> data URI length ${playUrl.length}")
    }

    private suspend fun fetchAndUnwrapPlaylist(url: String): String? {
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Origin" to mainUrl
            )
            val raw = app.get(url, referer = "$mainUrl/", headers = headers).text.trim()
            if (raw.startsWith("#EXTM3U")) return raw

            val key = wasmKey
            if (key != null) {
                val result = xorUnwrap(raw, key).trim()
                if (result.startsWith("#EXTM3U")) return result
            }

            Log.w(TAG, "fetchAndUnwrapPlaylist: response body is not valid M3U8 for $url")
            null
        } catch (e: Exception) {
            Log.w(TAG, "fetchAndUnwrapPlaylist failed for $url: ${e.message}")
            null
        }
    }

    /** Runs the whole decryption chain and returns the master m3u8 URL. */
    private suspend fun decryptMasterUrl(data: Map<String, Any?>, embedUrl: String): String? {
        return try {
            val seed = data["obfuscation_seed"] as? String ?: run {
                Log.w(TAG, "missing obfuscation_seed in data object (keys=${data.keys})")
                return null
            }
            val wPayload = data["w_payload"] as? String ?: return null

            // dynamic field mapping (mirrors the site's ea())
            var e = seed
            repeat(3) { e = sha256Hex(e + it.toString()) }
            var n = e
            repeat(3) { n = sha256Hex(n + it.toString()) }
            val keyField = "kf_" + e.substring(8, 16)
            val ivField = "ivf_" + e.substring(16, 24)
            val containerName = "cd_" + e.substring(24, 32)
            val arrayName = "ad_" + e.substring(32, 40)
            val objectName = "od_" + e.substring(40, 48)
            val tokenField = e.substring(48, 64) + "_" + e.substring(56, 64)
            val keyFrag2Field = n.substring(0, 16) + "_" + n.substring(16, 24)

            val ocd = data["obfuscated_crypto_data"] as? Map<*, *> ?: run {
                Log.w(TAG, "missing obfuscated_crypto_data")
                return null
            }
            val container = ocd[containerName] as? Map<*, *> ?: run {
                Log.w(TAG, "container $containerName not found (have ${ocd.keys})")
                return null
            }
            val arr = container[arrayName] as? List<*> ?: return null
            val obj0 = (arr.firstOrNull() as? Map<*, *>)?.get(objectName) as? Map<*, *> ?: return null
            val frag1 = b64(obj0[keyField] as? String ?: run { Log.w(TAG, "keyField $keyField missing"); return null })
            val iv = b64(obj0[ivField] as? String ?: run { Log.w(TAG, "ivField $ivField missing"); return null })
            val frag2 = b64(data[keyFrag2Field] as? String ?: run { Log.w(TAG, "keyFrag2Field $keyFrag2Field missing"); return null })
            val token = data[tokenField] as? String ?: run { Log.w(TAG, "tokenField $tokenField missing"); return null }

            // playback token response -> ciphertext + third fragment
            val tokenJson = app.get("$mainUrl/api/m3u8/$token", referer = embedUrl).text
            val z = JsObjectParser.parse(tokenJson) as? Map<*, *> ?: run {
                Log.w(TAG, "token response parse failed: ${tokenJson.take(100)}")
                return null
            }
            val ctB64 = z[sha256Hex(token + "vid").substring(0, 10)] as? String ?: return null
            val frag3 = b64(z[sha256Hex(token + "key").substring(0, 10)] as? String ?: return null)

            // execute the WASM transform
            val interp = WasmInterpreter(b64(wPayload))
            val c = frag1.size
            val lp = 1000; val pp = lp + c; val bp = pp + c; val cp = bp + c
            System.arraycopy(frag1, 0, interp.memory, lp, c)
            System.arraycopy(frag2, 0, interp.memory, pp, frag2.size)
            System.arraycopy(frag3, 0, interp.memory, bp, frag3.size)
            val q = seed.substring(0, 8).toLong(16).toInt()
            interp.call("_s", intArrayOf(q))
            interp.call("_r", intArrayOf(lp, pp, bp, cp, c))
            val d = interp.memory.copyOfRange(cp, cp + c)

            // keep the _c() key for encrypted-tier quality detection
            runCatching {
                val pkPtr = interp.call("_c", intArrayOf())
                wasmKey = interp.memory.copyOfRange(pkPtr, pkPtr + 32)
            }

            // PBKDF2 -> XOR with seed -> SHA-256 => AES key
            // (manual PBKDF2: JCE provider char->byte encodings vary across platforms)
            val ft = pbkdf2(d, seed.toByteArray(Charsets.UTF_8), 1000, 32)
            for (i in 0 until 32) ft[i] = (ft[i].toInt() xor seed[i % seed.length].code).toByte()
            val aesKey = MessageDigest.getInstance("SHA-256").digest(ft)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            val plain = String(cipher.doFinal(b64(ctB64)), Charsets.UTF_8).trim()
            plain.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.w(TAG, "decryptMasterUrl failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun b64(s: String): ByteArray = base64DecodeArray(s)

    /** Manual PBKDF2-HMAC-SHA256 (JCE providers differ on password encoding). */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(dkLen)
        var outPos = 0
        for (block in 1..blocks) {
            mac.reset()
            mac.update(salt)
            var u = mac.doFinal(
                byteArrayOf(
                    (block ushr 24).toByte(), (block ushr 16).toByte(),
                    (block ushr 8).toByte(), block.toByte()
                )
            )
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val copyLen = minOf(hLen, dkLen - outPos)
            System.arraycopy(t, 0, out, outPos, copyLen)
            outPos += copyLen
        }
        return out
    }

    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseMasterQuality(master: String): Int {
        val heights = Regex("""RESOLUTION=\d+x(\d+)""").findAll(master)
            .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return when (heights.maxOrNull() ?: 0) {
            in 2000..Int.MAX_VALUE -> Qualities.P2160.value
            in 1400..1999 -> Qualities.P1440.value
            in 1000..1399 -> Qualities.P1080.value
            in 700..999 -> Qualities.P720.value
            in 450..699 -> Qualities.P480.value
            in 300..449 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /** Picks highest RESOLUTION variant from an unwrapped master, resolving relative URLs against masterUrl. */
    private fun extractBestVariant(masterText: String, baseUrl: String): String? {
        val lines = masterText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var bestUrl: String? = null
        var bestHeight = -1
        var bestBw = -1
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val url = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val h = Regex("""RESOLUTION=\d+x(\d+)""").find(l)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
            val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
            val better = when {
                h != bestHeight -> h > bestHeight
                bw != bestBw -> bw > bestBw
                bestUrl == null -> true
                else -> false
            }
            if (better) {
                bestHeight = h
                bestBw = bw
                bestUrl = url
            }
        }
        if (bestUrl == null) return null
        // resolve relative
        return try {
            if (bestUrl.startsWith("http")) bestUrl
            else java.net.URL(java.net.URL(baseUrl), bestUrl).toString()
        } catch (e: Exception) {
            bestUrl
        }
    }

    /** Absolutizes segment URLs and URI="..." tag attributes in a media playlist against baseUrl. */
    private fun absolutizePlaylist(mediaText: String, baseUrl: String): String {
        val base = try { java.net.URL(baseUrl) } catch (e: Exception) { return mediaText }
        val uriTagRegex = Regex("""URI=["']([^"']+)["']""")

        return mediaText.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> {
                    uriTagRegex.replace(line) { match ->
                        val uri = match.groupValues[1]
                        if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("data:")) {
                            match.value
                        } else {
                            val abs = try { java.net.URL(base, uri).toString() } catch (e: Exception) { uri }
                            """URI="$abs""""
                        }
                    }
                }
                t.startsWith("http://") || t.startsWith("https://") || t.startsWith("data:") -> line
                else -> try {
                    java.net.URL(base, t).toString()
                } catch (e: Exception) { line }
            }
        }
    }

    /** fetch7 playlists are base64( XOR(plaintext, repeating 32-byte key) ). */
    private fun xorUnwrap(raw: String, key: ByteArray): String {
        return try {
            val blob = base64DecodeArray(raw.trim())
            val out = ByteArray(blob.size) { i -> (blob[i].toInt() xor key[i % key.size].toInt()).toByte() }
            String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Finds the `data:{...}` object inside the SvelteKit kit.start() call and
     * parses it with a tolerant JS-object-literal parser.
     */
    private fun findDataObject(html: String): Map<String, Any?>? {
        val regex = Regex("""data\s*:\s*\{""")
        for (match in regex.findAll(html)) {
            val start = match.range.last
            var depth = 0
            var inStr = false
            var esc = false
            var end = -1
            var i = start
            while (i < html.length) {
                val ch = html[i]
                when {
                    ch == '"' && !esc -> inStr = !inStr
                    ch == '\\' && inStr -> esc = !esc
                    ch == '{' && !inStr -> depth++
                    ch == '}' && !inStr -> {
                        depth--
                        if (depth == 0) { end = i; break }
                    }
                }
                if (ch != '\\') esc = false
                i++
            }
            if (end > start) {
                val block = html.substring(start, end + 1)
                val parsed = runCatching { JsObjectParser.parse(block) }.getOrNull()
                if (parsed is Map<*, *> && parsed.containsKey("obfuscation_seed")) {
                    @Suppress("UNCHECKED_CAST")
                    return parsed as Map<String, Any?>
                }
            }
        }
        return null
    }
}