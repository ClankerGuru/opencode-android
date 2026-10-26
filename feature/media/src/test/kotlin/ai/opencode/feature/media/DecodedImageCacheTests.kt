package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.testing.TestIsolationRule
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Port of iOS `DecodedImageCacheTests.swift`: costs are real `rowBytes × height`, so it runs
 * under Robolectric's native graphics. `attachmentAndMarkdownUseTheSameBudgetAndPreserveOriginalDimensions`
 * decodes through `CachedImage` (`:feature:session`) and `:server`'s Markdown image read, so it
 * belongs to those modules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecodedImageCacheTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun image(size: Int, color: Int = Color.RED): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun retainedPixelsPlateauAtTheSharedBudget() {
        val cache = DecodedImageCache()
        var unboundedBytes = 0L
        for (index in 0 until 8) {
            val image = image(2048, if (index % 2 == 0) Color.RED else Color.BLUE)
            unboundedBytes += DecodedImageCache.pixelBytes(image)
            cache.insert(image, "${if (index % 2 == 0) "markdown" else "attachment"}:$index")
            assertTrue(cache.retainedBytes <= cache.byteLimit)
        }
        println("PERF_IMAGES unboundedBytes=$unboundedBytes retainedBytes=${cache.retainedBytes} budget=${cache.byteLimit}")
        assertTrue(cache.retainedBytes > 0)
        assertTrue(cache.retainedBytes < unboundedBytes)
        assertNull(cache.image("markdown:0"))
        assertNotNull(cache.image("attachment:7"))
    }

    @Test
    fun replacesCostsAndEvictsLeastRecentlyUsed() {
        val small = image(32)
        val cost = DecodedImageCache.pixelBytes(small)
        val cache = DecodedImageCache(byteLimit = cost * 2)
        cache.insert(small, "a")
        cache.insert(small, "b")
        cache.image("a")
        cache.insert(small, "c")
        assertNull(cache.image("b"))
        assertTrue(cache.image("a") === small)
        cache.insert(small, "a")
        assertEquals(cost * 2, cache.retainedBytes)
        cache.insert(image(128), "a")
        assertNull(cache.image("a"))
        assertEquals(cost, cache.retainedBytes)
    }

    /**
     * iOS posts `didReceiveMemoryWarningNotification`, which every cache observes; Android's live
     * cache registers `ComponentCallbacks2` on the app, so this drives the live value with the
     * Robolectric application's `onTrimMemory`.
     */
    @Test
    fun memoryWarningPurgesEntriesAndPixelCost() {
        val application = RuntimeEnvironment.getApplication()
        val cache = withDependencies({ this[AppContext] = application }) { DecodedImageCache.liveValue }
        cache.removeAll()
        cache.insert(image(32), "markdown:a")
        cache.insert(image(64), "attachment:b")
        assertTrue(cache.retainedBytes > 0)
        application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(0, cache.retainedBytes)
        assertNull(cache.image("markdown:a"))
        assertNull(cache.image("attachment:b"))
        cache.insert(image(32), "after-warning")
        assertTrue(cache.retainedBytes > 0)
        cache.removeAll()
    }
}
