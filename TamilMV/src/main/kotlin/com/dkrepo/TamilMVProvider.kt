package com.dkrepo

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections

/**
 * 1TamilMV (TamilMV) is an IPS forum that rotates its domain very often.
 *
 * - The provider tries a list of candidate domains and caches the first one that
 *   responds, so a domain rotation only breaks the extension if every known
 *   domain dies at once (fix = add the new domain to DOMAIN_CANDIDATES).
 * - Stored URLs keep only the IPS topic path; they are rebuilt against the
 *   currently-resolved domain, so items keep working across rotations.
 * - Direct links: each topic lists per-quality "DIRECT LINK" buttons pointing at
 *   a shortlink host (currently cyberloom.best). The chain is plain GETs:
 *   shortlink page -> /out?t=... redirect target -> file page -> cdn.../files/...
 *   No JS or countdown is required. Magnet links are ignored on purpose.
 */
class TamilMVProvider : MainAPI() {
    override var mainUrl = "https://www.1tamilmv.ing"
    override var name = "1TamilMV"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ta"
    override var hasMainPage = true

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        /** newest first; resolved once per session then cached.
         *  www.1tamilmv.fi is the site's own permanent redirector (announced in its
         *  security banner) - following it reveals the current domain. */
        private val DOMAIN_CANDIDATES = listOf(
            "www.1tamilmv.ing", "1tamilmv.ing", "www.1tamilmv.fi",
            "www.1tamilmv.ms", "www.1tamilmv.tube", "www.1tamilmv.work", "www.1tamilmv.farm",
            "www.1tamilmv.kim", "www.1tamilmv.gold", "www.1tamilmv.moi", "www.1tamilmv.day",
            "www.1tamilmv.best", "www.1tamilmv.pink", "www.1tamilmv.boo",
            "www.1tamilmv.world", "www.1tamilmv.lc", "www.1tamilmv.wf",
        )

        /** language category path segment -> label */
        private val LANGUAGES = linkedMapOf(
            "9-tamil-language" to "Tamil",
            "22-telugu-language" to "Telugu",
            "56-hindi-language" to "Hindi",
            "34-malayalam-language" to "Malayalam",
            "67-kannada-language" to "Kannada",
            "45-english-language" to "English",
        )

        /** subforum slug fragments worth indexing per language */
        private val SUBFORUM_KEYS = listOf("web-hd", "hd-rips", "web-series", "predvd", "hollywood")

        private val mapper = jacksonObjectMapper()

        private val TOPIC_PATH_REGEX = Regex("""/forums/topic/(\d+)-([^/?#]*)/?""")
        private val OUT_LINK_REGEX = Regex("""https?://[^"'\s<>]+/out\?t=[^"'\s<>]+""")
        private val CDN_LINK_REGEX = Regex("""https?://cdn\.[A-Za-z0-9.-]+/files/[^\s"'<>]+""")

        // s03 ep02 / s03e02 / S01E05 etc. (also "Season 2 Episode 3", "EP 4", "E4", "EP.03")
        private val EP_SEASON_EP = Regex("""\bs[.\s]*(\d{1,2})[.\s]*(?:e|ep)[.\s]*(\d{1,3})\b""")
        private val EP_SEASON_WORD = Regex("""\bseason\s*(\d{1,2})\b.*?\b(?:e|ep)[.\s]*(\d{1,3})\b""")
        private val EP_WORD_ONLY = Regex("""\b(?:episode|ep|e)[.\s]*(\d{1,3})\b""")
        private val EP_SEASON_ONLY = Regex("""\bseason\s*(\d{1,2})\b""")

        private fun qualityFromLabel(label: String): Int {
            val n = label.lowercase()
            return when {
                "4k" in n || "2160" in n -> Qualities.P2160.value
                "1080" in n -> Qualities.P1080.value
                "720" in n -> Qualities.P720.value
                "480" in n -> Qualities.P480.value
                "360" in n -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }

        /** "Magudam (2026) Tamil HQ PreDVD - [1080p..." -> ("Magudam", 2026) */
        private fun cleanTitle(raw: String): Pair<String, Int?> {
            val base = raw.substringBefore(" - [").substringBefore(" | ").trim()
            val year = Regex("""\((\d{4})\)""").find(base)?.groupValues?.get(1)?.toIntOrNull()
            val name = base
                .replace(Regex("""\s*\(\d{4}\)\s*"""), " ")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
            return (name.ifEmpty { raw.trim() }) to year
        }
    }

    private val mobileHeaders = mapOf("User-Agent" to MOBILE_UA)

    @Volatile
    private var cachedDomain: String? = null

    /** category path -> child subforum full paths (per language), per session */
    private val subforumCache = Collections.synchronizedMap(HashMap<String, List<String>>())

    private suspend fun rawGet(url: String): String =
        app.get(url, headers = mobileHeaders).text

    private fun looksLikeSite(html: String): Boolean =
        "/forums/" in html && ("ipsDataItem" in html || "TamilMV" in html)

    /** Find a live domain among the candidates; caches the winner.
     *  Candidates that redirect (e.g. 1tamilmv.fi -> current domain) are followed,
     *  and the final host is what gets cached. */
    private suspend fun domain(forceRefresh: Boolean = false): String {
        cachedDomain?.takeIf { !forceRefresh }?.let { return it }
        for (host in DOMAIN_CANDIDATES) {
            try {
                val res = app.get("https://$host/", headers = mobileHeaders)
                if (!looksLikeSite(res.text)) continue
                val finalHost = try {
                    java.net.URI(res.url).host ?: host
                } catch (e: Exception) {
                    host
                }
                cachedDomain = finalHost
                Log.i("TamilMV", "resolved domain -> $finalHost (via $host)")
                return finalHost
            } catch (e: Exception) {
                Log.d("TamilMV", "domain candidate $host failed: ${e.message}")
            }
        }
        throw ErrorLoadingException("No live 1TamilMV domain found in ${DOMAIN_CANDIDATES.size} candidates")
    }

    /** GET with one retry after re-resolving the domain (handles rotations mid-session). */
    private suspend fun siteGet(path: String): String {
        val d = domain()
        try {
            return rawGet("https://$d$path")
        } catch (e: Exception) {
            cachedDomain = null
            Log.w("TamilMV", "request failed on $d, re-resolving: ${e.message}")
            return rawGet("https://${domain(true)}$path")
        }
    }

    // ---------- parsing ----------

    /**
     * A single downloadable file from a topic. Serialized into load() data.
     * u = shortlink (resolved to a CDN url at play time), xl = exact bytes when known.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FileRef(
        @JsonProperty("n") val n: String? = null,
        @JsonProperty("s") val s: String? = null,
        @JsonProperty("u") val u: String? = null,
        @JsonProperty("xl") val xl: Long? = null
    )

    private data class TopicRow(val id: Long, val url: String, val title: String)

    /** IPS rows appear as `a.ipsDataItem_title` (sidebar), `h4.ipsDataItem_title > a` (forum lists)
     *  and `h2.ipsStreamItem_title > a` (search results). */
    private fun parseTopicRows(html: String, domain: String, searchMode: Boolean = false): List<TopicRow> {
        val doc = Jsoup.parse(html)
        val anchors = if (searchMode) {
            doc.select("h2.ipsStreamItem_title a[href]")
        } else {
            doc.select("a.ipsDataItem_title") + doc.select("h4.ipsDataItem_title a[href]")
        }
        val seen = HashMap<Long, TopicRow>()
        for (a in anchors) {
            val href = a.attr("href")
            val m = TOPIC_PATH_REGEX.find(href) ?: continue
            val id = m.groupValues[1].toLongOrNull() ?: continue
            val text = a.text().trim()
            if (text.isNotEmpty() && !seen.containsKey(id)) {
                seen[id] = TopicRow(id, "https://$domain/index.php?/forums/topic/${m.groupValues[1]}-${m.groupValues[2]}/", text)
            }
        }
        return seen.values.sortedByDescending { it.id }
    }

    /** first offsite image in a post = poster (topics lead with a poster/screenshot) */
    private fun firstImage(post: org.jsoup.nodes.Element): String? =
        post.select("img[src]")
            .map { it.attr("abs:src") }
            .firstOrNull {
                it.contains("pixelbb") ||
                    (!it.contains("/uploads/") && Regex("""\.(jpe?g|png|webp)""", RegexOption.IGNORE_CASE).containsMatchIn(it))
            }

    private val posterCache = Collections.synchronizedMap(HashMap<Long, String?>())

    /**
     * Poster for a topic: first image in its first post. Cached per topic id
     * (including misses) so home pages don't refetch the same topics.
     */
    private suspend fun posterFor(row: TopicRow): String? {
        posterCache[row.id]?.let { return it }
        if (posterCache.containsKey(row.id)) return null
        val m = TOPIC_PATH_REGEX.find(row.url) ?: return null
        return try {
            val html = siteGet("/index.php?/forums/topic/${m.groupValues[1]}-${m.groupValues[2]}/")
            val doc = Jsoup.parse(html)
            val post = doc.selectFirst("[itemprop=commentText], .cPost_contentWrap") ?: doc.body()
            val poster = firstImage(post)
            posterCache[row.id] = poster
            poster
        } catch (e: Exception) {
            Log.d("TamilMV", "poster fetch failed ${row.id}: ${e.message}")
            null
        }
    }

    private suspend fun rowsToSearchResponses(rows: List<TopicRow>): List<SearchResponse> =
        coroutineScope {
            val gate = Semaphore(6)
            rows.map { row ->
                async {
                    gate.withPermit {
                        rowToSearch(row)
                    }
                }
            }.awaitAll().filterNotNull()
        }

    private suspend fun rowToSearch(row: TopicRow): SearchResponse? {
        val (title, year) = cleanTitle(row.title)
        val isSeries = EP_SEASON_EP.containsMatchIn(row.title.lowercase()) ||
            EP_WORD_ONLY.containsMatchIn(row.title.lowercase())
        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        val poster = posterFor(row)
        return newMovieSearchResponse(title, row.url, type) {
            this.year = year
            this.posterUrl = poster
        }
    }

    // ---------- Cloudstream entry points ----------

    override val mainPage = mainPageOf(
        "latest" to "Latest",
        *LANGUAGES.entries.map { (path, label) -> path to label }.toTypedArray(),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val d = domain()
        val items = try {
            if (request.data == "latest") {
                if (page > 1) emptyList()
                else rowsToSearchResponses(parseTopicRows(siteGet("/index.php"), d))
            } else {
                val subforums = subforumsFor(request.data)
                coroutineScope {
                    subforums.map { sub ->
                        async { parseTopicRows(siteGet("$sub" + if (page > 1) "&page=$page" else ""), d) }
                    }.awaitAll().flatten()
                }
                    .distinctBy { it.id }
                    .sortedByDescending { it.id }
                    .let { rowsToSearchResponses(it) }
            }
        } catch (e: Exception) {
            Log.w("TamilMV", "getMainPage failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    private suspend fun subforumsFor(category: String): List<String> {
        subforumCache[category]?.let { return it }
        val html = siteGet("/index.php?/forums/forum/$category/")
        val doc = Jsoup.parse(html)
        val slugs = doc.select("a[href]")
            .map { it.attr("href") }
            .mapNotNull { href -> Regex("""/forums/forum/([^/?#"]+)""").find(href)?.groupValues?.get(1) }
            .filter { slug -> slug != category && SUBFORUM_KEYS.any { slug.contains(it) } }
            .distinct()
        val resolved = slugs.map { "/index.php?/forums/forum/$it/" }
            .ifEmpty { listOf("/index.php?/forums/forum/$category/") }
        subforumCache[category] = resolved
        return resolved
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val d = domain()
        val encoded = URLEncoder.encode(query, "UTF-8")
        return try {
            // IPS needs the pathinfo-style query: /search/&q=... (a leading ? is ignored for guests)
            rowsToSearchResponses(
                parseTopicRows(siteGet("/index.php?/search/&q=$encoded"), d, searchMode = true)
            )
        } catch (e: Exception) {
            Log.w("TamilMV", "search failed: ${e.message}")
            emptyList()
        }
    }

    private data class EpKey(val season: Int?, val episode: Int?)

    private fun epKeyFor(label: String): EpKey {
        val n = label.lowercase()
        EP_SEASON_EP.find(n)?.let {
            return EpKey(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull())
        }
        EP_SEASON_WORD.find(n)?.let {
            return EpKey(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull())
        }
        EP_WORD_ONLY.find(n)?.let { return EpKey(null, it.groupValues[1].toIntOrNull()) }
        EP_SEASON_ONLY.find(n)?.let { return EpKey(it.groupValues[1].toIntOrNull(), null) }
        return EpKey(null, null)
    }

    override suspend fun load(url: String): LoadResponse {
        val m = TOPIC_PATH_REGEX.find(url) ?: throw ErrorLoadingException("Bad topic url: $url")
        val html = siteGet("/index.php?/forums/topic/${m.groupValues[1]}-${m.groupValues[2]}/")
        val doc = Jsoup.parse(html)

        val rawTitle = doc.selectFirst("h1, .ipsType_pageTitle")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").trim()
        val (title, year) = cleanTitle(rawTitle)

        val post = doc.selectFirst("[itemprop=commentText], .cPost_contentWrap") ?: doc.body()

        // poster: first offsite image in the first post (topics lead with a poster)
        val poster = firstImage(post)
        m.groupValues[1].toLongOrNull()?.let { posterCache[it] = poster }

        // variants: posts interleave MAGNET anchors (dn=/xl= label the file) with
        // DIRECT LINK buttons. Pair each direct button with the nearest unpaired
        // preceding magnet; leftover magnets become torrent links.
        fun magnetRef(href: String): FileRef? {
            val dn = Regex("""[?&]dn=([^&]*)""").find(href)?.groupValues?.get(1)
            val name = dn?.let { URLDecoder.decode(it, "UTF-8") }
                ?.replace(Regex("""^www\.[^.]+\.\w+\s*-\s*"""), "")
                ?.trim()
            val xl = Regex("""[?&]xl=(\d+)""").find(href)?.groupValues?.get(1)?.toLongOrNull()
            val size = xl?.let { bytes ->
                when {
                    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes.toDouble() / (1L shl 30))
                    bytes >= 1L shl 20 -> String.format("%.0f MB", bytes.toDouble() / (1L shl 20))
                    else -> "$bytes B"
                }
            }
            return FileRef(name ?: title, size, href, xl)
        }

        val files = mutableListOf<FileRef>()
        val magnetQueue = ArrayDeque<FileRef>()
        for (a in post.select("a[href]")) {
            val href = a.attr("href")
            when {
                href.startsWith("magnet:") -> magnetRef(href)?.let { magnetQueue.add(it) }
                a.hasClass("download-button") && href.startsWith("http") -> {
                    val m = if (magnetQueue.isEmpty()) null else magnetQueue.removeFirst()
                    files.add(FileRef(m?.n ?: title, m?.s, href, m?.xl))
                }
            }
        }
        // magnet-only topics: keep them as torrent links (older topics have no direct buttons)
        files.addAll(magnetQueue)
        Log.i("TamilMV", "load '$title' variants=${files.size}")

        fun refs(list: List<FileRef>) = mapper.writeValueAsString(list)

        fun LoadResponse.applyMeta(): LoadResponse {
            posterUrl = poster
            this.year = year
            return this
        }

        val grouped = LinkedHashMap<EpKey, MutableList<FileRef>>()
        files.forEach { f -> grouped.getOrPut(epKeyFor(f.n ?: "")) { mutableListOf() }.add(f) }
        val multiEpisode = grouped.keys.count { it.episode != null } >= 2

        if (multiEpisode) {
            val episodes = grouped.entries
                .sortedWith(compareBy({ it.key.season ?: 1 }, { it.key.episode ?: Int.MAX_VALUE }))
                .map { (key, group) ->
                    val epNum = key.episode
                    val label = when {
                        key.season != null && epNum != null -> "S${key.season} Episode $epNum"
                        key.season != null -> "Season ${key.season}"
                        epNum != null -> "Episode $epNum"
                        else -> "Extras"
                    }
                    newEpisode(refs(group)) {
                        this.name = label
                        this.season = key.season
                        this.episode = epNum
                        this.description = group.joinToString("\n") { listOfNotNull(it.n, it.s).joinToString(" • ") }
                    }
                }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes).applyMeta()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, refs(files)).applyMeta()
    }

    /** shortlink -> (optional /out?t= hop) -> file page -> cdn direct url */
    private suspend fun resolveDirect(shortUrl: String): String? {
        val first = rawGet(shortUrl)
        CDN_LINK_REGEX.find(first)?.let { return it.value }
        val out = OUT_LINK_REGEX.find(first)?.value ?: return null
        val second = rawGet(out)
        return CDN_LINK_REGEX.find(second)?.value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val refs = try {
            mapper.readValue(data, Array<FileRef>::class.java).toList()
        } catch (e: Exception) {
            Log.w("TamilMV", "loadLinks bad data: ${e.message}")
            return false
        }
        if (refs.isEmpty()) return false

        var found = false
        coroutineScope {
            refs.map { ref ->
                async {
                    try {
                        val label = listOfNotNull(ref.n, ref.s?.let { "($it)" }).joinToString(" ")
                        val url = ref.u ?: return@async
                        if (url.startsWith("magnet:")) {
                            // magnet-only variant fallback
                            found = true
                            callback(
                                ExtractorLink(
                                    source = this@TamilMVProvider.name,
                                    name = (label.ifEmpty { "Torrent" }),
                                    url = url,
                                    referer = "",
                                    quality = qualityFromLabel(ref.n ?: ""),
                                    type = ExtractorLinkType.TORRENT
                                )
                            )
                            return@async
                        }
                        val link = resolveDirect(url) ?: return@async
                        found = true
                        callback(
                            ExtractorLink(
                                source = this@TamilMVProvider.name,
                                name = label.ifEmpty { "Direct" },
                                url = link,
                                referer = "",
                                quality = qualityFromLabel(ref.n ?: ""),
                                type = ExtractorLinkType.VIDEO,
                                headers = mobileHeaders
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("TamilMV", "resolve failed ${ref.u}: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        return found
    }
}
