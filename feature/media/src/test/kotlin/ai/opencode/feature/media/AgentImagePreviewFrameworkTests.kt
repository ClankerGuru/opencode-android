package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The `AgentImagePreviewTests` (`AgentImagePreviewTests.swift`) case that reads a real JPEG's
 * EXIF orientation through the platform decoder, under Robolectric's native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentImagePreviewFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    /** iOS writes `kCGImagePropertyOrientation` with ImageIO; here `ExifInterface` writes it into the encoded file. */
    private fun jpeg(width: Int, height: Int, orientation: Int): ByteArray {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 149, 0)) }
        val file = temporaryDirectory("agent-image-jpeg").apply { mkdirs() }.resolve("image.jpg")
        try {
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun aJPEGsShapeTurnsWithItsOrientation() {
        val upright = jpeg(400, 300, orientation = 1)
        val turned = jpeg(400, 300, orientation = 6)
        assertEquals(AgentImageSize(400.0, 300.0), AgentImageHeader.size(upright, count = minOf(4096, upright.size)))
        assertEquals("shown a quarter turn", AgentImageSize(300.0, 400.0), AgentImageHeader.size(turned, count = minOf(4096, turned.size)))
        assertEquals(AgentImageSize(400.0, 300.0), AgentImageHeader.jpegSize(upright))
    }
}

/**
 * The `AgentImageGrowthAndMarkerTests` cases whose unmeasured changes expire on the main
 * `Looper` (Robolectric's paused looper stands in for iOS's real sleeps).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentImageGrowthAndMarkerFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    private var clock = 1000.0

    /** Advances both the growth's clock and the main looper's. */
    private fun sleep(seconds: Double) {
        clock += seconds
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((seconds * 1000).toLong()))
    }

    @Test
    fun aReportAfterAGrowthEndedNeverJoinsIt() {
        for (end in listOf("settle", "finished")) {
            val growth = AgentImageRowGrowth(now = { clock })
            growth.beginGrowth()
            if (end == "settle") growth.settle() else growth.growthFinished()
            // Within the join window, but the growth is over: it waits for its own.
            growth.add(block = 1, delta = 80f)
            assertTrue("$end: not part of a finished growth", growth.isAwaitingGrowth)
            growth.measuredUnchanged()
            sleep(AgentImageRowGrowth.UNMEASURED_LIFETIME + 0.2)
            assertTrue("$end: every hold clears", growth.pending.isEmpty())
        }
    }

    @Test
    fun anUnmeasuredChangeExpiresOnItsOwn() {
        val growth = AgentImageRowGrowth(now = { clock })
        sleep(0.15)
        growth.add(block = 2, delta = 50f)
        // Frame updates of an unrelated growth don't keep it alive.
        repeat(5) { growth.update(remaining = 10f) }
        sleep(AgentImageRowGrowth.UNMEASURED_LIFETIME + 0.2)
        assertTrue(growth.pending.isEmpty())
    }
}
