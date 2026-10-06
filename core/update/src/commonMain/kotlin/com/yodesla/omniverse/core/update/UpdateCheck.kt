package com.yodesla.omniverse.core.update

/** The outcome of one update check. Never thrown: the caller always gets a value. */
sealed interface UpdateCheck {
    /** No newer build for this device (or the manifest is not newer at all). */
    data object UpToDate : UpdateCheck

    /** A newer, installable build exists. */
    data class Available(val manifest: UpdateManifest) : UpdateCheck

    /** A newer build exists but the manifest is not trustworthy, or the check failed. */
    data class Failed(val reason: String) : UpdateCheck
}
