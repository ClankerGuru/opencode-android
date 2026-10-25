package ai.opencode.feature.transcript.user

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** The bubble's text: body with iOS's 2 pt of line spacing. */
internal val UserBubbleTextStyle = OpenCodeTextStyles.body.copy(lineHeight = (22 + UserBubbleFold.lineSpacing).sp)

/**
 * A user message's text bubble (iOS `TranscriptUserBubble`). Holding it lifts it and opens its
 * menu: Copy and Select (plus Send Now, Move to Queue and Delete while it waits as a steering
 * message) and Revert to Here when allowed, rather than word-by-word selection; Select enters
 * native range selection over the whole text. A failed send's bubble opens Retry, Edit and Delete
 * on a tap instead.
 */
@Composable
internal fun TranscriptUserBubble(
    text: String,
    /** Dimmed while the message waits in the inbox, or after its send failed. */
    isPending: Boolean = false,
    itemID: String? = null,
    reveal: AgentMessageReveal.Frame = AgentMessageReveal.Frame.rest,
    failedSend: TranscriptFailedSend? = null,
    pendingActions: TranscriptPendingActions? = null,
    onCopy: (String) -> Unit = {},
    onRevertToHere: (() -> Unit)? = null,
    /** Tight where it meets another surface of its run. */
    corners: UserSurfaceCorners = UserSurfaceCorners.round,
    fold: UserBubbleFold.State = UserBubbleFold.State(),
    modifier: Modifier = Modifier,
) {
    var isSelecting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val folds = remember { BooleanArray(1) }
    val reduceMotion = rememberReduceMotion()
    val context = LocalTranscriptRowContext.current
    // A new item, or closing its fold, ends selection (iOS `onChange(of: itemID / isExpanded)`).
    LaunchedEffect(itemID) { isSelecting = false }
    LaunchedEffect(fold.isExpanded) { if (!fold.isExpanded) isSelecting = false }

    fun beginSelection() {
        if (folds[0] && !fold.isExpanded) fold.toggle()
        isSelecting = true
    }

    // The bubble lifts while its menu is open, as iOS's context menu lifts its preview.
    val lift by animateFloatAsState(if (menu) 1.03f else 1f, if (reduceMotion) Motion.reducedFadeSpec() else Motion.microPop.spec(), label = "lift")
    TrailingFractionLayout(TranscriptMetrics.maximumUserBubbleFraction, modifier) {
        Box {
            val face: @Composable (Modifier) -> Unit = { faceModifier ->
                UserBubbleFace(
                    text = text,
                    isPending = isPending,
                    isSelecting = isSelecting,
                    reveal = reveal,
                    corners = corners,
                    fold = fold,
                    folds = folds,
                    reduceMotion = reduceMotion,
                    onEndSelection = { isSelecting = false },
                    modifier = faceModifier.graphicsLayer {
                        scaleX = lift
                        scaleY = lift
                    },
                )
            }
            if (failedSend != null) {
                face(
                    Modifier
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu = true }
                        .semantics { customActions = failedSend.accessibilityActions },
                )
                UserMessageMenu(menu, { menu = false }) { failedSend.Actions { menu = false } }
            } else {
                // Long press opens the bubble's menu (lifting the bubble shape), not word-by-word
                // text selection; Select enters native range selection.
                val hasMenu = pendingActions != null || text.isNotEmpty()
                face(
                    Modifier.transcriptDragSource(
                        payload = if (isSelecting || isPending || pendingActions != null) null else TranscriptDragPayload.Text(text),
                        cornerRadius = TranscriptMetrics.bubbleCornerRadius,
                        surface = TranscriptDragSurfaceStyle.Bubble,
                        context = TranscriptDragContext(context.server, context.directory),
                        onLift = if (hasMenu && !isSelecting) ({ menu = true }) else null,
                        // Dragging out closes the menu the hold opened.
                        onDragStart = { menu = false },
                    ),
                )
                UserMessageMenu(menu, { menu = false }) {
                    val dismiss = { menu = false }
                    if (pendingActions != null) {
                        pendingActions.Menu(copying = text, dismiss = dismiss, selecting = ::beginSelection)
                    } else if (text.isNotEmpty()) {
                        UserMenuItem("Copy", "square.on.square", dismiss) { onCopy(text) }
                        UserMenuItem("Select", "selection.pin.in.out", dismiss, onClick = ::beginSelection)
                        if (onRevertToHere != null) UserMenuItem("Revert to Here", "arrow.uturn.backward", dismiss, onClick = onRevertToHere)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubbleFace(
    text: String,
    isPending: Boolean,
    isSelecting: Boolean,
    reveal: AgentMessageReveal.Frame,
    corners: UserSurfaceCorners,
    fold: UserBubbleFold.State,
    folds: BooleanArray,
    reduceMotion: Boolean,
    onEndSelection: () -> Unit,
    modifier: Modifier,
) {
    val morphs = LocalPromotionMorph.current
    // Opacity rather than a secondary style, so promotion can animate it.
    val pendingAlpha by animateFloatAsState(if (isPending) 0.6f else 1f, PromotionMotion.animation(reduceMotion, morphs), label = "pending")
    val metrics = UserBubbleFold.Metrics.current()
    UserBubbleFoldView(
        metrics = metrics,
        fold = fold,
        folds = folds,
        content = {
            Box {
                // TODO(hand-off): `SessionMentionText` (session link chips) once rows/ ports it.
                Text(
                    text,
                    style = UserBubbleTextStyle,
                    color = OpenCodeTheme.colors.text,
                    modifier = Modifier
                        .graphicsLayer { alpha = (if (isSelecting) 0f else pendingAlpha) * reveal.text.toFloat() }
                        .then(if (isSelecting) Modifier.clearAndSetSemantics { } else Modifier),
                )
                if (isSelecting) {
                    UserBubbleSelectableText(
                        text = text,
                        isPending = isPending,
                        onEndSelection = onEndSelection,
                        modifier = Modifier.matchParentSize().graphicsLayer { alpha = reveal.text.toFloat() },
                    )
                }
            }
        },
        control = {
            // Inside the bubble, a quiet line under the text.
            Box(Modifier.graphicsLayer { alpha = pendingAlpha * reveal.text.toFloat() }) { fold.Label() }
        },
        modifier = modifier
            .voiceMessageBackground(VoiceMessageStyle.Standard, isPending, reveal, corners)
            .padding(horizontal = TranscriptMetrics.contentGutter, vertical = TranscriptMetrics.bubbleVertical),
    )
}

/**
 * A long user message folds: past [overLines] lines its bubble shows [showLines], fading out over
 * its last two into Show More, which opens it in place on the list's disclosure motion (Show Less
 * closes it). Whatever sent it, typed, dictated, or another client's paste. TalkBack reads the
 * whole message either way (iOS `UserBubbleFold`).
 */
object UserBubbleFold {
    const val overLines = 12
    const val showLines = 8
    const val lineSpacing = 2f
    val fade: Dp = 44.dp
    /** Show More's row, under the text inside the bubble. */
    val controlHeight: Dp = 32.dp

    /** Open or folded, and the toggle, from the list's `TranscriptDisclosureState`. */
    class State(val isExpanded: Boolean = false, val toggle: () -> Unit = {}) {
        @Composable
        fun Label() {
            val color = OpenCodeTheme.colors.secondaryText
            val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isExpanded) "Show Less" else "Show More", style = style, color = color)
                OpenCodeIcon(if (isExpanded) "chevron.up" else "chevron.down", contentDescription = null, size = smallGlyph(style), tint = color)
            }
        }
    }

    /** Heights at the body font's size, for this font scale, in pixels. */
    data class Metrics(val lineHeight: Float, val lineSpacing: Float) {
        fun height(lines: Int): Float = lines * lineHeight + max(lines - 1, 0) * lineSpacing

        /** Text up to this tall shows whole; a little slack keeps a 12-line message from folding on rounding. */
        val limit: Float get() = height(overLines) + lineHeight / 2
        /** The folded text, cut halfway into the gap under its last line. */
        val folded: Float get() = height(showLines) + lineSpacing / 2

        companion object {
            @Composable
            fun current(): Metrics {
                val density = LocalDensity.current
                return remember(density) {
                    with(density) { Metrics(OpenCodeTextStyles.body.lineHeight.toPx(), lineSpacing.sp.toPx()) }
                }
            }
        }
    }

    /** Whether text this tall folds. */
    fun folds(height: Float, metrics: Metrics): Boolean = height > metrics.limit
}

/**
 * Lays the text out whole when it fits the limit, otherwise folded with its control: one
 * measurement, so the row's first layout is already its height (iOS `UserBubbleFoldView`).
 * Folded, the text is laid out whole and cut, so its last lines fade out mid-sentence rather than
 * ending on an ellipsis.
 */
@Composable
private fun UserBubbleFoldView(
    metrics: UserBubbleFold.Metrics,
    fold: UserBubbleFold.State,
    folds: BooleanArray,
    content: @Composable () -> Unit,
    control: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fadePx = with(LocalDensity.current) { UserBubbleFold.fade.toPx() }
    val currentFold by rememberUpdatedState(fold)
    Layout(
        contents = listOf(
            {
                Box(
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            if (folds[0] && !currentFold.isExpanded) {
                                val end = metrics.folded
                                val start = max(0f, end - fadePx)
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Black,
                                        (start / end) to Color.Black,
                                        1f to Color.Transparent,
                                        startY = 0f,
                                        endY = end,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        },
                ) { content() }
            },
            {
                val label = if (fold.isExpanded) "Show Less" else "Show More"
                Box(
                    Modifier
                        .clickable(onClick = fold.toggle)
                        .semantics {
                            contentDescription = label
                            role = Role.Button
                        },
                    contentAlignment = Alignment.BottomStart,
                ) { control() }
            },
        ),
        modifier = modifier,
    ) { (contentMeasurables, controlMeasurables), constraints ->
        val text = contentMeasurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
        val isFolding = UserBubbleFold.folds(text.height.toFloat(), metrics)
        folds[0] = isFolding
        if (!isFolding) {
            return@Layout layout(text.width, text.height) { text.placeRelative(0, 0) }
        }
        val controlMin = UserBubbleFold.controlHeight.roundToPx()
        val ctl = controlMeasurables.first().measure(
            Constraints(minWidth = text.width, maxWidth = max(text.width, constraints.maxWidth), minHeight = controlMin),
        )
        val textHeight = if (fold.isExpanded) text.height else metrics.folded.toInt()
        val width = max(text.width, ctl.width)
        layout(width, textHeight + ctl.height) {
            text.placeRelative(0, 0)
            ctl.placeRelative(0, textHeight)
        }
    }
}

/**
 * Native range selection over a user bubble after choosing Select from its menu: the whole
 * message selected with the platform handles and text toolbar, until the selection collapses or
 * the field loses focus (iOS `UserBubbleSelectableText`). A read-only text field, so the
 * keyboard stays down.
 */
@Composable
private fun UserBubbleSelectableText(text: String, isPending: Boolean, onEndSelection: () -> Unit, modifier: Modifier) {
    val state: TextFieldState = rememberTextFieldState(text)
    val focus = remember { FocusRequester() }
    val end by rememberUpdatedState(onEndSelection)
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (state.text.toString() != text) state.edit { replace(0, length, text) }
        state.edit { selectAll() }
        focus.requestFocus()
    }
    LaunchedEffect(Unit) {
        val activatedAt = System.nanoTime()
        var adjusted = false
        snapshotFlow { state.selection }.collect { selection ->
            val full = state.text.length
            if (selection.collapsed) {
                // Within 0.45 s of opening, a collapse is the system settling: select it all again.
                if ((System.nanoTime() - activatedAt) / 1e9 < 0.45 && !adjusted) state.edit { selectAll() } else end()
            } else if (selection.min != 0 || selection.max != full) {
                adjusted = true
            }
        }
    }
    BasicTextField(
        state = state,
        readOnly = true,
        textStyle = UserBubbleTextStyle.copy(color = OpenCodeTheme.colors.text.copy(alpha = if (isPending) 0.6f else 1f)),
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) end()
            },
    )
}

/**
 * A Voice message's bubble, and every user bubble's fill: an agent's reveal lights it on arrival
 * (iOS `VoiceMessageStyle`). The badge on its corner ([VoiceMessageIndicator]) is what sets it
 * apart.
 */
enum class VoiceMessageStyle {
    /** The user's own bubble: same fill, no outline. */
    Standard,
    /** No fill, a hairline outline. */
    Outlined;

    companion object {
        val current = Standard
    }
}

/** iOS `VoiceMessageBackground`: the bubble's fill (or outline) and the reveal's light, one layer. */
@Composable
internal fun Modifier.voiceMessageBackground(
    style: VoiceMessageStyle,
    isPending: Boolean,
    reveal: AgentMessageReveal.Frame = AgentMessageReveal.Frame.rest,
    corners: UserSurfaceCorners = UserSurfaceCorners.round,
): Modifier {
    val reduceMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val glow = remember { AgentRevealGlow() }
    // Only the shape: a run's corners tighten or round on their own.
    val full = TranscriptMetrics.bubbleCornerRadius
    val spec = PromotionMotion.animation<Dp>(reduceMotion)
    val topEnd by animateDpAsState(if (corners.top) UserMessageRun.tightRadius else full, spec, label = "topEnd")
    val bottomEnd by animateDpAsState(if (corners.bottom) UserMessageRun.tightRadius else full, spec, label = "bottomEnd")
    val fillAlpha by animateFloatAsState(if (isPending) 0.55f else 1f, PromotionMotion.animation(reduceMotion, LocalPromotionMorph.current), label = "fill")
    val shape = RoundedCornerShape(topStart = full, topEnd = topEnd, bottomEnd = bottomEnd, bottomStart = full)
    val increased = colors.highContrast
    return drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val resting = reveal == AgentMessageReveal.Frame.rest
        val body = reveal.body.toFloat()
        when (style) {
            VoiceMessageStyle.Standard -> if (resting) {
                drawOutline(outline, colors.userBubble.copy(alpha = colors.userBubble.alpha * fillAlpha))
            } else {
                // One layer, so the body's fade-in doesn't let the fill show through the light.
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = body })
                    drawOutline(outline, colors.userBubble.copy(alpha = colors.userBubble.alpha * fillAlpha))
                    with(glow) { draw(reveal.light, corners, colors.isDark) }
                    canvas.restore()
                }
            }
            VoiceMessageStyle.Outlined -> {
                if (!resting) {
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = body })
                        with(glow) { draw(reveal.light, corners, colors.isDark) }
                        canvas.restore()
                    }
                }
                drawOutline(
                    outline,
                    SolidColor(if (increased) colors.secondaryText else colors.border),
                    alpha = (if (isPending) 0.55f else 1f) * body,
                    style = Stroke(1.dp.toPx()),
                )
            }
        }
    }
}
