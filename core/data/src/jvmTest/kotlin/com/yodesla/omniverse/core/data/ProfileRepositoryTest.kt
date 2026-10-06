package com.yodesla.omniverse.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yodesla.omniverse.core.data.impl.ProfileRepositoryImpl
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileRepositoryTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L
    private val clock = Clock { now }
    private val db = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })
    private var nextId = 0
    private val profiles = ProfileRepositoryImpl(db, io, clock, newId = { "p${++nextId}" })
    /** Mirrors AppGraph: UserDataRepositoryImpl reads the active id through a volatile cache. */
    private var currentId = ProfileRepository.DEFAULT_ID
    private val activeIds = MutableStateFlow(currentId)
    private val user = UserDataRepositoryImpl(db, io, clock, profileId = { currentId }, profileIds = activeIds)

    private fun activate(id: String) { currentId = id; activeIds.value = id }

    private val keyA = ContentKey(SourceId("s"), ContentKind.VOD, RemoteId("a"))
    private val keyB = ContentKey(SourceId("s"), ContentKind.VOD, RemoteId("b"))

    @Test
    fun createRenameAndCurrent() = runTest {
        profiles.ensureDefault()
        assertEquals(listOf(ProfileRepository.DEFAULT_ID), profiles.profiles().first().map { it.id })
        assertEquals("Me", profiles.current().first().name)
        assertFalse(profiles.current().first().isKids)

        val kid = profiles.create("Kids TV", 3, true)
        assertEquals(3, kid.avatar)
        assertTrue(kid.isKids)
        profiles.setCurrent(kid.id)
        activate(kid.id)
        assertEquals(kid.id, profiles.current().first().id)

        profiles.rename(kid.id, "  Juniors  ")
        assertEquals("Juniors", profiles.profiles().first().first { it.id == kid.id }.name)
        profiles.setAvatar(kid.id, 99)
        profiles.setKids(kid.id, false)
        val edited = profiles.profiles().first().first { it.id == kid.id }
        assertEquals(ProfileRepository.AVATAR_COUNT - 1, edited.avatar)
        assertFalse(edited.isKids)

        // Unknown ids never become current; current() then keeps the stored-but-unknown id out.
        profiles.setCurrent("nope")
        assertEquals(kid.id, profiles.current().first().id)
        // ensureDefault is idempotent once rows exist.
        profiles.ensureDefault()
        assertEquals(2, profiles.profiles().first().size)
    }

    @Test
    fun deleteLastProfileBecomesGuest() = runTest {
        profiles.ensureDefault()
        user.setFavorite(keyA, true)
        user.putSetting("search_history", "kept")
        // Task 84b: the last profile is not removed - it converts into the Guest in place.
        assertTrue(profiles.delete(ProfileRepository.DEFAULT_ID))
        val rows = profiles.profiles().first()
        assertEquals(1, rows.size)
        val guest = rows.first()
        assertEquals(ProfileRepository.DEFAULT_ID, guest.id, "the Guest keeps the id so all data stays keyed to it")
        assertEquals(ProfileRepository.GUEST_NAME, guest.name)
        assertFalse(guest.isKids)
        assertTrue(guest.isGuest)
        // Everything the profile owned survives untouched.
        assertEquals(listOf(keyA), user.favorites().first())
        assertEquals("kept", user.setting("search_history").first())
        assertEquals(guest.id, profiles.current().first().id)
    }

    @Test
    fun createWhileGuestTakesItOver() = runTest {
        profiles.ensureDefault()
        user.setFavorite(keyA, true)
        assertTrue(profiles.delete(ProfileRepository.DEFAULT_ID))
        val taken = profiles.create("Kory", 12, false)
        assertEquals(ProfileRepository.DEFAULT_ID, taken.id, "the Guest row is converted in place")
        assertFalse(taken.isGuest)
        assertEquals(1, profiles.profiles().first().size)
        assertEquals(listOf(keyA), user.favorites().first())
        // Once the Guest is gone, a second profile is a normal insert with a fresh id.
        val second = profiles.create("Other", 1, false)
        assertFalse(second.id == ProfileRepository.DEFAULT_ID)
        assertEquals(2, profiles.profiles().first().size)
    }

    @Test
    fun deleteRemovesThatProfilesRows() = runTest {
        profiles.ensureDefault()
        val p2 = profiles.create("Other", 1, false)
        activate(p2.id)
        user.setFavorite(keyA, true)
        user.saveProgress(keyB, null, 500, 1_000)
        activate(ProfileRepository.DEFAULT_ID)
        user.setFavorite(keyB, true)
        activate(p2.id)

        assertTrue(profiles.delete(p2.id))
        assertEquals(0, db.userOpsQueries.favoritesByKind(p2.id, null).executeAsList().size)
        assertNull(db.userOpsQueries.progressByKey(p2.id, "s", "VOD", "b").executeAsOneOrNull())
        assertEquals(1, db.userOpsQueries.favoritesByKind(ProfileRepository.DEFAULT_ID, null).executeAsList().size)
        assertEquals(1, profiles.profiles().first().size)
        assertEquals(ProfileRepository.DEFAULT_ID, profiles.current().first().id)
    }

    @Test
    fun twoProfilesIsolateFavoritesAndProgress() = runTest {
        profiles.ensureDefault()
        val p2 = profiles.create("Other", 1, false)

        activate(ProfileRepository.DEFAULT_ID)
        user.setFavorite(keyA, true)
        user.saveProgress(keyA, null, 100, 1_000)
        activate(p2.id)
        user.setFavorite(keyB, true)
        user.saveProgress(keyB, null, 900, 1_000)

        assertEquals(listOf("a"), db.userOpsQueries.favoritesByKind(ProfileRepository.DEFAULT_ID, null).executeAsList().map { it.remote_id })
        assertEquals(listOf("b"), db.userOpsQueries.favoritesByKind(p2.id, null).executeAsList().map { it.remote_id })
        assertEquals(100L, db.userOpsQueries.progressByKey(ProfileRepository.DEFAULT_ID, "s", "VOD", "a").executeAsOne().position_ms)
        assertEquals(900L, db.userOpsQueries.progressByKey(p2.id, "s", "VOD", "b").executeAsOne().position_ms)

        // The repository follows the active profile only.
        assertEquals(listOf(keyB), user.favorites().first())
        assertEquals(listOf(keyB), user.continueWatching(10).first().map { it.key })
        activate(ProfileRepository.DEFAULT_ID)
        assertEquals(listOf(keyA), user.favorites().first())
        assertEquals(listOf(keyA), user.continueWatching(10).first().map { it.key })
    }

    @Test
    fun switchingProfilesRebindsFlowsAndScopesViewerSettings() = runTest {
        profiles.ensureDefault()
        val other = profiles.create("Other", 1, false)
        activate(ProfileRepository.DEFAULT_ID)
        user.setCategoryHidden(SourceId("s"), ContentKind.VOD, "adult", true)
        user.putSetting("search_history", "default search")
        user.putSetting("category_order_VOD", "default order")

        activate(other.id)
        assertTrue(user.hiddenCategoryKeys().first().isEmpty())
        assertNull(user.setting("search_history").first())
        user.putSetting("search_history", "other search")
        user.putSetting("category_order_VOD", "other order")

        activate(ProfileRepository.DEFAULT_ID)
        assertEquals(setOf("VOD|s|adult"), user.hiddenCategoryKeys().first())
        assertEquals("default search", user.setting("search_history").first())
        assertEquals("default order", user.setting("category_order_VOD").first())
    }

    @Test
    fun trackChoicesAreScopedPerProfile() = runTest {
        profiles.ensureDefault()
        val other = profiles.create("Other", 1, false)
        val show = "tracks_plex_show"
        activate(ProfileRepository.DEFAULT_ID)
        user.putSetting(show, "a=eng;s=full:spa")
        activate(other.id)
        assertNull(user.setting(show).first())
        user.putSetting(show, "a=jpn;s=off")
        activate(ProfileRepository.DEFAULT_ID)
        assertEquals("a=eng;s=full:spa", user.setting(show).first())
    }

    @Test
    fun homeLayoutAndDensityAreScopedPerProfile() = runTest {
        profiles.ensureDefault()
        val other = profiles.create("Other", 1, false)
        activate(ProfileRepository.DEFAULT_ID)
        user.putSetting("home_layout", "rows:continue|next")
        user.putSetting("home_density", "compact")
        activate(other.id)
        assertNull(user.setting("home_layout").first())
        assertNull(user.setting("home_density").first())
        user.putSetting("home_density", "comfortable")
        activate(ProfileRepository.DEFAULT_ID)
        assertEquals("rows:continue|next", user.setting("home_layout").first())
        assertEquals("compact", user.setting("home_density").first())
    }

    /** Task 104: "which crash did this profile already hear about" is a viewer setting. */
    @Test
    fun crashNoticeIsScopedPerProfile() = runTest {
        profiles.ensureDefault()
        val other = profiles.create("Other", 1, false)
        activate(ProfileRepository.DEFAULT_ID)
        user.putSetting("crash_notice", "1000")
        activate(other.id)
        assertNull(user.setting("crash_notice").first())
        user.putSetting("crash_notice", "2000")
        activate(ProfileRepository.DEFAULT_ID)
        assertEquals("1000", user.setting("crash_notice").first())
    }

    @Test
    fun guideFilterIsScopedPerProfile() = runTest {
        profiles.ensureDefault()
        val other = profiles.create("Other", 1, false)
        activate(ProfileRepository.DEFAULT_ID)
        user.putSetting("guide_filter", "SPORTS")
        activate(other.id)
        assertNull(user.setting("guide_filter").first())
        user.putSetting("guide_filter", "KIDS")
        activate(ProfileRepository.DEFAULT_ID)
        assertEquals("SPORTS", user.setting("guide_filter").first())
    }

    @Test
    fun spoilerAndBackFromSearchAreScopedPerProfile() = runTest {
        profiles.ensureDefault()
        val other = profiles.create("Other", 1, false)
        activate(ProfileRepository.DEFAULT_ID)
        user.putSetting("spoiler_free", "true")
        user.putSetting("spoiler_free_hide_titles", "true")
        user.putSetting("back_from_search", "guide")
        activate(other.id)
        assertNull(user.setting("spoiler_free").first())
        assertNull(user.setting("spoiler_free_hide_titles").first())
        assertNull(user.setting("back_from_search").first())
        user.putSetting("spoiler_free", "false")
        activate(ProfileRepository.DEFAULT_ID)
        assertEquals("true", user.setting("spoiler_free").first())
        assertEquals("guide", user.setting("back_from_search").first())
    }

    @Test
    fun legacyViewerSettingsMigrateOnlyToDefault() = runTest {
        profiles.ensureDefault()
        db.userDataQueries.putSetting("search_history", "legacy")
        db.userDataQueries.putSetting("category_order_VOD", "old order")
        db.userDataQueries.putSetting("home_layout", "rows:continue")
        db.userDataQueries.putSetting("home_density", "compact")
        db.userDataQueries.putSetting("spoiler_free", "true")
        db.userDataQueries.putSetting("back_from_search", "guide")
        user.migrateLegacyProfileSettings()
        assertNull(db.userDataQueries.getSetting("search_history").executeAsOneOrNull())
        assertEquals("legacy", db.userDataQueries.getSetting("search_history::default").executeAsOneOrNull())
        assertEquals("old order", db.userDataQueries.getSetting("category_order_VOD::default").executeAsOneOrNull())
        assertNull(db.userDataQueries.getSetting("home_layout").executeAsOneOrNull())
        assertEquals("rows:continue", db.userDataQueries.getSetting("home_layout::default").executeAsOneOrNull())
        assertEquals("compact", db.userDataQueries.getSetting("home_density::default").executeAsOneOrNull())
        assertNull(db.userDataQueries.getSetting("spoiler_free").executeAsOneOrNull())
        assertEquals("true", db.userDataQueries.getSetting("spoiler_free::default").executeAsOneOrNull())
        assertEquals("guide", db.userDataQueries.getSetting("back_from_search::default").executeAsOneOrNull())
    }

    @Test
    fun startScreenDecision() {
        // One profile (or none): never ask, whatever the setting says.
        assertFalse(shouldShowProfilePicker(0, true, false))
        assertFalse(shouldShowProfilePicker(1, true, false))
        assertFalse(shouldShowProfilePicker(1, false, false))
        // Setting off: straight into the last profile.
        assertFalse(shouldShowProfilePicker(3, false, false))
        // Kids last used: keep starting in it (PIN is only asked when actively leaving).
        assertFalse(shouldShowProfilePicker(3, true, true))
        assertFalse(shouldShowProfilePicker(2, false, true))
        // 2+ profiles, picker on, adult last used: ask.
        assertTrue(shouldShowProfilePicker(2, true, false))
        assertTrue(shouldShowProfilePicker(5, true, false))
        assertTrue(profileAdminNeedsPin(currentIsKids = true, pinEnabled = true))
        assertFalse(profileAdminNeedsPin(currentIsKids = false, pinEnabled = true))
        assertFalse(profileAdminNeedsPin(currentIsKids = true, pinEnabled = false))
    }
}
