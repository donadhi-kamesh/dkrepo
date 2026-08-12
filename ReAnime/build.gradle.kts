import com.android.build.gradle.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension

version = 30

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) =
    extensions.getByName<LibraryExtension>("android").configuration()

android {
    namespace = "com.dkrepo.reanime"
}

cloudstream {
    language = "en"
    description = "Re:ANIME extension provider for Cloudstream"
    authors = listOf("dkrepo")
    status = 1
    tvTypes = listOf("Anime", "OVA", "AnimeMovie")
    iconUrl = "https://reanime.to/favicon-32x32.png"
}
