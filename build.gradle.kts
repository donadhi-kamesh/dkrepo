import com.android.build.gradle.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) =
    extensions.getByName<LibraryExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(providers.environmentVariable("GITHUB_REPOSITORY").getOrElse("donadhi-kamesh/dkrepo"))
    }

    android {
        compileSdk = 34

        defaultConfig {
            minSdk = 21
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = freeCompilerArgs + listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-implicit-assertion-errors"
            )
        }
    }

    dependencies {
        val cloudstream = configurations.getByName("cloudstream")
        val implementation = configurations.getByName("implementation")

        cloudstream("com.github.recloudstream:cloudstream:master-SNAPSHOT")
        implementation("org.jsoup:jsoup:1.17.2")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
