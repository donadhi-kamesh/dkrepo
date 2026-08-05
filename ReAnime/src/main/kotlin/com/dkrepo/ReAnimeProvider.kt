package com.dkrepo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Re:ANIME"
    override var supportedTypes = setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)
    override var lang = "en"
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/api/v1/top/anime" to "Top Anime",
        "$mainUrl/api/v1/search?q=a" to "Popular Anime",
        "$mainUrl/home" to "Latest Releases"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        try {
            if (request.data.contains("/top/anime")) {
                val response = app.get(request.data).text
                val parsed = parseJson<TopAnimeResponse>(response)
                parsed.data?.forEach { item ->
                    val title = item.title?.english?.ifEmpty { null }
                        ?: item.title?.romaji?.ifEmpty { null }
                        ?: item.title?.native ?: "Unknown"
                    val animeId = item.animeId ?: return@forEach
                    val cover = item.coverImage?.extraLarge?.ifEmpty { null }
                        ?: item.coverImage?.large?.ifEmpty { null }
                        ?: item.coverImage?.medium ?: ""

                    items.add(
                        newAnimeSearchResponse(title, "$mainUrl/anime/$animeId", TvType.Anime) {
                            this.posterUrl = cover
                        }
                    )
                }
            } else if (request.data.contains("/search?q=")) {
                val response = app.get(request.data).text
                val parsed = parseJson<SearchApiResponse>(response)
                parsed.results?.forEach { item ->
                    val title = item.title?.english?.ifEmpty { null }
                        ?: item.title?.romaji?.ifEmpty { null }
                        ?: item.title?.native ?: "Unknown"
                    val animeId = item.animeId ?: return@forEach
                    val cover = item.coverImage?.extraLarge?.ifEmpty { null }
                        ?: item.coverImage?.large?.ifEmpty { null }
                        ?: item.coverImage?.medium ?: ""

                    items.add(
                        newAnimeSearchResponse(title, "$mainUrl/anime/$animeId", TvType.Anime) {
                            this.posterUrl = cover
                        }
                    )
                }
            } else {
                // Home page HTML fallback
                val doc = app.get(request.data).document
                doc.select("a[href^=/anime/]").forEach { element ->
                    val href = element.attr("href")
                    val title = element.text().trim()
                    if (title.isNotEmpty() && href.startsWith("/anime/")) {
                        items.add(
                            newAnimeSearchResponse(title, "$mainUrl$href", TvType.Anime)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/v1/search?q=${query.encodeUri()}"
        val response = app.get(url).text
        val parsed = parseJson<SearchApiResponse>(response)

        return parsed.results?.mapNotNull { item ->
            val animeId = item.animeId ?: return@mapNotNull null
            val title = item.title?.english?.ifEmpty { null }
                ?: item.title?.romaji?.ifEmpty { null }
                ?: item.title?.native ?: "Unknown"
            val cover = item.coverImage?.extraLarge?.ifEmpty { null }
                ?: item.coverImage?.large?.ifEmpty { null }
                ?: item.coverImage?.medium ?: ""

            newAnimeSearchResponse(title, "$mainUrl/anime/$animeId", TvType.Anime) {
                this.posterUrl = cover
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.removePrefix("$mainUrl/anime/").removePrefix("$mainUrl/watch/").substringBefore("?")
        val apiUrl = "$mainUrl/api/v1/anime/$animeId"

        try {
            val response = app.get(apiUrl).text
            val details = parseJson<AnimeDetailsResponse>(response)

            val title = details.title?.english?.ifEmpty { null }
                ?: details.title?.romaji?.ifEmpty { null }
                ?: details.title?.native ?: "Unknown"
            val poster = details.coverImage?.extraLarge?.ifEmpty { null }
                ?: details.coverImage?.large?.ifEmpty { null }
                ?: details.coverImage?.medium
            val banner = details.bannerImage?.ifEmpty { null }
            val description = details.description
            val year = details.seasonYear
            val status = when (details.status?.lowercase()) {
                "releasing" -> ShowStatus.Ongoing
                "finished" -> ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }

            val episodesList = mutableListOf<Episode>()
            val totalEps = details.episodesTotal ?: details.lastEpisode ?: details.episodes ?: 0

            if (totalEps > 0) {
                for (epNum in 1..totalEps) {
                    episodesList.add(
                        newEpisode("$mainUrl/watch/$animeId?ep=$epNum") {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                        }
                    )
                }
            } else {
                // Fallback: parse watch page HTML for available episode links
                val watchUrl = "$mainUrl/watch/$animeId"
                val watchHtml = app.get(watchUrl).text
                val doc = Jsoup.parse(watchHtml)
                doc.select("a[href*=/watch/$animeId?ep=]").forEach { element ->
                    val epHref = element.attr("href")
                    val epName = element.text().trim()
                    val epNumber = epHref.substringAfter("ep=").toIntOrNull()
                    episodesList.add(
                        newEpisode(fixUrl(epHref)) {
                            this.name = if (epName.isNotEmpty()) epName else "Episode $epNumber"
                            this.episode = epNumber
                        }
                    )
                }
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = description
                this.year = year
                this.showStatus = status
                this.genres = details.genres
                addEpisodes(DubStatus.Subed, episodesList.distinctBy { it.episode })
            }
        } catch (e: Exception) {
            // HTML parsing fallback
            val doc = app.get(url).document
            val title = doc.selectFirst("h1")?.text() ?: "Unknown"
            val plot = doc.selectFirst("div.description, p")?.text()
            val poster = doc.selectFirst("img")?.attr("src")

            val episodesList = mutableListOf<Episode>()
            doc.select("a[href*=?ep=]").forEach { element ->
                val epHref = element.attr("href")
                val epName = element.text().trim()
                val epNumber = epHref.substringAfter("ep=").toIntOrNull()
                episodesList.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = if (epName.isNotEmpty()) epName else "Episode $epNumber"
                        this.episode = epNumber
                    }
                )
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subed, episodesList.distinctBy { it.episode })
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchHtml = app.get(data).text
        val doc = Jsoup.parse(watchHtml)
        var count = 0

        // Extract iframe video embeds
        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotEmpty()) {
                loadExtractor(src, data, subtitleCallback, callback)
                count++
            }
        }

        // Extract direct video source tags
        doc.select("video source[src], video[src]").forEach { video ->
            val src = fixUrl(video.attr("src"))
            if (src.isNotEmpty()) {
                callback(
                    ExtractorLink(
                        this.name,
                        this.name,
                        src,
                        data,
                        getQualityFromName(video.attr("res") ?: "720p"),
                        isM3u8 = src.contains(".m3u8")
                    )
                )
                count++
            }
        }

        // Extract embedded HLS stream links if present in scripts
        val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
        m3u8Regex.findAll(watchHtml).forEach { match ->
            val streamUrl = match.value
            callback(
                ExtractorLink(
                    this.name,
                    "HLS Stream",
                    streamUrl,
                    data,
                    Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            count++
        }

        return count > 0
    }

    // Jackson Data Models for Re:ANIME REST API v1
    data class TitleData(
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("romaji") val romaji: String? = null
    )

    data class CoverImageData(
        @JsonProperty("extra_large") val extraLarge: String? = null,
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null
    )

    data class AnimeSearchItem(
        @JsonProperty("anime_id") val animeId: String? = null,
        @JsonProperty("title") val title: TitleData? = null,
        @JsonProperty("cover_image") val coverImage: CoverImageData? = null,
        @JsonProperty("format") val format: String? = null
    )

    data class SearchApiResponse(
        @JsonProperty("results") val results: List<AnimeSearchItem>? = null
    )

    data class TopAnimeResponse(
        @JsonProperty("data") val data: List<AnimeSearchItem>? = null
    )

    data class AnimeDetailsResponse(
        @JsonProperty("anime_id") val animeId: String? = null,
        @JsonProperty("title") val title: TitleData? = null,
        @JsonProperty("cover_image") val coverImage: CoverImageData? = null,
        @JsonProperty("banner_image") val bannerImage: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("season_year") val seasonYear: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("episodes_total") val episodesTotal: Int? = null,
        @JsonProperty("last_episode") val lastEpisode: Int? = null
    )
}
