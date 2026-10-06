package com.yodesla.omniverse.core.data

import kotlinx.coroutines.flow.Flow

/**
 * A local viewer profile (task 59). All user data (favorites, progress, overrides, collections)
 * is keyed by [id]; "default" owns everything created before profiles existed. [avatar] is an
 * index into the built-in drawn avatars (gradients first, then the pixel-art set — see
 * [ProfileRepository.AVATAR_COUNT]); [isKids] applies the parental locks as a hard filter
 * (no PIN unlock offered) and requires the PIN to leave.
 * [isGuest] (task 84b) marks the "no profile" state: deleting the last profile converts its row
 * into the Guest in place, so all data keyed by [id] survives untouched. The Guest only ever
 * exists as the single remaining profile.
 */
data class Profile(
    val id: String,
    val name: String,
    val avatar: Int,
    val isKids: Boolean,
    val sort: Long = 0,
    val createdMs: Long = 0,
    val isGuest: Boolean = false,
)

interface ProfileRepository {
    /** All profiles in display order. */
    fun profiles(): Flow<List<Profile>>

    /** The active profile; falls back to the first profile when the stored id is unknown. */
    fun current(): Flow<Profile>

    /** One-shot form of [current] for startup wiring (before the UI loads). */
    suspend fun currentProfile(): Profile

    /** Insert the "default" profile when the table is empty (fresh installs; idempotent). */
    suspend fun ensureDefault()

    suspend fun create(name: String, avatar: Int, isKids: Boolean): Profile
    suspend fun rename(id: String, name: String)
    suspend fun setAvatar(id: String, avatar: Int)
    suspend fun setKids(id: String, isKids: Boolean)

    /**
     * The Kids daily limit + bedtime saved for [id] (task 106). Everything off when unset. These
     * are read for the profile being edited, which is not necessarily the active one.
     */
    suspend fun limits(id: String): KidsLimits = KidsLimits()

    /** Save [limits] for [id] (task 106). Off values clear the rows instead of storing them. */
    suspend fun setLimits(id: String, limits: KidsLimits) {}

    /** Make [id] active (stored in the global setting table). Unknown ids are ignored. */
    suspend fun setCurrent(id: String)

    /**
     * Delete a profile and every row it owns in the user-data tables.
     * Deleting the last remaining profile (task 84b) instead converts its row into the Guest
     * in place — same id, name "Guest", not kids, [Profile.isGuest] = true — and keeps every
     * profile-scoped row, setting and hidden library it owns. Always returns true for a known id.
     */
    suspend fun delete(id: String): Boolean

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "Me"
        /** Name of the last-profile-left Guest state (task 84b). */
        const val GUEST_NAME = "Guest"
        /** Global setting key holding the active profile id (NOT profile-scoped). */
        const val CURRENT_KEY = "current_profile"
        /** Global setting key: ask "Who's watching?" at startup (absent = on). */
        const val PICKER_KEY = "profile_picker_at_start"
        /**
         * Built-in drawn avatars: 8 gradient+initial discs (indexes 0..7, meaning unchanged)
         * followed by the pixel-art set (8..23) defined in feature-home ProfileAvatars.kt.
         * Keep this equal to 8 + the number of pixel grids there (ProfileAvatarsTest asserts it).
         */
        const val AVATAR_COUNT = 24
    }
}

/**
 * The start-screen decision (task 59 + reviewer request): show the profile picker at startup
 * only when there is more than one profile, the viewer left "Ask who is watching at startup"
 * on, and the last used profile is not a Kids profile — a Kids profile is simply kept (it
 * starts straight into it; the parental PIN is only asked when actively leaving it).
 */
fun shouldShowProfilePicker(profileCount: Int, pickerAtStart: Boolean, currentIsKids: Boolean): Boolean =
    profileCount >= 2 && pickerAtStart && !currentIsKids

/** Every route into profile administration is PIN-gated while the active viewer is a kid. */
fun profileAdminNeedsPin(currentIsKids: Boolean, pinEnabled: Boolean): Boolean = currentIsKids && pinEnabled
