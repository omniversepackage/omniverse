package com.yodesla.omniverse.core.brand

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything that differs between white-label builds (PLAN.md §10). Loaded from
 * brands/<brand>/brand.json, which the Android build copies into assets.
 * Feature code reads branding ONLY from here — never hardcode an app name, color or URL.
 */
@Serializable
data class BrandConfig(
    val id: String,
    val appName: String,
    /** Android applicationId (also used as the Apple bundle id later). */
    val applicationId: String,
    /** #RRGGBB accent used sparingly: focus glow, progress, live dot. */
    val accentColor: String,
    val defaultExperienceMode: ExperienceMode = ExperienceMode.SIMPLE,
    val features: FeatureFlags = FeatureFlags(),
    /** Non-null = provider-locked build: users only enter username/password. */
    val lockedPortal: LockedPortal? = null,
    val supportUrl: String? = null,
    /** Shown in About + onboarding. Keep the "we provide no content" statement. */
    val disclaimer: String,
    /** Public manifest for in-app update checks (P3.7). null = updates disabled. */
    val updateManifestUrl: String? = null,
    /**
     * Public/distribution build: third-party streaming logos can't ship, so service categories
     * render a text wordmark in the brand colour instead of the bundled logo vectors, and
     * Rotten Tomatoes scores default off. Default false keeps private builds unchanged. The
     * Gradle property -PpublicBuild=true also forces this on for the same brand.json.
     */
    val publicBuild: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): BrandConfig = json.decodeFromString(serializer(), text)
    }
}

@Serializable
enum class ExperienceMode {
    @SerialName("simple") SIMPLE,
    @SerialName("full") FULL,
}

@Serializable
data class FeatureFlags(
    val allowM3u: Boolean = true,
    val allowAddSource: Boolean = true,
    val allowMultipleSources: Boolean = true,
    val allowFullControlMode: Boolean = true,
    val allowRecording: Boolean = true,
    val allowLibrarySources: Boolean = true,
)

@Serializable
data class LockedPortal(
    val displayName: String,
    /** Tried in order; RemoteConfigProvider may replace the list at runtime. */
    val servers: List<String>,
)
