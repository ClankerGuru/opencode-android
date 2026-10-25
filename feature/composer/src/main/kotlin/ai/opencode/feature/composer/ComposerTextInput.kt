package ai.opencode.feature.composer

import ai.opencode.core.diagnostics.PerfFrameLatency
import ai.opencode.core.ui.BackSwipeRules
import ai.opencode.core.ui.LocalDrawerKeyboardSignals
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.drawerKeyboardField
import ai.opencode.server.model.PastedText
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The composer tunables the text input reads (iOS `ComposerTunables`, `Session/ComposerViews.swift`):
 * their baked defaults. The session area's composer tunables keep the same names.
 */
object ComposerTextInputTunables {
    /**
     * `composer.spacing.expandedTopInset`: the expanded composer's resting top inset. It
     * scrolls away with the text, rather than clipping text below a permanent empty band.
     */
    val expandedTopInset: Dp = 16.dp

    /** `composer.controls.swipeUpDistance`: swipe-up focus distance (dp). */
    const val SWIPE_UP_DISTANCE = 18f

    /** `composer.controls.swipeUpVelocity`: swipe-up flick velocity (dp/s). */
    const val SWIPE_UP_VELOCITY = 260f
}

/**
 * Rules for a downward swipe on the focused composer dismissing the keyboard, and an upward
 * swipe on the unfocused composer pulling it up (iOS `ComposerSwipeDown`). Distances in dp
 * (iOS pt), velocities in dp/s; `translation.y > 0` is downward.
 */
object ComposerSwipeDown {
    const val MINIMUM_DISTANCE = 18f
    const val PREDICTED_DISTANCE = 60f
    const val MINIMUM_FLING_DISTANCE = 10f
    const val VERTICAL_RATIO = 1.3f

    fun shouldDismissKeyboard(
        translation: Offset,
        predictedEndTranslation: Offset = Offset.Zero,
        isFocused: Boolean,
        isHoldingMic: Boolean = false,
        isScrollingUp: Boolean = false,
    ): Boolean {
        if (!isFocused || isHoldingMic || isScrollingUp) return false
        val dy = translation.y
        val dx = translation.x
        if (dy <= 0 || abs(dy) <= abs(dx) * VERTICAL_RATIO) return false
        return dy >= MINIMUM_DISTANCE || (dy >= MINIMUM_FLING_DISTANCE && predictedEndTranslation.y >= PREDICTED_DISTANCE)
    }

    fun shouldFocusKeyboard(
        translation: Offset,
        predictedEndTranslation: Offset = Offset.Zero,
        velocityY: Float? = null,
        isFocused: Boolean,
        isHoldingMic: Boolean = false,
        isScrollingDown: Boolean = false,
        minimumDistance: Float = ComposerTextInputTunables.SWIPE_UP_DISTANCE,
        minimumFlickVelocity: Float = ComposerTextInputTunables.SWIPE_UP_VELOCITY,
    ): Boolean {
        if (isFocused || isHoldingMic || isScrollingDown) return false
        val dy = translation.y
        val dx = translation.x
        if (dy >= 0 || abs(dy) <= abs(dx) * VERTICAL_RATIO) return false
        val distance = -dy
        if (distance >= minimumDistance) return true
        if (distance < MINIMUM_FLING_DISTANCE) return false
        if (velocityY != null) return -velocityY >= minimumFlickVelocity
        val predictedExtra = max(0f, -(predictedEndTranslation.y - dy))
        return predictedExtra >= minimumFlickVelocity * 0.15f || -predictedEndTranslation.y >= max(PREDICTED_DISTANCE, minimumDistance)
    }
}

/**
 * Whether a drag over the composer belongs to a control inside it (the level, +, a held mic,
 * an open panel), iOS `ComposerSwipeOwnership`. Decided once, as the drag first reports, and
 * kept through its last callback: the control's own gesture can end first, and the drag must
 * not pass to the keyboard's dismissal. A plain object, owned by the composer view.
 */
class ComposerSwipeOwnership {
    private var start: Offset? = null
    private var focusedStart: Offset? = null
    private var yields = false
    private var focusedAtStart = false

    /** [yieldsNow] is read only for a drag not seen before. */
    fun yields(startLocation: Offset, isFocused: Boolean = false, yieldsNow: () -> Boolean): Boolean {
        if (start != startLocation) {
            start = startLocation
            focusedStart = startLocation
            focusedAtStart = isFocused
            yields = yieldsNow()
        }
        return yields
    }

    fun beganFocused(startLocation: Offset, isFocused: Boolean): Boolean {
        if (focusedStart != startLocation) {
            focusedStart = startLocation
            focusedAtStart = isFocused
        }
        return focusedAtStart
    }

    /** The drag ended: the next one decides afresh. */
    fun ended() {
        start = null
        focusedStart = null
        yields = false
        focusedAtStart = false
    }
}

/**
 * The composer's text handed off to its bubble (iOS `ComposerSendTransition`). On iOS it
 * snapshots the field's pixels and flies them into the transcript cell (`SendLandingProbe`
 * markers). Here the field reports its laid-out text and where it sits ([ComposerTextInput]);
 * [prepare] captures the send's words, their layout and their visible rect as a [Prepared],
 * which the session screen's flight overlay draws and flies toward the bubble (it owns both
 * ends, as the transcript's list and the composer meet there). Flights overlap: each is keyed
 * by the message it lands on, and preparing the next send never touches one already in the air.
 */
@Stable
class ComposerSendTransition {
    /** The next send's words as they leave the composer (window px). */
    data class Prepared(
        val text: String,
        /** The words' visible rect: what iOS snapshots (`usedRect` ∩ the field's bounds). */
        val bounds: Rect,
        /** Whether these are the composer's own laid-out words, not words laid out again where it was. */
        val fromComposer: Boolean,
        /** The words as laid out, drawn with their top-left at [origin] and clipped to [bounds]. */
        val layout: TextLayoutResult? = null,
        val origin: Offset = bounds.topLeft,
    )

    /** The field's text and its visible bounds in the window (px), updated by [ComposerTextInput]. */
    var inputText: String = ""
        internal set
    var inputBounds: Rect? = null
        internal set

    /** The field's laid-out text and the window position (px) its layout starts at. */
    internal var textLayout: TextLayoutResult? = null
    internal var textOrigin: Offset? = null

    var prepared: Prepared? by mutableStateOf(null)
        private set

    val isPrepared: Boolean get() = prepared != null

    fun prepare(reduceMotion: Boolean) {
        cancelPrepared()
        val visible = inputBounds
        val layout = textLayout
        val origin = textOrigin
        if (reduceMotion || inputText.isEmpty() || visible == null || layout == null || origin == null || layout.lineCount == 0) return
        var left = Float.MAX_VALUE
        var right = 0f
        for (line in 0 until layout.lineCount) {
            left = min(left, layout.getLineLeft(line))
            right = max(right, layout.getLineRight(line))
        }
        val used = Rect(origin.x + left, origin.y, origin.x + right, origin.y + layout.size.height)
        val bounds = used.intersect(visible)
        if (bounds.isEmpty) return
        prepared = Prepared(inputText, bounds, fromComposer = true, layout = layout, origin = origin)
    }

    /**
     * A send the composer didn't capture as it left: its words laid out as the composer lays
     * them out, starting where the field (or the dictation bar in its place) sits. [composer]
     * is in window px; [measure] lays out [text] at a maximum width (px).
     */
    fun prepare(
        text: String,
        composer: Rect,
        expandedTopInsetPx: Float,
        contentGutterPx: Float,
        measure: (String, Float) -> TextLayoutResult,
    ) {
        cancelPrepared()
        val layout = measure(text, max(composer.width - contentGutterPx * 2, 40f))
        val width = layout.size.width.toFloat()
        val height = layout.size.height.toFloat()
        // The field's first line: the capsule's top when it's tall, its middle when it's one row.
        val top = min(composer.top + expandedTopInsetPx, composer.center.y - height / 2)
        val bounds = Rect(composer.left + contentGutterPx, top, composer.left + contentGutterPx + width, top + height)
        prepared = Prepared(text, bounds, fromComposer = false, layout = layout, origin = bounds.topLeft)
    }

    /** The prepared send taken for its flight (the overlay now owns it); null when nothing was prepared. */
    fun takePrepared(): Prepared? = prepared.also { prepared = null }

    /** Fades a send's capture out where it is: a send that doesn't fly. Only while it's still the prepared one. */
    fun dissolvePrepared(snapshot: Prepared?) {
        if (prepared != null && prepared === snapshot) prepared = null
    }

    /** Drops the next send's capture; flights in the air carry on. */
    fun cancelPrepared() {
        prepared = null
    }

    companion object {
        /** The label fades and settles into place over the flight's last half, finishing with it. */
        fun chromeProgress(progress: Float): Float {
            val t = min(1f, max(0f, (progress - 0.5f) / 0.5f))
            return t * t * (3 - 2 * t)
        }

        fun interpolate(from: Rect, to: Rect, progress: Float) = Rect(
            from.left + (to.left - from.left) * progress,
            from.top + (to.top - from.top) * progress,
            from.right + (to.right - from.right) * progress,
            from.bottom + (to.bottom - from.bottom) * progress,
        )
    }
}

/** Per-field state the iOS `ImagePasteTextView` kept on itself: scroll pinning and swipe flags. */
private class ComposerTextInputController {
    /** The last text the parent passed (or we reported): a different one is a programmatic change. */
    var externalText: String? = null

    /**
     * Set after a programmatic text change: layouts keep the end in view while the composer
     * grows to its cap and the keyboard rises, until the reader drags, moves the caret, or types.
     */
    var pinsToEnd = false

    /** Set on a user edit so the layout pass that enables scrolling when the draft first crosses the cap still brings the caret into view. */
    var followsCaret = false

    /** A non-send edit (Shift+Return, a paste) is pending: the newline it adds isn't Return. */
    var nonSendEditFrom: String? = null

    var isScrollingUp = false
    var isScrollingDown = false
    var isSuppressingFocusAfterSwipe = false
    var isFocusingFromSwipe = false
    var swipeBeganFocused = false

    var layoutTick by mutableIntStateOf(0)
    var layout: TextLayoutResult? = null
}

/**
 * The composer's multiline text input (iOS `ComposerTextInput`, a `UITextView` bridge):
 *
 * - **Line cap.** Grows line by line to [lineLimit] (the composer's own six expanded, one
 *   collapsed) and only then scrolls; a short draft never rubber-bands. The expanded [topInset]
 *   scrolls away with the text.
 * - **Caret.** A programmatic draft (dictation, a restored draft, Share, Quote) lands with the
 *   end in view (or the autocomplete caret); a user edit keeps the caret in view as the field
 *   crosses its cap; dragging the text hands it over to the reader.
 * - **Paste and drop.** Images go to [onImagePaste] (never into the text); a paste past
 *   [PastedText]'s limits goes to [onLongTextPaste] as a Pasted Text card (null while answering
 *   a request: `takesPasteAttachments`, so it goes in as text); dropped files go to [onFileDrop];
 *   dropped text on an unfocused field is appended on a new line.
 * - **Hardware keyboard.** Ctrl/⌘+Return → [onCommandReturn]; Shift+Return inserts a newline
 *   that never sends ([onNonSendNewline]); ↑/↓, Escape, Tab and Return drive Composer
 *   Autocomplete while its list is open ([autocompleteBridge]).
 * - **Return sends.** With [returnKeySends] the IME shows Send; it inserts a newline the
 *   parent reads as Return (`new == old + "\n"`), as iOS does.
 * - **Swipes.** A downward swipe on the focused field dismisses the keyboard; an upward swipe
 *   on the unfocused field pulls it up ([ComposerSwipeDown], via [onDismissKeyboard] /
 *   [onFocusKeyboard]); neither fires while the text itself scrolls that way, nor while a hold
 *   (a held mic, [isHoldingMic]) owns the touch.
 * - **Keyboard insets.** The field only takes and drops focus; the session's composer docks
 *   above the IME with `imePadding()`/animated `WindowInsets.ime` (iOS keyboard docking).
 *
 * [isKeyboardHeld] is iOS `DrawerKeyboardHold`: under the open drawer no key, paste or drop
 * reaches the draft. [canTakeFocus] is iOS `isReachable`: never take focus under something that
 * takes its touches (the drawer, Settings).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerTextInput(
    text: String,
    onTextChange: (String) -> Unit,
    isFocused: Boolean,
    onFocusedChange: (Boolean) -> Unit,
    expanded: Boolean,
    onImagePaste: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = OpenCodeTextStyles.body,
    topInset: Dp = ComposerTextInputTunables.expandedTopInset,
    onLongTextPaste: ((String) -> Unit)? = null,
    onFileDrop: ((List<Uri>) -> Unit)? = null,
    onDismissKeyboard: (() -> Unit)? = null,
    onFocusKeyboard: (() -> Unit)? = null,
    onCommandReturn: (() -> Unit)? = null,
    onNonSendNewline: ((String) -> Unit)? = null,
    onDropTargetedChange: ((Boolean) -> Unit)? = null,
    returnKeySends: Boolean = false,
    sendTransition: ComposerSendTransition? = null,
    autocompleteBridge: ComposerAutocompleteTextBridge? = null,
    lineLimit: Int? = null,
    accessibilityLabel: String = "Message",
    isKeyboardHeld: Boolean = false,
    isHoldingMic: Boolean = false,
    canTakeFocus: Boolean = true,
) {
    val state = remember { TextFieldState(text, TextRange(text.length)) }
    val controller = remember { ComposerTextInputController() }
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var hasFocus by remember { mutableStateOf(false) }

    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val currentOnFocusedChange by rememberUpdatedState(onFocusedChange)
    val currentIsFocused by rememberUpdatedState(isFocused)
    val currentOnImagePaste by rememberUpdatedState(onImagePaste)
    val currentOnLongTextPaste by rememberUpdatedState(onLongTextPaste)
    val currentOnFileDrop by rememberUpdatedState(onFileDrop)
    val currentOnDismissKeyboard by rememberUpdatedState(onDismissKeyboard)
    val currentOnFocusKeyboard by rememberUpdatedState(onFocusKeyboard)
    val currentOnNonSendNewline by rememberUpdatedState(onNonSendNewline)
    val currentOnDropTargetedChange by rememberUpdatedState(onDropTargetedChange)
    val currentBridge by rememberUpdatedState(autocompleteBridge)
    // iOS `DrawerKeyboardHold.isHeld(textView)`: the drawer holds this field's keyboard.
    val fieldKey = remember { Any() }
    val drawerHold = LocalDrawerKeyboardSignals.current
    val held by rememberUpdatedState(isKeyboardHeld || drawerHold?.isHeld(fieldKey) == true)
    val holdingMic by rememberUpdatedState(isHoldingMic)

    // Programmatic updates (dictation, a restored draft, Share/Quote) follow the newest text;
    // synced before the field measures so the first frame of an expand already has it.
    SideEffect {
        if (controller.externalText == null) controller.externalText = text
        if (text == controller.externalText) return@SideEffect
        controller.externalText = text
        if (state.text.toString() == text) return@SideEffect
        val bridge = autocompleteBridge
        bridge?.beginProgrammaticTextUpdate()
        try {
            val caret = bridge?.caretUTF16Offset
            state.edit {
                replace(0, length, text)
                if (bridge != null && bridge.isEnabled && caret != null && caret in 0..text.length) {
                    selection = TextRange(caret)
                } else {
                    placeCursorAtEnd()
                }
            }
            if (bridge != null && bridge.isEnabled && caret != null && caret in 0..text.length) {
                bridge.syncLastSentCaretOffset()
                controller.pinsToEnd = false
            } else {
                controller.pinsToEnd = true
            }
        } finally {
            bridge?.endProgrammaticTextUpdate()
        }
    }

    // The reader's own edits: report them, with a non-send newline first so the parent
    // never reads it as Return.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { newText ->
            val previous = controller.externalText ?: return@collect
            if (newText == previous) return@collect
            // A reader's own edit owns the caret; drop any pending follow-the-end.
            controller.pinsToEnd = false
            controller.followsCaret = true
            val nonSendFrom = controller.nonSendEditFrom
            controller.nonSendEditFrom = null
            if (nonSendFrom != null && newText == nonSendFrom + "\n") currentOnNonSendNewline?.invoke(newText)
            controller.externalText = newText
            currentOnTextChange(newText)
            currentBridge?.notifyCaretAfterEdit(newText, state.selection, state.composition)
        }
    }

    // Moving the caret off the end is reading or editing earlier text.
    LaunchedEffect(state) {
        snapshotFlow { state.selection }.collect { selection ->
            if (!hasFocus) return@collect
            val current = state.text.toString()
            currentBridge?.selectionDidChange(current, selection, state.composition, controller.externalText ?: current)
            if (selection != TextRange(current.length)) controller.pinsToEnd = false
        }
    }

    // A drag on the text is the reader's: stop following the end or the caret.
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                controller.pinsToEnd = false
                controller.followsCaret = false
            }
        }
    }

    // Focus follows `isFocused`, settled after the update (iOS dispatches it to the next turn).
    LaunchedEffect(isFocused, canTakeFocus) {
        awaitFrame()
        if (isFocused && !hasFocus && canTakeFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else if (!isFocused && hasFocus) {
            focusManager.clearFocus()
        }
    }

    val lineHeightPx = with(density) {
        if (textStyle.lineHeight.isSp) textStyle.lineHeight.toPx() else textStyle.fontSize.toPx() * 1.3f
    }
    val lines = lineLimit ?: if (expanded) 6 else 1
    val insetPx = if (expanded) with(density) { topInset.toPx() } else 0f
    val capPx = ceil(lineHeightPx * lines + insetPx)
    val capDp = with(density) { capPx.toDp() }
    // Past the cap the text scrolls; below it, nothing does (no bounce, no indicator).
    val overflows = scroll.maxValue > 0

    LaunchedEffect(overflows) {
        if (!overflows) {
            controller.followsCaret = false
            if (scroll.value != 0) scroll.scrollTo(0)
        }
    }

    // Keep the end (programmatic text) or the caret (typing) in view once the text scrolls.
    LaunchedEffect(scroll, state) {
        snapshotFlow { Triple(scroll.maxValue, controller.layoutTick, state.selection) }.collectLatest { (maxValue, _, selection) ->
            if (maxValue <= 0) return@collectLatest
            val viewport = capPx
            if (controller.pinsToEnd) {
                if (scroll.value != maxValue) scroll.scrollTo(maxValue)
                return@collectLatest
            }
            if (!controller.followsCaret) return@collectLatest
            controller.followsCaret = false
            val textLength = state.text.length
            if (selection == TextRange(textLength)) {
                if (scroll.value != maxValue) scroll.scrollTo(maxValue)
                return@collectLatest
            }
            val layout = controller.layout ?: return@collectLatest
            val caret = runCatching { layout.getCursorRect(selection.end.coerceIn(0, textLength)) }.getOrNull() ?: return@collectLatest
            val top = caret.top + insetPx - 2f
            val bottom = caret.bottom + insetPx + 2f
            val visibleTop = scroll.value.toFloat()
            val visibleBottom = visibleTop + viewport
            if (bottom > visibleBottom) {
                scroll.scrollTo(min(maxValue.toFloat(), bottom - viewport).toInt())
            } else if (top < visibleTop) {
                scroll.scrollTo(max(0f, top).toInt())
            }
        }
    }

    val styling = autocompleteBridge?.let { rememberAutocompleteTextStyling(it, state.text.toString(), textStyle) }

    // Under the open drawer no edit reaches the composer.
    val holdTransformation = remember {
        object : InputTransformation {
            override fun TextFieldBuffer.transformInput() {
                if (held) revertAllChanges()
            }
        }
    }
    val inputTransformation = (autocompleteBridge?.let { holdTransformation.then(it.inputTransformation) } ?: holdTransformation)
        .then(PerfKeystroke)

    fun insertNonSendNewline() {
        controller.nonSendEditFrom = state.text.toString()
        state.edit { replaceSelection("\n") }
    }

    fun dismissKeyboard() {
        currentOnDismissKeyboard?.invoke()
        if (hasFocus) focusManager.clearFocus()
    }

    fun focusKeyboard() {
        if (controller.isFocusingFromSwipe) return
        currentOnFocusKeyboard?.invoke()
        controller.isFocusingFromSwipe = true
    }

    val receiveContent = remember {
        object : ReceiveContentListener {
            override fun onDragEnter() {
                currentOnDropTargetedChange?.invoke(true)
            }

            override fun onDragExit() {
                currentOnDropTargetedChange?.invoke(false)
            }

            override fun onDragEnd() {
                currentOnDropTargetedChange?.invoke(false)
            }

            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (held) return null
                val isDrop = transferableContent.source == TransferableContent.Source.DragAndDrop
                if (isDrop) currentOnDropTargetedChange?.invoke(false)
                val clip = transferableContent.clipEntry.clipData
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                // Images never enter the text: they become attachments (or, answering a request, nothing).
                if (transferableContent.hasMediaType(MediaType.Image) && uris.isNotEmpty()) {
                    currentOnImagePaste(uris)
                    return null
                }
                val fileDrop = currentOnFileDrop
                val pasted = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }.joinToString("")
                if (isDrop && fileDrop != null && uris.isNotEmpty() && pasted.isEmpty()) {
                    fileDrop(uris)
                    return null
                }
                if (pasted.isEmpty()) return transferableContent
                val longPaste = currentOnLongTextPaste
                if (PastedText.isLong(pasted) && longPaste != null) {
                    longPaste(pasted)
                    return null
                }
                if (isDrop && !hasFocus) {
                    // Dropped on a field that isn't being edited: a new line after the draft.
                    val current = state.text.toString()
                    val separator = if (current.isEmpty() || current.endsWith("\n") || current.endsWith(" ")) "" else "\n"
                    controller.nonSendEditFrom = current
                    state.edit {
                        append(separator + pasted)
                        placeCursorAtEnd()
                    }
                    return null
                }
                controller.nonSendEditFrom = state.text.toString()
                return transferableContent
            }
        }
    }

    Box(
        modifier
            .heightIn(max = capDp)
            .onGloballyPositioned { coordinates ->
                sendTransition?.let {
                    it.inputText = state.text.toString()
                    it.inputBounds = coordinates.boundsInWindow()
                }
            }
            .pointerInput(Unit) {
                val gestureScope = this
                coroutineScope { val settleScope = this; with(gestureScope) { awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // iOS `noteSwipeTouchDown`.
                    controller.isSuppressingFocusAfterSwipe = false
                    controller.isFocusingFromSwipe = false
                    controller.swipeBeganFocused = hasFocus
                    controller.isScrollingUp = scroll.maxValue > 0 && scroll.value > 0
                    controller.isScrollingDown = scroll.maxValue > 0 && scroll.value < scroll.maxValue
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var yieldedToHold = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val heldFor = (change.uptimeMillis - down.uptimeMillis) / 1000.0
                        if (!yieldedToHold && BackSwipeRules.yieldsToHold(heldFor, holdBegan = holdingMic)) yieldedToHold = true
                        val translation = (change.position - down.position) / density.density
                        val velocity = tracker.calculateVelocity().let { Offset(it.x, it.y) / density.density }
                        val predicted = translation + velocity * 0.15f
                        val ended = !change.pressed
                        if (controller.swipeBeganFocused) {
                            val dismisses = (ended && controller.isSuppressingFocusAfterSwipe) || ComposerSwipeDown.shouldDismissKeyboard(
                                translation = translation,
                                predictedEndTranslation = predicted,
                                isFocused = hasFocus || (!ended && controller.isSuppressingFocusAfterSwipe),
                                isScrollingUp = controller.isScrollingUp,
                            )
                            if (dismisses) {
                                controller.isSuppressingFocusAfterSwipe = true
                                dismissKeyboard()
                            }
                        } else if (!controller.isFocusingFromSwipe) {
                            val focuses = ComposerSwipeDown.shouldFocusKeyboard(
                                translation = translation,
                                predictedEndTranslation = predicted,
                                velocityY = velocity.y,
                                isFocused = hasFocus,
                                isHoldingMic = yieldedToHold,
                                isScrollingDown = controller.isScrollingDown,
                            )
                            if (focuses) focusKeyboard()
                        }
                        if (ended) break
                    }
                    // Settled on the next frame, as iOS does on the next main-queue turn.
                    settleScope.launch {
                        awaitFrame()
                        controller.isSuppressingFocusAfterSwipe = false
                        controller.isFocusingFromSwipe = false
                        controller.isScrollingUp = false
                        controller.isScrollingDown = false
                    }
                } } }
            }
            .verticalScroll(scroll, enabled = overflows),
    ) {
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (expanded) topInset else 0.dp)
                .onGloballyPositioned { coordinates -> sendTransition?.textOrigin = coordinates.positionInWindow() }
                .focusRequester(focusRequester)
                .drawerKeyboardField(fieldKey)
                .onFocusChanged { focus ->
                    if (focus.isFocused && controller.isSuppressingFocusAfterSwipe) {
                        // The touch that just dismissed the keyboard doesn't bring it back.
                        focusManager.clearFocus()
                        return@onFocusChanged
                    }
                    hasFocus = focus.isFocused
                    if (focus.isFocused != currentIsFocused) currentOnFocusedChange(focus.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    if (held) return@onPreviewKeyEvent event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (currentBridge?.handleKeyEvent(event) == true) return@onPreviewKeyEvent true
                    val isReturn = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (!isReturn) return@onPreviewKeyEvent false
                    when {
                        event.isMetaPressed || event.isCtrlPressed -> {
                            if (event.type == KeyEventType.KeyDown) onCommandReturn?.invoke()
                            true
                        }
                        event.isShiftPressed -> {
                            if (event.type == KeyEventType.KeyDown) insertNonSendNewline()
                            true
                        }
                        else -> false
                    }
                }
                .contentReceiver(receiveContent)
                .semantics { contentDescription = accessibilityLabel }
                .then(autocompleteBridge?.let { Modifier.autocompleteTextAnchor(it) } ?: Modifier),
            inputTransformation = inputTransformation,
            outputTransformation = autocompleteBridge?.takeIf { styling != null }?.outputTransformation,
            textStyle = textStyle.copy(color = OpenCodeTheme.colors.text),
            cursorBrush = SolidColor(OpenCodeTheme.colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (returnKeySends) ImeAction.Send else ImeAction.Default,
            ),
            onKeyboardAction = KeyboardActionHandler {
                if (held) return@KeyboardActionHandler
                val bridge = currentBridge
                if (bridge != null && bridge.isEnabled && bridge.isOpen && bridge.hasSelectableRow) {
                    bridge.onConfirmSelection?.invoke()
                    return@KeyboardActionHandler
                }
                // The Send key is Return: a newline the parent reads as `old + "\n"`.
                state.edit { replaceSelection("\n") }
            },
            lineLimits = TextFieldLineLimits.MultiLine(),
            onTextLayout = { getResult ->
                val result = getResult()
                controller.layout = result
                sendTransition?.textLayout = result
                autocompleteBridge?.textLayout = result
                controller.layoutTick += 1
            },
            decorator = { innerTextField ->
                Box {
                    innerTextField()
                    if (autocompleteBridge != null) AutocompleteInlineChips(autocompleteBridge)
                }
            },
        )
    }
}

/** iOS `PerfFrameLatency.begin("keystroke")` once the hold and autocomplete accept an edit. */
private object PerfKeystroke : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (PerfFrameLatency.isEnabled && !asCharSequence().contentEquals(originalText)) PerfFrameLatency.begin("keystroke")
    }
}

/** Replaces the selection with [text] and puts the caret after it. */
private fun TextFieldBuffer.replaceSelection(text: String) {
    val start = selection.min
    replace(start, selection.max, text)
    selection = TextRange(start + text.length)
}
