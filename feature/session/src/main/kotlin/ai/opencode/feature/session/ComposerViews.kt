package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.motionBlurReveal
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeShapeGeometry
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Port of iOS `Session/ComposerViews.swift`: the composer's layout, controls, pills and banners.

/** iOS `ComposerTextStyle`: Dynamic Type text styles for the message field and placeholder. */
enum class ComposerTextStyle(val rawValue: String) {
    footnote("footnote"), subheadline("subheadline"), callout("callout"), body("body"), headline("headline");

    val textStyle: TextStyle
        get() = when (this) {
            footnote -> OpenCodeTextStyles.footnote
            subheadline -> OpenCodeTextStyles.subheadline
            callout -> OpenCodeTextStyles.callout
            body -> OpenCodeTextStyles.body
            headline -> OpenCodeTextStyles.headline
        }
}

/**
 * iOS `ComposerTunables`: the composer's size, spacing and control geometry, as the shipped
 * defaults (dp for iOS points, seconds for durations). The developer Tuning Panel isn't ported.
 * Visual sizes are separate from hit targets, which stay at least 48 dp.
 */
object ComposerTunables {
    const val barHeight = 56f
    const val cornerRadius = 28f
    val textStyle = ComposerTextStyle.body

    const val horizontalMargin = 20f
    const val bottomGap = 0f
    const val keyboardGap = 8f
    const val innerHorizontal = 6f
    const val innerVertical = 6f
    const val textLeading = 52f
    const val expandedTextLeading = 11f
    const val expandedTopInset = 16f
    const val expandedRowGap = 12f
    const val pillGap = 10f

    const val plusOffset = 28f
    /** Nudges the + glyph's ink inside its target (OC-477). */
    const val plusInkOffset = 0f
    /** A composer pill's glyph frame starts this far in. */
    const val pillGlyphLeading = 8f
    const val micInkRise = 1f
    /** Between a pill's glyph and its label. */
    const val pillGlyphGap = 5f
    /** The queued pill's speech bubble drops so its optical centre sits on the end-cap's. */
    const val queuedGlyphDrop = 1f
    const val plusScale = 17f
    const val buttonDiameter = 34f
    const val trailingGap = 11f
    const val micScale = 17f
    const val trailingSpacing = 4f
    const val leadingSpacing = 9f
    const val rowRevealIn = 0.22
    const val rowRevealOut = 0.14
    const val rowRevealDelay = 0.04
    const val rowRevealBlur = 4f
    const val swipeUpDistance = 18f
    const val swipeUpVelocity = 260f

    /** The composer's mic glyph; the Call Tray's Mute wears the same one (OC-473). */
    const val micSymbol = "mic"

    /** iOS `micFont`: body medium at the default 17 pt. */
    val micFont: TextStyle get() = OpenCodeTextStyles.body.copy(fontWeight = FontWeight.Medium)
}

// region ComposerLayout

/** iOS `VerticalAlignment.composerFoldTop`: where a subview of a folded [ComposerFoldLayout] starts to stay. */
val ComposerFoldTop = HorizontalAlignmentLine(::min)

/** iOS `ComposerLayout.Inline`: answering a pending request, the field stays on the control row. */
data class ComposerLayoutInline(val hasLeading: Boolean, val lineHeight: Float)

/** iOS `ComposerLayout`'s statics. */
object ComposerLayoutMetrics {
    /** Expanded, the text starts where the +'s ink does. */
    val expandedTextInset: Float get() = ComposerTunables.expandedTextLeading
    val collapsedTextInset: Float get() = ComposerTunables.textLeading
    val expandedRowGap: Float get() = ComposerTunables.expandedRowGap
    val configuredRowHeight: Float get() = max(32f, ComposerTunables.barHeight - 2 * ComposerTunables.innerVertical)

    fun inputX(expanded: Boolean, inline: ComposerLayoutInline?): Float {
        // On the request panel's edge: titles 12 dp after the marks, or the panel's 20 dp edge.
        if (inline != null) return if (inline.hasLeading) 48f else 14f
        return if (expanded) expandedTextInset else collapsedTextInset
    }

    /** Above and below the inline field's lines, so one line centres on the controls. */
    fun inlinePad(controls: Float, inline: ComposerLayoutInline?): Float = max(0f, (controls - (inline?.lineHeight ?: 0f)) / 2)
}

/**
 * iOS `ComposerLayout`: three stable children (input, leading controls, trailing control). Only
 * their placement changes, so keyboard focus never moves to a replacement field. Provides
 * [ComposerFoldTop] where the control row starts, so a folded [ComposerFoldLayout] keeps only it.
 *
 * Android: control slots are measured at least 48 dp tall (touch targets) and centre on the
 * 44 dp visual row, overhanging it by 2 dp each side, as iOS's targets do past its glass.
 */
@Composable
fun ComposerLayout(
    expanded: Boolean,
    inline: ComposerLayoutInline?,
    input: @Composable () -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(input, leading, trailing), modifier = modifier) { (inputs, leadings, trailings), constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 320.dp.roundToPx()
        val px = { v: Float -> v.dp.toPx() }
        val trailingPlaceable = trailings.map { it.measure(Constraints()) }
        val trailingWidth = trailingPlaceable.maxOfOrNull { it.width } ?: 0
        val inputX = px(ComposerLayoutMetrics.inputX(expanded, inline))
        val collapsedRightPad = px(ComposerLayoutMetrics.collapsedTextInset) + px(4f)
        val inputWidth = when {
            inline != null -> width - inputX - px(4f) - trailingWidth
            expanded -> width - 2 * px(ComposerLayoutMetrics.expandedTextInset)
            else -> width - collapsedRightPad - trailingWidth
        }.roundToInt().coerceAtLeast(0)
        val inputPlaceable = inputs.map { it.measure(Constraints(minWidth = inputWidth, maxWidth = inputWidth)) }
        val inputHeight = inputPlaceable.maxOfOrNull { it.height } ?: 0
        val controls = px(ComposerLayoutMetrics.configuredRowHeight)
        val inlinePad = px(ComposerLayoutMetrics.inlinePad(ComposerLayoutMetrics.configuredRowHeight, inline))
        // Hit targets stay at least 48 dp tall even when the visual bar is compact.
        val targetHeight = max(48.dp.roundToPx(), controls.roundToInt())
        val leadingWidth = if (inline == null) max(px(44f), width - trailingWidth - px(4f)).roundToInt() else 48.dp.roundToPx()
        val leadingPlaceable = leadings.map { it.measure(Constraints(maxWidth = leadingWidth, maxHeight = targetHeight)) }
        val trailingPlaced = trailingPlaceable
        val height = when {
            inline != null -> max(controls, inputHeight + 2 * inlinePad)
            expanded -> inputHeight + px(ComposerLayoutMetrics.expandedRowGap) + controls
            else -> max(inputHeight.toFloat(), controls)
        }.roundToInt()
        // Folded for dictation, the composer keeps only the control row.
        val foldTop: Int? = when {
            inline != null -> (height - controls).roundToInt()
            expanded -> (inputHeight + px(ComposerLayoutMetrics.expandedRowGap)).roundToInt()
            else -> null
        }
        val lines: Map<androidx.compose.ui.layout.AlignmentLine, Int> = if (foldTop != null) mapOf(ComposerFoldTop to foldTop) else emptyMap()
        layout(width, height, lines) {
            val controlsY = height - controls / 2
            val inputY = when {
                inline != null -> height - inlinePad - inputHeight
                expanded -> 0f
                else -> height / 2f - inputHeight / 2f
            }
            inputPlaceable.forEach { it.place(inputX.roundToInt(), inputY.roundToInt()) }
            // The leading row ends 4 dp short of the trailing control: a mic at either end sits in
            // the slot the dictation bar's ■ takes, so tapping it changes nothing's place.
            leadingPlaceable.forEach { it.place(0, (controlsY - it.height / 2f).roundToInt()) }
            trailingPlaced.forEach { it.place(width - it.width, (controlsY - it.height / 2f).roundToInt()) }
        }
    }
}

// endregion

// region ComposerFoldLayout

private object ComposerFoldsMarker

/** iOS `composerFolds()`: folds away with the composer while dictating (a [ComposerFoldLayout] child). */
fun Modifier.composerFolds(): Modifier = layoutId(ComposerFoldsMarker)

/**
 * iOS `ComposerFoldLayout`: the composer's column (attachments, the field and its controls, status
 * lines), stacked like a leading column. Folded, it's only as tall as what stays: the children not
 * marked [composerFolds], from their [ComposerFoldTop] line down. Everything else keeps its place,
 * outside the bounds, for the composer's (clipped) edge to pass over.
 *
 * @param foldProgress 0 unfolded … 1 folded; animate it (iOS animates the layout itself).
 * @param attachmentRevealProgress the location row's reveal (iOS `Animatable` data).
 */
@Composable
fun ComposerFoldLayout(
    foldProgress: Float,
    modifier: Modifier = Modifier,
    attachmentRevealProgress: Float = 1f,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(minWidth = 0, maxWidth = width)) }
        val folds = measurables.map { it.folds }
        val firstStaying = folds.indexOfFirst { !it }
        val firstStayingFoldTop = if (firstStaying >= 0) {
            placeables[firstStaying][ComposerFoldTop].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
        } else {
            0
        }
        val sizes = placeables.map { it.height.toFloat() }
        val gap = spacing.toPx()
        val open = ComposerFoldFrames.frames(sizes, folds, firstStayingFoldTop.toFloat(), isFolded = false, attachmentRevealProgress, gap)
        val folded = ComposerFoldFrames.frames(sizes, folds, firstStayingFoldTop.toFloat(), isFolded = true, attachmentRevealProgress, gap)
        val p = foldProgress.coerceIn(0f, 1f)
        val lower = open.visible.start + (folded.visible.start - open.visible.start) * p
        val upper = open.visible.endInclusive + (folded.visible.endInclusive - open.visible.endInclusive) * p
        val height = (upper - lower).roundToInt().coerceAtLeast(0)
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(0, (open.frames[index] - lower).roundToInt())
            }
        }
    }
}

private val Measurable.folds: Boolean get() = layoutId == ComposerFoldsMarker

/** iOS `ComposerFoldLayout`'s static geometry: each child's top, and the visible range. */
object ComposerFoldFrames {
    data class Result(val frames: List<Float>, val visible: ClosedFloatingPointRange<Float>)

    fun attachmentRevealProgress(
        locationMounted: Boolean,
        locationPresented: Boolean,
        hasNonLocationAttachments: Boolean,
        reduceMotion: Boolean,
    ): Float = if (!locationMounted || hasNonLocationAttachments || reduceMotion) 1f else if (locationPresented) 1f else 0f

    fun frames(
        sizes: List<Float>,
        folds: List<Boolean>,
        firstStayingFoldTop: Float = 0f,
        isFolded: Boolean,
        attachmentRevealProgress: Float = 1f,
        spacing: Float = 8f,
    ): Result {
        var y = 0f
        val tops = ArrayList<Float>(sizes.size)
        for (size in sizes) {
            if (tops.isNotEmpty()) y += spacing
            tops += y
            y += size
        }
        val clampedReveal = attachmentRevealProgress.coerceIn(0f, 1f)
        if (!isFolded && clampedReveal >= 1f) return Result(tops, 0f..y)
        val staying = tops.indices.filter { !folds[it] }
        val first = staying.firstOrNull() ?: return Result(tops, 0f..y)
        val last = staying.last()
        if (!isFolded) return Result(tops, tops[first] * (1 - clampedReveal)..y)
        return Result(tops, (tops[first] + firstStayingFoldTop)..(tops[last] + sizes[last]))
    }
}

// endregion

// region Trailing control

/** iOS `ComposerTrailingControl`. */
sealed interface ComposerTrailingControl {
    data class Send(val enabled: Boolean) : ComposerTrailingControl
    data object Stop : ComposerTrailingControl
    data object Mic : ComposerTrailingControl
    /** Starts a voice call; holds Send's place while the field is empty. */
    data object Call : ComposerTrailingControl

    val symbolName: String
        get() = when (this) {
            is Send -> "arrow.up"
            Stop -> "stop.fill"
            Mic -> "mic"
            Call -> "waveform"
        }

    val accessibilityLabel: String
        get() = when (this) {
            is Send -> "Send message"
            Stop -> "Stop"
            Mic -> "Dictate message"
            Call -> "Start voice call"
        }

    @Composable
    fun foreground(): Color = when (this) {
        Mic -> OpenCodeTheme.colors.secondaryText
        is Send -> if (enabled) OpenCodeTheme.colors.sendButtonForeground else OpenCodeTheme.colors.secondaryText
        Stop, Call -> OpenCodeTheme.colors.canvas
    }

    @Composable
    fun background(): Color = when (this) {
        is Send -> if (enabled) OpenCodeTheme.colors.sendButtonBackground else Color.Transparent
        Stop, Call -> OpenCodeTheme.colors.text
        Mic -> Color.Transparent
    }
}

/** iOS `ComposerControls`: the trailing control and where dictation sits beside it. */
data class ComposerControls(
    val trailing: ComposerTrailingControl,
    val secondaryMic: MicPlacement,
    val dictation: DictationControls?,
) {
    enum class MicPlacement { none, leading, trailing }
    data class DictationControls(val isFinishing: Boolean)

    companion object {
        fun make(
            isDictating: Boolean,
            isFinishing: Boolean,
            isRunning: Boolean,
            canSend: Boolean,
            expanded: Boolean,
            canCall: Boolean = false,
        ): ComposerControls {
            val dictation = if (isDictating || isFinishing) DictationControls(isFinishing) else null
            // Empty and idle: the call button holds Send's place and dictation sits beside it.
            // During a run Stop takes the place and the call waits.
            val trailing: ComposerTrailingControl = when {
                canSend -> ComposerTrailingControl.Send(enabled = true)
                isRunning -> ComposerTrailingControl.Stop
                canCall && dictation == null -> ComposerTrailingControl.Call
                else -> ComposerTrailingControl.Mic
            }
            val secondaryMic = when {
                dictation != null || trailing == ComposerTrailingControl.Mic -> MicPlacement.none
                expanded -> MicPlacement.leading
                else -> MicPlacement.trailing
            }
            return ComposerControls(trailing, secondaryMic, dictation)
        }
    }
}

// endregion

// region Folded controls

/**
 * iOS `composerHidden(_:fadesOnReveal:)` (`ComposerFoldedControl`): folded away once faded out
 * (under the dictation bar, or a request's + until it's the recording's ×). Its space stays; it
 * leaves TalkBack and stops taking touches. Unfolding fades it in with the row (fadeStandard,
 * after contentDelay) unless [fadesOnReveal] is false.
 */
@Composable
fun ComposerHidden(
    hidden: Boolean,
    modifier: Modifier = Modifier,
    fadesOnReveal: Boolean = true,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val alpha = remember { Animatable(if (!hidden || !fadesOnReveal) 1f else 0f) }
    LaunchedEffect(hidden, fadesOnReveal) {
        if (!fadesOnReveal) return@LaunchedEffect alpha.snapTo(1f)
        if (hidden) {
            alpha.snapTo(0f)
        } else if (alpha.value < 1f) {
            alpha.animateTo(1f, Motion.easeOut(Motion.fadeStandard, if (reduceMotion) 0.0 else Motion.contentDelay, reduceMotion))
        }
    }
    val folded = if (hidden) {
        Modifier
            .alpha(0f)
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                // Swallow everything aimed at the folded control.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
    } else {
        Modifier.alpha(alpha.value)
    }
    Box(modifier.then(folded)) { content() }
}

/**
 * iOS `ComposerAccessoryRow`: the expanded composer's bottom accessory row (model chip and the
 * leading secondary mic). Mounted through the collapse so it fades and blurs out riding the edge,
 * then unmounted so collapsed controls leave TalkBack. A new [screen] resets it without motion.
 */
@Composable
fun ComposerAccessoryRow(
    isExpanded: Boolean,
    screen: Int,
    recording: Boolean,
    modifier: Modifier = Modifier,
    revealed: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    // Keyed by the screen: switching sessions resets the gate (iOS `reset(to:)`).
    val progress = remember(screen) { Animatable(if (isExpanded) 1f else 0f) }
    var mounted by remember(screen) { mutableStateOf(isExpanded) }
    LaunchedEffect(isExpanded, screen) {
        if (isExpanded) {
            mounted = true
            progress.animateTo(
                1f,
                Motion.easeOut(ComposerTunables.rowRevealIn, if (reduceMotion) 0.0 else ComposerTunables.rowRevealDelay, reduceMotion),
            )
        } else {
            progress.animateTo(0f, Motion.easeOut(ComposerTunables.rowRevealOut, reduceMotion = reduceMotion))
            mounted = false
        }
    }
    if (!mounted) return
    val rowAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (recording) 0f else 1f,
        Motion.easeOut(if (recording) Motion.fadeFast else Motion.fadeStandard, reduceMotion = reduceMotion),
        label = "accessoryRowFade",
    )
    val interactive = isExpanded && !recording
    val gate = if (interactive) Modifier else Modifier.clearAndSetSemantics { }.blockTouches()
    Row(modifier.alpha(rowAlpha).then(gate), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.motionBlurReveal(progress.value, maxBlur = ComposerTunables.rowRevealBlur.dp, reduceMotion = reduceMotion)) { revealed() }
        Box(
            Modifier.weight(1f).motionBlurReveal(progress.value, maxBlur = ComposerTunables.rowRevealBlur.dp, reduceMotion = reduceMotion),
            contentAlignment = Alignment.CenterEnd,
        ) { trailing() }
    }
}

/** Swallows every touch aimed at this element (iOS `.allowsHitTesting(false)` over controls). */
internal fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

// endregion

// region Control label

/**
 * iOS `ComposerControlLabel`: a trailing control's glyph in its circle. The circle grows with the
 * font scale up to 44 dp; the 48 dp target comes from the enclosing control.
 */
@Composable
fun ComposerControlLabel(
    systemImage: String,
    showsProgress: Boolean,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 18.dp,
    visualOffsetX: Float = 0f,
    glyphRise: Float = 0f,
) {
    val reduceMotion = rememberReduceMotion()
    val fontScale = LocalDensity.current.fontScale
    val diameter = min(34f * fontScale * (ComposerTunables.buttonDiameter / 34f), 44f)
    // Body grows 28/17 by Accessibility 1, where the glyph stops.
    val textScale = min(fontScale, 28f / 17f)
    Box(
        modifier
            .offset(x = visualOffsetX.dp)
            .size(diameter.dp)
            .background(background, OpenCodeShape.circle),
        contentAlignment = Alignment.Center,
    ) {
        if (showsProgress) {
            CircularProgressIndicator(Modifier.size(16.dp), color = foreground, strokeWidth = 2.dp)
        } else {
            AnimatedContent(
                targetState = systemImage,
                transitionSpec = {
                    val spec = if (reduceMotion) tween<Float>(0) else Motion.easeOut<Float>(Motion.fadeFast)
                    fadeIn(spec) togetherWith fadeOut(spec)
                },
                label = "composerControlGlyph",
            ) { symbol ->
                OpenCodeIcon(
                    symbol,
                    contentDescription = null,
                    size = (glyphSize.value * textScale).dp,
                    tint = foreground,
                    modifier = Modifier.offset(y = (-glyphRise * textScale).dp),
                )
            }
        }
    }
}

// endregion

// region Stop

/**
 * iOS `ComposerStopButton`: the run's Stop. Held (0.5 s, iOS `LongPressGesture`'s default), it
 * starts a call instead ([onHold]) and that press's release never stops the run. TalkBack's
 * activation always stops; the call is its own action.
 */
@Composable
fun ComposerStopButton(
    holdStartsCall: Boolean,
    onStop: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
) {
    val base = LocalViewConfiguration.current
    val config = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long get() = 500
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides config) {
        Box(
            modifier
                .pointerInput(holdStartsCall) {
                    detectTapGestures(
                        onLongPress = if (holdStartsCall) {
                            { onHold() }
                        } else {
                            null
                        },
                        onTap = { onStop() },
                    )
                }
                .semantics { role = Role.Button }
                .openCodeTapTarget(accessibility = false),
            contentAlignment = Alignment.Center,
        ) { label() }
    }
}

// endregion

// region Pills

/** iOS `ComposerPillLabel`: a pill above the composer (background work or pending messages). */
@Composable
fun ComposerPillLabel(
    title: String,
    modifier: Modifier = Modifier,
    rolls: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: @Composable () -> Unit,
) {
    // Slim surface, full 48 dp target.
    Box(
        modifier
            .then(if (onClick != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) else Modifier)
            .openCodeTapTarget(accessibility = false),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(vertical = 5.dp),
            shape = OpenCodeShape.capsule,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            ComposerPillContent(title = title, rolls = rolls, leading = leading)
        }
    }
}

/** iOS `ComposerPillContent`: a pill's glyph and title, without its surface. No chevron. */
@Composable
fun ComposerPillContent(title: String, rolls: Boolean = false, leading: @Composable () -> Unit) {
    val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .padding(start = ComposerTunables.pillGlyphLeading.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(ComposerTunables.pillGlyphGap.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides OpenCodeTheme.colors.text) { leading() }
        }
        if (rolls) {
            RollingNumber(text = title, style = style, color = OpenCodeTheme.colors.text)
        } else {
            Text(title, style = style, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * iOS `JumpToLatestPill`: scales and fades in and out on a spring, retargeting mid-flight; it
 * leaves the tree (and TalkBack) once its outro settles. In a pill row beside the queued pill its
 * slot (the gap before it included) opens and closes on the same spring. Reduce Motion cross-fades.
 */
@Composable
fun JumpToLatestPill(
    isShown: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    collapsesSlot: Boolean = false,
) {
    val reduceMotion = rememberReduceMotion()
    val presence = remember { Animatable(if (isShown) 1f else 0f) }
    var mounted by remember { mutableStateOf(isShown) }
    LaunchedEffect(isShown, reduceMotion) {
        if (isShown) mounted = true
        val spec = when {
            reduceMotion -> Motion.easeInOut<Float>(0.2)
            isShown -> JumpToLatestPillMotion.intro.spec()
            else -> JumpToLatestPillMotion.outro.spec()
        }
        presence.animateTo(if (isShown) 1f else 0f, spec)
        if (!isShown) mounted = false
    }
    val slotOpen = if (reduceMotion) (if (isShown) 1f else 0f) else presence.value.coerceIn(0f, 1f)
    val slotWidth = if (collapsesSlot) (48f + JumpToLatestPillMotion.gap) * slotOpen else 48f
    Box(modifier.width(slotWidth.dp).heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
        if (mounted) {
            val p = presence.value
            Box(
                Modifier
                    .scale(if (reduceMotion) 1f else 0.5f + 0.5f * p)
                    .alpha(p.coerceIn(0f, 1f))
                    .coveredByBackgroundWork()
                    .then(
                        if (isShown) {
                            Modifier
                                .clickable(onClickLabel = null, role = Role.Button, onClick = onClick)
                                .semantics { contentDescription = "Scroll to latest" }
                        } else {
                            Modifier.clearAndSetSemantics { }.blockTouches()
                        },
                    )
                    .openCodeTapTarget(accessibility = false),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = OpenCodeShape.circle,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        OpenCodeIcon(SFSymbol.ArrowDown, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.text)
                    }
                }
            }
        }
    }
}

/** [JumpToLatestPill]'s springs. */
object JumpToLatestPillMotion {
    const val gap = 8f
    val intro = MotionSpring(0.26, 0.22)
    val outro = MotionSpring(0.18, 0.0)
}

/** iOS `ComposerBanner`: a full-width bar that takes the composer's place, on its same surface. */
@Composable
fun ComposerBanner(
    symbol: SFSymbol,
    title: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = OpenCodeTheme.colors.secondaryText,
    detail: String? = null,
) {
    // The pressed radius must fit even the minimum-height banner.
    val radius = OpenCodeShapeGeometry.radius(28f, 52f)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(radius.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon(symbol, contentDescription = null, size = 18.dp, tint = iconTint, modifier = Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium), color = OpenCodeTheme.colors.text)
                if (detail != null) {
                    Text(detail, style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.widthIn(min = 8.dp))
            Text(action, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
        }
    }
}

// endregion
