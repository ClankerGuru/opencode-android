package ai.opencode.feature.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong


/**
 * Which words of each caption line have appeared, and when. New words queue and appear at
 * least [spacing] apart, so a burst of streamed text still flows in word by word. Immutable:
 * each change returns the next reveal. Times are seconds on the monotonic clock.
 */
data class CaptionReveal(
    val lines: List<Line> = emptyList(),
    val lastReveal: Double = Double.NEGATIVE_INFINITY,
    /** The oldest line still shown; lines before it have dropped off the top. */
    val firstLineID: String? = null,
) {
    data class Line(
        val id: String,
        val isUser: Boolean,
        val words: List<String>,
        /** Reveal times of the leading words; the rest are still queued. */
        val revealedAt: List<Double>,
        /** Words the pacer may reveal; a live line holds back its unfinished last word. */
        val revealable: Int,
    ) {
        val text: String get() = words.joinToString(" ")
    }

    /**
     * Matches lines by ID (or, when the live reply settles under a new ID, by position and
     * speaker) and keeps the reveal of every unchanged leading word. [revealed] shows
     * everything at once, for opening onto a call in progress.
     */
    fun sync(targets: List<CaptionTarget>, revealed: Boolean = false): CaptionReveal {
        var firstLineID = firstLineID
        var shown = targets
        if (firstLineID != null) {
            val start = targets.indexOfFirst { it.id == firstLineID }
            if (start >= 0) shown = targets.subList(start, targets.size) else firstLineID = null
        }
        val ids = shown.map { it.id }.toSet()
        val next = shown.mapIndexed { index, target ->
            val words = target.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val hasUnfinishedWord = target.isLive && !(target.text.lastOrNull()?.isWhitespace() ?: true)
            val revealable = if (hasUnfinishedWord) max(0, words.size - 1) else words.size
            var previous = lines.firstOrNull { it.id == target.id }
            if (previous == null && index in lines.indices && lines[index].isUser == target.isUser && lines[index].id !in ids) {
                previous = lines[index]
            }
            val revealedAt = when {
                revealed -> List(revealable) { Double.NEGATIVE_INFINITY }
                previous != null -> {
                    val unchanged = previous.words.zip(words).takeWhile { (a, b) -> a == b }.size
                    previous.revealedAt.take(min(unchanged, revealable))
                }
                else -> emptyList()
            }
            Line(id = target.id, isUser = target.isUser, words = words, revealedAt = revealedAt, revealable = revealable)
        }
        return copy(lines = next, firstLineID = firstLineID)
    }

    /**
     * Drops the oldest lines once more than [maxLines] show (any past [keptLines] when
     * [force]d), or `null` when there's nothing to drop. Call it only while nothing is
     * appearing, so the column's shrink is never read as growth to glide.
     */
    fun trim(force: Boolean = false): CaptionReveal? {
        if (lines.size <= (if (force) keptLines else maxLines)) return null
        val kept = lines.drop(lines.size - keptLines)
        return copy(lines = kept, firstLineID = kept.firstOrNull()?.id)
    }

    val hasQueued: Boolean get() = lines.any { it.revealedAt.size < it.revealable }

    /** Seconds until the next queued word may appear, or `null` when none is queued. */
    fun nextRevealDelay(now: Double): Double? = if (hasQueued) max(0.0, lastReveal + spacing - now) else null

    fun revealNext(now: Double): CaptionReveal {
        val index = lines.indexOfFirst { it.revealedAt.size < it.revealable }
        if (index < 0) return this
        val updated = lines.toMutableList()
        updated[index] = updated[index].copy(revealedAt = updated[index].revealedAt + now)
        return copy(lines = updated, lastReveal = now)
    }

    companion object {
        const val spacing = 0.045

        /**
         * Every line is laid out word by word on each update, so the column keeps only the
         * newest lines: past [maxLines] it drops back to [keptLines].
         */
        const val keptLines = 30
        const val maxLines = 50
    }
}

/** A critically damped follow: when the column jumps by `delta`, it is drawn offset back by that much, and the offset decays exponentially to zero. */
data class CaptionGlide(val value: Float = 0f, val start: Double = 0.0) {
    fun offset(now: Double): Float = value * exp(-max(0.0, now - start) / timeConstant).toFloat()

    fun push(delta: Float, now: Double): CaptionGlide = CaptionGlide(value = offset(now) + delta, start = now)

    /** When the offset falls under a third of a dp. */
    val settlesAt: Double get() = if (abs(value) < 0.3f) start else start + timeConstant * ln(abs(value) / 0.3)

    companion object {
        const val timeConstant = 0.16
    }
}

/** Each word fades in where it lies, out of a light blur (iOS `CaptionWordRenderer`). */
object CaptionWordRenderer {
    const val duration = 0.42

    /** Eased progress of a word revealed [at] by [now] (0 not yet, 1 whole). */
    fun eased(at: Double, now: Double): Float {
        val progress = (now - at) / duration
        if (progress >= 1) return 1f
        if (progress <= 0) return 0f
        return (1 - (1 - progress).pow(3)).toFloat()
    }

    /** The word's blur radius (dp) at [eased]; none under Reduce Motion. */
    fun blur(eased: Float, reduceMotion: Boolean): Float = if (reduceMotion) 0f else (1 - eased) * 3
}

/** Monotonic seconds, the clock captions use (and Compose's frame time). */
private fun captionNow(): Double = System.nanoTime() / 1e9

/**
 * Recent lines of the call, newest at the bottom. The column stays anchored to the bottom and
 * glides when it grows; it follows only while the reader is already at the bottom. Hidden while
 * the surface closes, it shows new words at once and draws nothing frame by frame.
 */
@Composable
internal fun VoiceCaptions(store: VoiceStoreOf, isVisible: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val targets by store.observe { it.captionTargets }
    val isAudioInterrupted by store.observe { it.isAudioInterrupted }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Opening onto a call in progress shows what's there from the first frame.
    var reveal by remember {
        val opening = CaptionReveal().sync(store.state.captionTargets, revealed = true)
        mutableStateOf(opening.trim(force = true) ?: opening)
    }
    var glide by remember { mutableStateOf(CaptionGlide()) }
    var now by remember { mutableDoubleStateOf(captionNow()) }
    var columnHeight by remember { mutableStateOf<Int?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var isFollowing by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val queueChanged = remember { Channel<Unit>(Channel.CONFLATED) }
    val currentVisible by rememberUpdatedState(isVisible)

    // When the newest word has finished appearing and the glide has settled.
    val animationEnd = if (reduceMotion) reveal.lastReveal + CaptionWordRenderer.duration
    else max(reveal.lastReveal + CaptionWordRenderer.duration, glide.settlesAt)
    val isActive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(animationEnd, isVisible, isActive) {
        // Paused when settled, hidden, or in the background; a paused timeline's time is stale,
        // so settled drawing reads the real time.
        now = captionNow()
        if (!animationEnd.isFinite() || !isVisible || !isActive) return@LaunchedEffect
        while (now < animationEnd) withFrameNanos { now = it / 1e9 }
        now = captionNow()
    }

    LaunchedEffect(animationEnd) {
        // Sleep can wake early, and the end may already have passed: wait on the same clock.
        if (!animationEnd.isFinite()) return@LaunchedEffect
        while (captionNow() < animationEnd) delay(((animationEnd - captionNow()) * 1000).roundToLong().coerceAtLeast(1))
        // Settled at the bottom: older lines drop off the top, out of sight.
        if (isFollowing && !scrollState.isScrollInProgress) reveal.trim()?.let { reveal = it }
    }

    // Reveals queued words for the call's lifetime, waking when lines change: one long-lived
    // pacer, never keyed on a flag that toggles within one update.
    LaunchedEffect(Unit) {
        queueChanged.trySend(Unit)
        for (signal in queueChanged) {
            while (true) {
                val delayFor = reveal.nextRevealDelay(captionNow()) ?: break
                if (delayFor > 0) delay((delayFor * 1000).roundToInt().toLong().coerceAtLeast(1))
                reveal = reveal.revealNext(captionNow())
            }
        }
    }
    val firstTargets = remember { BooleanFlag() }
    LaunchedEffect(targets) {
        if (!firstTargets.set) {
            firstTargets.set = true
            return@LaunchedEffect
        }
        reveal = reveal.sync(targets, revealed = !currentVisible)
        queueChanged.trySend(Unit)
    }
    val firstVisible = remember { BooleanFlag() }
    LaunchedEffect(isVisible) {
        if (!firstVisible.set) {
            firstVisible.set = true
            return@LaunchedEffect
        }
        if (isVisible) {
            // Opens on the latest lines.
            isFollowing = true
            scrollState.scrollTo(scrollState.maxValue)
        } else {
            reveal = reveal.sync(store.state.captionTargets, revealed = true)
        }
    }
    // Starts at the bottom; only the reader scrolls away from it, a growing column doesn't.
    LaunchedEffect(scrollState) {
        scrollState.scrollTo(scrollState.maxValue)
        var lastMax = scrollState.maxValue
        snapshotFlow { Triple(scrollState.value, scrollState.maxValue, scrollState.isScrollInProgress) }.collect { (value, maxValue, scrolling) ->
            val fromBottom = with(density) { (maxValue - value).toDp().value }
            if (fromBottom <= 8f) isFollowing = true
            else if (scrolling && lastMax == maxValue) isFollowing = false
            lastMax = maxValue
        }
    }

    fun columnResized(height: Int) {
        // Empty (before the first sync, or after the call moved sessions): start over, so what
        // appears next shows at once instead of gliding.
        if (reveal.lines.none { it.revealedAt.isNotEmpty() }) {
            columnHeight = null
            glide = CaptionGlide()
            isFollowing = true
            return
        }
        val previous = columnHeight
        columnHeight = height
        if (previous == null || !isFollowing) return
        if (height > previous && !reduceMotion && isVisible) {
            glide = glide.push(with(density) { (height - previous).toDp().value }, captionNow())
        }
        if (height != previous) scope.launch { scrollState.scrollTo(scrollState.maxValue) }
    }

    // Pulling down past the top dismisses, like a sheet.
    val pullConnection = remember(store) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                    if (pulled / density.density > 56f) {
                        pulled = 0f
                        store.send(VoiceFeature.Action.CaptionsDismissed)
                    }
                } else if (available.y <= 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                pulled = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    val lines = reveal.lines.filter { it.revealedAt.isNotEmpty() }
    Box(
        modifier
            .padding(top = 6.dp)
            .onSizeChanged { viewportHeight = it.height }
            // Lines rise in under the top edge rather than look clipped, and fade out before they
            // reach the bar, with no line between them.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val top = 14.dp.toPx()
                val bottom = 16.dp.toPx()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        (top / size.height).coerceIn(0f, 1f) to Color.Black,
                        (1 - bottom / size.height).coerceIn(0f, 1f) to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .clickable(interactionSource = null, indication = null) { store.send(VoiceFeature.Action.CaptionsDismissed) }
            .nestedScroll(pullConnection)
            .verticalScroll(scrollState),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = with(density) { viewportHeight.toDp() }),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { columnResized(it.height) }
                    .graphicsLayer { translationY = glide.offset(now) * density.density }
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (lines.isEmpty()) {
                    Text("Say something to your session.", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
                }
                // A line takes space from its first word, so lines still queued behind another leave no gap.
                for (line in lines) {
                    androidx.compose.runtime.key(line.id) {
                        CaptionLine(line = line, now = { now }, reduceMotion = reduceMotion)
                    }
                }
                if (isAudioInterrupted) {
                    Text("Audio paused by another app or call", style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
                }
            }
        }
    }
}

private class BooleanFlag {
    var set = false
}

/**
 * Both speakers start at the leading edge; your lines are dimmer. No visible speaker labels:
 * TalkBack hears “You:” before yours. A line is laid out as its words appear (one view per
 * word, [CaptionWordFlow]), so an appended word never moves an earlier one.
 */
@Composable
private fun CaptionLine(line: CaptionReveal.Line, now: () -> Double, reduceMotion: Boolean) {
    val style = OpenCodeTextStyles.body
    val measurer = rememberTextMeasurer()
    val spaceWidth = remember(style, measurer) { measurer.measure(" ", style).size.width }
    val color = if (line.isUser) OpenCodeTheme.colors.secondaryText else OpenCodeTheme.colors.text
    val composedAt = captionNow()
    Layout(
        modifier = Modifier.clearAndSetSemantics { contentDescription = if (line.isUser) "You: ${line.text}" else line.text },
        content = {
            for (index in line.revealedAt.indices) {
                val at = line.revealedAt[index]
                // Only words still revealing redraw frame by frame.
                val settled = at + CaptionWordRenderer.duration <= composedAt
                Text(
                    line.words[index],
                    style = style,
                    color = color,
                    modifier = if (settled) Modifier else Modifier.graphicsLayer {
                        val eased = CaptionWordRenderer.eased(at, now())
                        alpha = eased
                        val radius = if (eased < 1f) CaptionWordRenderer.blur(eased, reduceMotion) else 0f
                        renderEffect = if (radius > 0.05f) BlurEffect(radius * density, radius * density, TileMode.Decal) else null
                    },
                )
            }
        },
        measurePolicy = captionWordFlow(spaceWidth),
    )
}

/**
 * Words wrapped greedily, one view each (iOS `CaptionWordFlow`). A paragraph of text avoids
 * leaving one word on its last line by pulling the word before it down, so appending a word
 * there would move the previous one; here a word never moves once placed.
 */
private fun captionWordFlow(spaceWidth: Int) = MeasurePolicy { measurables, constraints ->
    val width = constraints.maxWidth
    var x = 0
    var rowY = 0
    var rowHeight = 0
    val placed = measurables.map { measurable ->
        var placeable = measurable.measure(Constraints())
        if (placeable.width > width) {
            // A word wider than the line wraps within its own row.
            placeable = measurable.measure(Constraints(maxWidth = width))
        }
        if (x > 0 && x + spaceWidth + placeable.width > width) {
            rowY += rowHeight
            rowHeight = 0
            x = 0
        } else if (x > 0) {
            x += spaceWidth
        }
        val position = IntOffset(x, rowY)
        x += placeable.width
        rowHeight = max(rowHeight, placeable.height)
        placeable to position
    }
    val maxX = placed.maxOfOrNull { (p, pos) -> pos.x + p.width } ?: 0
    val maxY = placed.maxOfOrNull { (p, pos) -> pos.y + p.height } ?: 0
    layout(maxX.coerceIn(constraints.minWidth, constraints.maxWidth), maxY.coerceAtLeast(constraints.minHeight)) {
        for ((p, pos) in placed) p.place(pos.x, pos.y)
    }
}
