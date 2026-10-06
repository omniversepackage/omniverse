package com.yodesla.omniverse.core.update

import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import com.yodesla.omniverse.core.source.SourceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource

private const val GOOD_SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

private fun manifestJson(
    versionCode: Int = 7,
    versionName: String = "0.3.0",
    apkUrl: String = "https://cdn.example.com/omniverse-0.3.0.apk",
    sha256: String = GOOD_SHA,
    notes: String = "",
    minSdk: Int = 23,
    mandatory: Boolean = false,
    extra: String = "",
): String = """
    {
      "versionCode": $versionCode,
      "versionName": "$versionName",
      "apkUrl": "$apkUrl",
      "sha256": "$sha256",
      "notes": "$notes",
      "minSdk": $minSdk,
      "mandatory": $mandatory${if (extra.isEmpty()) "" else ", $extra"}
    }
""".trimIndent()

private class FakeResponse(
    override val status: Int = 200,
    body: String,
    override val headers: Map<String, String> = emptyMap(),
) : HttpResponse {
    private val source: BufferedSource = Buffer().apply { writeUtf8(body) }
    override val body: BufferedSource get() = source
    override fun close() {}
}

private class FakeHttpClient(
    private val action: () -> HttpResponse,
) : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = action()
}

class UpdateCheckerTest {
    private fun checker(
        client: HttpClient,
        current: Int = 6,
        deviceSdk: Int = 34,
    ) = UpdateChecker(client, "https://cdn.example.com/manifest.json", current, deviceSdk)

    private fun withBody(body: String, status: Int = 200, current: Int = 6, deviceSdk: Int = 34): UpdateCheck =
        runBlocking {
            checker(FakeHttpClient { FakeResponse(status, body) }, current, deviceSdk).check()
        }

    @Test
    fun newerValidManifestIsAvailable() {
        val result = withBody(manifestJson())
        assertTrue(result is UpdateCheck.Available, "expected Available, got $result")
        assertEquals(7, result.manifest.versionCode)
        assertEquals("0.3.0", result.manifest.versionName)
    }

    @Test
    fun sameVersionCodeIsUpToDate() {
        assertEquals(UpdateCheck.UpToDate, withBody(manifestJson(versionCode = 6)))
    }

    @Test
    fun olderVersionCodeIsUpToDate() {
        assertEquals(UpdateCheck.UpToDate, withBody(manifestJson(versionCode = 5)))
    }

    @Test
    fun newerButMinSdkTooHighIsUpToDate() {
        assertEquals(UpdateCheck.UpToDate, withBody(manifestJson(minSdk = 35), deviceSdk = 34))
    }

    @Test
    fun httpApkUrlIsFailed() {
        val result = withBody(manifestJson(apkUrl = "http://cdn.example.com/omniverse.apk"))
        assertTrue(result is UpdateCheck.Failed, "expected Failed, got $result")
    }

    @Test
    fun badShaIsFailed() {
        val result = withBody(manifestJson(sha256 = "zz-nope"))
        assertTrue(result is UpdateCheck.Failed, "expected Failed, got $result")
    }

    @Test
    fun htmlBodyIsFailed() {
        val result = withBody("<html><body>502 Bad Gateway</body></html>")
        assertTrue(result is UpdateCheck.Failed, "expected Failed, got $result")
    }

    @Test
    fun networkSourceExceptionIsFailed() {
        val client = FakeHttpClient { throw SourceException.Network("dns lookup failed") }
        val result = runBlocking { checker(client).check() }
        assertTrue(result is UpdateCheck.Failed, "expected Failed, got $result")
        assertEquals("dns lookup failed", result.reason)
    }

    @Test
    fun non200StatusIsFailed() {
        val result = withBody("not found", status = 404)
        assertTrue(result is UpdateCheck.Failed, "expected Failed, got $result")
    }

    @Test
    fun unknownJsonKeysAreIgnored() {
        val result = withBody(manifestJson(extra = """"promo": {"banner": "spring"}"""))
        assertTrue(result is UpdateCheck.Available, "expected Available, got $result")
    }

    @Test
    fun cancellationPropagates() {
        val client = FakeHttpClient {
            throw CancellationException("gone")
        }
        val checker = checker(client)
        var caught = false
        runBlocking {
            try {
                checker.check()
            } catch (e: CancellationException) {
                caught = true
            }
        }
        assertTrue(caught, "check() swallowed the cancellation")
    }
}
