package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.SyncEngine
import com.yodesla.omniverse.core.data.SyncProgress
import com.yodesla.omniverse.core.data.SyncScope
import com.yodesla.omniverse.core.data.SyncStage
import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.source.SourceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Audit M8: remote progress import must belong to the sync itself, not to whichever caller happened
 * to remember it. Anything that collects [SyncEngine.sync] — Settings, onboarding, "add a Plex
 * server", the background worker — gets the import.
 */
class RemoteProgressSyncTest {
    private val plex = SourceId("plex-1")
    private val other = SourceId("xtream-2")

    private val events = listOf(
        SyncProgress(plex, SyncStage.VOD, 120),
        SyncProgress(plex, SyncStage.SERIES, 12),
        SyncProgress(plex, SyncStage.FINALIZE, 1, finished = true),
    )

    private class FakeEngine(
        private val events: List<SyncProgress>,
        private val failure: SourceException? = null,
        private val stale: Boolean = true,
        private val order: MutableList<String>? = null,
    ) : SyncEngine {
        var runs = 0
        var lastScope: SyncScope? = null
        var lastForce: Boolean? = null

        override fun sync(sourceId: SourceId, scope: SyncScope, force: Boolean): Flow<SyncProgress> = flow {
            runs++
            lastScope = scope
            lastForce = force
            events.forEach {
                order?.add("event:${it.stage}")
                emit(it)
            }
            if (failure != null) throw failure
        }

        override suspend fun isStale(sourceId: SourceId, scope: SyncScope): Boolean = stale
    }

    @Test
    fun aCompletedSyncImportsRemoteProgressOnceAfterTheLastEvent() = runBlocking {
        val order = mutableListOf<String>()
        val imports = mutableListOf<SourceId>()
        val engine = RemoteProgressSyncEngine(FakeEngine(events, order = order), { imports += it; order += "import" })

        val seen = engine.sync(plex).toList()

        assertEquals(3, seen.size)
        assertTrue(seen.last().finished)
        assertEquals(listOf(plex), imports)
        assertEquals("import", order.last(), "import ran before the sync finished: $order")
    }

    @Test
    fun aCallerThatIgnoresProgressStillImports() = runBlocking {
        // The shape Settings refresh, onboarding and "add a Plex server" use.
        val fake = FakeEngine(events)
        val engine = RemoteProgressSyncEngine(fake, importProgress = { progressImports++ })
        engine.sync(plex, SyncScope.ALL, force = true).collect { }
        assertEquals(1, progressImports)
    }

    @Test
    fun aCancelledSyncNeverImports() = runBlocking {
        val fake = FakeEngine(events)
        val engine = RemoteProgressSyncEngine(fake, importProgress = { progressImports++ })
        val partial = engine.sync(plex).take(1).toList()
        assertEquals(1, partial.size)
        assertEquals(0, progressImports, "a half-finished sync must not claim Plex's state")
    }

    @Test
    fun aFailedSyncNeverImportsAndTheFailureStillReachesTheCaller() = runBlocking {
        val fake = FakeEngine(events, failure = SourceException.AuthFailed("Plex token expired"))
        val engine = RemoteProgressSyncEngine(fake, importProgress = { progressImports++ })
        assertFailsWith<SourceException.AuthFailed> { engine.sync(plex).toList() }
        assertEquals(0, progressImports)
    }

    @Test
    fun everySyncImportsAgainSoLaterRemoteChangesArrive() = runBlocking {
        val engine = RemoteProgressSyncEngine(FakeEngine(events), importProgress = { progressImports++ })
        engine.sync(plex).collect { }
        engine.sync(plex).collect { }
        assertEquals(2, progressImports)
    }

    @Test
    fun anImportFailureIsReportedAndNeverFailsTheSync() = runBlocking {
        val failures = mutableListOf<Pair<SourceId, Throwable>>()
        val engine = RemoteProgressSyncEngine(
            FakeEngine(events),
            { throw java.io.IOException("onDeck request failed") },
            onImportFailed = { id, e -> failures += id to e },
        )
        val seen = engine.sync(plex).toList()
        assertEquals(3, seen.size)
        assertEquals(1, failures.size)
        assertEquals(plex, failures.first().first)
    }

    @Test
    fun scopeAndForceReachTheRealEngineUnchanged() = runBlocking {
        val fake = FakeEngine(events)
        val engine = RemoteProgressSyncEngine(fake, importProgress = { progressImports++ })
        engine.sync(plex, SyncScope.VOD_AND_SERIES, force = true).collect { }
        assertEquals(SyncScope.VOD_AND_SERIES, fake.lastScope)
        assertEquals(true, fake.lastForce)
        assertEquals(1, fake.runs)
    }

    @Test
    fun eachSourceGetsItsOwnImport() = runBlocking {
        val imports = mutableListOf<SourceId>()
        val engine = RemoteProgressSyncEngine(FakeEngine(listOf(SyncProgress(other, SyncStage.FINALIZE, 1, finished = true))), { imports += it })
        engine.sync(other).collect { }
        assertEquals(listOf(other), imports)
    }

    @Test
    fun stalenessStillComesFromTheRealEngine() = runBlocking {
        val engine = RemoteProgressSyncEngine(FakeEngine(events, stale = false), importProgress = { progressImports++ })
        assertFalse(engine.isStale(plex, SyncScope.ALL))
        assertEquals(0, progressImports)
    }

    /** Shared counter so a test can prove an import did *not* happen. */
    private var progressImports = 0
}
