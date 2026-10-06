package com.yodesla.omniverse.core.data

import androidx.paging.PagingSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.CatalogRepositoryImpl
import com.yodesla.omniverse.core.data.impl.SearchRepositoryImpl
import com.yodesla.omniverse.core.data.impl.SourceRepositoryImpl
import com.yodesla.omniverse.core.data.impl.SyncEngineImpl
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.AccountInfo
import com.yodesla.omniverse.core.model.AccountStatus
import com.yodesla.omniverse.core.model.Capability
import com.yodesla.omniverse.core.model.Category
import com.yodesla.omniverse.core.model.ChannelRecord
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.PlaybackRequest
import com.yodesla.omniverse.core.model.PlaybackSpec
import com.yodesla.omniverse.core.model.ProgrammeRecord
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SeriesDetail
import com.yodesla.omniverse.core.model.SeriesRecord
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.model.SourceKind
import com.yodesla.omniverse.core.model.TimeWindow
import com.yodesla.omniverse.core.model.VodDetail
import com.yodesla.omniverse.core.model.VodRecord
import com.yodesla.omniverse.core.source.ContentSource
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceFactory
import com.yodesla.omniverse.core.source.SyncDiagnostics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRegressionTest {
    private val now = 1_790_424_000_000L
    private val clock = Clock { now }

    private fun lit(v: String?): String = if (v == null) "NULL" else "'" + v.replace("'", "''") + "'"
    private fun num(v: Any?): String = v?.toString() ?: "NULL"

    private fun newDriver(): JdbcSqliteDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }

    private fun threeSources(): JdbcSqliteDriver = newDriver().apply {
        execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('iptvA','XTREAM','IPTV A','{}',0),('iptvB','XTREAM','IPTV B','{}',1),('plex','PLEX','Plex','{}',2)", 0)
    }

    private fun JdbcSqliteDriver.vod(
        source: String, remote: String, name: String, poster: String?, category: String,
        tmdb: String?, year: Int?, addedMs: Long?, sort: Int,
    ) {
        execute(null, "INSERT INTO vod(source_id,remote_id,name,poster_url,primary_category_id,year,added_ms,tmdb_id,sort_index,sync_gen) VALUES " +
            "('$source','$remote',${lit(name)},${lit(poster)},'$category',${num(year)},${num(addedMs)},${lit(tmdb)},$sort,1)", 0)
    }

    private fun JdbcSqliteDriver.series(
        source: String, remote: String, name: String, poster: String?, category: String,
        tmdb: String?, year: Int?, modifiedMs: Long?, sort: Int,
    ) {
        execute(null, "INSERT INTO series(source_id,remote_id,name,poster_url,primary_category_id,year,last_modified_ms,tmdb_id,sort_index,sync_gen) VALUES " +
            "('$source','$remote',${lit(name)},${lit(poster)},'$category',${num(year)},${num(modifiedMs)},${lit(tmdb)},$sort,1)", 0)
    }

    private fun vodKey(source: String, remote: String) = ContentKey(SourceId(source), ContentKind.VOD, RemoteId(remote))
    private fun PosterRow.slot() = "${key.sourceId.value}/${key.remoteId.value}"
    private fun PosterRow.isExcludedBy(excluded: Set<String>) =
        categoryId != null && "${key.kind.name}|${key.sourceId.value}|${categoryId.value}" in excluded

    private fun List<PosterRow>.groupedById(): List<PosterRow> =
        distinctBy { it.tmdbId?.takeIf(String::isNotBlank) ?: it.slot() }

    private suspend fun CatalogRepository.allCards(excluded: Set<String>, kind: ContentKind): List<PosterRow> {
        val paging = if (kind == ContentKind.VOD) vodAll(excluded) else seriesAll(excluded)
        return (paging.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data
    }

    @Test
    fun threeCopiesOfOneTmdbIdProduceExactlyOneCardInEveryBrowseSurface() = runTest {
        val driver = threeSources()
        driver.vod("plex", "p42", "Dune", "https://plex/dune.jpg", "movies", "42", 2021, now - 10, 0)
        driver.vod("iptvA", "a42", "Dune", "https://iptva/dune.jpg", "movies", "42", 2021, now - 20, 0)
        driver.vod("iptvA", "a42b", "Dune", "https://iptva/dune-alt.jpg", "movies", "42", 2021, now - 21, 1)
        driver.vod("iptvB", "b42", "Dune", "https://iptvb/dune.jpg", "movies", "42", 2021, now - 30, 0)
        driver.vod("iptvA", "a99", "Dune Part Two", "https://iptva/dune2.jpg", "movies", "99", 2024, now - 40, 2)
        driver.series("plex", "s42", "Dune Tales", "https://plex/tales.jpg", "shows", "42", 2021, now - 10, 0)
        driver.series("iptvA", "ss42", "Dune Tales", "https://iptva/tales.jpg", "shows", "42", 2021, now - 20, 0)
        driver.series("iptvA", "ss42b", "Dune Tales", "https://iptva/tales-alt.jpg", "shows", "42", 2021, now - 21, 1)
        driver.series("iptvB", "sb42", "Dune Tales", "https://iptvb/tales.jpg", "shows", "42", 2021, now - 30, 0)
        val db = OmniverseDb(driver)
        val io = UnconfinedTestDispatcher(testScheduler)
        val catalog = CatalogRepositoryImpl(db, io)

        assertEquals(2L, db.readQueries.countVodAll(emptyList()).executeAsOne())
        assertEquals(setOf("p42", "a99"), db.readQueries.vodPageAll(emptyList(), 20, 0).executeAsList().map { it.remote_id }.toSet())
        assertEquals(1L, db.readQueries.countSeriesAll(emptyList()).executeAsOne())
        assertEquals(setOf("s42"), db.readQueries.seriesPageAll(emptyList(), 20, 0).executeAsList().map { it.remote_id }.toSet())
        assertEquals(setOf("p42", "a99"), catalog.allCards(emptySet(), ContentKind.VOD).map { it.key.remoteId.value }.toSet())
        assertEquals(setOf("s42"), catalog.allCards(emptySet(), ContentKind.SERIES).map { it.key.remoteId.value }.toSet())

        val search = SearchRepositoryImpl(db, io)
        for (s in listOf("plex", "iptvA", "iptvB")) db.storeQueries.rebuildSearchVod(s)
        val hits = search.search("Dune").values.flatten().filter { it.title == "Dune" }
        assertEquals(4, hits.size, "search returns every copy of the title")
        val groupedHits = hits.mapNotNull { catalog.poster(it.key) }.groupedById()
        assertEquals(1, groupedHits.size, "the caller groups the hits into one card")
        assertEquals("42", groupedHits.single().tmdbId)

        val shelf = catalog.recentlyAdded(ContentKind.VOD, 30).first()
        assertEquals(2, shelf.size, "the shelf groups every copy into one card per title")
        assertEquals(setOf("p42", "a99"), shelf.map { it.key.remoteId.value }.toSet())

        assertEquals(2L, db.readQueries.countVod("iptvA", "movies").executeAsOne())
        assertEquals(listOf("a42", "a99"), db.readQueries.vodPage("iptvA", "movies", 20, 0).executeAsList().map { it.remote_id })
        assertEquals(1L, db.readQueries.countSeries("iptvA", "shows").executeAsOne())
        assertEquals(listOf("ss42"), db.readQueries.seriesPage("iptvA", "shows", 20, 0).executeAsList().map { it.remote_id })

        assertEquals(listOf("iptvA/a42", "iptvA/a42b", "iptvB/b42"), catalog.exactMovieMatches(vodKey("plex", "p42")).map { it.slot() })
        assertEquals(listOf("plex/p42", "iptvA/a42", "iptvA/a42b", "iptvB/b42"),
            catalog.titleCandidates(TitleIdentity(ContentKind.VOD, "tmdb", "42")).map { it.slot() })
        assertEquals(listOf("plex/s42", "iptvA/ss42", "iptvA/ss42b", "iptvB/sb42"),
            catalog.titleCandidates(TitleIdentity(ContentKind.SERIES, "tmdb", "42")).map { it.slot() })
        driver.close()
    }

    @Test
    fun sameTitleAndYearNeverMergeWithoutTheSameExactId() = runTest {
        val driver = threeSources()
        driver.vod("iptvA", "x1", "Star Voyage", null, "movies", "111", 2020, now, 0)
        driver.vod("iptvA", "x5", "Star Voyage", null, "movies", "111", 2020, now, 1)
        driver.vod("iptvB", "x2", "Star Voyage", null, "movies", "222", 2020, now, 0)
        driver.vod("plex", "x3", "Star Voyage", null, "movies", null, 2020, now, 0)
        driver.vod("iptvB", "x4", "Star Voyage", null, "movies", "", 2020, now, 1)
        val db = OmniverseDb(driver)
        val io = UnconfinedTestDispatcher(testScheduler)
        val catalog = CatalogRepositoryImpl(db, io)

        assertEquals(4L, db.readQueries.countVodAll(emptyList()).executeAsOne())
        assertEquals(setOf("x1", "x2", "x3", "x4"), catalog.allCards(emptySet(), ContentKind.VOD).map { it.key.remoteId.value }.toSet())
        assertEquals(listOf("iptvA/x5"), catalog.exactMovieMatches(vodKey("iptvA", "x1")).map { it.slot() })
        assertEquals(emptyList(), catalog.titleCandidates(TitleIdentity(ContentKind.VOD, "tmdb", "")))
        assertEquals(emptyList(), catalog.titleCandidates(TitleIdentity(ContentKind.VOD, "tmdb", "   ")))
        driver.close()
    }

    @Test
    fun oneMergedCardStillOffersEveryEditionVariantWithItsOwnLabel() = runTest {
        val driver = threeSources()
        driver.vod("plex", "p42", "Dune", "https://plex/dune.jpg", "movies", "42", 2021, now - 10, 0)
        driver.vod("iptvA", "a42", "Dune", "https://iptva/dune.jpg", "movies", "42", 2021, now - 20, 0)
        driver.vod("iptvB", "b42", "Dune", "https://iptvb/dune.jpg", "movies", "42", 2021, now - 30, 0)
        val db = OmniverseDb(driver)
        val io = UnconfinedTestDispatcher(testScheduler)
        val catalog = CatalogRepositoryImpl(db, io)
        val user = UserDataRepositoryImpl(db, io, clock)
        user.setItemOverride(vodKey("iptvA", "a42"), ItemOverride(editionLabel = "Director's Cut"))
        user.setItemOverride(vodKey("iptvB", "b42"), ItemOverride(editionLabel = "Extended Cut"))
        val identity = TitleIdentity(ContentKind.VOD, "tmdb", "42")

        assertEquals(listOf("p42"), catalog.allCards(emptySet(), ContentKind.VOD).map { it.key.remoteId.value })
        assertEquals(
            mapOf("plex/p42" to null, "iptvA/a42" to "Director's Cut", "iptvB/b42" to "Extended Cut"),
            catalog.titleCandidates(identity).associate { it.slot() to user.itemOverride(it.key)?.editionLabel },
        )
        assertEquals(
            listOf("iptvA/a42" to "Director's Cut", "iptvB/b42" to "Extended Cut"),
            catalog.exactMovieMatches(vodKey("plex", "p42")).map { it.slot() to user.itemOverride(it.key)?.editionLabel },
        )
        driver.close()
    }

    @Test
    fun switchingOffOrRemovingASourceTakesItsVariantArtworkAndChoiceWithIt() = runTest {
        val driver = threeSources()
        driver.vod("plex", "p42", "Dune", "https://plex/dune.jpg", "plexmovies", "42", 2021, now - 10, 0)
        driver.vod("iptvA", "a42", "Dune", "https://iptva/dune.jpg", "movies", "42", 2021, now - 20, 0)
        driver.vod("iptvB", "b42", "Dune", "https://iptvb/dune.jpg", "movies", "42", 2021, now - 30, 0)
        driver.vod("iptvB", "b77", "Reykjavik", "https://iptvb/reykjavik.jpg", "movies", "77", 2019, now - 40, 1)
        val db = OmniverseDb(driver)
        val io = UnconfinedTestDispatcher(testScheduler)
        val catalog = CatalogRepositoryImpl(db, io)
        val user = UserDataRepositoryImpl(db, io, clock)
        val sources = SourceRepositoryImpl(db, emptyList(), io, newId = { "unused" })
        val search = SearchRepositoryImpl(db, io)
        for (s in listOf("plex", "iptvA", "iptvB")) db.storeQueries.rebuildSearchVod(s)
        val identity = TitleIdentity(ContentKind.VOD, "tmdb", "42")
        suspend fun hidden() = user.hiddenCategoryKeys().first()
        suspend fun cards() = catalog.allCards(hidden(), ContentKind.VOD).map { it.key.remoteId.value }.toSet()
        suspend fun duneArtwork() = catalog.allCards(hidden(), ContentKind.VOD).first { it.tmdbId == "42" }.posterUrl
        suspend fun choices() = catalog.titleCandidates(identity).filterNot { it.isExcludedBy(hidden()) }.map { it.slot() }
        suspend fun reykjavikHits() = search.search("Reykjavik").values.flatten().filter { it.key.sourceId.value == "iptvB" }

        assertEquals(setOf("p42", "b77"), cards())
        assertEquals("https://plex/dune.jpg", duneArtwork())
        assertEquals(listOf("plex/p42", "iptvA/a42", "iptvB/b42"), choices())
        assertEquals(1L, catalog.counts(SourceId("plex")).first()[ContentKind.VOD])

        user.setCategoryHidden(SourceId("plex"), ContentKind.VOD, "plexmovies", hidden = true)
        assertEquals(setOf("a42", "b77"), cards())
        assertEquals("https://iptva/dune.jpg", duneArtwork())
        assertEquals(listOf("iptvA/a42", "iptvB/b42"), choices())
        assertEquals(1L, catalog.counts(SourceId("plex")).first()[ContentKind.VOD], "inventory is not a visibility rule")

        user.setCategoryHidden(SourceId("iptvA"), ContentKind.VOD, "movies", hidden = true)
        assertEquals(setOf("b42", "b77"), cards())
        assertEquals("https://iptvb/dune.jpg", duneArtwork())
        assertEquals(listOf("iptvB/b42"), choices())

        assertTrue(reykjavikHits().isNotEmpty())
        sources.remove(SourceId("iptvB"))
        assertEquals(emptyList(), reykjavikHits())
        assertNull(catalog.poster(vodKey("iptvB", "b42")))
        assertEquals(0L, catalog.counts(SourceId("iptvB")).first()[ContentKind.VOD])
        assertEquals(emptySet(), cards())
        assertEquals(emptyList(), choices())

        user.setCategoryHidden(SourceId("plex"), ContentKind.VOD, "plexmovies", hidden = false)
        assertEquals(setOf("p42"), cards())
        assertEquals("https://plex/dune.jpg", duneArtwork())
        driver.close()
    }

    @Test
    fun aLockedTitleNeverAppearsOnTheKidsProfileFromAnySourceVariant() = runTest {
        val driver = threeSources()
        driver.vod("iptvA", "a42", "Dune", "https://iptva/dune.jpg", "adult", "42", 2021, now - 10, 0)
        driver.vod("iptvB", "b42", "Dune", "https://iptvb/dune.jpg", "adult", "42", 2021, now - 10, 0)
        driver.vod("plex", "p42", "Dune", "https://plex/dune.jpg", "adult", "42", 2021, now - 10, 0)
        driver.vod("iptvA", "a77", "Reykjavik", "https://iptva/reykjavik.jpg", "adult", "77", 2019, now - 20, 1)
        driver.vod("plex", "p77", "Reykjavik", "https://plex/reykjavik.jpg", "kids", "77", 2019, now - 20, 0)
        val db = OmniverseDb(driver)
        val io = UnconfinedTestDispatcher(testScheduler)
        var profile = "kids"
        val user = UserDataRepositoryImpl(db, io, clock) { profile }
        val catalog = CatalogRepositoryImpl(db, io) { profile }
        val identity = TitleIdentity(ContentKind.VOD, "tmdb", "42")
        suspend fun hidden() = user.hiddenCategoryKeys().first()
        suspend fun cards() = catalog.allCards(hidden(), ContentKind.VOD).map { it.key.remoteId.value }
        suspend fun shelf() = catalog.recentlyAdded(ContentKind.VOD, 10, hidden()).first().filterNot { it.isExcludedBy(hidden()) }

        for (s in listOf("iptvA", "iptvB", "plex")) user.setCategoryHidden(SourceId(s), ContentKind.VOD, "adult", hidden = true)
        val locked = hidden()
        assertEquals(setOf("VOD|iptvA|adult", "VOD|iptvB|adult", "VOD|plex|adult"), locked)
        assertEquals(listOf("p77"), cards())
        assertEquals(listOf("Reykjavik"), shelf().map { it.name })
        assertEquals(emptyList(), catalog.titleCandidates(identity).filterNot { it.isExcludedBy(locked) })
        assertEquals(2, catalog.recentlyAdded(ContentKind.VOD, 10).first().size, "the shelf groups every variant into one card per title")

        profile = "default"
        assertEquals(emptySet(), hidden(), "the lock belongs to the kids profile")
        assertEquals(setOf("p42", "p77"), cards().toSet())
        assertEquals(setOf("Dune", "Reykjavik"), shelf().map { it.name }.toSet())
        assertEquals(listOf("plex/p42", "iptvA/a42", "iptvB/b42"), catalog.titleCandidates(identity).map { it.slot() })
        driver.close()
    }

    @Test
    fun progressOnOneVariantResumesTheMergedCardAndSurvivesThatSourceBeingResynced() = runTest {
        val driver = newDriver()
        driver.execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('iptvB','XTREAM','IPTV B','{}',1),('plex','PLEX','Plex','{}',2)", 0)
        driver.vod("iptvB", "b42", "Dune", "https://iptvb/dune.jpg", "movies", "42", 2021, now - 30, 0)
        driver.vod("plex", "p42", "Dune", "https://plex/dune.jpg", "movies", "42", 2021, now - 10, 0)
        val db = OmniverseDb(driver)
        val io = UnconfinedTestDispatcher(testScheduler)
        val fake = ScriptedSource(SourceId("iptvA"))
        val sources = SourceRepositoryImpl(db, listOf(SourceFactory { fake }), io, newId = { "iptvA" })
        val sync = SyncEngineImpl(db, sources, clock, SyncPolicy(batchSize = 1_000, stageRetryDelayMs = 0), io)
        val catalog = CatalogRepositoryImpl(db, io)
        val user = UserDataRepositoryImpl(db, io, clock)
        val search = SearchRepositoryImpl(db, io)
        val resynced = sources.add(SourceConfig.Xtream(SourceId("iptvA"), "IPTV A", "http://host", "u", "p"))
        driver.vod("iptvA", "a42", "Dune", "https://iptva/dune.jpg", "movies", "42", 2021, now - 20, 0)
        db.storeQueries.rebuildSearchVod("iptvA")
        val variantA = vodKey("iptvA", "a42")

        user.saveProgress(variantA, null, 1_200_000L, 3_000_000L)
        assertEquals(listOf(variantA), user.continueWatching().first().map { it.key })
        assertEquals(listOf("p42"), catalog.allCards(emptySet(), ContentKind.VOD).map { it.key.remoteId.value })
        assertTrue(catalog.titleCandidates(TitleIdentity(ContentKind.VOD, "tmdb", "42")).any { it.key == variantA },
            "the merged card must offer the variant holding the resume point")

        fake.vodCatalog = listOf(Category(resynced, ContentKind.VOD, RemoteId("movies"), "Movies", null, 0))
        fake.vodItems = listOf(
            VodRecord(
                sourceId = resynced, remoteId = RemoteId("a42"), name = "Dune", posterUrl = "https://iptva/dune.jpg",
                categoryIds = listOf(RemoteId("movies")), rating = 8f, year = 2021, addedAtMs = now - 20,
                containerExt = "mp4", tmdbId = "42", sortIndex = 0,
            ),
        )
        val events = sync.sync(resynced, SyncScope.VOD_AND_SERIES, force = true).toList()
        assertTrue(events.last().finished)
        assertTrue(events.none { it.error != null }, "errors: ${events.mapNotNull { it.error }}")

        assertEquals(1_200_000L, user.progress(variantA)?.positionMs)
        assertEquals(listOf(variantA), user.continueWatching().first().map { it.key })
        assertEquals(listOf("p42"), catalog.allCards(emptySet(), ContentKind.VOD).map { it.key.remoteId.value })
        assertEquals(1L, catalog.counts(resynced).first()[ContentKind.VOD])
        assertTrue(search.search("Dune").values.flatten().any { it.key == variantA }, "re-sync must keep the variant searchable")
        driver.close()
    }

    @Test
    fun pagingMergedCardsNeverRepeatOrSkipATitle() = runTest {
        val driver = threeSources()
        repeat(8) { i ->
            val id = "${i + 1}"
            driver.vod("plex", "p$id", "Title $id", null, "movies", id, 2020, now - i, i)
            driver.vod("iptvA", "a$id", "Title $id", null, "movies", id, 2020, now - i, i)
            driver.vod("iptvB", "b$id", "Title $id", null, "movies", id, 2020, now - i, i)
        }
        repeat(6) { i ->
            val id = "${i + 9}"
            driver.vod("iptvB", "b$id", "Title $id", null, "movies", id, 2020, now - i, i)
        }
        repeat(3) { i -> driver.vod("iptvA", "noid$i", "No Id $i", null, "movies", null, 2020, now - i, 10 + i) }
        driver.vod("iptvA", "y1", "Zebra", null, "movies", "900", 2001, now - 100, 20)
        driver.vod("plex", "y2", "Zebra", null, "movies", "901", 2005, now - 101, 21)
        val db = OmniverseDb(driver)
        val io = UnconfinedTestDispatcher(testScheduler)
        val paging = CatalogRepositoryImpl(db, io).vodAll(emptyList())
        val total = db.readQueries.countVodAll(emptyList()).executeAsOne()
        assertEquals(19L, total)

        val pages = ArrayList<List<PosterRow>>()
        var next: Int? = null
        var refresh = true
        var prevOfLast: Int? = null
        while (true) {
            val params: PagingSource.LoadParams<Int> =
                if (refresh) PagingSource.LoadParams.Refresh(null, 5, false) else PagingSource.LoadParams.Append(next!!, 5, false)
            val page = paging.load(params) as PagingSource.LoadResult.Page
            pages += page.data
            refresh = false
            if (page.nextKey == null) { prevOfLast = page.prevKey; break }
            next = page.nextKey
        }
        val walked = pages.flatten()
        assertEquals(total.toInt(), walked.size, "paging must neither skip nor repeat a title")
        assertEquals(walked.size, walked.map { it.key }.toSet().size, "no card may appear twice across pages")
        val ids = walked.mapNotNull { it.tmdbId }
        assertEquals(ids.size, ids.toSet().size, "one card per exact id, even across pages")
        val single = (paging.load(PagingSource.LoadParams.Refresh(0, 100, false)) as PagingSource.LoadResult.Page).data
        assertEquals(single, walked, "page order must match the unpaged order")
        val back = (paging.load(PagingSource.LoadParams.Prepend(prevOfLast!!, 5, false)) as PagingSource.LoadResult.Page).data
        assertEquals(pages[pages.lastIndex - 1], back, "walking back must return the page we came from")

        val alpha = CatalogRepositoryImpl(db, io).vodAllAlphabetic(emptyList())
        val alphaRows = ArrayList<PosterRow>()
        var aNext: Int? = null
        var aRefresh = true
        while (true) {
            val params: PagingSource.LoadParams<Int> =
                if (aRefresh) PagingSource.LoadParams.Refresh(null, 5, false) else PagingSource.LoadParams.Append(aNext!!, 5, false)
            val page = alpha.load(params) as PagingSource.LoadResult.Page
            alphaRows += page.data
            aRefresh = false
            if (page.nextKey == null) break
            aNext = page.nextKey
        }
        val names = alphaRows.map { it.name }
        assertEquals(total.toInt(), names.size, "title sort must neither skip nor repeat a title")
        assertEquals(names.size, alphaRows.map { it.key }.toSet().size, "no card repeats in title order")
        val alphaSingle = (alpha.load(PagingSource.LoadParams.Refresh(0, 100, false)) as PagingSource.LoadResult.Page).data
        assertEquals(alphaSingle, alphaRows, "title-sorted pages concatenate to the same order")
        assertEquals(listOf("No Id 0", "No Id 1", "No Id 2"), names.take(3))
        assertEquals(listOf(2001, 2005), alphaSingle.filter { it.name == "Zebra" }.map { it.year }, "year breaks a title tie")
        val alphaIds = alphaSingle.mapNotNull { it.tmdbId }
        assertEquals(alphaIds.size, alphaIds.toSet().size, "one card per exact id in title order too")
        driver.close()
    }

    @Test
    fun recentlyAddedGroupsToVisibleTitlesBeforeTheLimitSoLockedCopiesNeverRepresent() = runTest {
        val driver = threeSources()
        driver.vod("plex", "p42", "Dune", "https://plex/dune.jpg", "plexmovies", "42", 2021, now - 10, 0)
        driver.vod("iptvA", "a42", "Dune", "https://iptva/dune.jpg", "movies", "42", 2021, now - 20, 0)
        driver.vod("iptvB", "b42", "Dune", "https://iptvb/dune.jpg", "movies", "42", 2021, now - 30, 0)
        driver.vod("iptvB", "b77", "Reykjavik", "https://iptvb/reykjavik.jpg", "movies", "77", 2019, now - 40, 1)
        val db = OmniverseDb(driver)
        val io = UnconfinedTestDispatcher(testScheduler)
        val catalog = CatalogRepositoryImpl(db, io)
        val user = UserDataRepositoryImpl(db, io, clock)
        suspend fun hidden() = user.hiddenCategoryKeys().first()
        suspend fun shelf(excluded: Set<String>, limit: Int) = catalog.recentlyAdded(ContentKind.VOD, limit, excluded).first().map { it.key.remoteId.value }

        assertEquals(listOf("p42", "b77"), shelf(emptySet(), 30), "one card per title, newest visible copy first")

        user.setCategoryHidden(SourceId("plex"), ContentKind.VOD, "plexmovies", hidden = true)
        assertEquals(listOf("a42", "b77"), shelf(hidden(), 30), "a locked copy is never the representative")

        assertEquals(listOf("p42", "b77"), shelf(emptySet(), 2), "limit N returns N distinct visible titles")
        assertEquals(listOf("p42"), shelf(emptySet(), 1), "the limit is never eaten by hidden copies")
        driver.close()
    }

    private inner class ScriptedSource(override val id: SourceId) : ContentSource {
        override val kind = SourceKind.XTREAM
        override val capabilities = setOf(Capability.VOD, Capability.SERIES)
        var vodCatalog: List<Category> = emptyList()
        var vodItems: List<VodRecord> = emptyList()

        override suspend fun accountInfo() = AccountInfo(AccountStatus.ACTIVE, null, 2, 0, false, listOf("ts"), "UTC", now)
        override fun liveCategories() = emptyFlow<Category>()
        override fun liveChannels(diagnostics: SyncDiagnostics) = emptyFlow<ChannelRecord>()
        override fun vodCategories() = flow { vodCatalog.forEach { emit(it) } }
        override fun vodItems(diagnostics: SyncDiagnostics) = flow { vodItems.forEach { emit(it) } }
        override fun seriesCategories() = emptyFlow<Category>()
        override fun series(diagnostics: SyncDiagnostics) = emptyFlow<SeriesRecord>()
        override fun epg(window: TimeWindow, channelKeys: Set<String>?, diagnostics: SyncDiagnostics) = emptyFlow<ProgrammeRecord>()
        override suspend fun vodDetail(id: RemoteId): VodDetail = error("unused")
        override suspend fun seriesDetail(id: RemoteId): SeriesDetail = error("unused")
        override suspend fun shortEpg(channelId: RemoteId, limit: Int) = emptyList<ProgrammeRecord>()
        override suspend fun playback(request: PlaybackRequest): PlaybackSpec = error("unused")
    }
}
