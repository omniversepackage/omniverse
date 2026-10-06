package com.yodesla.omniverse.feature.home.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test
    fun blankQueryHasNoResults() {
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings(""))
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings("   "))
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings("\t\n"))
    }

    @Test
    fun unknownWordHasNoResults() {
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings("zzzzqqq"))
    }

    @Test
    fun subtitlesLandsOnTheSubtitlesRowFirst() {
        val hits = searchSettings("subtitles")
        assertEquals(SettingsRowKeys.SUBTITLES, hits.first().rowKey)
        assertEquals(SettingsCategory.Playback, hits.first().category)
        // The style row mentions subtitles only in its keywords, so it ranks below the row itself.
        assertTrue(SettingsRowKeys.SUBTITLE_STYLE in hits.map { it.rowKey })
        assertTrue(hits.map { it.rowKey }.indexOf(SettingsRowKeys.SUBTITLE_STYLE) > 0)
    }

    @Test
    fun pinLandsOnParentalControls() {
        val hits = searchSettings("pin")
        assertEquals(listOf(SettingsRowKeys.PARENTAL), hits.map { it.rowKey })
        assertEquals(SettingsCategory.ProfilesAndKids, hits.first().category)
    }

    @Test
    fun zoomLandsOnTextSize() {
        val hits = searchSettings("zoom")
        assertEquals(listOf(SettingsRowKeys.TEXT_SIZE), hits.map { it.rowKey })
        assertEquals(SettingsCategory.Display, hits.first().category)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(searchSettings("subtitles"), searchSettings("SUBTITLES"))
        assertEquals(searchSettings("Pin"), searchSettings("pin"))
    }

    @Test
    fun everyTokenMustMatchSomewhere() {
        // "subtitles captions": one token hits the label, the other a keyword.
        val rows = searchSettings("subtitles captions").map { it.rowKey }
        assertTrue(SettingsRowKeys.SUBTITLES in rows)
        // A token that matches nothing excludes the entry even if the other token matches.
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings("subtitle zzzz"))
        // The style row still wins its own phrase on a label match.
        assertEquals(SettingsRowKeys.SUBTITLE_STYLE, searchSettings("subtitle style").first().rowKey)
    }

    @Test
    fun keywordMatchingIsPrefixBasedNotSubstring() {
        // "subtit" (partial word) still finds the row.
        assertTrue(SettingsRowKeys.SUBTITLES in searchSettings("subtit").map { it.rowKey })
        // "pin" must not leak into "zapping" - prefix matching, not substring.
        assertTrue(SettingsRowKeys.PRELOAD_NEXT_CHANNEL !in searchSettings("pin").map { it.rowKey })
    }

    @Test
    fun kidsMatchesBothProfileRows() {
        val rows = searchSettings("kids").map { it.rowKey }
        assertTrue(SettingsRowKeys.MANAGE in rows)
        assertTrue(SettingsRowKeys.PARENTAL in rows)
    }

    @Test
    fun backFromSearchIsFindable() {
        // Task 107: the new Playback row is reachable by phrase and by keyword.
        assertEquals(listOf(SettingsRowKeys.BACK_FROM_SEARCH), searchSettings("back from").map { it.rowKey })
        assertTrue(SettingsRowKeys.BACK_FROM_SEARCH in searchSettings("search result").map { it.rowKey })
    }

    @Test
    fun indexRowKeysExistInThePaneKeyOrder() {
        // Every indexed setting must be reachable by scrolling the pane that shows it.
        SettingsCategory.entries.forEach { category ->
            val normal = settingsPaneItemKeys(
                category, sourceIds = listOf("s"), hasHiddenCategories = true, spoilerFree = true,
                compact = false, guestMode = false, hasSlot = true, hasHiddenCwRow = true,
            )
            val guest = settingsPaneItemKeys(
                category, sourceIds = listOf("s"), hasHiddenCategories = true, spoilerFree = true,
                compact = false, guestMode = true, hasSlot = true, hasHiddenCwRow = true,
            )
            settingsSearchIndex.filter { it.category == category }.forEach { entry ->
                assertTrue(
                    "${entry.rowKey} missing from the ${category.name} pane",
                    entry.rowKey in normal || entry.rowKey in guest,
                )
            }
        }
    }

    @Test
    fun paneKeyOrderMirrorsThePane() {
        assertEquals(
            listOf(
                SettingsRowKeys.TITLE, SettingsRowKeys.AUDIO, SettingsRowKeys.SUBTITLES,
                SettingsRowKeys.SUBTITLE_STYLE, SettingsRowKeys.LOCAL_SKIP, SettingsRowKeys.COMMUNITY_SKIP,
                SettingsRowKeys.TVMAZE_CREDIT, SettingsRowKeys.INTRO, SettingsRowKeys.CREDITS,
                SettingsRowKeys.FRAME_RATE, SettingsRowKeys.PRELOAD_NEXT_CHANNEL, SettingsRowKeys.SPOILER_FREE,
                SettingsRowKeys.BACK_FROM_SEARCH, SettingsRowKeys.END_SPACER,
            ),
            settingsPaneItemKeys(SettingsCategory.Playback),
        )
        // TV-only row drops on phone; the spoiler sub-option only exists while the switch is on.
        assertTrue(SettingsRowKeys.FRAME_RATE in settingsPaneItemKeys(SettingsCategory.Playback))
        assertTrue(SettingsRowKeys.FRAME_RATE !in settingsPaneItemKeys(SettingsCategory.Playback, compact = true))
        assertTrue(SettingsRowKeys.SPOILER_FREE_HIDE_TITLES !in settingsPaneItemKeys(SettingsCategory.Playback))
        assertTrue(SettingsRowKeys.SPOILER_FREE_HIDE_TITLES in settingsPaneItemKeys(SettingsCategory.Playback, spoilerFree = true))
        assertEquals(
            listOf(
                SettingsRowKeys.TITLE, "src-a", "src-b", SettingsRowKeys.CATEGORY_LANG,
                SettingsRowKeys.CATEGORY_LANG_UNTAGGED, SettingsRowKeys.CATEGORY_LANG_VOD,
                SettingsRowKeys.HIDDEN_CATEGORIES, SettingsRowKeys.ADD_SOURCE, SettingsRowKeys.END_SPACER,
            ),
            settingsPaneItemKeys(SettingsCategory.Sources, sourceIds = listOf("a", "b"), hasHiddenCategories = true),
        )
        assertEquals(
            listOf(
                SettingsRowKeys.TITLE, SettingsRowKeys.EXPERIENCE, SettingsRowKeys.TEXT_SIZE,
                SettingsRowKeys.SCREENSAVER, SettingsRowKeys.CUSTOMIZE_HOME, SettingsRowKeys.HIDDEN_CW,
                SettingsRowKeys.END_SPACER,
            ),
            settingsPaneItemKeys(SettingsCategory.Display, hasSlot = true, hasHiddenCwRow = true),
        )
        assertEquals(
            listOf(
                SettingsRowKeys.TITLE, SettingsRowKeys.GUEST_NOTE, SettingsRowKeys.CREATE_PROFILE,
                SettingsRowKeys.PARENTAL, SettingsRowKeys.END_SPACER,
            ),
            settingsPaneItemKeys(SettingsCategory.ProfilesAndKids, guestMode = true, hasSlot = true),
        )
        assertEquals(
            listOf(
                SettingsRowKeys.TITLE, SettingsRowKeys.VERSION, SettingsRowKeys.LEGAL,
                SettingsRowKeys.UPDATE_CHECK, SettingsRowKeys.END_SPACER,
            ),
            settingsPaneItemKeys(SettingsCategory.AboutAndUpdates, hasSlot = true),
        )
        assertEquals(
            listOf(SettingsRowKeys.TITLE, SettingsRowKeys.SLOT, SettingsRowKeys.END_SPACER),
            settingsPaneItemKeys(SettingsCategory.PhoneRemote, hasSlot = true),
        )
    }

    @Test
    fun aSearchHitForARowNotRenderedInThePaneIsAMiss() {
        // Task 111 M5: "Also hide episode titles" only renders when spoiler-free is on. Searching it
        // with spoiler-free off must be a miss (index -1) so the pane shows a notice instead of
        // requesting focus on a row that is not there.
        val hit = searchSettings("hide episode titles").first()
        assertEquals(SettingsRowKeys.SPOILER_FREE_HIDE_TITLES, hit.rowKey)
        assertEquals(-1, settingsJumpIndex(settingsPaneItemKeys(SettingsCategory.Playback, spoilerFree = false), hit.rowKey))
        assertTrue(settingsJumpIndex(settingsPaneItemKeys(SettingsCategory.Playback, spoilerFree = true), hit.rowKey) >= 0)
    }

    @Test
    fun aboutUpdatesUpdateCheckIsItsOwnReachableItem() {
        // Task 100: the About & updates slot (Check for updates + diagnostics) is rendered as its own
        // LazyColumn item so a search jump can scroll to it and hand focus to its focus group. It must
        // come after the built-in rows and be the last content row (only the end spacer follows it).
        val keys = settingsPaneItemKeys(SettingsCategory.AboutAndUpdates, hasSlot = true)
        val idx = settingsJumpIndex(keys, SettingsRowKeys.UPDATE_CHECK)
        assertTrue("update check must be reachable by jump", idx >= 0)
        assertEquals(keys.indexOf(SettingsRowKeys.UPDATE_CHECK), idx)
        assertEquals(SettingsRowKeys.END_SPACER, keys[idx + 1])
        assertTrue(keys.indexOf(SettingsRowKeys.LEGAL) < idx)
        // No slot -> the row is not rendered -> the jump is a miss, so the pane shows a notice instead
        // of requesting focus on a group that is not there (the task 111 M5 dead-D-pad guard).
        assertEquals(-1, settingsJumpIndex(settingsPaneItemKeys(SettingsCategory.AboutAndUpdates), SettingsRowKeys.UPDATE_CHECK))
    }
}
