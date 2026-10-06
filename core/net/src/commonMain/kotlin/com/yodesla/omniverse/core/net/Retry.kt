package com.yodesla.omniverse.core.net

import com.yodesla.omniverse.core.source.SourceException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 8_000,
    val jitterRatio: Double = 0.2,
)

/**
 * Runs [block] (1-based [attempt]) until it succeeds, retrying only
 * [SourceException.Network] and retryable [SourceException.Http] with
 * exponential backoff plus jitter. The last failure is rethrown.
 */
suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy(),
    random: Random = Random.Default,
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 0
    while (true) {
        attempt++
        try {
            return block(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceException.Network) {
            if (attempt == policy.maxAttempts) throw e
        } catch (e: SourceException.Http) {
            if (!e.retryable || attempt == policy.maxAttempts) throw e
        } catch (e: SourceException) {
            throw e
        }
        val baseMs = min(policy.maxDelayMs, policy.initialDelayMs * 2.0.pow(attempt - 1).toLong())
        val jitterMs = (random.nextDouble(-1.0, 1.0) * policy.jitterRatio * baseMs).toLong()
        delay(baseMs + jitterMs)
    }
}
