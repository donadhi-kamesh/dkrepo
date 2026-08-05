package com.dkrepo

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Re:ANIME"
    override var supportedTypes = setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)
    override var lang = "en"
    override var hasMainPage = true

    private val flixCloud = FlixCloud()

    companion object {
        /**
         * Dedicated Jackson mapper for reanime.to.
         * Cloudstream's AppUtils.parseJson tries kotlinx first and its shared
         * Jackson mapper can silently drop snake_case keys (anilist_id -> 0).
         * We rely on explicit @JsonProperty so R8 obfuscation does not affect mapping.
         */
        private val apiMapper: ObjectMapper = jacksonObjectMapper().apply {
            this.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        private fun <T : Any> parseApi(text: String, clazz: Class<T>): T =
            apiMapper.readValue(text, clazz)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/v1/top/anime" to "Top Anime",
        "$mainUrl/api/v1/schedule" to "Latest Releases"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        try {
            if (request.data.contains("/schedule")) {
                val parsed = parseApi(app.get(request.data).text, ScheduleApiResponse::class.java)
                parsed.schedule?.forEach { day ->
                    day.episodes?.forEach { item ->
                        item.toSearchResponse()?.let { items.add(it) }
                    }
                }
            } else {
                val parsed = parseApi(app.get(request.data).text, TopAnimeResponse::class.java)
                parsed.data?.forEach { item ->
                    item.toSearchResponse()?.let { items.add(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val parsed = parseApi(
            app.get("$mainUrl/api/v1/search?q=$encodedQuery").text,
            SearchApiResponse::class.java
        )
        return parsed.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private fun AnimeSearchItem.toSearchResponse(): SearchResponse? {
        val id = animeId ?: return null
        val title = title?.english?.ifEmpty { null }
            ?: title?.romaji?.ifEmpty { null }
            ?: title?.native ?: return null
        val cover = coverImage?.extraLarge?.ifEmpty { null }
            ?: coverImage?.large?.ifEmpty { null }
            ?: coverImage?.medium
        val type = when (format?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", type) {
            this.posterUrl = cover
        }
    }
    override suspend fun load(url: String): LoadResponse {
        val animeId = url.removePrefix("$mainUrl/anime/").removePrefix("$mainUrl/watch/")
            .substringBefore("?").substringBefore("/")
        val details = parseApi(
            app.get("$mainUrl/api/v1/anime/$animeId").text,
            AnimeDetailsResponse::class.java
        )

        val title = details.title?.english?.ifEmpty { null }
            ?: details.title?.romaji?.ifEmpty { null }
            ?: details.title?.native ?: "Unknown"
        val poster = details.coverImage?.extraLarge?.ifEmpty { null }
            ?: details.coverImage?.large?.ifEmpty { null }
            ?: details.coverImage?.medium
        val type = when (details.format?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val status = when (details.status?.lowercase()) {
            "releasing" -> ShowStatus.Ongoing
            "finished" -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }

        val anilistId = details.anilistId ?: 0
        val tmdbId = details.themoviedbId ?: 0
        val tmdbSeason = details.externalSeasons?.tmdb?.takeIf { it > 0 } ?: 1
        Log.i("ReAnime", "load details animeId=$animeId anilistId=$anilistId tmdbId=$tmdbId season=$tmdbSeason title=${title.take(40)}")

        // full episode list (single request, high limit)
        val episodes = try {
            parseApi(
                app.get("$mainUrl/api/v1/anime/$animeId/episodes?limit=5000").text,
                EpisodesResponse::class.java
            ).data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        fun epData(epNum: Int) = "$animeId|$anilistId|$tmdbId|$tmdbSeason|$epNum"

        val subEpisodes = if (episodes.isNotEmpty()) {
            episodes.map { ep ->
                newEpisode(epData(ep.episodeNumber ?: 1)) {
                    this.name = ep.title?.ifEmpty { null } ?: "Episode ${ep.episodeNumber}"
                    this.episode = ep.episodeNumber
                    this.posterUrl = ep.thumbnail?.ifEmpty { null }
                    this.description = ep.description?.ifEmpty { null }
                }
            }
        } else {
            // fall back to a plain numbered list
            val count = details.subbed ?: details.episodesTotal ?: details.lastEpisode
                ?: details.episodes ?: 0
            (1..count).map { epNum ->
                newEpisode(epData(epNum)) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            }
        }

        val dubCount = details.dubbed ?: 0
        val dubEpisodes = if (dubCount > 0) {
            (1..dubCount).map { epNum ->
                val subEp = subEpisodes.firstOrNull { it.episode == epNum }
                newEpisode(epData(epNum)) {
                    this.name = subEp?.name ?: "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = subEp?.posterUrl
                    this.description = subEp?.description
                }
            }
        } else emptyList()

        val recommendationsList = details.relations?.mapNotNull { rel ->
            val relId = rel.animeId ?: return@mapNotNull null
            val relTitle = rel.title?.english?.ifEmpty { null }
                ?: rel.title?.romaji?.ifEmpty { null }
                ?: rel.title?.native ?: return@mapNotNull null
            val relCover = rel.coverImage?.extraLarge?.ifEmpty { null }
                ?: rel.coverImage?.large?.ifEmpty { null }
                ?: rel.coverImage?.medium
            newAnimeSearchResponse(relTitle, "$mainUrl/anime/$relId", TvType.Anime) {
                this.posterUrl = relCover
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.backgroundPosterUrl = details.bannerImage?.ifEmpty { null }
            this.plot = details.description?.replace(Regex("<[^>]*>"), "")
            this.year = details.seasonYear
            this.showStatus = status
            this.tags = details.genres
            if (!recommendationsList.isNullOrEmpty()) this.recommendations = recommendationsList
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i("ReAnime", "loadLinks data=$data")
        // episode data layouts:
        // new: animeId|anilistId|tmdbId|tmdbSeason|episodeNumber
        // old (cached): anilistId|tmdbId|tmdbSeason|episodeNumber
        val parts = data.split("|")
        var animeId = ""
        var anilistId: Int
        var tmdbId: Int
        var tmdbSeason: Int
        var epNum: Int
        if (parts.size >= 5) {
            animeId = parts[0]
            anilistId = parts[1].toIntOrNull() ?: 0
            tmdbId = parts[2].toIntOrNull() ?: 0
            tmdbSeason = parts[3].toIntOrNull() ?: 1
            epNum = parts[4].toIntOrNull() ?: 1
        } else {
            anilistId = parts.getOrNull(0)?.toIntOrNull() ?: 0
            tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: 0
            tmdbSeason = parts.getOrNull(2)?.toIntOrNull() ?: 1
            epNum = parts.getOrNull(3)?.toIntOrNull() ?: 1
        }

        // stale-cache fallback: if both ids are 0 but we have animeId, refetch details
        if (anilistId == 0 && tmdbId == 0 && animeId.isNotBlank()) {
            try {
                val details = parseApi(
                    app.get("$mainUrl/api/v1/anime/$animeId").text,
                    AnimeDetailsResponse::class.java
                )
                anilistId = details.anilistId ?: 0
                tmdbId = details.themoviedbId ?: 0
                if (anilistId != 0 || tmdbId != 0) {
                    Log.i("ReAnime", "loadLinks refetched $animeId -> anilistId=$anilistId tmdbId=$tmdbId")
                }
            } catch (e: Exception) {
                Log.w("ReAnime", "loadLinks refetch failed for $animeId: ${e.message}")
            }
        }

        val flixUrl = if (anilistId > 0) {
            "$mainUrl/api/flix/$anilistId/$epNum"
        } else {
            "$mainUrl/api/flix/0/$epNum?tmdb=$tmdbId&season=$tmdbSeason"
        }

        var found = false
        try {
            val flixText = app.get(flixUrl).text
            Log.i("ReAnime", "flix api $flixUrl len=${flixText.length}")
            val parsed = parseApi(flixText, FlixResponse::class.java)
            val seenLinks = HashSet<String>()
            val seenSubs = HashSet<String>()
            parsed.servers?.forEach { server ->
                val link = server.dataLink ?: return@forEach
                if (link.isBlank() || !seenLinks.add(link)) return@forEach
                try {
                    flixCloud.extract(
                        url = link,
                        subtitleCallback = { sub ->
                            if (seenSubs.add(sub.url)) subtitleCallback(sub)
                        },
                        callback = { extractorLink ->
                            found = true
                            callback(extractorLink)
                        },
                        emitSubtitles = true,
                        serverLabel = server.serverName
                    )
                } catch (e: Exception) {
                    Log.w("ReAnime", "extractor failed for $link: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Log.i("ReAnime", "servers tried=${parsed.servers?.size ?: 0} found=$found")
        } catch (e: Exception) {
            Log.w("ReAnime", "loadLinks failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return found
    }
    // ---------- API data classes (kept from R8) ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TitleData(
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("romaji") val romaji: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImageData(
        @JsonProperty("extra_large") val extraLarge: String? = null,
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeSearchItem(
        @JsonProperty("anime_id") val animeId: String? = null,
        @JsonProperty("title") val title: TitleData? = null,
        @JsonProperty("cover_image") val coverImage: CoverImageData? = null,
        @JsonProperty("format") val format: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchApiResponse(
        @JsonProperty("results") val results: List<AnimeSearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TopAnimeResponse(
        @JsonProperty("data") val data: List<AnimeSearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ScheduleDay(
        @JsonProperty("episodes") val episodes: List<AnimeSearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ScheduleApiResponse(
        @JsonProperty("schedule") val schedule: List<ScheduleDay>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RelationItem(
        @JsonProperty("anime_id") val animeId: String? = null,
        @JsonProperty("title") val title: TitleData? = null,
        @JsonProperty("cover_image") val coverImage: CoverImageData? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("relation_type") val relationType: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ExternalSeasons(
        @JsonProperty("tmdb") val tmdb: Int? = null,
        @JsonProperty("tvdb") val tvdb: Int? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetailsResponse(
        @JsonProperty("anime_id") val animeId: String? = null,
        @JsonProperty("anilist_id") val anilistId: Int? = null,
        @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
        @JsonProperty("mal_id") val malId: Int? = null,
        @JsonProperty("external_seasons") val externalSeasons: ExternalSeasons? = null,
        @JsonProperty("title") val title: TitleData? = null,
        @JsonProperty("cover_image") val coverImage: CoverImageData? = null,
        @JsonProperty("banner_image") val bannerImage: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("season_year") val seasonYear: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("episodes_total") val episodesTotal: Int? = null,
        @JsonProperty("last_episode") val lastEpisode: Int? = null,
        @JsonProperty("subbed") val subbed: Int? = null,
        @JsonProperty("dubbed") val dubbed: Int? = null,
        @JsonProperty("relations") val relations: List<RelationItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        @JsonProperty("episodeId") val episodeId: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("aired") val aired: String? = null,
        @JsonProperty("is_filler") val isFiller: Boolean? = null,
        @JsonProperty("is_recap") val isRecap: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        @JsonProperty("data") val data: List<EpisodeItem>? = null,
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FlixServer(
        @JsonProperty("serverName") val serverName: String? = null,
        @JsonProperty("dataLink") val dataLink: String? = null,
        @JsonProperty("dataType") val dataType: String? = null,
        @JsonProperty("softsub") val softsub: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FlixResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("servers") val servers: List<FlixServer>? = null
    )
}