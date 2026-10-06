package com.yodesla.omniverse.core.net

import com.yodesla.omniverse.core.model.Redact
import com.yodesla.omniverse.core.source.SourceException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource

class OkHttpHttpClient(
    private val client: OkHttpClient,
    private val userAgent: String,
) : HttpClient {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = send(url, headers, post = false)

    override suspend fun post(url: String, headers: Map<String, String>): HttpResponse = send(url, headers, post = true)

    private suspend fun send(url: String, headers: Map<String, String>, post: Boolean): HttpResponse {
        val requestBuilder = Request.Builder()
        if (post) requestBuilder.post(ByteArray(0).toRequestBody(null))
        try {
            requestBuilder.url(url)
        } catch (e: IllegalArgumentException) {
            throw SourceException.BadResponse("Invalid URL: ${Redact.text(url)}")
        }
        requestBuilder.header("User-Agent", userAgent)
        for ((name, value) in headers) {
            requestBuilder.header(name, value)
        }

        val call = client.newCall(requestBuilder.build())
        val response: Response = try {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    try {
                        call.execute()
                    } catch (e: IOException) {
                        throw SourceException.Network(
                            "Network error for ${Redact.text(url)}: ${e.javaClass.simpleName}",
                            e,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }

        val headerMap = LinkedHashMap<String, String>(response.headers.size)
        for (name in response.headers.names()) {
            headerMap.putIfAbsent(name.lowercase(), response.header(name) ?: "")
        }
        return OkHttpResponse(response, headerMap)
    }

    companion object {
        fun defaultOkHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private class OkHttpResponse(
    private val response: Response,
    override val headers: Map<String, String>,
) : HttpResponse {
    override val status: Int get() = response.code

    override val body: BufferedSource get() = response.body.source()

    override fun close() {
        response.close()
    }
}
