package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 95: SpoilerFree — setting parsing, which episodes stay hidden, and the replacement text. */
class SpoilerFreeTest {
    private val src = SourceId("s")
    private val series = ContentKey(src, ContentKind.SERIES, RemoteId("s1"))

    private fun epKey(id: String) = ContentKey(src, ContentKind.EPISODE, RemoteId(id))
    private fun progressOf(id: String, positionMs: Long, durationMs: Long?, updatedMs: Long, completed: Boolean = false) =
        id to Progress(epKey(id), series.remoteId, positionMs, durationMs, updatedMs, completed)

    @Test
    fun switchesDefaultOffAndOnlyTheLiteralTrueEnables() {
        assertFalse(SpoilerFree.enabled(null))
        assertFalse(SpoilerFree.enabled("false"))
        assertFalse(SpoilerFree.enabled("anything"))
        assertTrue(SpoilerFree.enabled("true"))
        assertFalse(SpoilerFree.hideTitles(null))
        assertFalse(SpoilerFree.hideTitles("false"))
        assertTrue(SpoilerFree.hideTitles("true"))
    }

    @Test
    fun withNothingWatchedEveryEpisodeIsHidden() {
        val ids = listOf("e1", "e2", "e3")
        assertEquals(ids.toSet(), SpoilerFree.hiddenEpisodeIds(ids, emptyMap()))
    }

    @Test
    fun episodesAfterTheNewestCompletedWatchAreHidden() {
        val ids = listOf("e1", "e2", "e3", "e4")
        val progress = mapOf(
            progressOf("e1", 60_000, 60_000, 10L, completed = true),
            progressOf("e2", 60_000, 60_000, 20L, completed = true),
        )
        assertEquals(setOf("e3", "e4"), SpoilerFree.hiddenEpisodeIds(ids, progress))
    }

    @Test
    fun theEpisodeInProgressIsNeverHidden() {
        val ids = listOf("e1", "e2", "e3", "e4")
        val progress = mapOf(
            progressOf("e1", 60_000, 60_000, 10L, completed = true),
            progressOf("e2", 30_000, 60_000, 20L),
        )
        assertEquals(setOf("e3", "e4"), SpoilerFree.hiddenEpisodeIds(ids, progress))
    }

    @Test
    fun skippedUnwatchedEpisodesBeforeTheAnchorStayOpen() {
        val ids = listOf("e1", "e2", "e3", "e4")
        // e1 watched long ago, e2 skipped (no row), e3 watched most recently.
        val progress = mapOf(
            progressOf("e1", 60_000, 60_000, 10L, completed = true),
            progressOf("e3", 60_000, 60_000, 30L, completed = true),
        )
        assertEquals(setOf("e4"), SpoilerFree.hiddenEpisodeIds(ids, progress))
    }

    @Test
    fun aWatchedEpisodeAfterTheAnchorStaysOpen() {
        val ids = listOf("e1", "e2", "e3", "e4")
        // e4 was watched long ago; the newest watch is e2, so e3 is the only spoiler.
        val progress = mapOf(
            progressOf("e2", 60_000, 60_000, 30L, completed = true),
            progressOf("e4", 60_000, 60_000, 10L, completed = true),
        )
        assertEquals(setOf("e3"), SpoilerFree.hiddenEpisodeIds(ids, progress))
    }

    @Test
    fun unknownLengthSentinelCountsAsWatched() {
        val ids = listOf("e1", "e2", "e3")
        val progress = mapOf(progressOf("e1", 1L, null, 10L))
        assertEquals(setOf("e2", "e3"), SpoilerFree.hiddenEpisodeIds(ids, progress))
    }

    @Test
    fun upNextSubtitleDropsTheEpisodeIdentityWhenOn() {
        assertEquals("Up next  ·  S2 · E4", SpoilerFree.upNextSubtitle(2, 4, spoilerFree = false))
        assertEquals("Up next", SpoilerFree.upNextSubtitle(2, 4, spoilerFree = true))
    }

    @Test
    fun playerNextTitleIsReplacedWhenOn() {
        assertEquals("The One Where It Happens", SpoilerFree.nextEpisodeTitle("The One Where It Happens", spoilerFree = false))
        assertEquals(SpoilerFree.HIDDEN_NEXT_TITLE, SpoilerFree.nextEpisodeTitle("The One Where It Happens", spoilerFree = true))
    }

    @Test
    fun hiddenDescriptionIsTheSpecText() {
        assertEquals("Description hidden - press OK to reveal", SpoilerFree.HIDDEN_DESCRIPTION)
    }
}
