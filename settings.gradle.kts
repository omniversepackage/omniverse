pluginManagement {
    includeBuild("build-logic")
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
    }
}

rootProject.name = "omniverse"

include(
    ":core:model",
    ":core:source-api",
    ":core:hooks",
    ":core:brand",
    ":core:net",
    ":core:source-xtream",
    ":core:source-plex",
    ":core:epg-xmltv",
    ":core:source-m3u",
    ":core:update",
    ":core:database",
    ":core:data",
    ":android:designsystem",
    ":android:parental",
    ":android:player",
    ":android:feature-onboarding",
    ":android:feature-live",
    ":android:feature-vod",
    ":android:feature-home",
    ":android:update",
    ":android:app",
    ":android:benchmark",
)
