package ai.opencode.feature.media

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.collectState
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The opener's prompt (iOS `ImageDrawingFeature.Add`): takes the drawn copy on the main thread,
 * or answers false once it no longer takes attachments.
 */
typealias ImageDrawingAdd = (OpenCodeOutgoingAttachment) -> Boolean

/**
 * Draw on Image (iOS `ImageDrawingFeature`): marks up a copy of one image and hands it to the
 * prompt that opened the viewer ([add]). It never sends, and the original stays as it was.
 *
 * iOS dismisses itself through `@Dependency(\.dismiss)`; here it asks its presenter with
 * [Action.DelegateAction.Dismiss], and dropping the editor cancels its export.
 */
class ImageDrawingFeature(private val add: ImageDrawingAdd) : Reducer<ImageDrawingFeature.State, ImageDrawingFeature.Action> {
    /** [image] compares by identity (a `Bitmap` has no value equality). */
    data class State(
        val image: Bitmap,
        val drawing: ImageDrawing = ImageDrawing(),
        val isAdding: Boolean = false,
        val isConfirmingDiscard: Boolean = false,
        val failure: Failure? = null,
    )

    enum class Failure(val message: String) {
        /** The drawn copy couldn't be made. */
        Flatten("Couldn’t create the image. Try again."),

        /** The prompt has gone (another session) or is answering a request. */
        Prompt("The prompt that opened this image no longer takes attachments."),
    }

    sealed interface Action {
        data class InkSelected(val ink: ImageDrawing.Ink) : Action

        /** A stroke the finger or stylus lifted from; a cancelled touch never gets here. */
        data class StrokeFinished(val stroke: ImageDrawing.Stroke) : Action
        data object UndoTapped : Action
        data object CancelTapped : Action
        data object DiscardConfirmed : Action
        data class IsConfirmingDiscardChanged(val isConfirmingDiscard: Boolean) : Action
        data object AddTapped : Action

        /** Null once the copy is in the prompt. */
        data class AddFinished(val failure: Failure?) : Action
        data object FailureDismissed : Action
        data class Delegate(val action: DelegateAction) : Action

        sealed interface DelegateAction {
            /** The drawn copy is in the opener's prompt. */
            data object Added : DelegateAction

            /** Put the editor away. */
            data object Dismiss : DelegateAction
        }
    }

    private enum class CancelID { Add }

    private val dismiss: Effect<Action> = Effect.send(Action.Delegate(Action.DelegateAction.Dismiss))

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.InkSelected -> Reduced(state.copy(drawing = state.drawing.copy(ink = action.ink)))

        is Action.StrokeFinished ->
            if (state.isAdding) {
                Reduced(state)
            } else {
                Reduced(state.copy(drawing = state.drawing.copy(strokes = state.drawing.strokes + action.stroke)))
            }

        Action.UndoTapped -> if (state.isAdding) Reduced(state) else Reduced(state.copy(drawing = state.drawing.undo()))

        Action.CancelTapped -> when {
            state.isAdding -> Reduced(state)
            !state.drawing.changed -> Reduced(state, dismiss)
            else -> Reduced(state.copy(isConfirmingDiscard = true))
        }

        Action.DiscardConfirmed -> Reduced(state.copy(isConfirmingDiscard = false), dismiss)

        is Action.IsConfirmingDiscardChanged -> Reduced(state.copy(isConfirmingDiscard = action.isConfirmingDiscard))

        Action.AddTapped -> {
            if (!state.drawing.changed || state.isAdding) {
                Reduced(state)
            } else {
                val imageDrawing = dependency(ImageDrawingClient)
                val id = dependency(UuidClient).next().toString()
                val drawing = state.drawing
                val image = state.image
                Reduced(
                    state.copy(isAdding = true),
                    Effect.run<Action>(onError = { _, send -> send(Action.AddFinished(Failure.Flatten)) }) { send ->
                        val attachment = imageDrawing.flatten(drawing, image, id)
                        if (attachment == null) {
                            send(Action.AddFinished(Failure.Flatten))
                            return@run
                        }
                        // Checked on the main thread with the add, so an editor put away
                        // meanwhile adds nothing.
                        currentCoroutineContext().ensureActive()
                        send(Action.AddFinished(if (add(attachment)) null else Failure.Prompt))
                    }.cancellable(CancelID.Add, cancelInFlight = true),
                )
            }
        }

        is Action.AddFinished -> {
            val failure = action.failure
            if (failure == null) {
                Reduced(state, Effect.merge(Effect.send(Action.Delegate(Action.DelegateAction.Added)), dismiss))
            } else {
                Reduced(state.copy(isAdding = false, failure = failure))
            }
        }

        Action.FailureDismissed -> Reduced(state.copy(failure = null))

        is Action.Delegate -> Reduced(state)
    }
}

/**
 * The editor (iOS `ImageDrawingEditor`): its own full-screen window, so the gallery's paging,
 * zoom, dismiss gestures and Back stay out of drawing. Back is Cancel.
 */
@Composable
fun ImageDrawingEditor(store: StoreOf<ImageDrawingFeature.State, ImageDrawingFeature.Action>) {
    val state by store.collectState()
    val enabled = !state.isAdding
    Dialog(
        onDismissRequest = { store.send(ImageDrawingFeature.Action.CancelTapped) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
                OpenCodeIconButton(
                    symbol = SFSymbol.Xmark,
                    contentDescription = "Cancel",
                    onClick = { store.send(ImageDrawingFeature.Action.CancelTapped) },
                    modifier = Modifier.testTag("image-drawing-cancel"),
                    enabled = enabled,
                )
                Spacer(Modifier.weight(1f))
                OpenCodeIconButton(
                    symbol = SFSymbol.ArrowUturnBackward,
                    contentDescription = "Undo",
                    onClick = { store.send(ImageDrawingFeature.Action.UndoTapped) },
                    modifier = Modifier.testTag("image-drawing-undo"),
                    enabled = enabled && state.drawing.changed,
                )
                Spacer(Modifier.width(8.dp))
                OpenCodeIconButton(
                    symbol = SFSymbol.Checkmark,
                    contentDescription = "Add to Prompt",
                    onClick = { store.send(ImageDrawingFeature.Action.AddTapped) },
                    modifier = Modifier.testTag("image-drawing-add"),
                    enabled = enabled && state.drawing.changed,
                )
            }
            AndroidView(
                factory = ::InkCanvas,
                update = { canvas ->
                    canvas.image = state.image
                    canvas.drawing = state.drawing
                    canvas.isEnabled = enabled
                    canvas.commit = { store.send(ImageDrawingFeature.Action.StrokeFinished(it)) }
                    canvas.invalidate()
                },
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("image-drawing-canvas"),
            )
            Surface(
                shape = OpenCodeShape.control,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
            ) {
                Row(Modifier.padding(horizontal = 6.dp)) {
                    ImageDrawing.Ink.entries.forEach { ink ->
                        val selected = ink == state.drawing.ink
                        Box(
                            Modifier
                                .size(48.dp)
                                .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                    store.send(ImageDrawingFeature.Action.InkSelected(ink))
                                }
                                .semantics { contentDescription = ink.name },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Box(Modifier.size(30.dp).border(2.dp, Color.White, CircleShape))
                            Box(Modifier.size(24.dp).background(Color(ink.argb), CircleShape).border(1.dp, Color.Gray, CircleShape))
                        }
                    }
                }
            }
        }
        if (state.isConfirmingDiscard) {
            OpenCodeAlertDialog(
                onDismissRequest = { store.send(ImageDrawingFeature.Action.IsConfirmingDiscardChanged(false)) },
                title = { Text("Discard drawing?") },
                confirmButton = {
                    TextButton(onClick = { store.send(ImageDrawingFeature.Action.DiscardConfirmed) }) {
                        Text("Discard Drawing", color = OpenCodeTheme.colors.destructive)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { store.send(ImageDrawingFeature.Action.IsConfirmingDiscardChanged(false)) }) {
                        Text("Keep Drawing")
                    }
                },
            )
        }
        state.failure?.let { failure ->
            OpenCodeAlertDialog(
                onDismissRequest = { store.send(ImageDrawingFeature.Action.FailureDismissed) },
                title = { Text("Couldn’t Add Drawing") },
                text = { Text(failure.message) },
                confirmButton = {
                    TextButton(onClick = { store.send(ImageDrawingFeature.Action.FailureDismissed) }) { Text("OK") }
                },
            )
        }
    }
}

/**
 * Owns touch sampling (historical points included) and the live stroke; only a finished stroke
 * reaches the reducer ([commit]). A cancelled touch never does (iOS `InkCanvas`).
 */
private class InkCanvas(context: Context) : View(context) {
    var image: Bitmap? = null
        set(value) {
            if (field !== value) {
                field = value
                fit()
            }
        }
    var drawing = ImageDrawing()
    var commit: (ImageDrawing.Stroke) -> Unit = {}
    private var live: MutableList<Offset>? = null
    private var liveInk = ImageDrawing.Ink.Red
    private var pointer = -1
    private val frame = RectF()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scale = 1f

    init {
        contentDescription = "Drawing canvas"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

    private fun fit() {
        val image = image ?: return
        scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        frame.set(0f, 0f, image.width * scale, image.height * scale)
        frame.offset((width - frame.width()) / 2, (height - frame.height()) / 2)
        live = null
    }

    override fun onDraw(canvas: Canvas) {
        val image = image ?: return
        canvas.save()
        canvas.clipRect(frame)
        canvas.translate(frame.left, frame.top)
        canvas.scale(scale, scale)
        canvas.drawBitmap(image, 0f, 0f, photoPaint)
        val pen = minOf(image.width, image.height) * 0.01f
        drawing.strokes.forEach { it.draw(canvas, pen, inkPaint) }
        live?.let { ImageDrawing.Stroke(liveInk, it).draw(canvas, pen, inkPaint) }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility") // A direct manipulation surface, not a button.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || image == null) {
            live = null
            return false
        }
        fun point(x: Float, y: Float) = Offset((x - frame.left) / scale, (y - frame.top) / scale)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!frame.contains(event.x, event.y)) return false
                pointer = event.getPointerId(0)
                liveInk = drawing.ink
                live = mutableListOf(point(event.x, event.y))
            }
            MotionEvent.ACTION_CANCEL -> live = null
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(pointer)
                val points = live
                if (index >= 0 && points != null) {
                    fun append(p: Offset) { if (points.last() != p) points += p }
                    for (i in 0 until event.historySize) append(point(event.getHistoricalX(index, i), event.getHistoricalY(index, i)))
                    append(point(event.getX(index), event.getY(index)))
                    if (event.actionMasked != MotionEvent.ACTION_MOVE && event.actionIndex == index) {
                        if (event.flags and MotionEvent.FLAG_CANCELED == 0) commit(ImageDrawing.Stroke(liveInk, points.toList()))
                        live = null
                    }
                }
            }
        }
        invalidate()
        return true
    }
}
