package ai.opencode.feature.composer

import ai.opencode.core.diagnostics.PerfFrameLatency
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.BackSwipeRules
import ai.opencode.core.ui.LocalDrawerKeyboardSignals
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.drawerKeyboardField
import ai.opencode.server.model.PastedText
import android.net.Uri
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.layout.layout
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    /** `composer.lines.grow`: a typed line's growth or shrink (0.28 s, no bounce). */
    val lineGrowth: MotionSpring = MotionSpring(duration = 0.28, bounce = 0.0)

    /** `composer.lines.sendShrinkSpeed`: the send shrink's ω as a multiple of the list's arrival ω. */
    const val SEND_SHRINK_SPEED = 1.25
}

/**
 * iOS `ImagePasteTextView`'s line growth (`lineGrowthAnimation`, `beginLineGrowth`,
 * `holdLineGrowth`, `sendShrinkAnimation`) as the plain rules [ComposerTextInput]'s layout follows.
 * Heights and offsets are px; a hold is iOS `contentOffset.y`, how far the text (laid out at the
 * target height) sits above the field's top while the field's height springs.
 *
 * iOS 60dddffa also holds no line mid-composition (`markedTextRange == nil`): there, setting
 * `contentOffset` committed an IME's marked text where the line wrapped (#190). Android has no such
 * hazard (the hold is a placement in a layout modifier, which never touches the IME's composing
 * region), and Android keyboards compose every Latin word (Gboard's suggestions), so gating on the
 * composing region would stop ordinary typing's line growth, which iOS keeps.
 */
object ComposerLineGrowth {
    /**
     * iOS `lineGrowthAnimation`: the spring a reader's edit from [old] to [new] rides; none under
     * reduce motion, and none for a Return that sends (it never animates the line it adds).
     */
    fun typedLineSpring(old: String, new: String, returnKeySends: Boolean, reduceMotion: Boolean): MotionSpring? =
        if (reduceMotion || (returnKeySends && new == old + "\n")) null else ComposerTextInputTunables.lineGrowth

    /**
     * iOS `sendShrinkAnimation`: a send's clear rides a critical spring of ω [omega]
     * ([ComposerSendTransition.sendShrinkOmega]); under reduce motion (or with no send) it lands at once.
     */
    fun sendShrinkSpring(omega: Double?, reduceMotion: Boolean): MotionSpring? =
        omega?.takeIf { !reduceMotion && it > 0 }?.let { MotionSpring(duration = 2 * PI / it, bounce = 0.0) }

    /**
     * Whether a height change from [from] rides [spring]: a typed line's, any change; a send's
     * shrink ([shrinks]), only a field taller than one line ([oneLine]).
     */
    fun rides(spring: MotionSpring?, shrinks: Boolean, from: Float, oneLine: Float): Boolean =
        spring != null && (!shrinks || from - oneLine > 0.5f)

    /**
     * iOS `holdLineGrowth`: while the field is [height] tall on its way to [target], the text is
     * held to the field's bottom (`target - height` above its top), so the caret's line stays put.
     * Growing for a word wrapped off an earlier line holds no further than keeps the caret's line
     * ([caretLineTop], from the text's top, after the field's top inset) in view: the lines below
     * pass under the bottom edge instead. A shrink holds to the bottom.
     */
    fun holdOffset(target: Float, height: Float, caretLineTop: Float?): Float {
        val y = target - height
        if (y <= 0f || caretLineTop == null || !caretLineTop.isFinite()) return y
        return max(0f, min(y, caretLineTop))
    }
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

    /**
     * Whether a drag on the text belongs to the text field, so no swipe acts on it: it started
     * on or near the selection (its handles sit at its edges), the finger rested before moving
     * (a long press selecting), or one of the field's own gestures took it (a selection drag).
     */
    fun textOwnsDrag(startsOnSelection: Boolean, heldFor: Double, holdBegan: Boolean, textGestureBegan: Boolean): Boolean =
        startsOnSelection || textGestureBegan || BackSwipeRules.yieldsToHold(heldFor, holdBegan)

    /**
     * Whether [point] is on or near one of a selection's rects; [reach] in the same units
     * (iOS `BackSwipeRules.selectionHandleReach`, 24 dp).
     */
    fun selectionClaims(point: Offset, selectionRects: List<Rect>, reach: Float = BackSwipeRules.selectionHandleReach.value): Boolean =
        selectionRects.any { rect -> rect.isFinite && rect.inflate(reach).contains(point) }

    /**
     * iOS `ImagePasteTextView.selectionClaims`: only a focused field's non-empty [selection]
     * claims a touch. An unfocused field shows no selection, and a caret isn't one; its rects
     * are only measured when it could claim.
     */
    fun selectionClaims(
        point: Offset,
        isFocused: Boolean,
        selection: TextRange,
        selectionRects: () -> List<Rect>,
        reach: Float = BackSwipeRules.selectionHandleReach.value,
    ): Boolean = isFocused && !selection.collapsed && selectionClaims(point, selectionRects(), reach)
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

    /**
     * [yieldsNow] is read only for a drag not seen before. Once the text field claims the drag
     * ([textClaims], a selection's handle or a long press), it stays yielded through its last
     * callback.
     */
    fun yields(startLocation: Offset, isFocused: Boolean = false, textClaims: Boolean = false, yieldsNow: () -> Boolean): Boolean {
        if (start != startLocation) {
            start = startLocation
            focusedStart = startLocation
            focusedAtStart = isFocused
            yields = yieldsNow()
        }
        if (textClaims) yields = true
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

    /**
     * iOS `beginLineGrowth`: the spring the next height change rides, and the text it was set
     * for (a measure for any other text takes its height at once).
     */
    var heightSpring: MotionSpring? = null
    var heightSpringText: String? = null
    var heightSpringShrinks = false

    /** iOS `measuredHeight` (px): the height the field last laid out to. */
    var measuredHeight: Int? = null
    var heightAnimation: Job? = null
    val animatedHeight = Animatable(0f)

    /** iOS `ComposerSwipePanRecognizer.textOwnership` for the drag in progress. */
    var isSwipeYieldedToText = false
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
 * - **Line growth.** A typed line's growth or shrink rides [ComposerTextInputTunables.lineGrowth]
 *   with the text held to the field's bottom, so the caret's line stays put while the top
 *   moves; none under reduce motion or for a Return that sends. A send's clear shrinks on
 *   [ComposerSendTransition.shrinkOnNextClear]'s spring, the one animation the composer around
 *   it follows ([ComposerSendTransition.isShrinking]).
 * - **Selecting.** A drag the text is selecting with (it starts on or near the selection, or
 *   rests first) is never a swipe ([ComposerSwipeDown.textOwnsDrag]).
 * - **Keyboard insets.** The field only takes and drops focus; the session's composer docks
 *   above the IME with `imePadding()`/animated `WindowInsets.ime` (iOS keyboard docking).
 *
 * [isKeyboardHeld] is iOS `DrawerKeyboardHold`: under the open drawer no key, paste or drop
 * reaches the draft. [canTakeFocus] is iOS `isReachable`: never take focus under something that
 * takes its touches (the drawer, Settings). [topInsetFraction] sets how much of [topInset] shows
 * (the keyboard morph scrubs it; null follows [expanded]); [refusesFocus] keeps the field from
 * taking focus while something else owns the composer (One Picker open).
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
    topInsetFraction: Float? = null,
    refusesFocus: (() -> Boolean)? = null,
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
    val currentRefusesFocus by rememberUpdatedState(refusesFocus)
    val currentReturnKeySends by rememberUpdatedState(returnKeySends)
    val reduceMotion = rememberReduceMotion()
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val growthScope = rememberCoroutineScope()

    // Programmatic updates (dictation, a restored draft, Share/Quote) follow the newest text;
    // synced before the field measures so the first frame of an expand already has it.
    SideEffect {
        if (controller.externalText == null) controller.externalText = text
        if (text == controller.externalText) return@SideEffect
        controller.externalText = text
        // A send's clear shrinks on its own spring (`sendShrinkAnimation`); any other change lands at once.
        controller.heightSpring = ComposerLineGrowth.sendShrinkSpring(sendTransition?.takeShrinkOmega(), reduceMotion)
        controller.heightSpringText = text
        controller.heightSpringShrinks = true
        if (state.text.toString() == text) return@SideEffect
        val bridge = autocompleteBridge
        bridge?.beginProgrammaticTextUpdate()
        try {
            val caret = bridge?.caretUTF16Offset
            // iOS `finishCompositionIfNeeded` (60dddffa): an open composition (an IME's composing
            // region, voice typing) must finish before the text changes under it (a send's clear),
            // or it lands in a stale range. A programmatic `edit` commits the composing region where
            // it is and restarts the IME's input, so the words it held don't come back.
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
        if (isFocused && !hasFocus && canTakeFocus && currentRefusesFocus?.invoke() != true) {
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
    // iOS `appliedTopInset`: the keyboard morph scrubs how much of the inset shows.
    val appliedTopInset = topInset * (topInsetFraction ?: if (expanded) 1f else 0f)
    val insetPx = with(density) { appliedTopInset.toPx() }
    val currentInsetPx by rememberUpdatedState(insetPx)
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
                if (held) {
                    revertAllChanges()
                    return
                }
                // iOS `lineGrowthAnimation`: a typed line's growth rides its spring, except under
                // reduce motion and for a Return that sends (it never animates the line it adds).
                val old = originalText.toString()
                val new = asCharSequence().toString()
                controller.heightSpring = ComposerLineGrowth.typedLineSpring(old, new, currentReturnKeySends, currentReduceMotion)
                controller.heightSpringText = new
                controller.heightSpringShrinks = false
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

    /**
     * iOS `ImagePasteTextView.selectionClaims`: whether [point] (in the field's viewport) is on or
     * near the selection being made; a caret isn't a selection.
     */
    fun selectionClaims(point: Offset, isFocused: Boolean): Boolean {
        val selection = state.selection
        val layout = controller.layout ?: return false
        val textPoint = Offset(point.x, point.y + scroll.value - currentInsetPx)
        return ComposerSwipeDown.selectionClaims(
            textPoint,
            isFocused = isFocused,
            selection = selection,
            selectionRects = { selectionRects(layout, selection) },
            reach = with(density) { BackSwipeRules.selectionHandleReach.toPx() },
        )
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
            .onGloballyPositioned { coordinates ->
                sendTransition?.let {
                    it.inputText = state.text.toString()
                    it.inputBounds = coordinates.boundsInWindow()
                }
            }
            // iOS `beginLineGrowth`/`holdLineGrowth`: the field's height springs to the laid-out
            // one with the text held to its bottom; the line coming or going passes under the top.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val target = placeable.height
                val previous = controller.measuredHeight
                controller.measuredHeight = target
                val spring = controller.heightSpring?.takeIf {
                    controller.heightSpringText == Snapshot.withoutReadObservation { state.text.toString() }
                }
                var height = target
                if (previous != null && abs(target - previous) > 0) {
                    controller.heightSpring = null
                    val running = controller.heightAnimation?.isActive == true
                    val from = if (running) controller.animatedHeight.value else previous.toFloat()
                    // A send's shrink takes a field taller than one line; a typed line, any change.
                    val oneLine = lineHeightPx + insetPx
                    if (spring != null && ComposerLineGrowth.rides(spring, controller.heightSpringShrinks, from, oneLine)) {
                        height = from.roundToInt()
                        val shrinks = controller.heightSpringShrinks
                        controller.heightAnimation = growthScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                // A retarget keeps the running spring's velocity, as SwiftUI's does.
                                if (!running) controller.animatedHeight.snapTo(from)
                                controller.animatedHeight.animateTo(target.toFloat(), spring.spec(visibilityThreshold = 0.5f))
                            } finally {
                                if (shrinks) sendTransition?.endShrink()
                            }
                        }
                    } else {
                        controller.heightAnimation?.cancel()
                        controller.heightAnimation = null
                    }
                } else if (controller.heightAnimation?.isActive == true) {
                    height = controller.animatedHeight.value.roundToInt()
                }
                sendTransition?.let { transition ->
                    transition.inputIsMultiline = height - (lineHeightPx + insetPx) > 0.5f
                    // A send's clear that rode no spring leaves the composer to resize as usual.
                    val shrinking = Snapshot.withoutReadObservation { transition.isShrinking }
                    if (shrinking && controller.heightAnimation?.isActive != true) transition.endShrink()
                }
                val caretLineTop = if (height < target) {
                    Snapshot.withoutReadObservation {
                        val layout = controller.layout
                        val end = state.selection.end
                        if (layout == null || end > layout.layoutInput.text.length) {
                            null
                        } else {
                            // From the visible text's top: a scrolled field's offset moves the line up.
                            runCatching { layout.getLineTop(layout.getLineForOffset(end)) - scroll.value }.getOrNull()
                        }
                    }
                } else {
                    null
                }
                val hold = ComposerLineGrowth.holdOffset(target.toFloat(), height.toFloat(), caretLineTop)
                layout(placeable.width, height) { placeable.place(0, -hold.roundToInt()) }
            }
            .clipToBounds()
            .heightIn(max = capDp)
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
                    // iOS `ComposerSwipePanRecognizer.textOwnership`: decided once the finger
                    // first moves the decision distance, then the text's for good.
                    val selectionAtDown = state.selection
                    val startsOnSelection = selectionClaims(down.position, isFocused = hasFocus)
                    var textOwnership: Boolean? = if (startsOnSelection) true else null
                    controller.isSwipeYieldedToText = startsOnSelection
                    val decisionPx = BackSwipeRules.decisionDistance.toPx()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val heldFor = (change.uptimeMillis - down.uptimeMillis) / 1000.0
                        if (!yieldedToHold && BackSwipeRules.yieldsToHold(heldFor, holdBegan = holdingMic)) yieldedToHold = true
                        // The field's own selecting (a long press, a drag that moves the selection).
                        val textGestureBegan = state.selection != selectionAtDown && !state.selection.collapsed
                        if (textOwnership == null && (change.position - down.position).getDistance() >= decisionPx) {
                            textOwnership = ComposerSwipeDown.textOwnsDrag(startsOnSelection, max(0.0, heldFor), holdBegan = holdingMic, textGestureBegan = textGestureBegan)
                        } else if (textOwnership == false && textGestureBegan) {
                            textOwnership = true
                        }
                        val yieldedToText = textOwnership == true
                        controller.isSwipeYieldedToText = yieldedToText
                        val translation = (change.position - down.position) / density.density
                        val velocity = tracker.calculateVelocity().let { Offset(it.x, it.y) / density.density }
                        val predicted = translation + velocity * 0.15f
                        val ended = !change.pressed
                        if (controller.swipeBeganFocused) {
                            val dismisses = (ended && controller.isSuppressingFocusAfterSwipe) || ComposerSwipeDown.shouldDismissKeyboard(
                                translation = translation,
                                predictedEndTranslation = predicted,
                                isFocused = hasFocus || (!ended && controller.isSuppressingFocusAfterSwipe),
                                isHoldingMic = yieldedToText,
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
                                isHoldingMic = yieldedToHold || yieldedToText,
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
                        controller.isSwipeYieldedToText = false
                    }
                } } }
            }
            .verticalScroll(scroll, enabled = overflows),
    ) {
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = appliedTopInset)
                .onGloballyPositioned { coordinates -> sendTransition?.textOrigin = coordinates.positionInWindow() }
                .focusRequester(focusRequester)
                .drawerKeyboardField(fieldKey)
                .onFocusChanged { focus ->
                    if (focus.isFocused && (controller.isSuppressingFocusAfterSwipe || currentRefusesFocus?.invoke() == true)) {
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
                // The field calls this from its own measure, which observes every state read here:
                // `layoutTick += 1` reads before it writes, so the write invalidated the field's
                // measure, which called this again — a relayout and redraw every frame for as long
                // as the screen stayed up (60 fps idle, found against a live server). Only the
                // layout result is the field's input; the rest is bookkeeping for other readers.
                Snapshot.withoutReadObservation {
                    controller.layout = result
                    sendTransition?.textLayout = result
                    autocompleteBridge?.textLayout = result
                    if (result != null) autocompleteBridge?.updateLineBreaks(result)
                    controller.layoutTick += 1
                }
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

/** The selection's rects per line, in the layout's coordinates (iOS `selectionRects(for:)`). */
private fun selectionRects(layout: TextLayoutResult, selection: TextRange): List<Rect> {
    val length = layout.layoutInput.text.length
    val start = selection.min.coerceIn(0, length)
    val end = selection.max.coerceIn(0, length)
    if (start >= end) return emptyList()
    val firstLine = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset(end)
    return (firstLine..lastLine).map { line ->
        val left = if (line == firstLine) layout.getHorizontalPosition(start, usePrimaryDirection = true) else layout.getLineLeft(line)
        val right = if (line == lastLine) layout.getHorizontalPosition(end, usePrimaryDirection = true) else layout.getLineRight(line)
        Rect(min(left, right), layout.getLineTop(line), max(left, right), layout.getLineBottom(line))
    }
}
