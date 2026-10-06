package com.yodesla.omniverse.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 102: the poster pre-loading window (pure maths + the prefetch queue). */
class PosterPrefetchTest {

    private val threeRows = listOf(List(10) { "u$it" }, List(10) { "v$it" }, List(10) { "w$it" })

    @Test
    fun forwardWindowIsEightAheadPlusSixOfTheRowBelow() {
        val got = posterPrefetchIndices(threeRows.map { it.size }, PosterFocus(0, 0))
        assertEquals(14, got.size)
        assertEquals((1..8).map { 0 to it }, got.take(8))
        assertEquals((0..5).map { 1 to it }, got.drop(8))
    }

    @Test
    fun backwardWindowWalksBackwardsThroughTheFocusedRow() {
        val got = posterPrefetchIndices(threeRows.map { it.size }, PosterFocus(0, 5), direction = -1)
        assertEquals((4 downTo 0).map { 0 to it }, got.take(5))
        assertEquals(1 to 0, got[5])
    }

    @Test
    fun sideRowsAboveAndBelowAreBothWarmed() {
        val got = posterPrefetchIndices(threeRows.map { it.size }, PosterFocus(1, 3))
        val rows = got.map { it.first }.toSet()
        assertTrue(0 in rows && 1 in rows && 2 in rows)
        assertEquals(6, got.count { it.first == 0 })
        assertEquals(6, got.count { it.first == 2 })
    }

    @Test
    fun windowAtTheEndOfARowFallsBackToTheNeighbouringRows() {
        val got = posterPrefetchIndices(threeRows.map { it.size }, PosterFocus(1, 9))
        assertTrue(got.none { it.first == 1 })
        assertEquals(12, got.size)
    }

    @Test
    fun windowIsCappedAndIgnoresRowsThatDoNotExist() {
        val wide = List(5) { List(100) { "x$it" } }
        assertEquals(POSTER_PREFETCH_MAX, posterPrefetchIndices(wide.map { it.size }, PosterFocus(2, 0), sideRows = 2).size)
        assertTrue(posterPrefetchIndices(listOf(3, 3), PosterFocus(5, 0)).isEmpty())
        assertTrue(posterPrefetchIndices(emptyList(), PosterFocus(0, 0)).isEmpty())
    }

    @Test
    fun directionFollowsRowThenIndex() {
        assertEquals(1, focusDirection(null, PosterFocus(0, 0)))
        assertEquals(0, focusDirection(PosterFocus(2, 4), PosterFocus(2, 4)))
        assertEquals(-1, focusDirection(PosterFocus(2, 4), PosterFocus(2, 3)))
        assertEquals(1, focusDirection(PosterFocus(2, 9), PosterFocus(3, 0)))
        assertEquals(-1, focusDirection(PosterFocus(3, 0), PosterFocus(2, 9)))
    }

    @Test
    fun fastScrollIsALeapOfThreeOrMore() {
        assertTrue(isFastPosterScroll(PosterFocus(0, 0), PosterFocus(0, 3)))
        assertTrue(isFastPosterScroll(PosterFocus(0, 0), PosterFocus(3, 0)))
        assertFalse(isFastPosterScroll(PosterFocus(0, 0), PosterFocus(0, 2)))
        assertFalse(isFastPosterScroll(PosterFocus(0, 0), PosterFocus(1, 1)))
        assertFalse(isFastPosterScroll(null, PosterFocus(9, 9)))
    }

    @Test
    fun urlsAreRewrittenToCardSizeDedupedAndBlanksDropped() {
        val rows = listOf(
            listOf("https://image.tmdb.org/t/p/original/a.jpg", null, "  ", "https://image.tmdb.org/t/p/w600_and_h900_bestv2/b.jpg"),
            listOf("https://image.tmdb.org/t/p/original/a.jpg", "https://example.org/c.jpg"),
        )
        val got = posterPrefetchUrls(rows, PosterFocus(0, 0), cardWidthDp = 124)
        assertEquals(
            listOf(
                "https://image.tmdb.org/t/p/w342/b.jpg",
                "https://image.tmdb.org/t/p/w342/a.jpg",
                "https://example.org/c.jpg",
            ),
            got,
        )
    }

    @Test
    fun heroSizedRequestsKeepTheirLargeVariant() {
        val rows = listOf(listOf("https://image.tmdb.org/t/p/original/a.jpg", "https://image.tmdb.org/t/p/original/b.jpg"))
        assertEquals(
            listOf("https://image.tmdb.org/t/p/original/b.jpg"),
            posterPrefetchUrls(rows, PosterFocus(0, 0), cardWidthDp = 400),
        )
    }

    @Test
    fun adaptiveColumnCountMatchesTheGridsOwnMaths() {
        // 1920.dp wide, 8.dp start padding, 48.dp TV rail, 128.dp minimum cell, 24.dp gaps.
        assertEquals(12, adaptiveColumnCount(1920f, 128f, 24f, 8f, 48f))
        assertEquals(3, adaptiveColumnCount(600f, 128f, 24f, 8f, 48f))
        assertEquals(1, adaptiveColumnCount(100f, 128f, 24f, 8f, 48f))
    }

    @Test
    fun cellWidthIsWhatTheCardsActuallyDrawAt() {
        val cell = adaptiveCellWidthDp(1920f, 12, 24f, 8f, 48f)
        assertEquals(133.333f, cell.value, 0.01f)
    }

    @Test
    fun flatGridBecomesReadingOrderRows() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")),
            posterRowsFromFlat(listOf("a", "b", "c", "d", "e"), 2),
        )
        assertTrue(posterRowsFromFlat(listOf("a"), 0).isEmpty())
    }

    @Test
    fun prefetcherEnqueuesAtCardSizeAndCapsWhatIsInFlight() {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val prefetcher = PosterPrefetcher(124, 186, maxInFlight = 12, enqueue = PosterEnqueue { url, w, h, _ ->
            calls += Triple(url, w, h)
            val cancel: () -> Unit = {}
            cancel
        })
        prefetcher.prefetch(List(30) { "u$it" })
        assertEquals(12, calls.size)
        assertEquals(12, prefetcher.pendingCount)
        assertEquals(Triple("u0", 124, 186), calls.first())
    }

    @Test
    fun aCompletedRequestFreesItsInFlightSlot() {
        val completes = mutableListOf<() -> Unit>()
        val calls = mutableListOf<String>()
        val prefetcher = PosterPrefetcher(104, 156, maxInFlight = 2, enqueue = PosterEnqueue { url, _, _, onComplete ->
            calls += url
            completes += onComplete
            val cancel: () -> Unit = {}
            cancel
        })
        prefetcher.prefetch(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b"), calls)
        assertEquals(2, prefetcher.pendingCount)
        completes[0]()
        assertEquals(1, prefetcher.pendingCount)
        prefetcher.prefetch(listOf("c"))
        assertEquals(listOf("a", "b", "c"), calls)
        assertEquals(2, prefetcher.pendingCount)
    }

    @Test
    fun prefetcherRequestsEachUrlOncePerSession() {
        val calls = mutableListOf<String>()
        val prefetcher = PosterPrefetcher(104, 156, enqueue = PosterEnqueue { url, _, _, _ ->
            calls += url
            val cancel: () -> Unit = {}
            cancel
        })
        prefetcher.prefetch(listOf("a", "b", "a"))
        prefetcher.prefetch(listOf("b", "c"))
        assertEquals(listOf("a", "b", "c"), calls)
        prefetcher.prefetch(emptyList())
        prefetcher.dispose()
    }

    @Test
    fun fastScrollCancelsEverythingStillInFlightAndLetsThemBeRefetched() {
        val cancelled = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val prefetcher = PosterPrefetcher(104, 156, enqueue = PosterEnqueue { url, _, _, _ ->
            calls += url
            val cancel: () -> Unit = { cancelled += url }
            cancel
        })
        prefetcher.prefetch(listOf("a", "b"))
        prefetcher.cancelPending()
        assertEquals(0, prefetcher.pendingCount)
        assertEquals(listOf("a", "b"), cancelled)
        prefetcher.prefetch(listOf("a"))
        assertEquals(listOf("a", "b", "a"), calls)
    }

    @Test
    fun aUrlAlreadyServedFromCacheIsNotRetried() {
        val calls = mutableListOf<String>()
        val prefetcher = PosterPrefetcher(104, 156, enqueue = PosterEnqueue { url, _, _, _ -> calls += url; null })
        prefetcher.prefetch(listOf("a"))
        prefetcher.prefetch(listOf("a"))
        assertEquals(0, prefetcher.pendingCount)
        assertEquals(listOf("a"), calls)
    }

    @Test
    fun aFailingEnqueueNeverBreaksTheWindow() {
        val calls = mutableListOf<String>()
        val prefetcher = PosterPrefetcher(104, 156, enqueue = PosterEnqueue { url, _, _, _ ->
            if (url == "bad") throw IllegalStateException("loader busy")
            calls += url
            val cancel: () -> Unit = {}
            cancel
        })
        prefetcher.prefetch(listOf("bad", "good"))
        assertEquals(listOf("good"), calls)
    }
}
