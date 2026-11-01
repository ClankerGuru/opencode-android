package ai.opencode.feature.composer

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.media.displayTitle
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.PastedText
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * iOS `ComposerPhotoViewerTests`. `removeHandsThePhotoBackAndPagesOnThenDiscardsTheLast` and
 * `transcriptViewersCannotRemove` test the viewer's Remove, which `:feature:media`'s
 * `TranscriptImageViewerFeature` doesn't have yet; they wait on it (see the Tests row).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerPhotoViewerTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private fun photo(id: Int, width: Int = 40, height: Int = 30) = OpenCodeOutgoingAttachment(
        id = String.format("00000000-0000-0000-0000-%012d", id),
        data = jpeg(width, height),
        mime = "image/jpeg",
        name = null,
    )

    @Test fun mediaIDsNeverMatchTheAttachmentAndRoundTrip() {
        val id = UUID.randomUUID().toString()
        val mediaID = ComposerPhotoViewer.mediaID(id)
        assertNotEquals(id, mediaID)
        assertEquals(id, ComposerPhotoViewer.attachmentID(mediaID))
        assertNull(ComposerPhotoViewer.attachmentID(id))
    }

    @Test fun previewPagesThroughEveryPendingPhotoOpenedOnTheTappedOne() {
        val first = photo(1, width = 40, height = 30)
        val paste = PastedText.attachment("line\n".repeat(400))
        val second = photo(2, width = 20, height = 50)
        val preview = ComposerPhotoViewer.preview(listOf(first, paste, second), selected = second.id)
        assertNotNull(preview)
        preview!!

        assertEquals(ComposerPhotoViewer.mediaID(second.id), preview.id)
        assertEquals(listOf(first, second).map { ComposerPhotoViewer.mediaID(it.id) }, preview.pages.map { it.id })
        assertEquals(listOf("Image attachment 1 of 2", "Image attachment 2 of 2"), preview.pages.map { it.label })
        assertTrue("Generic labels show no title", preview.pages.all { it.displayTitle == null })
        val size = AttachmentFileType.byteCount(second.data.size.toLong())
        assertEquals("JPEG · 20 × 50 · $size", preview.pages[1].detail)
        assertNull(ComposerPhotoViewer.preview(listOf(first), selected = second.id))
    }
}
