package com.yodesla.omniverse.feature.live.player

import com.yodesla.omniverse.player.FailureKind

/**
 * Task 94: decides when a stream failure means "a second connection would be refused too", so the
 * session stops pre-loading the next channel. The engine already buckets 401/403/429/509 (auth and
 * connection/device limits — exactly the too-many-connections case) into [FailureKind.DENIED]; every
 * other failure (network, 404, decoder) is channel-specific and says nothing about connection count,
 * so pre-loading stays on for those.
 */
object PreloadGuard {
    fun shouldDisable(kind: FailureKind): Boolean = kind == FailureKind.DENIED
}
