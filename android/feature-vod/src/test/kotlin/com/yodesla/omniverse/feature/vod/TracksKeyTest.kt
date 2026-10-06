package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals

class TracksKeyTest {
    @Test fun episodesShareTheirShowsTrackChoice() {
        val src = SourceId("plex")
        val e1 = ContentKey(src, ContentKind.EPISODE, RemoteId("e1"))
        val e2 = ContentKey(src, ContentKind.EPISODE, RemoteId("e2"))
        assertEquals(tracksKey(e1, RemoteId("show")), tracksKey(e2, RemoteId("show")))
        assertEquals("tracks_plex_show", tracksKey(e1, RemoteId("show")))
    }

    @Test fun moviesGetTheirOwnTrackChoice() {
        val src = SourceId("jellyfin")
        val m1 = ContentKey(src, ContentKind.VOD, RemoteId("m1"))
        val m2 = ContentKey(src, ContentKind.VOD, RemoteId("m2"))
        assertEquals("tracks_jellyfin_m1", tracksKey(m1, null))
        assertEquals(tracksKey(m1, null), tracksKey(m1, null))
        assertEquals(false, tracksKey(m1, null) == tracksKey(m2, null))
    }
}
