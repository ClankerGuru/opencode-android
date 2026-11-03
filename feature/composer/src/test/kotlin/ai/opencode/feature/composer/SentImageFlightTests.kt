package ai.opencode.feature.composer

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * iOS `SentImageFlightTests`: geometry and ownership guards. The list may scroll on its own,
 * reuse rows, or cancel a send, but each image has one owner and meets its live slot.
 *
 * iOS hosts UIKit snapshot views in a window with a collection cell carrying landing markers;
 * here the copies are [SentImageFlight.Tile] poses, the cell is a moving origin, and its
 * markers are a lookup in root pixels. The cell's hidden `contentView` is
 * [SentImageFlight.hidesContent]; `isPrepared` (text or images) is the images' `isActive`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SentImageFlightTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun imageOnlySendKeepsItsPathWhenTheCellScrolls() {
        for (count in listOf(1, 2, 4)) {
            val rig = Rig(count)
            rig.images.prepare(rig.attachments, reduceMotion = false, window = rig.window, decode = rig.decode)
            assertTrue("An image-only send must prepare a flight", rig.images.isActive)
            assertTrue(rig.images.isSending)
            val sources = rig.images.tiles
            assertEquals(count, sources.size)
            val origins = sources.map { it.frame }
            assertTrue(rig.images.begin("message", rig::marker))
            val previousY = origins.map { it.top }.toMutableList()
            // The follower has its own curve. Copies must travel upward continuously and meet
            // their actual slots even when the follower has not finished yet.
            for (frame in 0..240) {
                val p = min(1f, frame / 120f)
                rig.cellY = 240f - 100f * (1f - (1f - p).pow(3))
                rig.images.step("message", rig::marker, progress = p)
                assertTrue(rig.images.hidesContent("message"))
                for (index in 0 until count) {
                    assertTrue(sources[index].frame.top <= previousY[index] + 0.01f)
                    previousY[index] = sources[index].frame.top
                    if (frame == 240) assertEquals(rig.marker(SentImageFlight.imageID("message", index)), sources[index].frame)
                }
            }
            rig.images.finish("message")
            assertFalse(rig.images.hidesContent("message"))
            assertTrue(rig.images.tiles.isEmpty())
            assertFalse(rig.images.isActive)
            assertFalse(rig.images.isSending)
        }
    }

    @Test fun cancellationAndReducedMotionLeaveNoSnapshots() {
        val rig = Rig(2)
        rig.images.prepare(rig.attachments, reduceMotion = true, window = rig.window, decode = rig.decode)
        assertTrue(rig.images.isActive)
        val reducedCopies = rig.images.tiles
        assertTrue(rig.images.begin("message", rig::marker))
        rig.images.step("message", rig::marker, progress = 0.5f)
        reducedCopies.forEachIndexed { index, copy ->
            assertEquals(rig.marker(SentImageFlight.imageID("message", index)), copy.frame)
            assertEquals(0.5f, copy.alpha)
        }
        rig.images.cancel()
        rig.images.prepare(rig.attachments, reduceMotion = false, window = rig.window, decode = rig.decode)
        rig.images.dissolvePrepared(null)
        assertTrue("An absent stale token cannot dissolve an image-only send", rig.images.isActive)
        val token = rig.images.preparedToken
        rig.images.prepare(rig.attachments, reduceMotion = false, window = rig.window, decode = rig.decode)
        rig.images.dissolvePrepared(token)
        assertTrue("A stale scheduled send cannot dissolve its replacement", rig.images.isActive)
        assertTrue(rig.images.begin("message", rig::marker))
        rig.images.cancel()
        assertFalse(rig.images.hidesContent("message"))
        assertTrue(rig.images.tiles.isEmpty())
        assertFalse(rig.images.isSending)
    }

    /** iOS checks the copy's mask in its own coordinates; here [SentImageFlight.Tile.clip] is in root px, so it moves with the copy. */
    @Test fun liftedPhotosKeepTheirClipWithThem() {
        val rig = Rig(1)
        rig.images.prepare(rig.attachments, reduceMotion = false, window = rig.window, decode = rig.decode)
        val copy = rig.images.tiles.first()
        assertTrue(rig.images.begin("message", rig::marker))
        val band: (String) -> Rect? = { id -> rig.marker(id)?.let { Rect(it.left - 20f, it.top, it.right + 20f, it.bottom) } }
        fun clip(lift: Float): Rect {
            rig.images.step("message", rig::marker, progress = 1f, clips = band, lift = lift)
            return copy.clip!!
        }
        val level = clip(lift = 0f)
        val levelY = copy.frame.top
        val lifted = clip(lift = 30f)
        // The photo aims 30 pt higher, and its clip goes with it: nothing cut off.
        assertTrue(abs((levelY - copy.frame.top) - 30f) < 0.01f)
        assertTrue(abs((level.top - lifted.top) - 30f) < 0.01f)
        assertTrue(abs(lifted.height - level.height) < 0.01f)
        rig.images.finish("message")
    }

    @Test fun endpointFollowsLateSlotResize() {
        val rig = Rig(1)
        rig.images.prepare(rig.attachments, reduceMotion = false, window = rig.window, decode = rig.decode)
        val copy = rig.images.tiles.first()
        assertTrue(rig.images.begin("message", rig::marker))
        rig.images.step("message", rig::marker, progress = 0.5f)
        rig.markerFrames[0] = Rect(80f, 20f, 280f, 220f)
        rig.cellY = 180f
        rig.images.step("message", rig::marker, progress = 1f)
        assertTrue(rig.images.isLanded("message"))
        assertEquals(rig.marker(SentImageFlight.imageID("message", 0)), copy.frame)
        rig.images.finish("message")
        assertFalse(rig.images.hidesContent("message"))
        assertTrue(rig.images.tiles.none { it === copy })
    }

    @Test fun missingDestinationDropsPreparedCopies() {
        val rig = Rig(2)
        rig.images.prepare(rig.attachments, reduceMotion = false, window = rig.window, decode = rig.decode)
        assertFalse(rig.images.begin("not-this-message", rig::marker))
        assertFalse(rig.images.isActive)
        assertFalse(rig.images.hidesContent("message"))
        assertTrue(rig.images.tiles.isEmpty())
    }

    @Test fun photosShareBubbleProgressDuringKeyboardMovement() {
        val rig = Rig(2)
        rig.images.prepare(rig.attachments, reduceMotion = false, window = rig.window, decode = rig.decode)
        val copies = rig.images.tiles
        val origins = copies.map { it.frame }
        assertTrue(rig.images.begin("message", rig::marker))
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            rig.cellY = 240f + 100f * p
            rig.images.step("message", rig::marker, progress = p)
            copies.forEachIndexed { index, copy ->
                val target = rig.marker(SentImageFlight.imageID("message", index))!!
                val normalized = (copy.frame.top - origins[index].top) / (target.top - origins[index].top)
                assertTrue("No per-photo delay or second spring may separate a photo from the send clock", abs(normalized - p) < 0.0001f)
            }
        }
        assertTrue(rig.images.isLanded("message"))
    }

    /** A window with a row at (10, 240, 370 × 90), its image slots 68 pt square, and the composer's 76 pt thumbnails at y 500. */
    private class Rig(count: Int) {
        val window = Rect(0f, 0f, 402f, 874f)
        val images = SentImageFlight()
        val cellX = 10f
        var cellY = 240f
        val attachments = List(count) { OpenCodeOutgoingAttachment(data = byteArrayOf(1), mime = "image/png") }
        val markerFrames = MutableList(count) { index -> Rect(30f + index * 80, 0f, 98f + index * 80, 68f) }
        val decode: (OpenCodeOutgoingAttachment) -> ImageBitmap? = { ImageBitmap(16, 16) }

        init {
            attachments.forEachIndexed { index, attachment ->
                images.register(attachment.id, Rect(10f + index * 80, 500f, 86f + index * 80, 576f))
            }
        }

        fun marker(id: String): Rect? = (0 until markerFrames.size)
            .firstOrNull { SentImageFlight.imageID("message", it) == id }
            ?.let { markerFrames[it].translate(cellX, cellY) }
    }
}
