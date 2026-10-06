package com.yodesla.omniverse.core.source.xtream

import com.yodesla.omniverse.core.model.SourceId
import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import com.yodesla.omniverse.core.source.SourceConfig
import com.yodesla.omniverse.core.source.SourceException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Audit 2026-09-27: an error OBJECT where a list was expected must never read as "no items". */
class XtreamErrorBodyTest {
    private fun sourceAnswering(body: String) = XtreamSource(
        SourceConfig.Xtream(SourceId("x"), "P", "http://h", "u", "p"),
        object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = object : HttpResponse {
                override val status = 200
                override val headers = emptyMap<String, String>()
                override val body: BufferedSource = Buffer().writeUtf8(body)
                override fun close() = Unit
            }
        },
    )

    @Test
    fun emptyObjectMeansNoItems() = runBlocking {
        assertEquals(0, sourceAnswering("{}").liveChannels().toList().size)
    }

    @Test
    fun emptyBodyMeansNoItems() = runBlocking {
        assertEquals(0, sourceAnswering("").vodItems().toList().size)
    }

    @Test
    fun authZeroObjectIsAuthFailed() {
        assertFailsWith<SourceException.AuthFailed> {
            runBlocking { sourceAnswering("""{"user_info":{"auth":0}}""").liveChannels().toList() }
        }
    }

    @Test
    fun anyOtherObjectIsBadResponse() {
        assertFailsWith<SourceException.BadResponse> {
            runBlocking { sourceAnswering("""{"error":"maintenance"}""").series().toList() }
        }
    }
}
