package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.data.impl.ProfileRepositoryImpl
import com.yodesla.omniverse.core.data.impl.UserDataRepositoryImpl
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 60: Settings > Backup & restore. Export/import of the viewer's personal setup. */
class BackupRestoreTest {
    private val io = UnconfinedTestDispatcher()
    private var now = 1_790_424_000_000L // 2026-09-26 12:00 UTC
    private val clock = Clock { now }

    private fun newDb() = OmniverseDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OmniverseDb.Schema.create(it) })

    private fun codec(db: OmniverseDb) = BackupCodec(db, io, clock)

    /** One source, a handful of every backed-up table, and four settings rows that must never ship. */
    private fun seed(db: OmniverseDb) {
        db.profilesQueries.insertProfile("default", "Me", 0, 0, 0, now)
        db.catalogQueries.upsertSource("src1", "XTREAM", "Test provider", """{"host":"http://h","password":"hunter2"}""", 0, null, null, null, null, 1)
        db.userDataQueries.addFavorite("default", "favorites", "src1", "VOD", "movie-1", 0, now - 5_000)
        db.userDataQueries.addFavorite("default", "favorites", "src1", "SERIES", "show-1", 1, now - 4_000)
        db.userDataQueries.upsertProgress("default", "src1", "EPISODE", "ep-7", "show-1", 1_200_000, 2_400_000, now - 3_000, 0)
        db.userDataQueries.upsertProgress("default", "src1", "VOD", "movie-1", null, 600_000, 6_000_000, now - 2_000, 0)
        db.userOpsQueries.addHidden("default", "src1", "CATEGORY_VOD", "cat-kids")
        db.userOpsQueries.upsertItemOverride("default", "src1", "VOD", "movie-1", "Movie: Director's Cut", null, "Director's cut", "https://art.invalid/p.jpg?X-Plex-Token=fake-token")
        db.userOpsQueries.upsertTitleOverride("default", "SERIES", "tmdb", "456", "Show (remastered)", null, null)
        db.userOpsQueries.upsertBackupCollection("col-1", "default", "Watch later", "VOD", 1, "manual", null, 0)
        db.userOpsQueries.addCollectionMembership("default", "col-1", "src1", "VOD", "movie-1", 0)
        db.userOpsQueries.addCollectionTitleMembership("default", "col-1", "SERIES", "tmdb", "456", 0)
        db.userOpsQueries.upsertLocalSkipPoint("default", "src1", "VOD", "movie-1", "intro_end", 95_000)
        db.userDataQueries.putSetting("cw_dismissed_default", "src1|VOD|gone-1|${now - 1_000}")
        db.userDataQueries.putSetting("category_order_VOD::default", "src1|cat-action\nsrc1|cat-kids")
        db.userDataQueries.putSetting("experience_mode", "full")
        db.userDataQueries.putSetting("skip_intro_mode", "auto")
        db.userDataQueries.putSetting("parental_pin", "0123:abcd")
        db.userDataQueries.putSetting("plex_account_token_sealed", "${SecretBox.PREFIX}AAAA")
        db.userDataQueries.putSetting("device_token", "deadbeef")
        db.userDataQueries.putSetting("xtream_password_backup", "hunter2")
    }

    private fun key(kind: ContentKind, remote: String, source: String = "src1") = ContentKey(SourceId(source), kind, RemoteId(remote))

    private fun addMatchingSource(db: OmniverseDb, id: String = "src1") {
        db.catalogQueries.upsertSource(id, "XTREAM", "Test provider", "sealed:test", 0, null, null, null, null, 1)
    }

    @Test
    fun exportThenImportIntoAFreshDeviceReproducesTheSetup() = runTest(io) {
        val source = newDb().also(::seed)
        val json = codec(source).export()

        val fresh = newDb()
        addMatchingSource(fresh, "new-src")
        val result = codec(fresh).import(json, replace = false)
        assertTrue(result.ok, result.error ?: "import failed")
        val user = UserDataRepositoryImpl(fresh, io, clock)

        assertEquals(listOf("movie-1", "show-1"), user.favorites().first().map { it.remoteId.value })
        assertEquals(listOf("movie-1", "ep-7"), user.continueWatching(20).first().map { it.key.remoteId.value })
        assertEquals(600_000L, user.progress(key(ContentKind.VOD, "movie-1", "new-src"))?.positionMs)
        assertEquals("full", user.setting("experience_mode").first())
        assertEquals("auto", user.setting("skip_intro_mode").first())
        assertEquals("new-src|cat-action\nnew-src|cat-kids", user.setting("category_order_VOD").first())
        assertEquals("new-src|VOD|gone-1|${now - 1_000}", user.setting("cw_dismissed_default").first())
        assertTrue("VOD|new-src|cat-kids" in user.hiddenCategoryKeys().first())

        val item = user.itemOverrides().first()
        assertEquals("Director's cut", item[key(ContentKind.VOD, "movie-1", "new-src")]?.editionLabel)
        val title = user.titleOverrides().first()
        assertEquals("Show (remastered)", title[TitleIdentity(ContentKind.SERIES, "tmdb", "456")]?.displayTitle)

        val collections = user.collections().first()
        assertEquals(1, collections.size)
        assertEquals("Watch later", collections.first().name)
        assertTrue(collections.first().pinnedHome)
        assertEquals(listOf("movie-1"), user.collectionItems("col-1").map { it.remoteId.value })
        assertEquals(listOf("456"), user.collectionTitles("col-1").map { it.externalId })
        assertEquals(mapOf("intro_end" to 95_000L), user.localSkipPoints(key(ContentKind.VOD, "movie-1", "new-src")).first())

        // Sources are not restored (their configs hold credentials); only their name+kind label travels.
        assertEquals(listOf("new-src"), fresh.catalogQueries.allSources().executeAsList().map { it.id })
        assertEquals(listOf("Test provider"), result.sourceNames)
    }

    @Test
    fun guestProfileFlagRoundTrips() = runTest(io) {
        // Task 84b: the Guest state travels in the backup so a restore reproduces it.
        val source = newDb().also(::seed)
        source.profilesQueries.convertProfileToGuest("Guest", "default")
        val json = codec(source).export()
        val file = BackupCodec.jsonFormat.decodeFromString(BackupFile.serializer(), json)
        assertTrue(file.profiles.single().isGuest, "export must carry the guest flag")

        val fresh = newDb()
        addMatchingSource(fresh)
        val result = codec(fresh).import(json, replace = true)
        assertTrue(result.ok, result.error ?: "import failed")
        val row = assertNotNull(fresh.profilesQueries.profileById("default").executeAsOneOrNull())
        assertEquals(1L, row.is_guest)
        assertEquals("Guest", row.name)
    }

    @Test
    fun secretSettingsAndSourceConfigsNeverLeaveTheDevice() = runTest(io) {
        val db = newDb().also(::seed)
        val json = codec(db).export()

        for (secret in listOf("parental_pin", "plex_account_token_sealed", "device_token", "xtream_password_backup", "hunter2", "fake-token", "config_json", "http://h")) {
            assertFalse(json.contains(secret), "export leaked: $secret")
        }
        assertFalse(json.contains(SecretBox.PREFIX), "export leaked a sealed value")
        // The safe part of the setup is all there.
        for (kept in listOf("experience_mode", "skip_intro_mode", "category_order_VOD", "cw_dismissed_default", "Test provider", "XTREAM")) {
            assertTrue(json.contains(kept), "export is missing: $kept")
        }
        val file = BackupCodec.jsonFormat.decodeFromString(BackupFile.serializer(), json)
        assertEquals(BackupCodec.BACKUP_FORMAT, file.format)
        assertEquals(BackupCodec.BACKUP_VERSION, file.version)
        assertEquals(4, file.settingsSkipped)
        assertEquals(4, file.settings.size)
        assertEquals(listOf("default"), file.profiles.map { it.id })
        assertEquals(1, file.sources.size)
        assertEquals("Test provider", file.sources.single().name)
    }

    @Test
    fun importRefusesForeignOrMalformedFilesAndChangesNothing() = runTest(io) {
        val db = newDb().also(::seed)
        val before = codec(db).export()

        val bad = listOf(
            "not json at all",
            "",
            "{}",
            """{"format":"tivimate-backup","version":1}""",
            """{"format":"omniverse-backup","version":7}""",
            """{"format":"omniverse-backup","version":2,"complete":true,"profiles":[],"favorites":[]}""",
        )
        bad.forEach { text ->
            val result = codec(db).import(text, replace = true)
            assertFalse(result.ok, "accepted: $text")
            assertNotNull(result.error)
            assertEquals(before, codec(db).export(), "a rejected file changed the database: $text")
        }
    }

    @Test
    fun importAlsoRefusesSecretRowsInsideAWellFormedFile() = runTest(io) {
        val db = newDb()
        db.profilesQueries.insertProfile("default", "Me", 0, 0, 0, now)
        val baseFile = BackupCodec.jsonFormat.decodeFromString(BackupFile.serializer(), codec(db).export())
        val hostile = baseFile.copy(settings = listOf(
            BackupSetting("plex_account_token_sealed", "${SecretBox.PREFIX}zzz"),
            BackupSetting("my_password", "p"), BackupSetting("experience_mode", "full"),
        ))
        val result = codec(db).import(BackupCodec.jsonFormat.encodeToString(BackupFile.serializer(), hostile), replace = false)
        assertFalse(result.ok)
        assertNull(db.userDataQueries.getSetting("plex_account_token_sealed").executeAsOneOrNull())
        assertNull(db.userDataQueries.getSetting("my_password").executeAsOneOrNull())
        assertNull(db.userDataQueries.getSetting("experience_mode").executeAsOneOrNull())
    }

    @Test
    fun mergeKeepsTheNewerProgressRowWhicheverSideItIsOn() = runTest(io) {
        val json = codec(newDb().also(::seed)).export() // movie-1 @ now-2000, ep-7 @ now-3000

        val target = newDb()
        addMatchingSource(target)
        // Local watch is newer than the backup: the backup must not roll it back.
        target.userDataQueries.upsertProgress("default", "src1", "VOD", "movie-1", null, 3_000_000, 6_000_000, now + 10_000, 0)
        // Local watch is older than the backup: the backup wins.
        target.userDataQueries.upsertProgress("default", "src1", "EPISODE", "ep-7", "show-1", 100_000, 2_400_000, now - 90_000, 0)

        val result = codec(target).import(json, replace = false)
        assertTrue(result.ok)
        val user = UserDataRepositoryImpl(target, io, clock)

        val movie = user.progress(key(ContentKind.VOD, "movie-1"))
        assertNotNull(movie)
        assertEquals(3_000_000L, movie.positionMs)
        assertEquals(now + 10_000, movie.updatedMs)

        val episode = user.progress(key(ContentKind.EPISODE, "ep-7"))
        assertNotNull(episode)
        assertEquals(1_200_000L, episode.positionMs)
        assertEquals(now - 3_000, episode.updatedMs)
    }

    @Test
    fun mergeUsesTheCompleteFavoriteKey() = runTest(io) {
        val json = codec(newDb().also(::seed)).export()
        val target = newDb()
        addMatchingSource(target)
        target.profilesQueries.insertProfile("default", "Me", 0, 0, 0, now)
        target.userDataQueries.addFavorite("default", "favorites", "src1", "VOD", "local-only", 0, now + 1)

        assertTrue(codec(target).import(json, replace = false).ok)
        assertEquals(setOf("local-only", "movie-1", "show-1"), UserDataRepositoryImpl(target, io, clock).favorites().first().map { it.remoteId.value }.toSet())
    }

    @Test
    fun truncatedOrInvalidReplaceNeverClearsExistingData() = runTest(io) {
        val target = newDb().also(::seed)
        val before = codec(target).export()
        val invalid = listOf(
            """{"format":"omniverse-backup","version":2,"complete":true,"profiles":[{"id":"default","name":"Me","avatar":0,"isKids":false,"sort":0,"createdMs":0}]}""",
            BackupCodec.jsonFormat.encodeToString(
                BackupFile.serializer(),
                BackupCodec.jsonFormat.decodeFromString(BackupFile.serializer(), before).copy(
                    progress = listOf(BackupProgress("default", "src1", "VOD", "bad", null, -1, 100, now)),
                ),
            ),
        )
        invalid.forEach { text ->
            assertFalse(codec(target).import(text, replace = true).ok)
            assertEquals(before, codec(target).export())
        }
    }

    @Test
    fun replaceClearsBackedUpTablesButNeverASecretSetting() = runTest(io) {
        val json = codec(newDb().also(::seed)).export()

        val target = newDb()
        addMatchingSource(target)
        target.profilesQueries.insertProfile("default", "Me", 0, 0, 0, now)
        target.userDataQueries.addFavorite("default", "favorites", "src1", "VOD", "local-only", 0, now)
        target.userDataQueries.putSetting("experience_mode", "simple")
        target.userDataQueries.putSetting("parental_pin", "9999:keep")

        val result = codec(target).import(json, replace = true)
        assertTrue(result.ok)
        val user = UserDataRepositoryImpl(target, io, clock)

        assertEquals(listOf("movie-1", "show-1"), user.favorites().first().map { it.remoteId.value })
        assertEquals("full", user.setting("experience_mode").first())
        assertEquals("9999:keep", target.userDataQueries.getSetting("parental_pin").executeAsOneOrNull())
    }

    // ---- Audit 73 M3: the source pin inside smart_filter_json follows the source remap ----

    private fun seedSmartCollection(db: OmniverseDb, filter: SmartCollectionFilter) {
        db.userOpsQueries.upsertBackupCollection("col-smart", "default", "Smart", "VOD", 0, "smart", filter.encode(), 1)
    }

    private suspend fun restoredSmartFilter(db: OmniverseDb): SmartCollectionFilter? =
        UserDataRepositoryImpl(db, io, clock).collections().first().first { it.id == "col-smart" }.smartFilterJson.let(SmartCollectionFilter::decode)

    @Test
    fun smartCollectionSourcePinTravelsThroughTheSameSourceRemap() = runTest(io) {
        val source = newDb().also { seed(it); seedSmartCollection(it, SmartCollectionFilter(titleContains = "movie", sourceId = "src1")) }
        val json = codec(source).export()

        val fresh = newDb()
        addMatchingSource(fresh, "new-src")
        assertTrue(codec(fresh).import(json, replace = false).ok)

        val filter = restoredSmartFilter(fresh)
        assertNotNull(filter)
        assertEquals("new-src", filter.sourceId, "the pin must follow the source remap, not keep the exporting device's id")
        assertEquals("movie", filter.titleContains, "the rest of the filter is untouched")
    }

    @Test
    fun aSmartCollectionPinToAnUnknownSourceIsDroppedNotLeftStale() = runTest(io) {
        val source = newDb().also { seed(it); seedSmartCollection(it, SmartCollectionFilter(sourceId = "ghost")) }
        val json = codec(source).export()

        val fresh = newDb()
        addMatchingSource(fresh, "new-src")
        assertTrue(codec(fresh).import(json, replace = false).ok)

        val filter = restoredSmartFilter(fresh)
        assertNotNull(filter, "the collection itself still restores")
        assertNull(filter.sourceId, "a pin that maps to nothing on this TV is dropped, not left filtering on a stale id")
    }

    // ---- Audit 73 L5: duplicate same-name sources get rename advice, not missing-source advice ----

    @Test
    fun duplicateSourcesOfTheSameNameGetRenameAdviceNotMissingAdvice() = runTest(io) {
        val json = codec(newDb().also(::seed)).export() // one source: XTREAM "Test provider" (id src1)

        val target = newDb()
        target.catalogQueries.upsertSource("dup-a", "XTREAM", "Test provider", "sealed:a", 0, null, null, null, null, 1)
        target.catalogQueries.upsertSource("dup-b", "XTREAM", "Test provider", "sealed:b", 0, null, null, null, null, 1)
        val before = codec(target).export()

        val result = codec(target).import(json, replace = false)
        assertFalse(result.ok)
        assertNotNull(result.error)
        assertTrue("more than one source named Test provider" in result.error, "ambiguous duplicates must get rename advice, got: ${result.error}")
        assertTrue("Rename" in result.error)
        assertEquals(before, codec(target).export(), "a rejected import changes nothing")
    }

    // ---- Audit 73 L10: a restore never leaves current_profile pointing at a missing profile ----

    @Test
    fun replaceWithNoUsableActiveProfileNeverLeavesTheViewerPointingAtAGhost() = runTest(io) {
        val json = codec(newDb().also(::seed)).export() // seed sets no current_profile: activeProfileId is null

        val target = newDb()
        addMatchingSource(target)
        target.profilesQueries.insertProfile("gone", "Ghost", 0, 0, 0, now)
        target.userDataQueries.putSetting(ProfileRepository.CURRENT_KEY, "gone")

        assertTrue(codec(target).import(json, replace = true).ok)
        assertEquals(
            "default",
            target.userDataQueries.getSetting(ProfileRepository.CURRENT_KEY).executeAsOneOrNull(),
            "Replace cleared the ghost profile; the viewer must land on a profile this restore actually created",
        )
        assertEquals("default", ProfileRepositoryImpl(target, io, clock).current().first().id)
    }

    @Test
    fun anActiveProfileThatIsNotInTheFileAlsoFallsBackToARestoredProfile() = runTest(io) {
        val file = BackupCodec.jsonFormat.decodeFromString(BackupFile.serializer(), codec(newDb().also(::seed)).export())
            .copy(activeProfileId = "ghost")
        val json = BackupCodec.jsonFormat.encodeToString(BackupFile.serializer(), file)

        val target = newDb()
        addMatchingSource(target)
        assertTrue(codec(target).import(json, replace = false).ok)
        assertEquals("default", target.userDataQueries.getSetting(ProfileRepository.CURRENT_KEY).executeAsOneOrNull())
    }

    @Test
    fun aValidActiveProfileIdStillWinsAfterRestore() = runTest(io) {
        val source = newDb().also {
            seed(it)
            it.profilesQueries.insertProfile("kid", "Kid", 0, 1, 0, now)
            it.userDataQueries.putSetting(ProfileRepository.CURRENT_KEY, "kid")
        }
        val json = codec(source).export()

        val fresh = newDb()
        addMatchingSource(fresh)
        assertTrue(codec(fresh).import(json, replace = false).ok)
        assertEquals("kid", fresh.userDataQueries.getSetting(ProfileRepository.CURRENT_KEY).executeAsOneOrNull(), "the fallback must not override a valid recorded viewer")
    }

    @Test
    fun pictureModeAndPlaybackPrefsTravelToTheNewDevice() = runTest(io) {
        val source = newDb().also { db ->
            seed(db)
            db.userDataQueries.putSetting("picture_mode_src1_movie-1", "zoom")
            db.userDataQueries.putSetting("pref_audio_language", "spa")
            db.userDataQueries.putSetting("pref_subtitles", "off")
            db.userDataQueries.putSetting("home_layout::default", "rows:continue|next")
            db.userDataQueries.putSetting("home_density::default", "compact")
        }
        val json = codec(source).export()
        for (kept in listOf("picture_mode_src1_movie-1", "pref_audio_language", "pref_subtitles", "home_layout::default", "home_density::default")) {
            assertTrue(json.contains(kept), "export is missing: $kept")
        }

        val fresh = newDb()
        addMatchingSource(fresh, "new-src")
        assertTrue(codec(fresh).import(json, replace = false).ok)
        val user = UserDataRepositoryImpl(fresh, io, clock)

        // The source id inside a picture-mode key follows the same mapping as every other table.
        assertEquals("zoom", fresh.userDataQueries.getSetting("picture_mode_new-src_movie-1").executeAsOneOrNull())
        assertNull(fresh.userDataQueries.getSetting("picture_mode_src1_movie-1").executeAsOneOrNull())
        assertEquals("spa", user.setting("pref_audio_language").first())
        assertEquals("off", user.setting("pref_subtitles").first())
        assertEquals("rows:continue|next", user.setting("home_layout").first())
        assertEquals("compact", user.setting("home_density").first())
    }

    @Test
    fun aBackupWrittenBeforeHomeLayoutWasPerProfileStillRestores() = runTest(io) {
        val db = newDb()
        db.profilesQueries.insertProfile("default", "Me", 0, 0, 0, now)
        val base = BackupCodec.jsonFormat.decodeFromString(BackupFile.serializer(), codec(db).export())
        val legacy = base.copy(settings = listOf(
            BackupSetting("home_layout", "rows:continue"),
            BackupSetting("home_density", "compact"),
        ))
        val result = codec(db).import(BackupCodec.jsonFormat.encodeToString(BackupFile.serializer(), legacy), replace = false)
        assertTrue(result.ok, result.error ?: "import failed")
        // The bare keys land as global rows; the normal startup migration scopes them to the default profile.
        val user = UserDataRepositoryImpl(db, io, clock)
        user.migrateLegacyProfileSettings()
        assertEquals("rows:continue", user.setting("home_layout").first())
        assertEquals("compact", user.setting("home_density").first())
    }
}
