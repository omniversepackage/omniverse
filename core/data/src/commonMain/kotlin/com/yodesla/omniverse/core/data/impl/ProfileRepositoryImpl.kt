package com.yodesla.omniverse.core.data.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.yodesla.omniverse.core.data.BedtimeWindow
import com.yodesla.omniverse.core.data.Clock
import com.yodesla.omniverse.core.data.KidsLimits
import com.yodesla.omniverse.core.data.Profile
import com.yodesla.omniverse.core.data.ProfileRepository
import com.yodesla.omniverse.core.database.OmniverseDb
import com.yodesla.omniverse.core.database.Profile as ProfileRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Profiles over the `profile` table (task 59). The active profile id lives in the GLOBAL
 * setting table under [ProfileRepository.CURRENT_KEY] — settings are not per profile.
 * Deleting a profile removes its rows in every profile-scoped user table in one transaction.
 */
class ProfileRepositoryImpl(
    private val db: OmniverseDb,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val newId: () -> String = { "p-" + Random.nextLong(1_000_000_000_000_000L) },
) : ProfileRepository {

    private val q get() = db.profilesQueries

    private fun ProfileRow.toProfile() = Profile(id, name, avatar.toInt(), is_kids != 0L, sort, created_ms, is_guest != 0L)

    override fun profiles(): Flow<List<Profile>> =
        q.allProfiles().asFlow().mapToList(io).map { rows -> rows.map { it.toProfile() } }

    override fun current(): Flow<Profile> = combine(
        profiles(),
        db.userDataQueries.getSetting(ProfileRepository.CURRENT_KEY).asFlow().mapToOneOrNull(io),
    ) { list, storedId ->
        list.firstOrNull { it.id == storedId } ?: list.firstOrNull() ?: Profile(ProfileRepository.DEFAULT_ID, ProfileRepository.DEFAULT_NAME, 0, false)
    }

    override suspend fun currentProfile(): Profile = withContext(io) { current().first() }

    override suspend fun ensureDefault() = withContext(io) {
        if (q.countProfiles().executeAsOne() == 0L) {
            q.insertProfile(ProfileRepository.DEFAULT_ID, ProfileRepository.DEFAULT_NAME, 0, 0, 0, clock.nowMs())
        }
        Unit
    }

    override suspend fun create(name: String, avatar: Int, isKids: Boolean): Profile = withContext(io) {
        val clean = name.trim().ifEmpty { ProfileRepository.DEFAULT_NAME }.take(40)
        val av = avatar.coerceIn(0, ProfileRepository.AVATAR_COUNT - 1)
        db.transactionWithResult {
            // Task 84b: while the only profile is the Guest, "create" converts that row into the
            // new profile — same id, so everything the Guest carried (progress, lists, settings,
            // hidden libraries) is inherited untouched.
            val rows = q.allProfiles().executeAsList()
            val guest = rows.singleOrNull()?.takeIf { it.is_guest != 0L }
            if (guest != null) {
                q.convertGuestToProfile(clean, av.toLong(), if (isKids) 1L else 0L, guest.id)
                Profile(guest.id, clean, av, isKids, guest.sort, guest.created_ms)
            } else {
                val sort = (q.maxProfileSort().executeAsOneOrNull()?.max ?: -1L) + 1
                val id = newId()
                val created = clock.nowMs()
                q.insertProfile(id, clean, av.toLong(), if (isKids) 1L else 0L, sort, created)
                Profile(id, clean, av, isKids, sort, created)
            }
        }
    }

    override suspend fun rename(id: String, name: String) = withContext(io) {
        q.renameProfile(name.trim().ifEmpty { ProfileRepository.DEFAULT_NAME }.take(40), id)
        Unit
    }

    override suspend fun setAvatar(id: String, avatar: Int) = withContext(io) {
        q.setProfileAvatar(avatar.coerceIn(0, ProfileRepository.AVATAR_COUNT - 1).toLong(), id)
        Unit
    }

    override suspend fun setKids(id: String, isKids: Boolean) = withContext(io) {
        q.setProfileKids(if (isKids) 1L else 0L, id)
        Unit
    }

    /**
     * Task 106: Kids limits are profile-scoped settings, but the profile being edited is not
     * necessarily the active one, so these read and write the scoped keys directly instead of
     * going through UserDataRepository (which always follows the active profile).
     */
    override suspend fun limits(id: String): KidsLimits = withContext(io) {
        val daily = db.userDataQueries.getSetting(kidsKey(KidsLimits.DAILY_KEY, id)).executeAsOneOrNull()
        val bedtime = db.userDataQueries.getSetting(kidsKey(KidsLimits.BEDTIME_KEY, id)).executeAsOneOrNull()
        KidsLimits(daily?.toIntOrNull()?.coerceAtLeast(0) ?: 0, BedtimeWindow.decode(bedtime))
    }

    override suspend fun setLimits(id: String, limits: KidsLimits) = withContext(io) {
        db.transaction {
            putOrClearSetting(kidsKey(KidsLimits.DAILY_KEY, id), limits.dailyLimitMinutes.takeIf { it > 0 }?.toString())
            putOrClearSetting(kidsKey(KidsLimits.BEDTIME_KEY, id), limits.bedtime?.encode())
        }
        Unit
    }

    private fun kidsKey(key: String, profile: String) = "$key::$profile"

    private fun putOrClearSetting(key: String, value: String?) {
        if (value == null) db.userOpsQueries.deleteSettingByKey(key) else db.userDataQueries.putSetting(key, value)
    }

    override suspend fun setCurrent(id: String) = withContext(io) {
        if (q.profileById(id).executeAsOneOrNull() != null) {
            db.userDataQueries.putSetting(ProfileRepository.CURRENT_KEY, id)
        }
        Unit
    }

    override suspend fun delete(id: String): Boolean = withContext(io) {
        // Task 84b: the last profile is not removed — it becomes the Guest in place. Its id is
        // kept, so every profile-scoped row, its settings and hidden libraries stay exactly as
        // they were; only name/kids/guest flags change. Nothing is copied and nothing is lost.
        if (q.countProfiles().executeAsOne() <= 1L) {
            if (q.profileById(id).executeAsOneOrNull() == null) return@withContext false
            db.transaction { q.convertProfileToGuest(ProfileRepository.GUEST_NAME, id) }
            return@withContext true
        }
        db.transaction {
            if (db.userDataQueries.getSetting(ProfileRepository.CURRENT_KEY).executeAsOneOrNull() == id) {
                val fallback = q.allProfiles().executeAsList().first { it.id != id }
                db.userDataQueries.putSetting(ProfileRepository.CURRENT_KEY, fallback.id)
            }
            q.deleteProfileFavorite(id)
            q.deleteProfileHidden(id)
            q.deleteProfileProgress(id)
            q.deleteProfileRecent(id)
            q.deleteProfileSkipPoint(id)
            q.deleteProfileItemOverride(id)
            q.deleteProfileTitleOverride(id)
            q.deleteProfileCollection(id)
            q.deleteProfileMembership(id)
            q.deleteProfileTitleMembership(id)
            q.deleteProfileDismissals("cw_dismissed_$id")
            q.deleteProfileSettings(id)
            q.deleteProfile(id)
        }
        true
    }
}
