package com.yodesla.omniverse.core.net

import okio.BufferedSource

/**
 * Minimal streaming HTTP GET used by every source. CONTRACT:
 *  - [get] returns once headers arrive; [HttpResponse.body] is read incrementally (never buffered whole).
 *  - The caller MUST close the response (`response.use { ... }`), including on cancellation.
 *  - IO failures (DNS, connect, timeout, reset) throw SourceException.Network with a redacted message.
 *  - Non-2xx statuses are NOT thrown here; callers decide (see [HttpResponse.requireSuccess]).
 *  - Implementations are main-safe (they switch to an IO dispatcher internally).
 * jvmMain provides OkHttpHttpClient; iOS gets an NSURLSession one in Phase 6.
 */
interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /** Empty-body POST (e.g. plex.tv PIN creation). Fakes that never POST needn't implement it. */
    suspend fun post(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        throw UnsupportedOperationException("POST not supported by this HttpClient")
}

interface HttpResponse : AutoCloseable {
    val status: Int
    /** Header names lower-cased. */
    val headers: Map<String, String>
    val body: BufferedSource
}
