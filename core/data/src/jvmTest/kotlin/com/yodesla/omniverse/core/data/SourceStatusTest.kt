package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceStatusTest {
    private val hour = 3_600_000L

    private fun healthy() = mapOf(
        ContentKind.VOD to 1_234L,
        ContentKind.SERIES to 567L,
        ContentKind.LIVE to 89L,
    )

    @Test
    fun healthySourceShowsCounts() {
        val s = sourceStatus(SourceStatusInput(healthy(), null, null, null, plex = false))
        assertEquals(SourceStatus.UpToDate(1_234L, 567L, 89L), s)
        assertEquals("Up to date · 1,234 movies · 567 shows · 89 channels", s.statusText(0L))
        assertNull(s.retryAction())
    }

    @Test
    fun updatingStageWinsOverEverythingElse() {
        val s = sourceStatus(SourceStatusInput(healthy(), SyncStage.VOD, SourceErrorKind.AUTH, 5L, plex = true))
        assertEquals(SourceStatus.Updating(SyncStage.VOD), s)
        assertEquals("Updating… vod", s.statusText(0L))
        assertNull(s.retryAction())
    }

    @Test
    fun authFailureIsSignInExpired() {
        val plex = sourceStatus(SourceStatusInput(healthy(), null, SourceErrorKind.AUTH, 1L, plex = true))
        assertEquals(SourceStatus.SignInExpired(plex = true), plex)
        assertEquals("Sign-in expired", plex.statusText(0L))
        assertEquals("Re-link", plex.retryAction())

        val xtream = sourceStatus(SourceStatusInput(healthy(), null, SourceErrorKind.AUTH, 1L, plex = false))
        assertEquals(SourceStatus.SignInExpired(plex = false), xtream)
        assertEquals("Check your login", xtream.statusText(0L))
        assertEquals("Re-enter login", xtream.retryAction())
    }

    @Test
    fun networkFailureIsOfflineWithRetry() {
        val s = sourceStatus(SourceStatusInput(healthy(), null, SourceErrorKind.NETWORK, 1L, plex = false))
        assertEquals(SourceStatus.Offline, s)
        assertEquals("Offline — couldn't reach the server", s.statusText(0L))
        assertEquals("Retry", s.retryAction())
    }

    @Test
    fun otherFailureIsLastFailedWithRetry() {
        val s = sourceStatus(SourceStatusInput(healthy(), null, SourceErrorKind.OTHER, 0L, plex = false))
        assertEquals(SourceStatus.LastFailed(0L), s)
        assertEquals("Last update failed", s.statusText(0L))
        assertEquals("Retry", s.retryAction())
    }

    @Test
    fun lastFailedShowsRelativeTime() {
        val sixHoursAgo = 10 * hour - 6 * hour
        assertEquals("Last update failed 6 h ago", SourceStatus.LastFailed(sixHoursAgo).statusText(10 * hour))
        val momentsAgo = 10 * hour - 30_000L
        assertEquals("Last update failed just now", SourceStatus.LastFailed(momentsAgo).statusText(10 * hour))
    }

    @Test
    fun emptyCountsBeforeFirstReadIsChecking() {
        val s = sourceStatus(SourceStatusInput(emptyMap(), null, null, null, plex = false))
        assertEquals(SourceStatus.Checking, s)
        assertEquals("Checking…", s.statusText(0L))
    }

    @Test
    fun countsFormatWithThousandsGroups() {
        assertEquals("0 movies · 0 shows · 0 channels", formatCounts(0, 0, 0))
        assertEquals("999 movies · 1 shows · 12 channels", formatCounts(999, 1, 12))
        assertEquals("1,000 movies · 12,345 shows · 1,234,567 channels", formatCounts(1_000, 12_345, 1_234_567))
    }

    @Test
    fun relativeTimeBuckets() {
        assertEquals("just now", relativeTime(30_000L, 0L))
        assertEquals("12 min ago", relativeTime(12 * 60_000L, 0L))
        assertEquals("6 h ago", relativeTime(6 * hour, 0L))
        assertEquals("3 days ago", relativeTime(3 * 24 * hour, 0L))
    }

    @Test
    fun statusKeysAndEncodingRoundTrip() {
        assertEquals("src_err_abc", SourceStatusKeys.error(SourceId("abc")))
        assertEquals("src_sync_abc", SourceStatusKeys.sync(SourceId("abc")))

        assertEquals("AUTH|123", encodeError(SourceErrorKind.AUTH, 123L))
        val decoded = decodeError("AUTH|123")
        assertEquals(SourceErrorKind.AUTH, decoded.first)
        assertEquals(123L, decoded.second)
        assertNull(decodeError("").first)
        assertNull(decodeError("").second)
        assertNull(decodeError(null).first)
        assertNull(decodeError(null).second)

        assertEquals("EPG", encodeSync(SyncStage.EPG))
        assertEquals(SyncStage.EPG, decodeSync("EPG"))
        assertNull(decodeSync(""))
        assertNull(decodeSync(null))
    }
}
