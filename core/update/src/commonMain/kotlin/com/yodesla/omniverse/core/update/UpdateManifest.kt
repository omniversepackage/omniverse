package com.yodesla.omniverse.core.update

import kotlinx.serialization.Serializable

/**
 * The public update manifest (PLAN.md P3.7). Hosted somewhere that is not the user's house;
 * the URL comes from BrandConfig.updateManifestUrl. Parsed leniently: unknown keys are ignored.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String = "",
    val minSdk: Int = 23,
    val mandatory: Boolean = false,
)
