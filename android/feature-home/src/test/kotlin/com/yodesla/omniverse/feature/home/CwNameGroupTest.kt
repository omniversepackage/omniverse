package com.yodesla.omniverse.feature.home

import com.yodesla.omniverse.core.model.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Task 84k: Continue Watching name grouping — copies of one title without TMDB ids collapse. */
class CwNameGroupTest {
    @Test
    fun providerPrefixYearAndQualityTagsDoNotSplitCopies() {
        assertEquals(
            cwNameGroup(ContentKind.VOD, "Armageddon"),
            cwNameGroup(ContentKind.VOD, "EN | Armageddon (1998) [4K]"),
        )
    }

    @Test
    fun sameTitleUnderDifferentKindsStaysSeparate() {
        assertNotEquals(cwNameGroup(ContentKind.SERIES, "Loki"), cwNameGroup(ContentKind.VOD, "Loki"))
    }

    @Test
    fun differentTitlesStaySeparate() {
        assertNotEquals(cwNameGroup(ContentKind.VOD, "Dune"), cwNameGroup(ContentKind.VOD, "Dune Part Two"))
    }
}
