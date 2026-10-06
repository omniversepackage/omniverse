package com.yodesla.omniverse.core.hooks

/*
 * Seams for a future B2B / cloud edition (PLAN.md §10). Kory's build ships ONLY the NoOp
 * implementations: no backend, no telemetry, nothing leaves the device (rule 1).
 * A white-label build swaps implementations via DI without touching feature code.
 */

/** Remote settings a provider could push: portal/DNS failover list, feature flags, announcements. */
interface RemoteConfigProvider {
    /** Ordered server URLs to try for locked-portal brands. Empty = use what the user typed. */
    suspend fun portalServers(): List<String>
    suspend fun flag(name: String, default: Boolean): Boolean
    suspend fun announcement(): String?
}

/** Device activation / licensing for commercial brands. */
interface LicenseProvider {
    suspend fun status(): LicenseStatus
}

enum class LicenseStatus { NOT_REQUIRED, ACTIVE, EXPIRED, NOT_ACTIVATED }

/** Product analytics. Must never receive credentials or stream URLs. */
interface AnalyticsSink {
    fun event(name: String, params: Map<String, String> = emptyMap())
}

/** Cross-device sync of per-profile user data (favorites, progress). Viewer-owned clouds only. */
interface CloudSyncProvider {
    val isAvailable: Boolean
    suspend fun push(snapshot: ByteArray)
    suspend fun pull(): ByteArray?
}

interface CrashReporter {
    fun record(throwable: Throwable, context: Map<String, String> = emptyMap())
}

object NoOpRemoteConfig : RemoteConfigProvider {
    override suspend fun portalServers(): List<String> = emptyList()
    override suspend fun flag(name: String, default: Boolean): Boolean = default
    override suspend fun announcement(): String? = null
}

object NoOpLicense : LicenseProvider {
    override suspend fun status() = LicenseStatus.NOT_REQUIRED
}

object NoOpAnalytics : AnalyticsSink {
    override fun event(name: String, params: Map<String, String>) = Unit
}

object NoOpCloudSync : CloudSyncProvider {
    override val isAvailable = false
    override suspend fun push(snapshot: ByteArray) = Unit
    override suspend fun pull(): ByteArray? = null
}

object NoOpCrashReporter : CrashReporter {
    override fun record(throwable: Throwable, context: Map<String, String>) = Unit
}
