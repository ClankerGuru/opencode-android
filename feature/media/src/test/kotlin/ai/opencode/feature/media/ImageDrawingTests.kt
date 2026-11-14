package ai.opencode.feature.media

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/** Port of iOS `ImageDrawingTests` (`ImageDrawingTests.swift`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageDrawingTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun undoingAllStrokesLeavesNothingToAttach() = runTest {
        var drawing = ImageDrawing()
        assertEquals(ImageDrawing.Ink.Red, drawing.ink)
        drawing = drawing.copy(strokes = drawing.strokes + ImageDrawing.Stroke(drawing.ink, listOf(Offset.Zero)))
        drawing = drawing.copy(ink = ImageDrawing.Ink.Yellow)
        drawing = drawing.copy(strokes = drawing.strokes + ImageDrawing.Stroke(drawing.ink, listOf(Offset.Zero)))
        drawing = drawing.undo()
        assertEquals(listOf(ImageDrawing.Ink.Red), drawing.strokes.map { it.ink })
        drawing = drawing.undo()
        assertFalse(drawing.changed)
        assertNull(drawing.attachment(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), UUID(0, 0).toString()))
    }

    @Test fun exportFlattensTheStrokeIntoANewBoundedJPEG() = runTest {
        val image = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = image.copy(Bitmap.Config.ARGB_8888, false)
        val drawing = ImageDrawing(strokes = listOf(ImageDrawing.Stroke(ImageDrawing.Ink.Red, listOf(Offset(200f, 500f), Offset(1800f, 500f)))))
        val id = UUID(0, 7).toString()
        val output = drawing.attachment(image, id)!!
        val flat = BitmapFactory.decodeByteArray(output.data, 0, output.data.size)
        assertEquals(id, output.id)
        assertEquals("image/jpeg", output.mime)
        assertEquals(1568, flat.width)
        assertEquals(784, flat.height)
        val center = flat.getPixel(flat.width / 2, flat.height / 2)
        assertTrue(Color.red(center) > 200 && Color.green(center) < 120 && Color.blue(center) < 120)
        assertTrue(image.sameAs(before))
    }
}

/**
 * Port of iOS `ImageDrawingFeatureTests`: the editor and the viewer that presents it. On
 * Robolectric for a real failed decode and an agent file model's main-thread store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageDrawingFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val image = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
    private val stroke = ImageDrawing.Stroke(ImageDrawing.Ink.Red, listOf(Offset(2f, 2f), Offset(30f, 20f)))

    private fun copy(id: UUID): OpenCodeOutgoingAttachment =
        OpenCodeOutgoingAttachment(id = id.toString(), data = byteArrayOf(0xFF.toByte(), 0xD8.toByte()), mime = "image/jpeg")

    private val added = ImageDrawingFeature.Action.Delegate(ImageDrawingFeature.Action.DelegateAction.Added)
    private val dismiss = ImageDrawingFeature.Action.Delegate(ImageDrawingFeature.Action.DelegateAction.Dismiss)

    private fun kotlinx.coroutines.test.TestScope.editor(
        accepts: Boolean = true,
        flattens: Boolean = true,
        offered: Locked<List<OpenCodeOutgoingAttachment>>,
    ) = TestStore(
        ImageDrawingFeature.State(image),
        ImageDrawingFeature { attachment ->
            offered.update { it + attachment }
            accepts
        },
        this,
    ) {
        this[UuidClient] = UuidClient.incrementing()
        this[ImageDrawingClient] = ImageDrawingClient(flatten = { _, _, id -> if (flattens) copy(UUID.fromString(id)) else null })
    }

    @Test fun strokesInkAndUndoChangeOnlyTheDrawing() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val store = editor(offered = offered)
        store.send(ImageDrawingFeature.Action.StrokeFinished(stroke)) { it.copy(drawing = it.drawing.copy(strokes = listOf(stroke))) }
        val yellow = ImageDrawing.Stroke(ImageDrawing.Ink.Yellow, listOf(Offset(5f, 5f)))
        store.send(ImageDrawingFeature.Action.InkSelected(ImageDrawing.Ink.Yellow)) { it.copy(drawing = it.drawing.copy(ink = ImageDrawing.Ink.Yellow)) }
        store.send(ImageDrawingFeature.Action.StrokeFinished(yellow)) { it.copy(drawing = it.drawing.copy(strokes = listOf(stroke, yellow))) }
        store.send(ImageDrawingFeature.Action.UndoTapped) { it.copy(drawing = it.drawing.copy(strokes = listOf(stroke))) }
        store.send(ImageDrawingFeature.Action.UndoTapped) { it.copy(drawing = it.drawing.copy(strokes = emptyList())) }
        store.send(ImageDrawingFeature.Action.AddTapped)
        store.finish()
        assertTrue(offered.current.isEmpty())
    }

    @Test fun cancelGoesAtOnceWithNothingDrawn() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val store = editor(offered = offered)
        store.send(ImageDrawingFeature.Action.CancelTapped)
        store.receive(dismiss)
        store.finish()
        assertTrue(offered.current.isEmpty())
    }

    @Test fun cancelAsksBeforeDiscardingADrawing() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val store = editor(offered = offered)
        store.send(ImageDrawingFeature.Action.StrokeFinished(stroke)) { it.copy(drawing = it.drawing.copy(strokes = listOf(stroke))) }
        store.send(ImageDrawingFeature.Action.CancelTapped) { it.copy(isConfirmingDiscard = true) }
        // Keep Drawing.
        store.send(ImageDrawingFeature.Action.IsConfirmingDiscardChanged(false)) { it.copy(isConfirmingDiscard = false) }
        store.send(ImageDrawingFeature.Action.CancelTapped) { it.copy(isConfirmingDiscard = true) }
        store.send(ImageDrawingFeature.Action.DiscardConfirmed) { it.copy(isConfirmingDiscard = false) }
        store.receive(dismiss)
        store.finish()
        assertTrue(offered.current.isEmpty())
    }

    @Test fun addToPromptHandsANewCopyToTheOpenerAndGoes() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val store = editor(offered = offered)
        store.send(ImageDrawingFeature.Action.StrokeFinished(stroke)) { it.copy(drawing = it.drawing.copy(strokes = listOf(stroke))) }
        store.send(ImageDrawingFeature.Action.AddTapped) { it.copy(isAdding = true) }
        store.receive(ImageDrawingFeature.Action.AddFinished(null))
        store.receive(added)
        store.receive(dismiss)
        store.finish()
        assertEquals(listOf(copy(UUID(0, 0))), offered.current)
    }

    @Test fun aCopyThatCantBeMadeSaysSoAndKeepsTheDrawing() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val store = editor(flattens = false, offered = offered)
        store.send(ImageDrawingFeature.Action.StrokeFinished(stroke)) { it.copy(drawing = it.drawing.copy(strokes = listOf(stroke))) }
        store.send(ImageDrawingFeature.Action.AddTapped) { it.copy(isAdding = true) }
        store.receive(ImageDrawingFeature.Action.AddFinished(ImageDrawingFeature.Failure.Flatten)) {
            it.copy(isAdding = false, failure = ImageDrawingFeature.Failure.Flatten)
        }
        store.send(ImageDrawingFeature.Action.FailureDismissed) { it.copy(failure = null) }
        store.finish()
        assertEquals("Couldn’t create the image. Try again.", ImageDrawingFeature.Failure.Flatten.message)
        assertTrue(offered.current.isEmpty())
    }

    @Test fun aPromptThatNoLongerTakesAttachmentsSaysSoAndKeepsTheDrawing() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val store = editor(accepts = false, offered = offered)
        store.send(ImageDrawingFeature.Action.StrokeFinished(stroke)) { it.copy(drawing = it.drawing.copy(strokes = listOf(stroke))) }
        store.send(ImageDrawingFeature.Action.AddTapped) { it.copy(isAdding = true) }
        store.receive(ImageDrawingFeature.Action.AddFinished(ImageDrawingFeature.Failure.Prompt)) {
            it.copy(isAdding = false, failure = ImageDrawingFeature.Failure.Prompt)
        }
        store.finish()
        assertEquals("The prompt that opened this image no longer takes attachments.", ImageDrawingFeature.Failure.Prompt.message)
        // Offered once; the opener declined it.
        assertEquals(listOf(copy(UUID(0, 0))), offered.current)
    }

    // The viewer

    private fun kotlinx.coroutines.test.TestScope.viewer(
        allowsDrawing: Boolean = true,
        page: TranscriptImagePreview.Page = TranscriptImagePreview.Page(id = "img_1", image = image, label = "Image"),
        suspends: Boolean = false,
        offered: Locked<List<OpenCodeOutgoingAttachment>>,
    ) = TestStore(
        TranscriptImageViewerFeature.State(TranscriptImagePreview(page.id, listOf(page)), isShowingControls = true, allowsDrawing = allowsDrawing),
        TranscriptImageViewerFeature { attachment ->
            offered.update { it + attachment }
            true
        },
        this,
    ) {
        this[UuidClient] = UuidClient.incrementing()
        this[ImageDrawingClient] = ImageDrawingClient(flatten = { _, _, id ->
            if (suspends) awaitCancellation()
            copy(UUID.fromString(id))
        })
    }

    private fun drawing(action: ImageDrawingFeature.Action) =
        TranscriptImageViewerFeature.Action.Drawing(PresentationAction.Presented(action))

    @Test fun drawOpensOnlyOnADecodedImageForAPromptThatTakesIt() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        viewer(allowsDrawing = false, offered = offered).send(TranscriptImageViewerFeature.Action.DrawTapped)
        val video = TranscriptImagePreview.Page(id = "img_video", image = image, label = "Video", isVideo = true)
        viewer(page = video, offered = offered).send(TranscriptImageViewerFeature.Action.DrawTapped)
        val loading = TranscriptImagePreview.Page(id = "img_loading", image = null, label = "Image", mediaHash = "abc")
        viewer(page = loading, offered = offered).send(TranscriptImageViewerFeature.Action.DrawTapped)

        val store = viewer(offered = offered)
        store.send(TranscriptImageViewerFeature.Action.DrawTapped) { it.copy(drawing = ImageDrawingFeature.State(image)) }
        store.finish()
        assertTrue(offered.current.isEmpty())
    }

    /**
     * iOS keeps a page's thumbnail when its original fails to decode (`originalFailed`). Android
     * opens on full-size originals only: a page whose image fails to decode is `loadFailed` with
     * no image, so Draw stays off.
     */
    @Test fun drawStaysOffOnAThumbnailWhoseOriginalFailedToDecode() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val page = TranscriptImagePreview.Page(id = "img_thumb", image = null, label = "Image", mediaHash = "abc")
        val store = viewer(page = page, offered = offered)
        store.send(TranscriptImageViewerFeature.Action.MediaLoaded("img_thumb", MediaReferenceLoadResult.Loaded(byteArrayOf(1, 2, 3)))) {
            it.copy(preview = it.preview.copy(pages = listOf(page.copy(loadFailed = true))))
        }
        store.send(TranscriptImageViewerFeature.Action.DrawTapped)
        store.finish()
        assertTrue(offered.current.isEmpty())
    }

    @Test fun theViewerClosesOnlyOnceAnAddedDrawingsEditorHasGone() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val store = viewer(offered = offered)
        store.send(TranscriptImageViewerFeature.Action.DrawTapped) { it.copy(drawing = ImageDrawingFeature.State(image)) }
        store.send(drawing(ImageDrawingFeature.Action.StrokeFinished(stroke))) {
            it.copy(drawing = it.drawing!!.copy(drawing = ImageDrawing(strokes = listOf(stroke))))
        }
        store.send(drawing(ImageDrawingFeature.Action.AddTapped)) { it.copy(drawing = it.drawing!!.copy(isAdding = true)) }
        store.receive(drawing(ImageDrawingFeature.Action.AddFinished(null)))
        store.receive(drawing(added)) { it.copy(closesAfterDrawing = true) }
        store.receive(drawing(dismiss)) { it.copy(drawing = null) }
        store.finish()
        // The view puts the viewer away once the editor has gone.
        assertFalse(store.state.isDismissed)
        assertEquals(listOf(copy(UUID(0, 0))), offered.current)
    }

    @Test fun closingTheEditorMidExportAddsNothing() = runTest {
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val store = viewer(suspends = true, offered = offered)
        store.send(TranscriptImageViewerFeature.Action.DrawTapped) { it.copy(drawing = ImageDrawingFeature.State(image)) }
        store.send(drawing(ImageDrawingFeature.Action.StrokeFinished(stroke))) {
            it.copy(drawing = it.drawing!!.copy(drawing = ImageDrawing(strokes = listOf(stroke))))
        }
        store.send(drawing(ImageDrawingFeature.Action.AddTapped)) { it.copy(drawing = it.drawing!!.copy(isAdding = true)) }
        store.send(TranscriptImageViewerFeature.Action.Drawing(PresentationAction.Dismiss)) { it.copy(drawing = null) }
        store.finish()
        assertTrue(offered.current.isEmpty())
    }

    @Test fun closingAFileEditorMidExportAddsNothing() {
        val gate = CompletableDeferred<Unit>()
        val offered = Locked(listOf<OpenCodeOutgoingAttachment>())
        val model = AgentFileViewerModel(
            reference = AgentFileReference(path = "/fixture/drawer.png"),
            server = OpenCodeServer(url = "https://fixture.invalid", username = "", password = ""),
            directory = null,
            context = RuntimeEnvironment.getApplication(),
        )
        withDependencies({
            this[UuidClient] = UuidClient.incrementing()
            this[ImageDrawingClient] = ImageDrawingClient(flatten = { _, _, id ->
                gate.await()
                copy(UUID.fromString(id))
            })
        }) {
            model.draw(image) { attachment ->
                offered.update { it + attachment }
                true
            }
        }
        // Held here so its export keeps running: the worst case, an editor whose effect outlives
        // its page (Android: the page's model is released).
        val editor = model.drawing!!
        editor.send(ImageDrawingFeature.Action.StrokeFinished(stroke))
        editor.send(ImageDrawingFeature.Action.AddTapped)
        assertTrue(editor.state.isAdding)
        model.release()
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The export landed and was turned away", ImageDrawingFeature.Failure.Prompt, editor.state.failure)
        assertTrue(offered.current.isEmpty())
        assertFalse(model.drawingAdded)
        editor.close()
    }
}
