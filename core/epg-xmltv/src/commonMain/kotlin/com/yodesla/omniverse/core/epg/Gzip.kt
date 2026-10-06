package com.yodesla.omniverse.core.epg

import okio.BufferedSource

/**
 * If [source] starts with the gzip magic bytes `1f 8b`, returns a source that transparently
 * decompresses it; otherwise returns [source] unchanged. Peeks only, consumes nothing.
 * The returned source is not closed by this function.
 */
expect fun maybeGunzip(source: BufferedSource): BufferedSource
