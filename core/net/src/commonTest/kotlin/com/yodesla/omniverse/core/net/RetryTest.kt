package com.yodesla.omniverse.core.net

import com.yodesla.omniverse.core.source.SourceException
import kotlin.OptIn
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTest {
    @Test
    fun succeedsOnThirdAttemptAfterTwoNetworkFailures() = runTest {
        var attempts = 0
        val result = withRetry(policy = RetryPolicy(), random = Random(42)) { attempt ->
            attempts++
            if (attempt < 3) throw SourceException.Network("boom")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun http404IsNotRetried() = runTest {
        var attempts = 0
        val e = assertFailsWith<SourceException.Http> {
            withRetry(policy = RetryPolicy(), random = Random(1)) {
                attempts++
                throw SourceException.Http(404, "HTTP 404 for /x")
            }
        }
        assertEquals(1, attempts)
        assertEquals(404, e.status)
        assertTrue(!e.retryable)
    }

    @Test
    fun http503IsRetriedUpToMaxAttemptsThenRethrown() = runTest {
        var attempts = 0
        val e = assertFailsWith<SourceException.Http> {
            withRetry(policy = RetryPolicy(), random = Random(2)) {
                attempts++
                throw SourceException.Http(503, "HTTP 503 for /x")
            }
        }
        assertEquals(3, attempts)
        assertEquals(503, e.status)
    }

    @Test
    fun authFailedIsNeverRetried() = runTest {
        var attempts = 0
        assertFailsWith<SourceException.AuthFailed> {
            withRetry(policy = RetryPolicy(), random = Random(3)) {
                attempts++
                throw SourceException.AuthFailed("nope")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun backoffBetweenFirstAndSecondAttemptIs400To600Ms() = runTest {
        var attempts = 0
        assertFailsWith<SourceException.Network> {
            withRetry(policy = RetryPolicy(maxAttempts = 2), random = Random(7)) {
                attempts++
                throw SourceException.Network("boom")
            }
        }
        assertEquals(2, attempts)
        val waitedMs = testScheduler.currentTime
        assertTrue(waitedMs in 400..600, "expected backoff in 400..600 ms, got $waitedMs")
    }
}
