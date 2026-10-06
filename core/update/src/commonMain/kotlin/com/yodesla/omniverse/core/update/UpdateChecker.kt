package com.yodesla.omniverse.core.update

import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.requireSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val manifestJson = Json { ignoreUnknownKeys = true }

private val Sha256Hex = Regex("^[0-9a-fA-F]{64}$")

/**
 * Polls the public update manifest (PLAN.md P3.7).
 *
 * A newer manifest becomes [UpdateCheck.Available] only when it is installable on THIS device:
 * [UpdateManifest.minSdk] fits, the APK URL is https, and the checksum looks like 64 hex chars.
 * A newer but untrustworthy manifest is [UpdateCheck.Failed]; everything else is [UpdateCheck.UpToDate].
 * Network, HTTP and parse problems are [UpdateCheck.Failed] — [check] never throws
 * (cancellation still propagates).
 */
open class UpdateChecker(
    private val http: HttpClient,
    private val manifestUrl: String,
    private val currentVersionCode: Int,
    private val deviceSdk: Int,
) {
    open suspend fun check(): UpdateCheck = try {
        val response = http.get(manifestUrl).requireSuccess(manifestUrl)
        // Reading the body blocks: keep it off the caller's (Main) thread.
        val text = withContext(Dispatchers.IO) { response.use { it.body.readUtf8() } }
        val manifest = manifestJson.decodeFromString(UpdateManifest.serializer(), text)
        decide(manifest)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        UpdateCheck.Failed(e.message ?: e::class.simpleName.orEmpty())
    }

    private fun decide(m: UpdateManifest): UpdateCheck {
        if (m.versionCode <= currentVersionCode) return UpdateCheck.UpToDate
        if (m.minSdk > deviceSdk) return UpdateCheck.UpToDate
        if (!m.apkUrl.startsWith("https://")) return UpdateCheck.Failed("APK link must use https://")
        if (!Sha256Hex.matches(m.sha256)) return UpdateCheck.Failed("Checksum is not 64 hex characters")
        return UpdateCheck.Available(m)
    }
}
