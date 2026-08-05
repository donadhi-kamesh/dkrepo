import com.lagradost.cloudstream3.gradle.CloudstreamExtension

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

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

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

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
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
        val cloudstream by configurations
        val implementation by configurations

        cloudstream("com.github.recloudstream:cloudstream:master-SNAPSHOT")

        implementation("org.jsoup:jsoup:1.17.2")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
