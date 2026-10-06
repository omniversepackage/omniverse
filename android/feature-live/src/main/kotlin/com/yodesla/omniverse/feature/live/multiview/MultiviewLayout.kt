package com.yodesla.omniverse.feature.live.multiview

import com.yodesla.omniverse.core.model.ContentKey

enum class PadDirection { UP, DOWN, LEFT, RIGHT }

data class MultiviewGrid(val rows: Int, val cols: Int)

/**
 * Pure layout/focus/audio rules for Multiview (Task 55), so the D-pad behaviour is unit-testable
 * without Compose: tile count → grid, neighbor per D-pad direction, which tile is audible, and the
 * picker's pick-up-to-4 selection.
 */
object MultiviewLayout {
    const val MIN_TILES = 2
    const val MAX_TILES = 4

    /** 2 channels side by side; 3–4 in a 2x2 quad (a 3-tile quad leaves the bottom-right cell empty). */
    fun gridFor(count: Int): MultiviewGrid = when (count.coerceIn(1, MAX_TILES)) {
        1 -> MultiviewGrid(1, 1)
        2 -> MultiviewGrid(1, 2)
        else -> MultiviewGrid(2, 2)
    }

    /**
     * Tile focus moves one cell in [direction]. Pressing into a screen edge or into the empty cell
     * of a 3-tile quad has no neighbor (the press then falls through to the focus system).
     */
    fun neighbor(index: Int, count: Int, direction: PadDirection): Int? {
        if (count < 1 || index !in 0 until count) return null
        val grid = gridFor(count)
        val row = index / grid.cols
        val col = index % grid.cols
        val r = when (direction) { PadDirection.UP -> row - 1; PadDirection.DOWN -> row + 1; else -> row }
        val c = when (direction) { PadDirection.LEFT -> col - 1; PadDirection.RIGHT -> col + 1; else -> col }
        if (r !in 0 until grid.rows || c !in 0 until grid.cols) return null
        val target = r * grid.cols + c
        return if (target < count) target else null
    }

    /** Only the focused tile has audio; an out-of-range focus (nothing focused yet) has none. */
    fun audibleIndex(focused: Int, count: Int): Int? = if (focused in 0 until count) focused else null

    /** OK toggles a channel: removes it if picked, appends it (order = badge) while under the cap. */
    fun toggled(selection: List<ContentKey>, key: ContentKey): List<ContentKey> = when {
        key in selection -> selection.filterNot { it == key }
        selection.size >= MAX_TILES -> selection
        else -> selection + key
    }
}
