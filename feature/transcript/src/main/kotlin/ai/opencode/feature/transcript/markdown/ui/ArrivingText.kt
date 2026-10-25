package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.TranscriptAction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a row does with a link: a file link opens the viewer, a session link opens its session,
 * a web link opens (iOS `onOpenFile` / the transcript's link handling), and the hold menu's
 * actions go to the session (iOS `LinkMenuHandler.perform`).
 */
internal object MarkdownLinkActions {
    fun tap(url: String, send: (TranscriptAction) -> Unit) {
        val file = MarkdownLinks.fileReference(url)
        send(if (file != null) TranscriptAction.OpenFile(file) else TranscriptAction.LinkTapped(url))
    }

    fun perform(action: LinkMenuAction, url: String, send: (TranscriptAction) -> Unit) {
        send(if (action == LinkMenuAction.OpenSession) TranscriptAction.LinkTapped(url) else TranscriptAction.LinkMenu(action, url))
    }
}

/** A link listener for `LinkAnnotation`s (TalkBack's activation; touches go through [ArrivingText]'s own gesture). */
@Composable
internal fun rememberLinkListener(): LinkInteractionListener {
    val send by rememberUpdatedState(LocalTranscriptRowContext.current.send)
    return remember {
        LinkInteractionListener { link -> (link as? LinkAnnotation.Clickable)?.tag?.let { MarkdownLinkActions.tap(it, send) } }
    }
}

/**
 * Text whose newest characters fade in (iOS `ArrivingText`). The text is laid out once as it
 * is; while chunks arrive, each chunk's own characters are erased by `1 - opacity` over the
 * drawn text (opacity only, read in the draw phase from a frame clock), so no glyph moves and
 * none takes another chunk's clock. Once every fade has run it is plain text again.
 *
 * Also draws the text's session chips under their glyphs (iOS `SessionChipRenderer`), opens
 * links on tap and their hold menu on long press (iOS `linkMenu(for:)`), and gives TalkBack the
 * menu's actions.
 */
@Composable
internal fun ArrivingText(
    presented: PresentedText,
    arrivals: List<TranscriptTextReveal.Arrival>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
) {
    val context = LocalTranscriptRowContext.current
    val send by rememberUpdatedState(context.send)
    val reveal = LocalTranscriptTextReveal.current ?: remember { TranscriptTextReveal() }
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val now = reveal.now()
    // Typeset math sits in the text as images; it appears without the per-character fade.
    val active = if (presented.hasMath) emptyList() else arrivals.filter { now - it.time < it.duration }
    val ends = active.maxOfOrNull { it.time + it.duration }
    val frame = rememberRevealFrame(ends, reveal.now)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pressed by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    val haptics = remember { dependency(HapticsClient) }
    val chipGlyphs = if (presented.chips.isEmpty()) null else ChipGlyphs(
        session = painterResource(SFSymbol.BubbleLeftFill.drawable),
        subagent = painterResource(SFSymbol.ArrowTurnDownRight.drawable),
        muted = painterResource(SFSymbol.Number.drawable),
    )
    val emPx = with(density) { style.fontSize.toPx() }
    val chipStyle = ChipStyle(
        ink = colors.text,
        glyph = colors.secondaryText,
        fillScale = (if (colors.isDark) 1.6f else 1f) * (if (colors.highContrast) 1.8f else 1f),
    )
    val links = presented.menuLinks

    Box(modifier) {
        Text(
            text = presented.text,
            style = style,
            color = color,
            softWrap = softWrap,
            inlineContent = presented.inlineContent,
            onTextLayout = { layout = it },
            modifier = Modifier
                .graphicsLayer {
                    compositingStrategy = if (ends != null) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawWithContent {
                    val result = layout
                    if (result != null && chipGlyphs != null) {
                        for (chip in presented.chips) drawChipBackground(result, chip, emPx, chipStyle, chip.url == pressed)
                    }
                    drawContent()
                    if (result != null && chipGlyphs != null) {
                        for (chip in presented.chips) drawChipGlyph(result, chip, emPx, chipStyle, chipGlyphs)
                    }
                    if (result != null && active.isNotEmpty()) {
                        frame.longValue
                        eraseArriving(result, active, reveal.now())
                    }
                }
                .then(
                    if (presented.menuLinks.isEmpty() && presented.text.getLinkAnnotations(0, presented.text.length).isEmpty()) {
                        Modifier
                    } else {
                        Modifier.pointerInput(presented) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val url = layout?.let { linkAt(it, presented, down.position) } ?: return@awaitEachGesture
                                down.consume()
                                pressed = url
                                val slop = viewConfiguration.touchSlop
                                // Holding decides a little before selection's own long press, so the
                                // menu wins and the consumed events cancel selection's.
                                val holdAfter = (viewConfiguration.longPressTimeoutMillis * 0.85).toLong()
                                // 0 cancelled, 1 tap, 2 hold
                                val decided = withTimeoutOrNull(holdAfter) {
                                    var result = -1
                                    while (result < 0) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        result = when {
                                            change == null -> 0
                                            !change.pressed -> { change.consume(); 1 }
                                            (change.position - down.position).getDistance() > slop -> 0
                                            else -> -1
                                        }
                                    }
                                    result
                                }
                                val outcome = decided ?: 2
                                when (outcome) {
                                    1 -> MarkdownLinkActions.tap(url, send)
                                    2 -> if (MarkdownLinks.offersMenu(url)) {
                                        haptics.play(Haptic.tap)
                                        menu = url to down.position
                                        // Holding: the rest of the touch belongs to the menu.
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            event.changes.forEach { it.consume() }
                                            if (event.changes.none { it.pressed }) break
                                        }
                                    }
                                }
                                pressed = null
                            }
                        }
                    },
                )
                .semantics {
                    if (links.isNotEmpty()) {
                        customActions = links.flatMap { url ->
                            MarkdownLinks.accessibilityActions(url).map { action ->
                                CustomAccessibilityAction(MarkdownLinks.accessibilityName(action, url, links)) {
                                    MarkdownLinkActions.perform(action, url, send)
                                    true
                                }
                            }
                        }
                    }
                },
        )
        val shown = menu
        if (shown != null) {
            val (url, at) = shown
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.size(0.dp)) {
                DropdownMenu(expanded = true, onDismissRequest = { menu = null }, offset = DpOffset.Zero) {
                    for (action in MarkdownLinks.menuActions(url)) {
                        DropdownMenuItem(
                            text = { Text(action.title) },
                            leadingIcon = { OpenCodeIcon(action.systemImage, contentDescription = null) },
                            onClick = {
                                menu = null
                                MarkdownLinkActions.perform(action, url, send)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The link under [position], if a character's box holds it (taps beside a line's end miss). */
private fun linkAt(layout: TextLayoutResult, presented: PresentedText, position: Offset): String? {
    if (presented.text.isEmpty()) return null
    val offset = layout.getOffsetForPosition(position)
    for (candidate in listOf(offset, offset - 1)) {
        if (candidate !in 0 until presented.text.length) continue
        val box = layout.getBoundingBox(candidate).inflate(4f)
        if (!box.contains(position)) continue
        val link = presented.text.getLinkAnnotations(candidate, candidate + 1).firstOrNull()?.item as? LinkAnnotation.Clickable
        if (link != null) return link.tag
    }
    return null
}

/** Erases each arriving chunk's characters by `1 - opacity` (DstOut over the offscreen layer). */
private fun DrawScope.eraseArriving(layout: TextLayoutResult, active: List<TranscriptTextReveal.Arrival>, now: Double) {
    val length = layout.layoutInput.text.length
    val sorted = active.sortedBy { it.offset }
    for ((index, arrival) in sorted.withIndex()) {
        val start = arrival.offset.coerceIn(0, length)
        val end = (if (index + 1 < sorted.size) sorted[index + 1].offset else length).coerceIn(start, length)
        if (end <= start) continue
        val hidden = 1f - TranscriptTextReveal.opacity(arrival, now)
        if (hidden <= 0.004f) continue
        val path: Path = layout.getPathForRange(start, end)
        drawPath(path, Color.Black, alpha = hidden, blendMode = BlendMode.DstOut)
    }
}

private class ChipGlyphs(val session: Painter, val subagent: Painter, val muted: Painter)

private class ChipStyle(val ink: Color, val glyph: Color, val fillScale: Float)

private object SessionChipMetrics {
    /** The chip's height and how far its centre sits above the baseline, in ems (iOS `SessionLinkTunables`). */
    const val height = 1.16f
    const val rise = 0.31f
    const val fill = 0.08f
    /** The chip's corner radius at body size (17 pt); it scales with the text. */
    const val radius = 4.5f
}

/** Each line's run of the chip, as boxes around the text (iOS `SessionChipMetrics.box`). */
private fun chipBoxes(layout: TextLayoutResult, chip: SessionChip, em: Float): List<Rect> {
    val length = layout.layoutInput.text.length
    val start = chip.start.coerceIn(0, length)
    val end = chip.end.coerceIn(start, length)
    if (end <= start) return emptyList()
    val boxes = mutableListOf<Rect>()
    var line = -1
    var minX = 0f
    var maxX = 0f
    fun flush() {
        if (line < 0) return
        val centre = layout.getLineBaseline(line) - em * SessionChipMetrics.rise
        val h = em * SessionChipMetrics.height
        boxes += Rect(minX, centre - h / 2, maxX, centre + h / 2)
    }
    for (offset in start until end) {
        val box = layout.getBoundingBox(offset)
        val l = layout.getLineForOffset(offset)
        if (l != line) {
            flush()
            line = l
            minX = box.left
            maxX = box.right
        } else {
            minX = minOf(minX, box.left)
            maxX = maxOf(maxX, box.right)
        }
    }
    flush()
    return boxes
}

private fun DrawScope.drawChipBackground(layout: TextLayoutResult, chip: SessionChip, em: Float, style: ChipStyle, pressed: Boolean) {
    val alpha = (SessionChipMetrics.fill * style.fillScale * (if (chip.muted) 0.6f else 1f) * (if (pressed) 2f else 1f)).coerceAtMost(1f)
    for (box in chipBoxes(layout, chip, em)) {
        val radius = minOf(SessionChipMetrics.radius * em / 17f, box.height / 2)
        drawRoundRect(style.ink.copy(alpha = style.ink.alpha * alpha), box.topLeft, box.size, CornerRadius(radius, radius))
    }
}

/** The glyph over the chip's leading end: as far from the leading edge as from the top and bottom. */
private fun DrawScope.drawChipGlyph(layout: TextLayoutResult, chip: SessionChip, em: Float, style: ChipStyle, glyphs: ChipGlyphs) {
    val box = chipBoxes(layout, chip, em).firstOrNull() ?: return
    val painter = when {
        chip.muted -> glyphs.muted
        chip.isSubagent -> glyphs.subagent
        else -> glyphs.session
    }
    val side = em * 0.6f
    val centre = Offset(box.left + box.height / 2, box.center.y)
    val tint = style.glyph.copy(alpha = style.glyph.alpha * (if (chip.muted) 0.7f else 1f))
    translate(centre.x - side / 2, centre.y - side / 2) {
        with(painter) { draw(Size(side, side), colorFilter = ColorFilter.tint(tint)) }
    }
}
