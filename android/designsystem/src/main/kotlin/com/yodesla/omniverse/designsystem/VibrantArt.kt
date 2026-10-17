package com.yodesla.omniverse.designsystem

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/** Artwork saturation multiplier for the vibrancy pass (Kory, 2026-10-11). 1f = untouched. */
const val ART_SATURATION = 1.3f

/**
 * Colour filter for posters, stills, logos and cards so artwork pops against the unchanged dark
 * backgrounds. Full-screen backdrops deliberately do not use it.
 */
val VibrantArt: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(ART_SATURATION) })
