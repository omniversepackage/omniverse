package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.model.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 107 + 118: the stack rewrite behind "Back from a search result goes to" (Settings › Playback).
 * A channel opened from Search gets the Guide as its Back landing. A title leaves the Search entry
 * alone (Kory, task 118): Back returns to the same Search screen, so its query, results, focus and
 * scroll position are still there. With the "Search" option nothing is rewritten at all.
 */
class SearchBackStackTest {
    private val src = SourceId("s")
    private val liveKey = ContentKey(src, ContentKind.LIVE, RemoteId("c1"))
    private val movieKey = ContentKey(src, ContentKind.VOD, RemoteId("m1"))
    private val showKey = ContentKey(src, ContentKind.SERIES, RemoteId("s1"))
    private val episodeKey = ContentKey(src, ContentKind.EPISODE, RemoteId("e1"))

    @Test
    fun channelFromSearchLandsOnTheGuide() {
        assertEquals(
            Screen.Live(inGuide = true),
            searchBackReplacement(Screen.Player(liveKey, RemoteId("cat")), toGuideOrDetails = true),
        )
    }

    // Task 118: the old Movies/Shows landing for a search-opened title is gone — Back returns to Search.
    @Test
    fun titlesFromSearchKeepTheSearchScreen() {
        assertEquals(null, searchBackReplacement(Screen.Detail(movieKey), toGuideOrDetails = true))
        assertEquals(null, searchBackReplacement(Screen.Detail(showKey), toGuideOrDetails = true))
        assertEquals(null, searchBackReplacement(Screen.Detail(episodeKey), toGuideOrDetails = true))
    }

    @Test
    fun optionSearchKeepsTheSearchScreen() {
        assertEquals(null, searchBackReplacement(Screen.Player(liveKey, RemoteId("cat")), toGuideOrDetails = false))
        assertEquals(null, searchBackReplacement(Screen.Detail(movieKey), toGuideOrDetails = false))
    }

    @Test
    fun destinationsWithoutALandingSectionKeepTheSearchScreen() {
        assertEquals(null, searchBackReplacement(Screen.Multiview, toGuideOrDetails = true))
    }

    @Test
    fun liveSectionDefaultsToListMode() {
        assertEquals(false, Screen.Live().inGuide)
        assertEquals("live", Screen.Live(inGuide = true).id)
    }

    /** search → title → Back: the Search entry (query included) is what the pop returns to. */
    @Test
    fun titleFromSearchReturnsToTheSameSearchOnBack() {
        val search = Screen.Search("frozen")
        val stack = mutableListOf<Screen>(Screen.Home, search)
        openCardOnStack(stack, Screen.Detail(movieKey), toGuideOrDetails = true)
        assertEquals(listOf(Screen.Home, search, Screen.Detail(movieKey)), stack)
        stack.removeAt(stack.lastIndex) // the shell's pop()
        assertEquals(search, stack.last())
    }

    /** The same title opened twice in a row still returns to the original Search entry. */
    @Test
    fun repeatedTitleOpensKeepReturningToTheSameSearchQuery() {
        val search = Screen.Search("frozen")
        val stack = mutableListOf<Screen>(Screen.Home, search)
        for (key in listOf(movieKey, showKey, episodeKey)) {
            openCardOnStack(stack, Screen.Detail(key), toGuideOrDetails = true)
            stack.removeAt(stack.lastIndex)
        }
        assertEquals(listOf<Screen>(Screen.Home, search), stack)
    }

    /** Task 107 stays: a channel opened from Search backs out to the Guide on that channel. */
    @Test
    fun channelFromSearchReturnsToTheGuideOnBack() {
        val stack = mutableListOf<Screen>(Screen.Home, Screen.Search("cnn"))
        openCardOnStack(stack, Screen.Player(liveKey, RemoteId("cat")), toGuideOrDetails = true)
        assertEquals(listOf(Screen.Home, Screen.Live(inGuide = true), Screen.Player(liveKey, RemoteId("cat"))), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(Screen.Live(inGuide = true), stack.last())
    }

    /** Option "Search": even a channel backs out onto the Search screen it came from. */
    @Test
    fun optionSearchReturnsToTheSearchScreenOnBack() {
        val search = Screen.Search("cnn")
        val stack = mutableListOf<Screen>(Screen.Home, search)
        openCardOnStack(stack, Screen.Player(liveKey, RemoteId("cat")), toGuideOrDetails = false)
        assertEquals(listOf(Screen.Home, search, Screen.Player(liveKey, RemoteId("cat"))), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(search, stack.last())
    }

    /** Opening a card from any other section leaves that section under it — normal Back everywhere. */
    @Test
    fun otherEntryRoutesAreUntouched() {
        val stack = mutableListOf<Screen>(Screen.Home, Screen.Movies)
        openCardOnStack(stack, Screen.Detail(movieKey), toGuideOrDetails = true)
        assertEquals(listOf(Screen.Home, Screen.Movies, Screen.Detail(movieKey)), stack)
        val guide = mutableListOf<Screen>(Screen.Home, Screen.Live(inGuide = true))
        openCardOnStack(guide, Screen.Player(liveKey, RemoteId("cat")), toGuideOrDetails = true)
        assertEquals(listOf(Screen.Home, Screen.Live(inGuide = true), Screen.Player(liveKey, RemoteId("cat"))), guide)
    }
}
