package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.SyncScope
import com.yodesla.omniverse.core.model.SourceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogRevisionSyncTest {
    @Test fun parserRevisionForcesCatalogEvenWhenFresh() = runBlocking {
        val source = SourceId("iptv")
        val calls = mutableListOf<Triple<SourceId, SyncScope, Boolean>>()
        val complete = syncSourcesForCatalogRevision(
            listOf(source), refreshCatalog = true,
            isStale = { _, _ -> false },
            runSync = { id, scope, force -> calls += Triple(id, scope, force); true },
        )

        assertTrue(complete)
        assertEquals(listOf(Triple(source, SyncScope.VOD_AND_SERIES, true)), calls)
    }

    @Test fun failedOrBusyCatalogRunKeepsRevisionPendingForRetry() = runBlocking {
        val source = SourceId("iptv")
        var attempts = 0
        suspend fun refresh() = syncSourcesForCatalogRevision(
            listOf(source), refreshCatalog = true,
            isStale = { _, _ -> false },
            runSync = { _, scope, force ->
                assertEquals(SyncScope.VOD_AND_SERIES, scope)
                assertTrue(force)
                ++attempts > 1
            },
        )

        assertFalse(refresh())
        assertTrue(refresh())
        assertEquals(2, attempts)
    }

    @Test fun ordinaryStaleSyncDoesNotForceCatalog() = runBlocking {
        val source = SourceId("iptv")
        val calls = mutableListOf<Pair<SyncScope, Boolean>>()
        syncSourcesForCatalogRevision(
            listOf(source), refreshCatalog = false,
            isStale = { _, scope -> scope == SyncScope.ALL },
            runSync = { _, scope, force -> calls += scope to force; true },
        )

        assertEquals(listOf(SyncScope.ALL to false), calls)
    }
}
