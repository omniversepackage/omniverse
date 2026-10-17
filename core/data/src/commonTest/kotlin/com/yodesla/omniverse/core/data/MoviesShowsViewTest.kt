package com.yodesla.omniverse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 203: Movies & Shows view — only the literal "horizontal" leaves the Classic sidebar. */
class MoviesShowsViewTest {
    @Test
    fun absentOrUnknownValuesUseTheClassicDefault() {
        assertEquals(MoviesShowsView.CLASSIC, MoviesShowsView.parse(null))
        assertEquals(MoviesShowsView.CLASSIC, MoviesShowsView.parse(""))
        assertEquals(MoviesShowsView.CLASSIC, MoviesShowsView.parse(MoviesShowsView.CLASSIC))
        assertEquals(MoviesShowsView.CLASSIC, MoviesShowsView.parse("true"))
        assertEquals(MoviesShowsView.CLASSIC, MoviesShowsView.parse("Horizontal"))
        assertEquals(MoviesShowsView.CLASSIC, MoviesShowsView.parse("horizontal "))
        assertEquals(MoviesShowsView.CLASSIC, MoviesShowsView.parse("sidebar"))
    }

    @Test
    fun onlyTheLiteralHorizontalValueSelectsTheNewLayout() {
        assertEquals(MoviesShowsView.HORIZONTAL, MoviesShowsView.parse(MoviesShowsView.HORIZONTAL))
    }
}
