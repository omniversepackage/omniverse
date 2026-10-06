package com.yodesla.omniverse.core.model

enum class AccountStatus { ACTIVE, EXPIRED, BANNED, DISABLED, AUTH_FAILED, UNKNOWN }

data class AccountInfo(
    val status: AccountStatus,
    /** null = never expires / unknown. */
    val expiresAtMs: Long?,
    /** Simultaneous streams the account allows. null = unknown. PLAN.md rule 7: always respect it. */
    val maxConnections: Int?,
    /** Streams open right now on this account (all devices). null = unknown. */
    val activeConnections: Int?,
    val isTrial: Boolean,
    /** e.g. ["ts", "m3u8"]. Empty = unknown (assume ts). */
    val allowedOutputFormats: List<String>,
    /** IANA zone of the provider server (catch-up URLs use server-local time). */
    val serverTimeZone: String?,
    val serverNowMs: Long?,
)
