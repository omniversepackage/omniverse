package com.yodesla.omniverse.core.net

import com.yodesla.omniverse.core.model.Redact
import com.yodesla.omniverse.core.source.SourceException

/**
 * Throws [SourceException.Http] for non-2xx statuses (closing this response first).
 * 2xx responses are returned unchanged; the caller still owns closing them.
 */
fun HttpResponse.requireSuccess(url: String): HttpResponse {
    if (status in 200..299) return this
    close()
    throw SourceException.Http(status, "HTTP $status for ${Redact.text(url)}")
}
