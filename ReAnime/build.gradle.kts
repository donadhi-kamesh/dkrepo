import com.lagradost.cloudstream3.gradle.CloudstreamExtension

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

cloudstream {
    language = "en"
    description = "Re:ANIME extension provider for Cloudstream"
    authors = listOf("dkrepo")
    status = 2
    tvTypes = setOf("Anime", "OVA", "AnimeMovie")
    iconUrl = "https://reanime.to/favicon-32x32.png"
}
