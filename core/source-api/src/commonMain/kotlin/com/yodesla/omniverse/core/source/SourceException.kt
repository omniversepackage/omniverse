package com.yodesla.omniverse.core.source

/**
 * The only exception type a ContentSource may throw. Messages must be credential-free (use Redact).
 * The UI maps each subtype to a friendly, actionable message.
 */
sealed class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Wrong username/password, or account banned/disabled. */
    class AuthFailed(message: String = "Login rejected by provider") : SourceException(message)

    /** Account valid but expired. */
    class Expired(message: String = "Subscription expired") : SourceException(message)

    /** DNS/connect/timeout/reset. Usually transient: retry with backoff. */
    class Network(message: String, cause: Throwable? = null) : SourceException(message, cause)

    /** Non-2xx HTTP status. 429/5xx are retryable. */
    class Http(val status: Int, message: String) : SourceException(message) {
        val retryable: Boolean get() = status == 429 || status >= 500
    }

    /** Body wasn't what we expected (HTML error page, truncated JSON...). */
    class BadResponse(message: String, cause: Throwable? = null) : SourceException(message, cause)

    /** The item doesn't exist on the provider any more. */
    class NotFound(message: String) : SourceException(message)

    /** This source can't do that (e.g. catch-up on a channel without archive). */
    class Unsupported(message: String) : SourceException(message)
}
