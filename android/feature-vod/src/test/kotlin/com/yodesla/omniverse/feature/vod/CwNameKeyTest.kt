package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Task 84k: Continue Watching grouping in the Movies/Shows browse row (kind + normalised name). */
class CwNameKeyTest {
    private val src = SourceId("s")
    private fun poster(kind: ContentKind, id: String, name: String) =
        PosterRow(ContentKey(src, kind, RemoteId(id)), name, null, null, null)

    @Test
    fun providerPrefixYearAndQualityTagsDoNotSplitCopies() {
        assertEquals(
            cwNameKey(poster(ContentKind.VOD, "a1", "Armageddon")),
            cwNameKey(poster(ContentKind.VOD, "a2", "EN | Armageddon (1998) [4K]")),
        )
    }

    @Test
    fun sameTitleUnderDifferentKindsStaysSeparate() {
        assertNotEquals(cwNameKey(poster(ContentKind.SERIES, "l1", "Loki")), cwNameKey(poster(ContentKind.VOD, "l2", "Loki")))
    }

    @Test
    fun differentTitlesStaySeparate() {
        assertNotEquals(cwNameKey(poster(ContentKind.VOD, "d1", "Dune")), cwNameKey(poster(ContentKind.VOD, "d2", "Dune Part Two")))
    }
}
