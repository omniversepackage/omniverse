package com.yodesla.omniverse.app

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toPath

/**
 * Task 87 — cold-start posters. The app previously ran on Coil's defaults; these values pin the
 * cache to a known directory and a fixed 250 MB so posters survive a cold start on disk instead
 * of re-downloading at full resolution every launch.
 *
 * Coil 3 has no `respectCacheHeaders` switch: its default [coil3.network.CacheStrategy] already
 * ignores provider `Cache-Control: no-cache/no-store` headers (it writes any 2xx response and
 * serves the disk copy unconditionally), which is exactly the behaviour this app needs.
 */
object ImageCacheConfig {
    /** Disk cache lives in the app's own cache dir under a stable, named folder. */
    const val DISK_DIR_NAME = "image_cache"

    /** Fixed 250 MB — Coil's own default clamps to 2% of free space, which collapses to 10 MB
     *  on a nearly-full Shield and silently drops posters between launches. */
    const val DISK_MAX_BYTES = 250L * 1024 * 1024

    /** ~20% of the app's memory class for decoded bitmaps (Coil's non-low-RAM default). */
    const val MEMORY_PERCENT = 0.20

    /** Pure form of the memory-cache sizing rule, unit-testable without a Context. */
    fun memoryCacheBytes(totalMemoryBytes: Long): Long = (totalMemoryBytes * MEMORY_PERCENT).toLong()
}

/** The app-wide Coil loader: named 250 MB disk cache + 20% memory cache. The OkHttp engine is
 *  picked up automatically from coil-network-okhttp's ServiceLoader registration. */
fun newAppImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .diskCache {
            DiskCache.Builder()
                .directory((context.cacheDir.absolutePath + "/" + ImageCacheConfig.DISK_DIR_NAME).toPath())
                .maxSizeBytes(ImageCacheConfig.DISK_MAX_BYTES)
                .build()
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, ImageCacheConfig.MEMORY_PERCENT)
                .build()
        }
        .build()

/** Installs [newAppImageLoader] as the singleton every AsyncImage in the app resolves to. */
fun installAppImageLoader() {
    SingletonImageLoader.setSafe(object : SingletonImageLoader.Factory {
        override fun newImageLoader(context: coil3.PlatformContext): ImageLoader = newAppImageLoader(context)
    })
}
