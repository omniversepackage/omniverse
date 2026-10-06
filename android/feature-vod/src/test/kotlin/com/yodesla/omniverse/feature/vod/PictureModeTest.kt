package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals

class PictureModeTest {
    @Test fun cyclesFitStretchZoomAndDefaultsToFit() {
        assertEquals(PictureMode.FIT, PictureMode.of(null))
        assertEquals(PictureMode.FIT, PictureMode.of("garbage"))
        assertEquals(PictureMode.STRETCH, PictureMode.FIT.next())
        assertEquals(PictureMode.ZOOM, PictureMode.STRETCH.next())
        assertEquals(PictureMode.FIT, PictureMode.ZOOM.next())
    }

    @Test fun episodesShareTheirShowsSetting() {
        val src = SourceId("plex")
        val e1 = ContentKey(src, ContentKind.EPISODE, RemoteId("e1"))
        val e2 = ContentKey(src, ContentKind.EPISODE, RemoteId("e2"))
        assertEquals(pictureModeKey(e1, RemoteId("show")), pictureModeKey(e2, RemoteId("show")))
        val movie = ContentKey(src, ContentKind.VOD, RemoteId("m1"))
        assertEquals("picture_mode_plex_m1", pictureModeKey(movie, null))
    }
}
