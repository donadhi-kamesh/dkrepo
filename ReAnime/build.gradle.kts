import com.lagradost.cloudstream3.gradle.CloudstreamExtension

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

cloudstream {
    language = "en"
    description = "Re:ANIME extension provider for Cloudstream"
    authors = listOf("dkrepo")
    status = 2 // 1: Down, 2: OK, 3: Slow, 4: Beta
    types = setOf(
        com.lagradost.cloudstream3.TvType.Anime,
        com.lagradost.cloudstream3.TvType.OVA,
        com.lagradost.cloudstream3.TvType.AnimeMovie
    )
    iconUrl = "https://reanime.to/favicon-32x32.png"
}
