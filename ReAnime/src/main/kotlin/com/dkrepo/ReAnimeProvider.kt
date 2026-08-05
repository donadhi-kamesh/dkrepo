package com.dkrepo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import java.net.URLEncoder

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Re:ANIME"
    override var supportedTypes = setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)
    override var lang = "en"
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/api/v1/top/anime" to "Top Anime",
        "$mainUrl/api/v1/search?q=a" to "Trending Anime",
        "$mainUrl/api/v1/schedule" to "Latest Releases"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        try {
            if (request.data.contains("/schedule")) {
                val response = app.get(request.data).text
                val parsed = parseJson<ScheduleApiResponse>(response)
                parsed.schedule?.forEach { day ->
                    day.episodes?.forEach { item ->
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
                }
            } else if (request.data.contains("/top/anime")) {
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/v1/search?q=$encodedQuery"
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

            val subEpisodes = mutableListOf<Episode>()
            val dubEpisodes = mutableListOf<Episode>()

            val subCount = details.subbed ?: details.episodesTotal ?: details.lastEpisode ?: details.episodes ?: 0
            val dubCount = details.dubbed ?: 0

            if (subCount > 0) {
                for (epNum in 1..subCount) {
                    subEpisodes.add(
                        newEpisode("$mainUrl/watch/$animeId?ep=$epNum&lang=sub") {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                        }
                    )
                }
            }

            if (dubCount > 0) {
                for (epNum in 1..dubCount) {
                    dubEpisodes.add(
                        newEpisode("$mainUrl/watch/$animeId?ep=$epNum&lang=dub") {
                            this.name = "Episode $epNum (Dub)"
                            this.episode = epNum
                        }
                    )
                }
            }

            val recommendationsList = details.relations?.mapNotNull { rel ->
                val relId = rel.animeId ?: return@mapNotNull null
                val relTitle = rel.title?.english?.ifEmpty { null }
                    ?: rel.title?.romaji?.ifEmpty { null }
                    ?: rel.title?.native ?: "Unknown"
                val relCover = rel.coverImage?.extraLarge?.ifEmpty { null }
                    ?: rel.coverImage?.large?.ifEmpty { null }
                    ?: rel.coverImage?.medium ?: ""

                newAnimeSearchResponse(relTitle, "$mainUrl/anime/$relId", TvType.Anime) {
                    this.posterUrl = relCover
                }
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = description
                this.year = year
                this.showStatus = status
                this.tags = details.genres
                if (recommendationsList != null) {
                    this.recommendations = recommendationsList
                }
                if (subEpisodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                }
                if (dubEpisodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Dubbed, dubEpisodes)
                }
            }
        } catch (e: Exception) {
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
                addEpisodes(DubStatus.Subbed, episodesList.distinctBy { it.episode })
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

        doc.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotEmpty()) {
                loadExtractor(src, data, subtitleCallback, callback)
                count++
            }
        }

        doc.select("video source[src], video[src]").forEach { video ->
            val src = fixUrl(video.attr("src"))
            if (src.isNotEmpty()) {
                val isM3u8 = src.contains(".m3u8")
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = src,
                        referer = data,
                        quality = getQualityFromName(video.attr("res") ?: "720p"),
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                count++
            }
        }

        val m3u8Regex = Regex("""https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*""")
        m3u8Regex.findAll(watchHtml).forEach { match ->
            val streamUrl = match.value
            if (!streamUrl.contains("favicon") && !streamUrl.contains("logo") && !streamUrl.contains("banner")) {
                val isM3u8 = streamUrl.contains(".m3u8")
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = if (isM3u8) "HLS Stream" else "MP4 Video",
                        url = streamUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                count++
            }
        }

        return count > 0
    }

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

    data class RelationItem(
        @JsonProperty("anime_id") val animeId: String? = null,
        @JsonProperty("title") val title: TitleData? = null,
        @JsonProperty("cover_image") val coverImage: CoverImageData? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("relation_type") val relationType: String? = null
    )

    data class SearchApiResponse(
        @JsonProperty("results") val results: List<AnimeSearchItem>? = null
    )

    data class TopAnimeResponse(
        @JsonProperty("data") val data: List<AnimeSearchItem>? = null
    )

    data class ScheduleDay(
        @JsonProperty("episodes") val episodes: List<AnimeSearchItem>? = null
    )

    data class ScheduleApiResponse(
        @JsonProperty("schedule") val schedule: List<ScheduleDay>? = null
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
        @JsonProperty("last_episode") val lastEpisode: Int? = null,
        @JsonProperty("subbed") val subbed: Int? = null,
        @JsonProperty("dubbed") val dubbed: Int? = null,
        @JsonProperty("relations") val relations: List<RelationItem>? = null
    )
}
