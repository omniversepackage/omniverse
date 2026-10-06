package com.yodesla.omniverse.core.data.metadata

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.PosterRow
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import okio.IOException

class TmdbEnricherTest {
    private class FakeHttp(var failNext: Int = 0) : HttpClient {
        var calls = 0
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls++
            if (failNext > 0) { failNext--; throw IOException("network down") }
            val body = if (url.contains("/season/"))
                """{"episodes":[{"episode_number":1,"name":"Pilot","overview":"p","still_path":"/s1.jpg"}]}"""
            else
                """{"id":550,"backdrop_path":"/fallback.jpg","runtime":139,"overview":"A fight-club synopsis.",
                   "images":{"backdrops":[{"iso_639_1":null,"file_path":"/textless.jpg","vote_average":5.0,"aspect_ratio":1.777,"width":1920},
                    {"iso_639_1":"en","file_path":"/english.jpg","vote_average":9.0,"aspect_ratio":1.777,"width":1920}],
                   "logos":[{"iso_639_1":"en","file_path":"/logo.png","vote_average":3.0,"width":600}]},
                   "release_dates":{"results":[{"iso_3166_1":"US","type":3,"certification":"R"}]}}"""
            return object : HttpResponse {
                override val status = 200
                override val headers = emptyMap<String, String>()
                override val body: BufferedSource = Buffer().writeUtf8(body)
                override fun close() = Unit
            }
        }
    }

    private class MutableClock(var now: Long = 1_000_000L) : Clock {
        override fun nowMs(): Long = now
    }

    private fun newDb(): OmniverseDb =
        OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })

    private fun movieRow(
        backdrop: String? = "https://src/b.jpg", poster: String? = "https://src/p.jpg", plot: String? = null,
    ) = PosterRow(
        key = ContentKey(SourceId("s1"), ContentKind.VOD, RemoteId("m1")),
        name = "Fight Club", posterUrl = poster, year = 1999, rating = 8.4f,
        tmdbId = "550", backdropUrl = backdrop, plot = plot,
    )

    @Test fun freshCacheIsNotRefetched() = runBlocking {
        val http = FakeHttp()
        val clock = MutableClock()
        val e = TmdbEnricher(newDb(), clock, Tmdb(http, "k"))
        val first = e.meta(WikidataMetadata.Kind.MOVIE, "550")!!
        assertEquals("/textless.jpg", first.backdropPath)
        // Task 84f: the synopsis comes from the same response — no second request.
        assertEquals("A fight-club synopsis.", first.overview)
        assertEquals(1, http.calls)
        clock.now += TmdbEnricher.FRESH_MS - 1
        val cached = e.meta(WikidataMetadata.Kind.MOVIE, "550")!!
        assertEquals("/textless.jpg", cached.backdropPath)
        assertEquals("A fight-club synopsis.", cached.overview) // overview round-trips through the cache
        assertEquals(1, http.calls) // still fresh: no request
        clock.now += 2
        e.meta(WikidataMetadata.Kind.MOVIE, "550")
        assertEquals(2, http.calls) // 30 days old: refetched
    }

    @Test fun failedLookupBacksOffForOneDay() = runBlocking {
        val http = FakeHttp(failNext = 1)
        val clock = MutableClock()
        val e = TmdbEnricher(newDb(), clock, Tmdb(http, "k"))
        assertNull(e.meta(WikidataMetadata.Kind.MOVIE, "550"))
        assertEquals(1, http.calls)
        assertNull(e.meta(WikidataMetadata.Kind.MOVIE, "550"))
        assertEquals(1, http.calls) // miss row is not retried inside a day
        clock.now += TmdbEnricher.RETRY_MS + 1
        assertEquals("/textless.jpg", e.meta(WikidataMetadata.Kind.MOVIE, "550")?.backdropPath)
        assertEquals(2, http.calls)
    }

    @Test fun offOrKeylessBuildMakesNoRequestAtAll() = runBlocking {
        val http = FakeHttp()
        val off = TmdbEnricher(newDb(), MutableClock(), Tmdb(http, "k"), enabled = { false })
        assertNull(off.meta(WikidataMetadata.Kind.MOVIE, "550"))
        off.seasonStills("550", 1)
        off.prefetch(WikidataMetadata.Kind.MOVIE, listOf("550"))
        assertEquals(0, http.calls)
        val keyless = TmdbEnricher(newDb(), MutableClock(), null)
        assertNull(keyless.meta(WikidataMetadata.Kind.MOVIE, "550"))
        assertEquals(0, http.calls)
    }

    @Test fun seasonStillsAreStoredOnceAndServedFromCache() = runBlocking {
        val http = FakeHttp()
        val e = TmdbEnricher(newDb(), MutableClock(), Tmdb(http, "k"))
        assertEquals(1, e.seasonStills("550", 1).size)
        assertEquals(1, http.calls)
        assertEquals(1, e.seasonStills("550", 1).size)
        assertEquals(1, http.calls) // permanent cache: no second request
    }

    @Test fun emptySeasonBacksOffForOneDay() = runBlocking {
        val http = FakeHttp(failNext = 1)
        val clock = MutableClock()
        val e = TmdbEnricher(newDb(), clock, Tmdb(http, "k"))
        assertEquals(0, e.seasonStills("550", 2).size)
        assertEquals(1, http.calls)
        assertEquals(0, e.seasonStills("550", 2).size)
        assertEquals(1, http.calls) // failed season stays quiet
        clock.now += TmdbEnricher.RETRY_MS + 1
        assertEquals(1, e.seasonStills("550", 2).size)
        assertEquals(2, http.calls)
    }

    @Test fun sourceArtWinsAndTmdbFillsOnlyGaps() {
        val db = newDb()
        db.tmdbQueries.upsertTmdbMeta("550", "MOVIE", "/b.jpg", "/l.png", "/p.jpg", "R", 139L, "Drama", "tag", 1L, "A fight-club synopsis.", "/c.jpg")
        val art = TmdbArt(db)
        val full = art.fill(movieRow())
        assertEquals("https://src/b.jpg", full.backdropUrl) // source backdrop kept
        assertEquals("https://image.tmdb.org/t/p/w500/l.png", full.logoUrl) // logo only TMDB has
        assertEquals("R", full.certification)
        assertEquals(139, full.runtimeMin)
        // Task 84f: the cached synopsis reaches the UI as the plot (Netflix billboard + Detail).
        assertEquals("A fight-club synopsis.", full.plot)
        // Task 87b: card art is the second backdrop, and it is a different picture from the banner.
        assertEquals("https://image.tmdb.org/t/p/w1280/c.jpg", full.cardBackdropUrl)
        val bare = art.fill(movieRow(backdrop = null, poster = null))
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", bare.backdropUrl) // gap filled
        assertEquals("https://image.tmdb.org/t/p/w1280/c.jpg", bare.cardBackdropUrl)
        val batch = TmdbArt(db).fillAll(listOf(movieRow(), movieRow(backdrop = null).copy(key = ContentKey(SourceId("s1"), ContentKind.VOD, RemoteId("m2")))))
        assertEquals("https://src/b.jpg", batch[0].backdropUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", batch[1].backdropUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/c.jpg", batch[1].cardBackdropUrl)
        assertEquals("A fight-club synopsis.", batch[1].plot) // batch path carries the synopsis too
    }

    /** Task 84f: the provider's own plot is never overwritten, not even by a blank of its own. */
    @Test fun providerPlotBeatsTheCachedTmdbSynopsis() {
        val db = newDb()
        db.tmdbQueries.upsertTmdbMeta("550", "MOVIE", "/b.jpg", "/l.png", "/p.jpg", "R", 139L, "Drama", "tag", 1L, "A fight-club synopsis.", null)
        val art = TmdbArt(db)
        assertEquals("Provider plot.", art.fill(movieRow(plot = "Provider plot.")).plot)
        assertEquals("A fight-club synopsis.", art.fill(movieRow(plot = "")).plot)
        assertEquals("A fight-club synopsis.", art.fill(movieRow(plot = "   ")).plot)
        // Nothing cached for the id: the row is returned exactly as it came from the provider.
        val uncached = PosterRow(ContentKey(SourceId("s1"), ContentKind.VOD, RemoteId("z")), "Other", null, null, null, plot = "Provider plot.")
        assertEquals("Provider plot.", TmdbArt(db).fill(uncached).plot)
        assertNull(TmdbArt(db).fill(movieRow().copy(tmdbId = null)).plot)
    }

    // Task 87b: a title with only one backdrop gets no card art — the card then reuses the banner
    // with the title logo overlaid (see netflixCardArt / crunchyrollCardArt).
    @Test fun cardArtIsDroppedWhenItWouldDuplicateTheBanner() {
        val db = newDb()
        db.tmdbQueries.upsertTmdbMeta("550", "MOVIE", "/b.jpg", "/l.png", "/p.jpg", "R", 139L, "Drama", "tag", 1L, null, "/b.jpg")
        val filled = TmdbArt(db).fill(movieRow(backdrop = null))
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", filled.backdropUrl)
        assertNull(filled.cardBackdropUrl)
    }

    @Test fun enricherStoresBannerAndCardArtTogether() = runBlocking {
        val db = newDb()
        val e = TmdbEnricher(db, MutableClock(), Tmdb(FakeHttp(), "k"))
        val found = e.meta(WikidataMetadata.Kind.MOVIE, "550")!!
        assertEquals("/textless.jpg", found.backdropPath)
        assertEquals("/english.jpg", found.cardBackdropPath)
        val stored = db.tmdbQueries.tmdbMeta("550", "MOVIE").executeAsOne()
        assertEquals("/textless.jpg", stored.backdrop_path)
        assertEquals("/english.jpg", stored.card_backdrop_path)
    }

    @Test fun storedArtBumpsTheRefreshSignalOncePerDebounceWindow() = runBlocking {
        val clock = MutableClock()
        val e = TmdbEnricher(newDb(), clock, Tmdb(FakeHttp(), "k"))
        assertEquals(0L, e.artUpdated.value)
        // A whole page prefetched at the same instant: one signal, not one per title.
        e.prefetch(WikidataMetadata.Kind.MOVIE, listOf("1", "2", "3"))
        assertEquals(1L, e.artUpdated.value)
        clock.now += TmdbEnricher.ART_SIGNAL_DEBOUNCE_MS - 1
        e.prefetch(WikidataMetadata.Kind.MOVIE, listOf("4"))
        assertEquals(1L, e.artUpdated.value) // still inside the 1.5 s window
        clock.now += 2
        e.prefetch(WikidataMetadata.Kind.MOVIE, listOf("5"))
        assertEquals(2L, e.artUpdated.value)
    }

    @Test fun failedLookupsEmitNoRefreshSignal() = runBlocking {
        val e = TmdbEnricher(newDb(), MutableClock(), Tmdb(FakeHttp(failNext = 2), "k"))
        assertNull(e.meta(WikidataMetadata.Kind.MOVIE, "550"))
        assertEquals(0L, e.artUpdated.value)
    }

    @Test fun rowsWithoutTmdbIdsAreUntouched() {
        val db = newDb()
        val row = PosterRow(ContentKey(SourceId("s1"), ContentKind.VOD, RemoteId("x")), "No Id", null, null, null)
        assertEquals(row, TmdbArt(db).fill(row))
    }
}
