package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 89: Continue Watching is deduped in the data layer to ONE row per exact title. Copies of the
 * same title (Plex + IPTV, two IPTV categories sharing a stream, a Plex onDeck import plus a local
 * watch) are separate progress rows; the deduped source collapses each title to its newest visible
 * copy and that copy's progress. Grouping is by non-empty exact TMDB id only — never by title/year —
 * so a copy with no TMDB id stays its own card, and a copy in a switched-off category is never the
 * representative.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingDedupTest {
    private val now = 1_790_424_000_000L
    private val clock = Clock { now }

    private fun lit(v: String?): String = if (v == null) "NULL" else "'" + v.replace("'", "''") + "'"
    private fun num(v: Any?): String = v?.toString() ?: "NULL"

    private fun newDriver(): JdbcSqliteDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) }

    private fun sources(): JdbcSqliteDriver = newDriver().apply {
        execute(null, "INSERT INTO source(id,kind,name,config_json,sort_index) VALUES " +
            "('iptvA','XTREAM','IPTV A','{}',0),('iptvB','XTREAM','IPTV B','{}',1),('plex','PLEX','Plex','{}',2)", 0)
    }

    private fun JdbcSqliteDriver.vod(
        source: String, remote: String, name: String, category: String, tmdb: String?,
    ) {
        execute(null, "INSERT INTO vod(source_id,remote_id,name,poster_url,primary_category_id,year,added_ms,tmdb_id,sort_index,sync_gen) VALUES " +
            "('$source','$remote',${lit(name)},NULL,'$category',2021,${num(now)},${lit(tmdb)},0,1)", 0)
    }

    private fun JdbcSqliteDriver.series(
        source: String, remote: String, name: String, category: String, tmdb: String?,
    ) {
        execute(null, "INSERT INTO series(source_id,remote_id,name,poster_url,primary_category_id,year,last_modified_ms,tmdb_id,sort_index,sync_gen) VALUES " +
            "('$source','$remote',${lit(name)},NULL,'$category',2021,${num(now)},${lit(tmdb)},0,1)", 0)
    }

    private fun key(source: String, kind: ContentKind, remote: String) =
        ContentKey(SourceId(source), kind, RemoteId(remote))

    private fun repo(driver: JdbcSqliteDriver, io: kotlinx.coroutines.CoroutineDispatcher) =
        UserDataRepositoryImpl(OmniverseDb(driver), io, clock)

    private suspend fun cw(user: UserDataRepositoryImpl, excluded: List<String> = emptyList()) =
        user.continueWatchingDeduped(20, excluded).first()

    @Test
    fun twoCopiesSameTmdbCollapseToTheNewestProgress() = runTest {
        val driver = sources()
        driver.vod("plex", "p42", "Armageddon", "movies", "42")
        driver.vod("iptvA", "a42", "Armageddon", "movies", "42")
        val user = repo(driver, UnconfinedTestDispatcher(testScheduler))
        user.importRemoteProgress(key("plex", ContentKind.VOD, "p42"), null, 30_000, 100_000, atMs = 1_000L)
        user.importRemoteProgress(key("iptvA", ContentKind.VOD, "a42"), null, 70_000, 100_000, atMs = 2_000L)

        val rows = cw(user)
        assertEquals(1, rows.size, "the same TMDB id watched on two copies is one Continue Watching card")
        assertEquals("a42", rows.single().key.remoteId.value, "the newest copy represents the title")
        assertEquals(70_000L, rows.single().positionMs, "the representative carries its own progress")
        assertEquals(2_000L, rows.single().updatedMs)
    }

    @Test
    fun plexOnDeckImportPlusLocalProgressSameTmdbIsOneRow() = runTest {
        val driver = sources()
        driver.vod("plex", "p42", "Armageddon", "movies", "42")
        driver.vod("iptvA", "a42", "Armageddon", "movies", "42")
        val user = repo(driver, UnconfinedTestDispatcher(testScheduler))
        user.saveProgress(key("iptvA", ContentKind.VOD, "a42"), null, 40_000, 100_000) // local watch, newest
        user.importRemoteProgress(key("plex", ContentKind.VOD, "p42"), null, 20_000, 100_000, atMs = now - 1_000L)

        val rows = cw(user)
        assertEquals(1, rows.size)
        assertEquals("a42", rows.single().key.remoteId.value, "the newer local watch wins over the older onDeck import")
    }

    @Test
    fun differentTmdbIdsWithTheSameTitleStaySeparate() = runTest {
        val driver = sources()
        driver.vod("iptvA", "x1", "Armageddon", "movies", "111")
        driver.vod("iptvB", "x2", "Armageddon", "movies", "222")
        val user = repo(driver, UnconfinedTestDispatcher(testScheduler))
        user.saveProgress(key("iptvA", ContentKind.VOD, "x1"), null, 10_000, 100_000)
        user.saveProgress(key("iptvB", ContentKind.VOD, "x2"), null, 20_000, 100_000)

        assertEquals(2, cw(user).size, "different exact ids are never merged, even with an identical title")
    }

    @Test
    fun copiesWithoutTmdbIdAreNotMerged() = runTest {
        val driver = sources()
        driver.vod("iptvA", "x3", "Armageddon", "movies", null)
        driver.vod("iptvB", "x4", "Armageddon", "movies", "")
        driver.vod("plex", "p5", "Armageddon", "movies", "555")
        val user = repo(driver, UnconfinedTestDispatcher(testScheduler))
        user.saveProgress(key("iptvA", ContentKind.VOD, "x3"), null, 10_000, 100_000)
        user.saveProgress(key("iptvB", ContentKind.VOD, "x4"), null, 20_000, 100_000)
        user.saveProgress(key("plex", ContentKind.VOD, "p5"), null, 30_000, 100_000)

        val rows = cw(user)
        assertEquals(3, rows.size, "a copy with no TMDB id is its own card; title/year is never trusted to merge")
        assertEquals(setOf("x3", "x4", "p5"), rows.map { it.key.remoteId.value }.toSet())
    }

    @Test
    fun hiddenCopyIsNeverChosenAsTheRepresentative() = runTest {
        val driver = sources()
        driver.vod("plex", "p42", "Armageddon", "plexmovies", "42")
        driver.vod("iptvA", "a42", "Armageddon", "movies", "42")
        val user = repo(driver, UnconfinedTestDispatcher(testScheduler))
        user.importRemoteProgress(key("plex", ContentKind.VOD, "p42"), null, 30_000, 100_000, atMs = 2_000L) // newest, but switchable-off
        user.importRemoteProgress(key("iptvA", ContentKind.VOD, "a42"), null, 10_000, 100_000, atMs = 1_000L) // older, visible

        assertEquals("p42", cw(user).single().key.remoteId.value, "with nothing switched off the newest copy represents")
        assertEquals(
            "a42",
            cw(user, listOf("VOD|plex|plexmovies")).single().key.remoteId.value,
            "a switched-off newest copy cannot hide a visible older copy",
        )
        assertEquals(0, cw(user, listOf("VOD|plex|plexmovies", "VOD|iptvA|movies")).size, "all copies off -> no card")
    }

    @Test
    fun episodesCollapseToOneRowPerSeries() = runTest {
        val driver = sources()
        driver.series("plex", "s42", "Attack on Titan", "shows", "42")
        driver.series("iptvA", "ss42", "Attack on Titan", "shows", "42")
        driver.series("iptvB", "sb99", "Attack on Titan", "shows", "99")
        val user = repo(driver, UnconfinedTestDispatcher(testScheduler))
        user.importRemoteProgress(key("plex", ContentKind.EPISODE, "e1"), RemoteId("s42"), 30_000, 60_000, atMs = 1_000L)
        user.importRemoteProgress(key("iptvA", ContentKind.EPISODE, "e2"), RemoteId("ss42"), 40_000, 60_000, atMs = 2_000L)
        user.importRemoteProgress(key("iptvB", ContentKind.EPISODE, "e3"), RemoteId("sb99"), 50_000, 60_000, atMs = 3_000L)

        val rows = cw(user)
        assertEquals(2, rows.size, "episodes of the same series (same TMDB id) are one card; a different series stays separate")
        assertEquals(setOf("e2", "e3"), rows.map { it.key.remoteId.value }.toSet())
    }
}
