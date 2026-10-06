package com.yodesla.omniverse.app

import com.yodesla.omniverse.core.data.Progress
import com.yodesla.omniverse.core.data.SecretBox
import com.yodesla.omniverse.core.data.UserDataRepository
import com.yodesla.omniverse.core.model.ContentKey
import com.yodesla.omniverse.core.model.ContentKind
import com.yodesla.omniverse.core.model.RemoteId
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Audit M9: the /identity probe must not be able to hold the thread that opened Settings. Headers
 * alone are not enough — a stalled body has to be aborted by a real deadline, the response closed,
 * and the whole probe (plus Keystore open) has to happen off the caller's dispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppPlexLinkerProbeTest {
    private val slowHost = "http://192.168.1.20:32400"

    private fun linker(http: HttpClient, probeTimeoutMs: Long) = AppPlexLinker(
        http, ProbeUserData(), newId = { "client-1" }, secrets = SecretBox.None, probeTimeoutMs = probeTimeoutMs,
    )

    @Test
    fun aStalledIdentityBodyIsAbortedByTheDeadlineAndNeverSelectsThatAddress() = runBlocking {
        val http = ProbeHttp(stallingHost = slowHost, stallMs = 5_000)
        val started = System.nanoTime()
        val choices = assertNotNull(linker(http, probeTimeoutMs = 150).accountServers())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(listOf("Fast Server"), choices.map { it.name }, "probes=${http.identityProbes} stall=${http.stallLog}")
        assertTrue(elapsedMs < 2_000, "stalled body blocked the lookup for $elapsedMs ms")
        assertTrue(http.closed >= 1, "the stalled probe response was never closed")
        assertTrue(http.identityProbes.any { it.startsWith(slowHost) }, "the stalled address was never probed")
    }

    @Test
    fun networkBodyReadAndParseNeverRunOnTheCallingThread() = runBlocking {
        val http = ProbeHttp(stallingHost = slowHost, stallMs = 5_000)
        val caller = newSingleThreadContext("plex-caller-thread")
        try {
            val choices = withContext(caller) { assertNotNull(linker(http, probeTimeoutMs = 150).accountServers()) }
            assertEquals(listOf("Fast Server"), choices.map { it.name })
            assertTrue(http.probeThreads.isNotEmpty(), "no identity probe ran")
            assertTrue(
                http.probeThreads.none { it == "plex-caller-thread" },
                "identity probe ran on the caller's thread: ${http.probeThreads.distinct()}",
            )
        } finally {
            caller.close()
        }
    }

    @Test
    fun cancellingTheLookupPropagatesAndClosesTheInFlightProbe() = runBlocking {
        val http = ProbeHttp(stallingHost = slowHost, stallMs = 1_000)
        val job = async { linker(http, probeTimeoutMs = 1_000).accountServers() }
        delay(50)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(http.closed >= 1, "cancelling the probe left its response open")
    }
}

/** plex.tv resources + /identity, with one address whose body never finishes. */
private class ProbeHttp(private val stallingHost: String?, private val stallMs: Long) : HttpClient {
    // Probes run concurrently on IO threads: a plain list can lose an entry.
    val identityProbes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    /** Thread name at the moment each probe's body was read. */
    // Probes run concurrently on IO threads: a plain list can lose an entry.
    val probeThreads: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    /** What the stalled body did: deadline honoured, or never seen. */
    // Probes run concurrently on IO threads: a plain list can lose an entry.
    val stallLog: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val closedCount = java.util.concurrent.atomic.AtomicInteger()
    val closed: Int get() = closedCount.get()

    private val machineAt = mapOf(
        "http://192.168.1.10:32400" to "fast-machine",
        stallingHost to "slow-machine",
    )

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = when {
        url.contains("/api/v2/resources?") -> response(200, """
            [
              {"name":"Fast Server","provides":"server","clientIdentifier":"fast-machine","accessToken":"fast-token",
               "publicAddressMatches":true,
               "connections":[{"address":"192.168.1.10","port":32400,"uri":"http://192.168.1.10:32400","local":true}]},
              {"name":"Slow Server","provides":"server","clientIdentifier":"slow-machine","accessToken":"slow-token",
               "publicAddressMatches":true,
               "connections":[{"address":"192.168.1.20","port":32400,"uri":"http://192.168.1.20:32400","local":true}]}
            ]
        """.trimIndent())

        url.endsWith("/identity") -> {
            val base = url.removeSuffix("/identity")
            identityProbes += url
            probeThreads += Thread.currentThread().name
            val machine = machineAt[base]
            when {
                base == stallingHost -> stalled("""{"MediaContainer":{"machineIdentifier":"$machine"}}""")
                machine != null -> response(200, """{"MediaContainer":{"machineIdentifier":"$machine"}}""")
                else -> response(404, "{}")
            }
        }

        else -> response(404, "{}")
    }

    private fun response(status: Int, body: String) = object : HttpResponse {
        override val status = status
        override val headers = emptyMap<String, String>()
        override val body: BufferedSource = Buffer().writeUtf8(body)
        override fun close() { closedCount.incrementAndGet() }
    }

    /** Headers arrive at once; the body stalls like a half-open connection until a deadline stops it. */
    private fun stalled(body: String) = object : HttpResponse {
        override val status = 200
        override val headers = emptyMap<String, String>()
        override val body: BufferedSource = StallingSource(body, stallMs, stallLog).buffer()
        override fun close() { closedCount.incrementAndGet() }
    }
}

/**
 * A body that stalls like a half-open connection. It carries its own [Timeout] (a plain okio
 * Buffer reports `Timeout.NONE`, which is exactly what a socket-backed body does not do), so the
 * deadline the linker sets on `body.timeout()` is the one this read has to honour.
 */
private class StallingSource(
    body: String,
    private val stallMs: Long,
    private val log: MutableList<String>,
) : ForwardingSource(Buffer().writeUtf8(body)) {
    private val ioTimeout = Timeout()

    override fun timeout(): Timeout = ioTimeout

    override fun read(sink: Buffer, byteCount: Long): Long {
        val t0 = System.nanoTime()
        val until = t0 + stallMs * 1_000_000L
        while (System.nanoTime() < until) {
            val timeout = timeout()
            val ms = (System.nanoTime() - t0) / 1_000_000L
            if (timeout.hasDeadline()) {
                if (timeout.deadlineNanoTime() <= System.nanoTime()) {
                    log += "deadline hit after ${ms}ms"
                    throw java.io.InterruptedIOException("body read exceeded its deadline")
                }
            } else if (ms > 400 && log.none { it.startsWith("no deadline") }) {
                log += "no deadline seen at ${ms}ms"
            }
            Thread.sleep(10)
        }
        log += "answered after ${(System.nanoTime() - t0) / 1_000_000L}ms"
        return super.read(sink, byteCount)
    }
}

private class ProbeUserData : UserDataRepository {
    private val settings = mutableMapOf<String, String>()

    init {
        settings["plex_account_token_sealed"] = "user-token"
    }

    override fun favorites(kind: ContentKind?): Flow<List<ContentKey>> = flowOf(emptyList())
    override fun isFavorite(key: ContentKey): Flow<Boolean> = flowOf(false)
    override suspend fun setFavorite(key: ContentKey, favorite: Boolean) = Unit
    override suspend fun saveProgress(key: ContentKey, parentId: RemoteId?, positionMs: Long, durationMs: Long?) = Unit
    override fun continueWatching(limit: Int): Flow<List<Progress>> = flowOf(emptyList())
    override suspend fun progress(key: ContentKey): Progress? = null
    override suspend fun recordChannelWatched(key: ContentKey) = Unit
    override fun recentChannels(limit: Int): Flow<List<ContentKey>> = flowOf(emptyList())
    override suspend fun setHidden(key: ContentKey, hidden: Boolean) = Unit
    override fun setting(key: String): Flow<String?> = flowOf(settings[key])
    override suspend fun putSetting(key: String, value: String) { settings[key] = value }
}
