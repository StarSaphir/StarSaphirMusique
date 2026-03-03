pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "YTMusicApp"
include(":app")
include(":core:database")
include(":core:domain")
include(":core:common")
include(":feature:browser")
include(":feature:downloader")
include(":feature:library")
include(":feature:player")
include(":feature:playlists")
include(":feature:favorites")
include(":feature:stats")
include(":feature:settings")
