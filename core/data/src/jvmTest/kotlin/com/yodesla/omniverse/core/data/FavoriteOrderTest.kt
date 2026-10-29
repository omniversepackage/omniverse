package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Live TV hold-OK › Move: dropping a grabbed favourite saves the new channel order in one write,
 * and favourites that were not in the list (My List movies/shows, hidden channels) keep their slots.
 */
class FavoriteOrderTest {
    private val io = UnconfinedTestDispatcher()
    private val clock = Clock { 1_790_424_000_000L }
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private val user = UserDataRepositoryImpl(db, io, clock)

    private fun key(remote: String, kind: ContentKind = ContentKind.LIVE) = ContentKey(SourceId("x"), kind, RemoteId(remote))

    @Test
    fun reorderSavesTheGivenOrderAndIgnoresNonFavourites() = runTest(io) {
        val (a, b, c) = listOf(key("a"), key("b"), key("c"))
        listOf(a, b, c).forEach { user.setFavorite(it, true) }

        user.reorderFavorites(listOf(c, a, key("nope"), b))
        assertEquals(listOf(c, a, b), user.favorites(ContentKind.LIVE).first())

        user.reorderFavorites(listOf(a, b, c))
        assertEquals(listOf(a, b, c), user.favorites(ContentKind.LIVE).first())
    }

    @Test
    fun unlistedFavouritesKeepTheirSlots() = runTest(io) {
        val a = key("a")
        val movie = key("m", ContentKind.VOD)
        val hidden = key("h")
        val b = key("b")
        listOf(a, movie, hidden, b).forEach { user.setFavorite(it, true) }

        user.reorderFavorites(listOf(b, a))
        assertEquals(listOf(b, movie, hidden, a), user.favorites().first(), "the movie and the hidden channel stay put")
    }

    @Test
    fun duplicateSortIndexesAreRenumberedSoTheOrderStillSaves() = runTest(io) {
        val (a, b) = listOf(key("a"), key("b"))
        db.userDataQueries.addFavorite("default", "favorites", "x", "LIVE", "a", 5, 1)
        db.userDataQueries.addFavorite("default", "favorites", "x", "LIVE", "b", 5, 2)

        user.reorderFavorites(listOf(b, a))
        assertEquals(listOf(b, a), user.favorites(ContentKind.LIVE).first())
        user.reorderFavorites(listOf(a, b))
        assertEquals(listOf(a, b), user.favorites(ContentKind.LIVE).first())
    }
}
