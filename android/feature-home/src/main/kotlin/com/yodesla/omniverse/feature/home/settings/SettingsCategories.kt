package com.yodesla.omniverse.feature.home.settings

import androidx.compose.runtime.Composable

/**
 * Task 84e: the two-pane Settings model. Categories are the left rail; the pure functions here
 * decide which categories and which gated rows appear, so the rules are testable without Compose.
 */
enum class SettingsCategory(val id: String) {
    Sources("sources"),
    Playback("playback"),
    Display("display"),
    ProfilesAndKids("profiles"),
    Metadata("metadata"),
    PhoneRemote("phone-remote"),
    BackupRestore("backup"),
    AboutAndUpdates("about"),
}

/** A per-category content slot supplied by the app module (typed replacement for extraSections). */
typealias SettingsSlot = @Composable () -> Unit

/**
 * Which categories appear, in order. [hasPhoneRemote] / [hasBackup] / [hasDiagnostics] reflect
 * whether the app module provides content for those features (this build ships no phone remote or
 * crash-log UI, so those categories stay hidden). [compact], [hasPlex], [hasPin] and [guestMode]
 * gate ROWS (see [settingsRows]), never whole categories: Plex servers render under each Plex
 * source, and the parental PIN lives in the Profiles & Kids slot.
 */
fun settingsCategories(
    compact: Boolean = false,
    hasPlex: Boolean = false,
    hasPin: Boolean = false,
    guestMode: Boolean = false,
    hasPhoneRemote: Boolean = false,
    hasBackup: Boolean = false,
    hasDiagnostics: Boolean = false,
): List<SettingsCategory> = buildList {
    add(SettingsCategory.Sources)
    add(SettingsCategory.Playback)
    add(SettingsCategory.Display)
    add(SettingsCategory.ProfilesAndKids)
    add(SettingsCategory.Metadata)
    if (hasPhoneRemote) add(SettingsCategory.PhoneRemote)
    if (hasBackup) add(SettingsCategory.BackupRestore)
    if (hasDiagnostics) { /* diagnostics rows live inside About & updates; no extra category */ }
    add(SettingsCategory.AboutAndUpdates)
    // compact/hasPlex/hasPin/guestMode: row-level flags only (documented contract).
}

/** Stable keys for the built-in rows; also the focus-restore keys in [RowFocusRegistry]. */
object SettingsRowKeys {
    const val ADD_SOURCE = "add_source"
    const val CATEGORY_LANG = "category_lang"
    const val CATEGORY_LANG_UNTAGGED = "category_lang_untagged"
    const val CATEGORY_LANG_VOD = "category_lang_vod"
    const val HIDDEN_CATEGORIES = "hidden_categories"
    const val AUDIO = "audio_language"
    const val SUBTITLES = "subtitles"
    const val SUBTITLE_STYLE = "subtitle_style"
    const val LOCAL_SKIP = "local_skip"
    const val COMMUNITY_SKIP = "community_skip"
    const val TVMAZE_CREDIT = "tvmaze_credit"
    const val INTRO = "intro_mode"
    const val CREDITS = "credits_mode"
    const val FRAME_RATE = "match_frame_rate"
    const val PRELOAD_NEXT_CHANNEL = "preload_next_channel"
    const val SPOILER_FREE = "spoiler_free"
    const val SPOILER_FREE_HIDE_TITLES = "spoiler_free_hide_titles"
    // Task 107: Back-from-search destination (Settings › Playback).
    const val BACK_FROM_SEARCH = "back_from_search"
    const val EXPERIENCE = "experience_mode"
    const val CUSTOMIZE_HOME = "customize_home"
    const val HIDE_SPORTS_SCORES = "hide_sports_scores"
    const val SCREENSAVER = "screensaver"
    const val MANAGE = "manage_profiles"
    const val GUEST_NOTE = "guest_note"
    const val CREATE_PROFILE = "create_profile"
    const val PICKER = "picker_at_start"
    const val PARENTAL = "parental"
    const val WIKIDATA = "wikidata"
    const val RT = "rt_scores"
    const val VERSION = "version"
    const val UPDATE_CHECK = "update_check"
    const val DIAGNOSTICS = "diagnostics"
    const val LEGAL = "legal"

    // Task 99: the pane's own LazyColumn item keys (search jumps scroll by index into these).
    const val TITLE = "category-title"
    const val TEXT_SIZE = "text-size"
    const val HIDDEN_CW = "hidden-cw"
    const val SLOT = "slot"
    const val END_SPACER = "pane-end-spacer"
    const val SOURCE_PREFIX = "src-"
}

/**
 * The built-in rows of a category, in order. Slot-provided rows (Customize Home, Check for
 * updates, Parental controls, Backup) are listed too so the contract is testable; the panes
 * render slot content after the built-in rows they own.
 */
fun settingsRows(
    category: SettingsCategory,
    compact: Boolean = false,
    guestMode: Boolean = false,
    hasPin: Boolean = false,
    hasDiagnostics: Boolean = false,
    hasUpdateCheck: Boolean = false,
    hasCustomizeHome: Boolean = false,
): List<String> = when (category) {
    SettingsCategory.Sources -> listOf(
        // Task 84h: the category-language filter lives here, right under the sources it applies to.
        SettingsRowKeys.CATEGORY_LANG,
        SettingsRowKeys.CATEGORY_LANG_UNTAGGED,
        SettingsRowKeys.CATEGORY_LANG_VOD,
        SettingsRowKeys.ADD_SOURCE,
    )
    SettingsCategory.Playback -> buildList {
        add(SettingsRowKeys.AUDIO)
        add(SettingsRowKeys.SUBTITLES)
        add(SettingsRowKeys.SUBTITLE_STYLE)
        add(SettingsRowKeys.LOCAL_SKIP)
        add(SettingsRowKeys.COMMUNITY_SKIP)
        add(SettingsRowKeys.TVMAZE_CREDIT)
        add(SettingsRowKeys.INTRO)
        add(SettingsRowKeys.CREDITS)
        // TV-only: refresh-rate matching means nothing on a phone.
        if (!compact) add(SettingsRowKeys.FRAME_RATE)
        // Task 94: near-instant zapping by pre-buffering the next channel on a hidden engine.
        add(SettingsRowKeys.PRELOAD_NEXT_CHANNEL)
        // Task 95: spoiler protection for unwatched episodes (sub-option renders only while it is on).
        add(SettingsRowKeys.SPOILER_FREE)
        add(SettingsRowKeys.SPOILER_FREE_HIDE_TITLES)
        // Task 107: where Back lands after opening a search result.
        add(SettingsRowKeys.BACK_FROM_SEARCH)
    }
    SettingsCategory.Display -> buildList {
        add(SettingsRowKeys.EXPERIENCE)
        add(SettingsRowKeys.HIDE_SPORTS_SCORES)
        if (hasCustomizeHome) add(SettingsRowKeys.CUSTOMIZE_HOME)
        // TV-only: the phone build has no Android TV screen-saver picker to point at.
        if (!compact) add(SettingsRowKeys.SCREENSAVER)
    }
    SettingsCategory.ProfilesAndKids -> buildList {
        if (guestMode) {
            add(SettingsRowKeys.GUEST_NOTE)
            add(SettingsRowKeys.CREATE_PROFILE)
        } else {
            add(SettingsRowKeys.MANAGE)
            add(SettingsRowKeys.PICKER)
        }
        if (hasPin) add(SettingsRowKeys.PARENTAL)
    }
    SettingsCategory.Metadata -> listOf(SettingsRowKeys.WIKIDATA, SettingsRowKeys.RT)
    SettingsCategory.PhoneRemote -> emptyList()
    SettingsCategory.BackupRestore -> emptyList()
    SettingsCategory.AboutAndUpdates -> buildList {
        add(SettingsRowKeys.VERSION)
        if (hasUpdateCheck) add(SettingsRowKeys.UPDATE_CHECK)
        if (hasDiagnostics) add(SettingsRowKeys.DIAGNOSTICS)
        add(SettingsRowKeys.LEGAL)
    }
}

/**
 * Task 99: the exact LazyColumn item-key order [SettingsCategoryPane] emits for a category, given
 * the same flags the pane uses. Settings search jumps by scrolling to the hit's index in this
 * list, so the order here must mirror the pane's emission order exactly (see SettingsPanes.kt);
 * SettingsSearchTest pins the contract.
 */
fun settingsPaneItemKeys(
    category: SettingsCategory,
    sourceIds: List<String> = emptyList(),
    hasHiddenCategories: Boolean = false,
    spoilerFree: Boolean = false,
    compact: Boolean = false,
    guestMode: Boolean = false,
    hasSlot: Boolean = false,
    hasHiddenCwRow: Boolean = false,
): List<String> = buildList {
    add(SettingsRowKeys.TITLE)
    when (category) {
        SettingsCategory.Sources -> {
            sourceIds.forEach { add(SettingsRowKeys.SOURCE_PREFIX + it) }
            add(SettingsRowKeys.CATEGORY_LANG)
            add(SettingsRowKeys.CATEGORY_LANG_UNTAGGED)
            add(SettingsRowKeys.CATEGORY_LANG_VOD)
            if (hasHiddenCategories) add(SettingsRowKeys.HIDDEN_CATEGORIES)
            add(SettingsRowKeys.ADD_SOURCE)
        }
        SettingsCategory.Playback -> {
            add(SettingsRowKeys.AUDIO)
            add(SettingsRowKeys.SUBTITLES)
            add(SettingsRowKeys.SUBTITLE_STYLE)
            add(SettingsRowKeys.LOCAL_SKIP)
            add(SettingsRowKeys.COMMUNITY_SKIP)
            add(SettingsRowKeys.TVMAZE_CREDIT)
            add(SettingsRowKeys.INTRO)
            add(SettingsRowKeys.CREDITS)
            if (!compact) add(SettingsRowKeys.FRAME_RATE)
            add(SettingsRowKeys.PRELOAD_NEXT_CHANNEL)
            add(SettingsRowKeys.SPOILER_FREE)
            if (spoilerFree) add(SettingsRowKeys.SPOILER_FREE_HIDE_TITLES)
            // Task 107: renders last in playbackRows, so it must be last here too.
            add(SettingsRowKeys.BACK_FROM_SEARCH)
        }
        SettingsCategory.Display -> {
            add(SettingsRowKeys.EXPERIENCE)
            add(SettingsRowKeys.TEXT_SIZE)
            if (!compact) add(SettingsRowKeys.SCREENSAVER)
            if (hasSlot) add(SettingsRowKeys.CUSTOMIZE_HOME)
            if (hasHiddenCwRow) add(SettingsRowKeys.HIDDEN_CW)
        }
        SettingsCategory.ProfilesAndKids -> {
            if (guestMode) {
                add(SettingsRowKeys.GUEST_NOTE)
                add(SettingsRowKeys.CREATE_PROFILE)
            } else {
                add(SettingsRowKeys.MANAGE)
                add(SettingsRowKeys.PICKER)
            }
            if (hasSlot) add(SettingsRowKeys.PARENTAL)
        }
        SettingsCategory.Metadata -> {
            add(SettingsRowKeys.WIKIDATA)
            add(SettingsRowKeys.RT)
        }
        SettingsCategory.PhoneRemote, SettingsCategory.BackupRestore -> add(SettingsRowKeys.SLOT)
        SettingsCategory.AboutAndUpdates -> {
            add(SettingsRowKeys.VERSION)
            add(SettingsRowKeys.LEGAL)
            if (hasSlot) add(SettingsRowKeys.UPDATE_CHECK)
        }
    }
    add(SettingsRowKeys.END_SPACER)
}
