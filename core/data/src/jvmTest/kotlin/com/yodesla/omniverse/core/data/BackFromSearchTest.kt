package com.yodesla.omniverse.core.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 107: "Back from a search result goes to" — only the literal "search" keeps Search. */
class BackFromSearchTest {
    @Test
    fun absentOrUnknownValuesUseTheGuideOrDetailsDefault() {
        assertFalse(backFromSearchGoesToSearch(null))
        assertFalse(backFromSearchGoesToSearch(""))
        assertFalse(backFromSearchGoesToSearch(UserDataRepository.BACK_FROM_SEARCH_GUIDE_OR_DETAILS))
        assertFalse(backFromSearchGoesToSearch("true"))
        assertFalse(backFromSearchGoesToSearch("Search"))
    }

    @Test
    fun onlyTheLiteralSearchValueKeepsTheSearchScreen() {
        assertTrue(backFromSearchGoesToSearch(UserDataRepository.BACK_FROM_SEARCH_SEARCH))
    }
}
