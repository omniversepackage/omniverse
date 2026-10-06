package com.yodesla.omniverse.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True on phones (and narrow tablets): touch UI under a bottom bar. Screens use it to swap
 * TV side columns for top chip rows, shrink hero type, drop TV-only hints and clocks.
 * Provided by the app root; defaults to false (TV).
 */
val LocalCompact = staticCompositionLocalOf { false }
