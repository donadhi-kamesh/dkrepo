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
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * movieswood.cloud only serves content to mobile user agents (desktop UA gets a
 * redirect loop to an empty page), so every request must carry a mobile UA.
 */
class MoviesWoodProvider : MainAPI() {
    override var mainUrl = "https://movieswood.cloud"
    override var name = "MoviesWood"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override var hasMainPage = true

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        /** category path (with trailing slash) -> display label */
        private val CATEGORIES = linkedMapOf(
            "tamil/" to "Tamil",
            "dubs/" to "Telugu Dubbed",
            "holly/" to "English",
            "bolly/" to "Hindi",
            "malayalam/" to "Malayalam",
            "web/" to "Web Series",
            "telly/" to "TV Shows",
        )

        private val SERIES_CATEGORIES = setOf("web/", "telly/")

        private val mapper = jacksonObjectMapper()

        private val DATA_HREF_REGEX = Regex("""data-href="([^"]+)"""")

        // s03 ep02 / s03e02 / s3 e2
        private val EP_SEASON_EP = Regex("""\bs\s*(\d{1,2})\s*(?:e|ep)\s*(\d{1,3})\b""")
        // Season 1 Episode 2 / Season 1 EP2
        private val EP_SEASON_WORD = Regex("""\bseason\s*(\d{1,2})\b.*?\b(?:e|ep)\s*(\d{1,3})\b""")
        // standalone episode number: "Episode 2" / "EP 2" / "E2"
        private val EP_WORD_ONLY = Regex("""\b(?:episode|ep|e)\s*(\d{1,3})\b""")
        // Season N without an episode (whole-season pack)
        private val EP_SEASON_ONLY = Regex("""\bseason\s*(\d{1,2})\b""")

        private fun qualityFromName(name: String, sizeMb: Double?): Int {
            val n = name.lowercase()
            return when {
                "1080" in n -> Qualities.P1080.value
                "2160" in n || "4k" in n -> Qualities.P2160.value
                "720" in n -> Qualities.P720.value
                "480" in n -> Qualities.P480.value
                "360" in n -> Qualities.P360.value
                // the site often strips "1080" leaving a trailing " p" on the big file
                n.trimEnd().endsWith(" p") && (sizeMb ?: 0.0) > 1200 -> Qualities.P1080.value
                else -> Qualities.Unknown.value
            }
        }
    }

    private val mobileHeaders = mapOf("User-Agent" to MOBILE_UA)

    private suspend fun getMobile(url: String): String =
        app.get(url, headers = mobileHeaders).text

    /** A single downloadable file offered on a detail page. Serialized into load() data. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FileRef(
        @JsonProperty("n") val n: String? = null,   // display name e.g. "29 Tamil 1080p"
        @JsonProperty("s") val s: String? = null,   // human size e.g. "2.9 GB"
        @JsonProperty("u") val u: String? = null    // absolute rating.php URL holding the direct link
    )

    override val mainPage = mainPageOf(
        *CATEGORIES.entries.map { (path, label) ->
            "$mainUrl/$path?list=new" to "$label • Latest"
        }.toTypedArray(),
        "$mainUrl/tamil/?list=all" to "Tamil • A-Z"
    )

    // ---------- parsing helpers ----------

    private fun parseCards(html: String, pageUrl: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, pageUrl)
        return doc.select("a.card").mapNotNull { card ->
            val href = card.attr("abs:href").ifEmpty { return@mapNotNull null }
            val name = card.selectFirst(".card-name")?.text()?.trim()
            if (name.isNullOrEmpty()) return@mapNotNull null
            val poster = card.selectFirst(".card-img img")?.attr("abs:src")
            val meta = card.select(".card-meta span").map { it.text().trim() }
            val year = meta.firstOrNull { it.matches(Regex("\\d{4}")) }?.toIntOrNull()
            val rating = meta.firstOrNull { it.matches(Regex("\\d{1,2}(\\.\\d)?")) }?.toDoubleOrNull()

            val category = CATEGORIES.keys.firstOrNull { href.contains("/$it") }
            val type = if (category in SERIES_CATEGORIES) TvType.TvSeries else TvType.Movie

            newMovieSearchResponse(name, href, type) {
                this.posterUrl = poster?.takeIf { it.isNotEmpty() }
                this.year = year
                this.score = Score.from10(rating)
            }
        }
    }

    // ---------- Cloudstream entry points ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}&page=$page" else request.data
        val items = try {
            parseCards(getMobile(url), url)
        } catch (e: Exception) {
            Log.w("MoviesWood", "getMainPage failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return coroutineScope {
            CATEGORIES.keys.map { path ->
                async {
                    try {
                        val url = "$mainUrl/$path?q=$encoded"
                        parseCards(getMobile(url), url)
                    } catch (e: Exception) {
                        Log.w("MoviesWood", "search $path failed: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.url }
    }

    private data class ParsedFile(
        val name: String,
        val size: String?,
        val ratingUrl: String,
        val sizeMb: Double?
    )

    private data class EpKey(val season: Int?, val episode: Int?)

    private fun parseFileHtml(file: org.jsoup.nodes.Element): ParsedFile? {
        val name = file.selectFirst(".file-name")?.text()?.trim() ?: return null
        val size = file.selectFirst(".file-size")?.text()
            ?.removePrefix("Size:")?.trim()
        val ratingUrl = file.selectFirst("a.dl-btn")?.attr("abs:href") ?: return null
        val sizeMb = size?.let {
            Regex("([\\d.]+)\\s*(GB|MB)").find(it.uppercase())?.let { m ->
                val v = m.groupValues[1].toDoubleOrNull()
                when (m.groupValues[2]) {
                    "GB" -> (v ?: 0.0) * 1024
                    else -> v ?: 0.0
                }
            }
        }
        return ParsedFile(name, size?.ifEmpty { null }, ratingUrl, sizeMb)
    }

    /** Extract (season, episode) from a file name; nulls when not present. */
    private fun epKeyFor(name: String): EpKey {
        val n = name.lowercase()
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
        val html = getMobile(url)
        val doc = Jsoup.parse(html, url)

        val title = doc.selectFirst("h1.movie-title")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(".movie-poster img")?.attr("abs:src")?.ifEmpty { null }
        val overview = doc.selectFirst(".movie-overview")?.text()?.trim()

        var year: Int? = null
        var score: Double? = null
        doc.select(".movie-meta .meta-tag").forEach { tag ->
            val t = tag.text().trim()
            when {
                "📅" in t -> year = Regex("\\d{4}").find(t)?.value?.toIntOrNull()
                "⭐" in t -> score = Regex("\\d+(\\.\\d)?").find(t)?.value?.toDoubleOrNull()
            }
        }

        val files = doc.select(".file-item").mapNotNull { parseFileHtml(it) }
        Log.i("MoviesWood", "load $title files=${files.size}")

        val category = CATEGORIES.keys.firstOrNull { url.contains("/$it") }
        val isSeriesCategory = category in SERIES_CATEGORIES

        // group files by episode
        val grouped = LinkedHashMap<EpKey, MutableList<ParsedFile>>()
        files.forEach { f ->
            grouped.getOrPut(epKeyFor(f.name)) { mutableListOf() }.add(f)
        }
        val hasMultipleEpisodes = grouped.keys.count { it.episode != null } >= 2

        fun refs(list: List<ParsedFile>) = mapper.writeValueAsString(
            list.map { FileRef(it.name, it.size, it.ratingUrl) }
        )

        fun LoadResponse.applyMeta(): LoadResponse {
            posterUrl = poster
            plot = overview
            this.year = year
            this.score = Score.from10(score)
            return this
        }

        if (isSeriesCategory || hasMultipleEpisodes) {
            val episodes = grouped.entries
                .sortedWith(compareBy({ it.key.season ?: 1 }, { it.key.episode ?: Int.MAX_VALUE }))
                .flatMap { (key, group) ->
                    val epNum = key.episode
                    val label = when {
                        key.season != null && epNum != null -> "S${key.season} Episode $epNum"
                        key.season != null -> "Season ${key.season}"
                        epNum != null -> "Episode $epNum"
                        else -> ""
                    }
                    group.map { f ->
                        newEpisode(refs(listOf(f))) {
                            this.name = label.ifEmpty { f.name }
                            this.season = key.season
                            this.episode = epNum
                            this.description = listOfNotNull(f.name, f.size).joinToString(" • ")
                        }
                    }
                }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes).applyMeta()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, refs(files)).applyMeta()
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
            Log.w("MoviesWood", "loadLinks bad data: ${e.message}")
            return false
        }
        if (refs.isEmpty()) return false

        var found = false
        coroutineScope {
            refs.map { ref ->
                async {
                    val ratingUrl = ref.u ?: return@async
                    val sizeMb = ref.s?.let { s ->
                        Regex("([\\d.]+)\\s*(GB|MB)").find(s.uppercase())?.let { m ->
                            val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
                            if (m.groupValues[2] == "GB") v * 1024 else v
                        }
                    }
                    try {
                        val page = getMobile(ratingUrl)
                        val link = DATA_HREF_REGEX.find(page)?.groupValues?.get(1) ?: return@async
                        found = true
                        val label = listOfNotNull(
                            ref.n,
                            ref.s?.let { "($it)" }
                        ).joinToString(" ").ifEmpty { "Download" }
                        callback(
                            ExtractorLink(
                                source = this@MoviesWoodProvider.name,
                                name = label,
                                url = link,
                                referer = "",
                                quality = qualityFromName(ref.n ?: "", sizeMb),
                                type = ExtractorLinkType.VIDEO,
                                headers = mobileHeaders
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("MoviesWood", "rating fetch failed ${ref.u}: ${e.message}")
                    }
                }
            }.awaitAll()
        }
        return found
    }
}
