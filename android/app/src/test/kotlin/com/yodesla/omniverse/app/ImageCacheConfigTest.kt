package com.yodesla.omniverse.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCacheConfigTest {
    @Test
    fun diskCacheIsNamedAndFixedAt250Mb() {
        assertEquals("image_cache", ImageCacheConfig.DISK_DIR_NAME)
        assertEquals(250L * 1024 * 1024, ImageCacheConfig.DISK_MAX_BYTES)
    }

    @Test
    fun memoryCacheIsTwentyPercent() {
        assertEquals(0.20, ImageCacheConfig.MEMORY_PERCENT, 1e-9)
        assertEquals(20L * 1024 * 1024, ImageCacheConfig.memoryCacheBytes(100L * 1024 * 1024))
        assertEquals(0L, ImageCacheConfig.memoryCacheBytes(0L))
    }
}
