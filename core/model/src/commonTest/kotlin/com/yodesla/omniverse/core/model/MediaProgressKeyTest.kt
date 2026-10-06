package com.yodesla.omniverse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MediaProgressKeyTest {
    @Test
    fun versionsKeepSeparateProgressAndResolveToOnePoster() {
        val movie = ContentKey(SourceId("plex"), ContentKind.VOD, RemoteId("77"))
        val first = mediaProgressKey(movie, "media-1")
        val second = mediaProgressKey(movie, "media-2")
        assertNotEquals(first, second)
        assertNotEquals(movie, first)
        assertEquals(movie, progressPosterKey(first, movie.remoteId))
        assertEquals(movie, progressPosterKey(second, movie.remoteId))
        assertEquals(movie, progressPosterKey(movie, null))
    }
}
