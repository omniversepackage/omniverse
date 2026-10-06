package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.CatalogRepositoryImpl
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCustomizationTest {
    @Test
    fun smartFilterRoundTripsWithoutNetworkOrProviderMutation() {
        val filter = SmartCollectionFilter(titleContains = "star", yearFrom = 2000, ratingAtLeast = 7.5f, sourceId = "plex")
        assertEquals(filter, SmartCollectionFilter.decode(filter.encode()))
        assertNull(SmartCollectionFilter.decode("not-json"))
        assertEquals(true, SmartCollectionFilter.decode(SmartCollectionFilter(hasEditionLabel = true).encode())?.hasEditionLabel)
        assertEquals(false, SmartCollectionFilter.decode(SmartCollectionFilter(hasEditionLabel = false).encode())?.hasEditionLabel)
        // Old JSON without the field decodes to "ignore" and keeps its other fields.
        val old = SmartCollectionFilter.decode("""{"titleContains":"star","started":true}""")
        assertEquals("star", old?.titleContains)
        assertEquals(true, old?.started)
        assertNull(old?.hasEditionLabel)
    }

    @Test
    fun editionLabelFilterIsProfileScoped() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        driver.execute(null, "INSERT INTO vod(source_id,remote_id,name,primary_category_id,sort_index,sync_gen) VALUES " +
            "('iptv','m1','Film A','movies',0,1),('iptv','m2','Film B','movies',1,1)", 0)
        driver.execute(null, "INSERT INTO item_override(profile_id,source_id,kind,remote_id,edition_label) VALUES " +
            "('one','iptv','VOD','m1','Director''s Cut'),('two','iptv','VOD','m2','Remastered')", 0)
        var profile = "one"
        val catalog = CatalogRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler)) { profile }
        val labeled = SmartCollectionFilter(hasEditionLabel = true)
        val unlabeled = SmartCollectionFilter(hasEditionLabel = false)
        assertEquals(listOf("m1"), catalog.smartCollectionItems(ContentKind.VOD, labeled).map { it.key.remoteId.value })
        assertEquals(listOf("m2"), catalog.smartCollectionItems(ContentKind.VOD, unlabeled).map { it.key.remoteId.value })
        profile = "two"
        assertEquals(listOf("m2"), catalog.smartCollectionItems(ContentKind.VOD, labeled).map { it.key.remoteId.value })
        assertEquals(listOf("m1"), catalog.smartCollectionItems(ContentKind.VOD, unlabeled).map { it.key.remoteId.value })
        profile = "three"
        assertEquals(emptyList(), catalog.smartCollectionItems(ContentKind.VOD, labeled))
        assertEquals(listOf("m1", "m2"), catalog.smartCollectionItems(ContentKind.VOD, unlabeled).map { it.key.remoteId.value })
        driver.close()
    }
    @Test
    fun overridesAndCollectionsStayLocalAndProfileScoped() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        var profile = "one"
        val user = UserDataRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler), Clock { 1L }) { profile }
        val movie = ContentKey(SourceId("plex"), ContentKind.VOD, RemoteId("77"))
        val override = ItemOverride(sortTitle = "Batman 2", editionLabel = "Director's Cut")
        user.setItemOverride(movie, override)
        assertEquals(override, user.itemOverride(movie))
        assertEquals(mapOf(movie to override), user.itemOverrides().first())
        user.setLocalSkipPoint(movie, "intro_end", 45_000)
        assertEquals(mapOf("intro_end" to 45_000L), user.localSkipPoints(movie).first())
        val show = ContentKey(SourceId("iptv"), ContentKind.SERIES, RemoteId("show"))
        user.setLocalSkipPoint(show, "intro_end", 60_000)
        user.setLocalSkipPoint(show, "credits_remaining", 90_000)
        assertEquals(mapOf("intro_end" to 60_000L, "credits_remaining" to 90_000L), user.localSkipPoints(show).first())

        val collection = LibraryCollection("c1", "Batman", ContentKind.VOD, pinnedHome = true)
        user.saveCollection(collection)
        user.addToCollection("c1", movie)
        assertEquals(listOf(collection), user.collections().first())
        assertEquals(listOf(movie), user.collectionItems("c1"))

        profile = "two"
        assertNull(user.itemOverride(movie))
        assertEquals(emptyMap(), user.itemOverrides().first())
        assertEquals(emptyMap(), user.localSkipPoints(movie).first())
        assertEquals(emptyMap(), user.localSkipPoints(show).first())
        assertEquals(emptyList(), user.collections().first())
        profile = "one"
        user.deleteCollection("c1")
        assertEquals(emptyList(), user.collectionItems("c1"))
        user.setItemOverride(movie, ItemOverride())
        assertNull(user.itemOverride(movie))
        user.setLocalSkipPoint(movie, "intro_end", null)
        assertEquals(emptyMap(), user.localSkipPoints(movie).first())
        driver.close()
    }

    @Test
    fun titleMembershipAddListRemoveWithStableOrderAndProfileIsolation() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        var profile = "one"
        val user = UserDataRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler), Clock { 1L }) { profile }
        user.saveCollection(LibraryCollection("shows", "Shows", ContentKind.SERIES))
        val batman = TitleIdentity(ContentKind.SERIES, "tmdb", "1399")
        val breaking = TitleIdentity(ContentKind.SERIES, "tmdb", "1396")
        val movie = TitleIdentity(ContentKind.VOD, "tmdb", "27205")

        // Content-kind mismatch: a VOD title cannot join a SERIES collection.
        assertFailsWith<IllegalArgumentException> { user.addTitleToCollection("shows", movie) }
        // Invalid identity: non VOD/SERIES kind.
        assertFailsWith<IllegalArgumentException> {
            user.addTitleToCollection("shows", TitleIdentity(ContentKind.LIVE, "tmdb", "1"))
        }
        // Invalid identity: blank namespace / externalId.
        assertFailsWith<IllegalArgumentException> {
            user.addTitleToCollection("shows", TitleIdentity(ContentKind.SERIES, "  ", "1399"))
        }
        assertFailsWith<IllegalArgumentException> {
            user.addTitleToCollection("shows", TitleIdentity(ContentKind.SERIES, "tmdb", ""))
        }
        // A non-existent collection is a safe no-op (profile-scoped lookup finds nothing).
        user.addTitleToCollection("missing", batman)
        assertEquals(emptyList(), user.collectionTitles("missing"))

        user.addTitleToCollection("shows", batman)
        user.addTitleToCollection("shows", breaking)
        assertEquals(listOf(batman, breaking), user.collectionTitles("shows"))

        // Duplicate add is idempotent and must not reorder.
        user.addTitleToCollection("shows", batman)
        user.addTitleToCollection("shows", batman)
        assertEquals(listOf(batman, breaking), user.collectionTitles("shows"))

        // Concrete-item membership coexists and is untouched.
        val showKey = ContentKey(SourceId("iptv"), ContentKind.SERIES, RemoteId("batman"))
        user.addToCollection("shows", showKey)
        assertEquals(listOf(showKey), user.collectionItems("shows"))
        assertEquals(listOf(batman, breaking), user.collectionTitles("shows"))

        // Profile isolation: a second profile sees none of the first's titles.
        profile = "two"
        assertEquals(emptyList(), user.collectionTitles("shows"))
        // Removing under the second profile must not touch the first's rows.
        user.removeTitleFromCollection("shows", batman)
        profile = "one"
        assertEquals(listOf(batman, breaking), user.collectionTitles("shows"))

        // Remove one; the other keeps its position.
        user.removeTitleFromCollection("shows", batman)
        assertEquals(listOf(breaking), user.collectionTitles("shows"))
        // Removing an absent title is a safe no-op.
        user.removeTitleFromCollection("shows", batman)
        assertEquals(listOf(breaking), user.collectionTitles("shows"))
        driver.close()
    }

    @Test
    fun deletingCollectionRemovesOnlyItsTitleMemberships() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OmniverseDb.Schema.create(driver)
        var profile = "one"
        val user = UserDataRepositoryImpl(OmniverseDb(driver), UnconfinedTestDispatcher(testScheduler), Clock { 1L }) { profile }
        val shows = LibraryCollection("shows", "Shows", ContentKind.SERIES)
        val movies = LibraryCollection("movies", "Movies", ContentKind.VOD)
        user.saveCollection(shows)
        user.saveCollection(movies)
        val batman = TitleIdentity(ContentKind.SERIES, "tmdb", "1399")
        val film = TitleIdentity(ContentKind.VOD, "tmdb", "27205")
        user.addTitleToCollection("shows", batman)
        user.addTitleToCollection("movies", film)
        user.addToCollection("shows", ContentKey(SourceId("iptv"), ContentKind.SERIES, RemoteId("batman")))

        // Delete under a different profile: nothing of profile one's data is touched.
        profile = "two"
        user.deleteCollection("shows")
        profile = "one"
        assertEquals(listOf(batman), user.collectionTitles("shows"))
        assertEquals(listOf(film), user.collectionTitles("movies"))

        // Delete under the owning profile: its title rows go, other collections survive.
        user.deleteCollection("shows")
        assertEquals(emptyList(), user.collectionTitles("shows"))
        assertEquals(emptyList(), user.collectionItems("shows"))
        assertEquals(listOf(film), user.collectionTitles("movies"))
        assertEquals(1, user.collections().first().count { it.id == "movies" })
        driver.close()
    }
}
