package ai.opencode.feature.session.seams

import ai.opencode.core.dependencies.DependencyKey
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap

// Seams for the session screen scaffold (group VIEW-MAIN).

/**
 * SEAM: real type belongs to :feature:media (iOS `Core/DecodedImageCache.swift`, the
 * `transcriptImageCache` dependency); delete when that area merges.
 *
 * Decoded images by key, so a row never decodes the same bytes twice.
 */
class TranscriptImageCache(private val maxEntries: Int) {
    private val cache = LruCache<String, ImageBitmap>(maxEntries)

    fun image(key: String): ImageBitmap? = cache.get(key)

    fun insert(image: ImageBitmap, key: String) {
        cache.put(key, image)
    }

    companion object Key : DependencyKey<TranscriptImageCache> {
        override val liveValue = TranscriptImageCache(64)
        override val testValue = TranscriptImageCache(8)
    }
}
