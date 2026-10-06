package com.yodesla.omniverse.core.data

import com.yodesla.omniverse.core.model.AccountStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 105: the source-health warning rules, tested as pure logic. */
class SourceHealthTest {
    private val day = SourceHealth.DAY
    private val now = 1_790_424_000_000L

    private fun snap(
        sourceId: String = "s1",
        sourceName: String = "Provider",
        checkedAtMs: Long = now,
        accountStatus: String? = AccountStatus.ACTIVE.name,
        expiresAtMs: Long? = null,
        lastSuccessSyncMs: Long? = now - day,
        movies: Long = 1_234L,
        shows: Long = 567L,
        channels: Long = 89L,
        prevMovies: Long = 1_234L,
        prevShows: Long = 567L,
        prevChannels: Long = 89L,
        countsKnown: Boolean = true,
        failCount: Int = 0,
        lastErrorKind: String? = null,
    ) = SourceHealthSnapshot(
        sourceId = sourceId, sourceName = sourceName, checkedAtMs = checkedAtMs,
        accountStatus = accountStatus, expiresAtMs = expiresAtMs, maxConnections = 3, activeConnections = 1,
        lastSuccessSyncMs = lastSuccessSyncMs,
        movies = movies, shows = shows, channels = channels,
        prevMovies = prevMovies, prevShows = prevShows, prevChannels = prevChannels,
        countsKnown = countsKnown, failCount = failCount, lastErrorKind = lastErrorKind,
    )

    private fun textOf(s: SourceHealthSnapshot): List<String> = sourceWarnings(s, now).map { it.text }

    @Test
    fun healthySourceWarnsAboutNothing() {
        assertTrue(sourceWarnings(snap(), now).isEmpty())
        assertNull(homeAlert(sourceWarnings(snap(), now)))
    }

    @Test
    fun expiryWithinSevenDaysIsChipAndBanner() {
        val w = sourceWarnings(snap(expiresAtMs = now + 5 * day), now)
        assertEquals(listOf("Expires in 5 days"), w.map { it.text })
        assertEquals(SourceWarningKind.EXPIRING, w.first().kind)
        assertEquals(SourceWarningSeverity.WARNING, w.first().severity)
        val alert = assertNotNull(homeAlert(w))
        assertEquals("Provider", alert.sourceName)
        assertEquals("Expires in 5 days", alert.text)
    }

    @Test
    fun expiryCountsDaysUpAndStopsAtSeven() {
        assertEquals("Expires in 1 day", textOf(snap(expiresAtMs = now + 1 * day)).first())
        // Half a day left still counts as one day, never as zero.
        assertEquals("Expires in 1 day", textOf(snap(expiresAtMs = now + day / 2)).first())
        assertEquals("Expires in 7 days", textOf(snap(expiresAtMs = now + 7 * day)).first())
        assertTrue(sourceWarnings(snap(expiresAtMs = now + 8 * day), now).isEmpty())
    }

    @Test
    fun expiryPastIsExpiredAndSevere() {
        val w = sourceWarnings(snap(expiresAtMs = now - day), now)
        assertEquals(listOf("Expired"), w.map { it.text })
        assertEquals(SourceWarningSeverity.SEVERE, w.first().severity)
        assertNotNull(homeAlert(w))
    }

    @Test
    fun providerDisabledStatusWarnsEvenWithoutAnExpiryDate() {
        assertEquals(
            listOf("Disabled by provider"),
            textOf(snap(accountStatus = AccountStatus.DISABLED.name, expiresAtMs = null)),
        )
        assertEquals(listOf("Disabled by provider"), textOf(snap(accountStatus = AccountStatus.BANNED.name)))
    }

    @Test
    fun authFailureIsLoginFailed() {
        val w = sourceWarnings(snap(lastErrorKind = SourceErrorKind.AUTH.name), now)
        assertTrue(w.any { it.text == "Login failed" && it.kind == SourceWarningKind.LOGIN_FAILED && it.severity == SourceWarningSeverity.SEVERE })
        assertNotNull(homeAlert(w))
    }

    @Test
    fun networkFailureAloneIsNotAWarningYet() {
        assertTrue(textOf(snap(lastErrorKind = SourceErrorKind.NETWORK.name, failCount = 1)).isEmpty())
    }

    @Test
    fun channelsDropReportsThePercent() {
        val w = sourceWarnings(snap(prevChannels = 100, channels = 40), now)
        assertEquals(listOf("Channels dropped 60%"), w.map { it.text })
        assertEquals(SourceWarningSeverity.SEVERE, w.first().severity)
    }

    @Test
    fun smallerDropIsAWarningNotSevereAndTinyDropIsSilent() {
        val w = sourceWarnings(snap(prevChannels = 100, channels = 74), now)
        assertEquals(listOf("Channels dropped 26%"), w.map { it.text })
        assertEquals(SourceWarningSeverity.WARNING, w.first().severity)
        assertTrue(sourceWarnings(snap(prevChannels = 100, channels = 76), now).isEmpty())
    }

    @Test
    fun tinyLibraryNeverReportsADrop() {
        assertTrue(sourceWarnings(snap(prevChannels = 10, channels = 1), now).isEmpty())
    }

    @Test
    fun moviesAndShowsDropsAreLabelledPerLibrary() {
        val w = sourceWarnings(snap(prevMovies = 1_000, movies = 500, prevShows = 200, shows = 150), now)
        assertEquals(listOf("Movies dropped 50%", "Shows dropped 25%"), w.map { it.text })
    }

    @Test
    fun growthAndUnknownCountsNeverReportADrop() {
        assertTrue(sourceWarnings(snap(prevChannels = 100, channels = 140), now).isEmpty())
        assertTrue(sourceWarnings(snap(prevChannels = 500, channels = 0, countsKnown = false), now).isEmpty())
    }

    @Test
    fun syncStalenessRules() {
        assertTrue(sourceWarnings(snap(lastSuccessSyncMs = now - 2 * day), now).isEmpty())
        assertEquals(
            listOf("No successful sync in 20 days"),
            textOf(snap(lastSuccessSyncMs = now - 20 * day)),
        )
        // Never synced but holding catalog rows: something is wrong.
        assertEquals(listOf("Never synced successfully"), textOf(snap(lastSuccessSyncMs = null)))
        // A brand-new source that has not synced yet and is not failing is still loading.
        assertTrue(textOf(snap(lastSuccessSyncMs = null, movies = 0, shows = 0, channels = 0, prevMovies = 0, prevShows = 0, prevChannels = 0)).isEmpty())
    }

    @Test
    fun repeatedFailuresOnlyStartAtThree() {
        assertTrue(sourceWarnings(snap(failCount = 2, lastErrorKind = SourceErrorKind.NETWORK.name), now).isEmpty())
        val w = sourceWarnings(snap(failCount = 3, lastErrorKind = SourceErrorKind.NETWORK.name), now)
        assertEquals(listOf("Keeps failing (3 checks in a row)"), w.map { it.text })
        assertNotNull(homeAlert(w))
    }

    @Test
    fun bannerIgnoresAPlainCountDrop() {
        val w = sourceWarnings(snap(prevChannels = 100, channels = 10), now)
        assertEquals(listOf("Channels dropped 90%"), w.map { it.text })
        assertNull(homeAlert(w))
    }

    @Test
    fun bannerPicksTheMostSeriousProblemOfAllSources() {
        val warnings = sourceWarnings(snap(sourceId = "a", sourceName = "IPTV", expiresAtMs = now + 3 * day), now) +
            sourceWarnings(snap(sourceId = "b", sourceName = "Plex", lastErrorKind = SourceErrorKind.AUTH.name), now)
        val alert = assertNotNull(homeAlert(warnings))
        assertEquals("b", alert.sourceId)
        assertEquals("Login failed", alert.text)
    }

    @Test
    fun dismissalFingerprintIsStablePerProblemAndChangesWithTheProblem() {
        val expiring = assertNotNull(homeAlert(sourceWarnings(snap(expiresAtMs = now + 5 * day), now)))
        val later = assertNotNull(homeAlert(sourceWarnings(snap(checkedAtMs = now + 2 * day, expiresAtMs = now + 5 * day), now)))
        assertEquals(expiring.fingerprint, later.fingerprint, "the same expiry stays the same problem")
        val nextWeek = assertNotNull(homeAlert(sourceWarnings(snap(expiresAtMs = now + 4 * day), now)))
        assertTrue(nextWeek.fingerprint != expiring.fingerprint, "a different expiry is a new problem")
        val failing = assertNotNull(homeAlert(sourceWarnings(snap(failCount = 3, lastErrorKind = SourceErrorKind.NETWORK.name), now)))
        assertTrue(failing.fingerprint != expiring.fingerprint)
    }

    @Test
    fun checkIsDueOnlyAfterTheInterval() {
        assertTrue(healthCheckDue(now, null))
        assertTrue(healthCheckDue(now, now - 8 * day))
        assertTrue(healthCheckDue(now, now - SourceHealth.CHECK_INTERVAL_MS))
        assertTrue(!healthCheckDue(now, now - 6 * day))
    }

    @Test
    fun warningTextsCarryNoUrlOrCredentialDetail() {
        val texts = sourceWarnings(
            snap(expiresAtMs = now + 2 * day, prevChannels = 500, channels = 100, failCount = 4, lastErrorKind = SourceErrorKind.AUTH.name),
            now,
        ).map { it.text }
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.all { !it.contains("http") && !it.contains("@") && !it.contains("user") && !it.contains("token") })
    }
}
