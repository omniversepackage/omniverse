package com.yodesla.omniverse.feature.vod

import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.Episode
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeQueueTest {
    private val source = SourceId("source")
    private val series = RemoteId("series")
    private val key = ContentKey(source, ContentKind.SERIES, series)

    private fun episode(id: String, season: Int, number: Int) = Episode(
        source, RemoteId(id), series, season, number, "Episode $number", null, null, null, "mkv", null,
    )

    @Test fun manualAndAutomaticNextCanCrossSeasonAndContinueAgain() {
        val first = episode("a", 1, 9)
        val second = episode("b", 1, 10)
        val third = episode("c", 2, 1)
        val progress = Progress(ContentKey(source, ContentKind.EPISODE, second.remoteId), series, 42_000, 120_000, 1)
        val queue = buildEpisodeQueue(first, listOf(first, second, third), key, "The Show", mapOf("b" to progress),
            seriesLookupTitle = "Original Provider Title", seriesYear = 2013)

        assertEquals("a", queue.key.remoteId.value)
        assertEquals("The Show · S1E9", queue.title)
        assertEquals("b", queue.next?.key?.remoteId?.value)
        assertEquals(42_000, queue.next?.resumeMs)
        assertEquals("c", queue.next?.next?.key?.remoteId?.value)
        assertEquals("Original Provider Title", queue.next?.next?.seriesLookupTitle)
        assertEquals(2013, queue.next?.next?.seriesYear)
        assertNull(queue.next?.next?.next)
    }

    @Test fun startOverRestartsOnlyTheChosenEpisode() {
        val first = episode("a", 1, 12)
        val second = episode("b", 1, 13)
        fun p(id: String) = Progress(ContentKey(source, ContentKind.EPISODE, RemoteId(id)), series, 300_000, 1_440_000, 1)
        val queue = buildEpisodeQueue(first, listOf(first, second), key, "The Show",
            mapOf("a" to p("a"), "b" to p("b")), fromStart = true)

        assertEquals(0, queue.resumeMs)
        assertEquals(300_000, queue.next?.resumeMs)
    }
}
