package com.yodesla.omniverse.android.parental

import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ParentalControlsTest {

    // Mirrors the real repository: setting() is a reactive Flow (Room asFlow) that re-emits
    // on putSetting, so per-key MutableStateFlow instead of a one-shot flowOf(snapshot).
    private class FakeUserData : UserDataRepository {
        private val flows = mutableMapOf<String, MutableStateFlow<String?>>()
        val store: Map<String, String>
            get() {
                val out = mutableMapOf<String, String>()
                for ((k, flow) in flows) flow.value?.let { out[k] = it }
                return out
            }
        override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
        override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
        override suspend fun setFavorite(key: ContentKey, favorite: Boolean) {}
        override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) {}
        override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
        override suspend fun progress(key: ContentKey): Progress? = null
        override suspend fun recordChannelWatched(key: ContentKey) {}
        override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
        override suspend fun setHidden(key: ContentKey, hidden: Boolean) {}
        override fun setting(key: String): Flow<String?> = flows.getOrPut(key) { MutableStateFlow(null) }
        override suspend fun putSetting(key: String, value: String) {
            flows.getOrPut(key) { MutableStateFlow(null) }.value = value
        }
    }

    private class Fixture(dispatcher: TestDispatcher) {
        val scope = CoroutineScope(dispatcher)
        var now = 0L
        val userData = FakeUserData()
        val controls = ParentalControls(
            userData = userData,
            clock = { now },
            scope = scope,
        )
        /** Cancels the session-expiry job so runTest's idle check doesn't wait forever. */
        fun close() = scope.cancel()
    }

    private fun make(): Fixture = Fixture(StandardTestDispatcher())

    @Test
    fun setPinRejectsMalformedAndAcceptsFourDigits() = runTest {
        val f = make()
        assertFailsWith<IllegalArgumentException> { f.controls.setPin("12") }
        assertFailsWith<IllegalArgumentException> { f.controls.setPin("abcd") }
        assertFailsWith<IllegalArgumentException> { f.controls.setPin("12345") }
        f.controls.setPin("0420")
        assertNotNull(f.userData.store[ParentalControls.PIN_KEY])
        assertTrue(f.controls.enabled.first())
        f.close()
    }

    @Test
    fun storedValueNeverContainsThePinDigitsInOrder() = runTest {
        val f = make()
        f.controls.setPin("0420")
        val stored = f.userData.store[ParentalControls.PIN_KEY]!!
        assertFalse(stored.contains("0420"))
        assertTrue(f.controls.check("0420"))
        f.close()
    }

    @Test
    fun checkAcceptsCorrectAndRejectsWrongPin() = runTest {
        val f = make()
        f.controls.setPin("1234")
        assertTrue(f.controls.check("1234"))
        assertFalse(f.controls.check("1235"))
        assertFalse(f.controls.check(""))
        f.close()
    }

    @Test
    fun fiveWrongChecksLockOutForSixtySeconds() = runTest {
        val f = make()
        f.controls.setPin("1234")
        repeat(4) {
            assertFalse(f.controls.check("0000"))
            assertNull(f.controls.lockedOutUntil.value)
        }
        assertFalse(f.controls.check("0000"))
        val until = f.controls.lockedOutUntil.value
        assertNotNull(until)
        assertEquals(f.now + ParentalControls.LOCKOUT_MS, until)

        f.now += 30_000
        assertFalse(f.controls.check("1234"))
        assertNotNull(f.controls.lockedOutUntil.value)

        f.now += 31_000
        assertTrue(f.controls.check("1234"))
        assertNull(f.controls.lockedOutUntil.value)
        f.close()
    }

    @Test
    fun lockoutSurvivesAnAppRestart() = runTest {
        val f = make()
        f.controls.setPin("1234")
        repeat(5) { f.controls.check("0000") }
        // "Restart": a fresh ParentalControls over the same stored settings.
        val restarted = ParentalControls(userData = f.userData, clock = { f.now }, scope = f.scope)
        assertFalse(restarted.check("1234"), "right PIN still refused during the lockout")
        f.now += ParentalControls.LOCKOUT_MS + 1_000
        assertTrue(restarted.check("1234"))
        f.close()
    }

    @Test
    fun lockRoundTripAndClearPinRequiresCorrectPin() = runTest {
        val f = make()
        f.controls.setPin("1234")

        f.controls.setLocked("LIVE", "src1", "c1", true)
        assertTrue(f.controls.isLocked("LIVE", "src1", "c1").first())
        assertEquals(setOf("LIVE|src1|c1"), f.controls.lockedKeys().first())

        f.controls.setLocked("LIVE", "src1", "c1", false)
        assertFalse(f.controls.isLocked("LIVE", "src1", "c1").first())
        assertEquals(emptySet<String>(), f.controls.lockedKeys().first())

        f.controls.setLocked("VOD", "src2", "m9", true)
        assertFalse(f.controls.clearPin("9999"))
        assertTrue(f.controls.enabled.first())
        assertEquals(setOf("VOD|src2|m9"), f.controls.lockedKeys().first())

        assertTrue(f.controls.clearPin("1234"))
        assertFalse(f.controls.enabled.first())
        assertEquals(emptySet<String>(), f.controls.lockedKeys().first())
        f.close()
    }

    @Test
    fun suggestLockMatchesOnlyAdultLookingNames() {
        val controls = ParentalControls(FakeUserData())
        assertTrue(controls.suggestLock("Adult XXX"))
        assertTrue(controls.suggestLock("18+ Movies"))
        assertFalse(controls.suggestLock("Middlesex Local"))
        assertFalse(controls.suggestLock("Essex"))
        assertFalse(controls.suggestLock("Kids"))
    }

    @Test
    fun sessionUnlockExpiresAfterTenMinutesIdle() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.controls.setPin("1234")
        assertTrue(f.controls.check("1234"))
        assertTrue(f.controls.unlockedForSession.value)

        f.now += 300_000
        f.controls.touch()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(f.controls.unlockedForSession.value)

        f.now += 601_000
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(f.controls.unlockedForSession.value)
        f.close()
    }

    @Test
    fun lockAgainClosesTheSession() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.controls.setPin("1234")
        assertTrue(f.controls.check("1234"))
        f.controls.lockAgain()
        assertFalse(f.controls.unlockedForSession.value)
        f.close()
    }

    @Test
    fun sqlGroupingExclusionsFollowSessionUnlock() = runTest {
        val f = make()
        f.controls.setPin("1234")
        f.controls.setLocked("VOD", "plex", "movies", true)
        assertEquals(setOf("VOD|plex|movies"), f.controls.excludedCategoryKeys.first())
        assertTrue(f.controls.check("1234"))
        assertEquals(emptySet(), f.controls.excludedCategoryKeys.first())
        f.controls.lockAgain()
        assertEquals(setOf("VOD|plex|movies"), f.controls.excludedCategoryKeys.first())
        f.close()
    }
}
