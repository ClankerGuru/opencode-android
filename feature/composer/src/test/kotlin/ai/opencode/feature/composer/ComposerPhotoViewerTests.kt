package ai.opencode.feature.composer

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.TranscriptImageViewerFeature
import ai.opencode.feature.media.TranscriptImageViewerFeature.Action
import ai.opencode.feature.media.displayTitle
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.PastedText
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.UUID

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

    @Test fun aVideoFramesPageIsMarkedAsVideo() {
        val photo = photo(1)
        val video = OpenCodeOutgoingAttachment(id = "video", data = jpeg(20, 20), mime = "image/jpeg", videoDuration = 10.0)
        val pages = ComposerPhotoViewer.preview(listOf(photo, video), selected = photo.id)!!.pages
        assertEquals(listOf(false, true), pages.map { it.isVideo })
    }

    @Test fun removeHandsThePhotoBackAndPagesOnThenDiscardsTheLast() = runTest {
        val photos = listOf(photo(1), photo(2), photo(3))
        val preview = ComposerPhotoViewer.preview(photos, selected = photos[1].id)!!
        val removed = mutableListOf<String>()
        val haptics = mutableListOf<String>()
        val store = TestStore(
            TranscriptImageViewerFeature.State(preview, isShowingControls = true, allowsRemove = true),
            ComposerPhotoViewerFeature { id -> removed += id },
            this,
        ) {
            this[HapticsClient] = HapticsClient(play = { haptics += it.name })
        }
        val ids = photos.map { ComposerPhotoViewer.mediaID(it.id) }

        // The middle photo goes; the next one takes its place.
        store.send(Action.RemoveTapped) {
            it.copy(preview = TranscriptImagePreview(ids[1], listOf(preview.pages[0], preview.pages[2])), selectedID = ids[2])
        }
        store.receive(Action.Delegate(Action.DelegateAction.Removed(ids[1])))
        store.finish()
        assertEquals(listOf(photos[1].id), removed)

        // The last page steps back to the one before it.
        store.send(Action.RemoveTapped) {
            it.copy(preview = TranscriptImagePreview(ids[1], listOf(preview.pages[0])), selectedID = ids[0])
        }
        store.receive(Action.Delegate(Action.DelegateAction.Removed(ids[2])))

        // The only photo left: the viewer goes without flying home.
        store.send(Action.RemoveTapped) { it.copy(isDiscarded = true) }
        store.receive(Action.Delegate(Action.DelegateAction.Removed(ids[0])))
        store.send(Action.RemoveTapped)
        store.finish()
        assertEquals(listOf(photos[1].id, photos[2].id, photos[0].id), removed)
        assertTrue(haptics.isEmpty())
    }

    @Test fun transcriptViewersCannotRemove() = runTest {
        val preview = TranscriptImagePreview("img-1", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), "shot.png")
        val store = TestStore(TranscriptImageViewerFeature.State(preview), TranscriptImageViewerFeature(), this)
        assertFalse(store.state.allowsRemove)
        store.send(Action.RemoveTapped)
    }
}
