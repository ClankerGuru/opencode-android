package ai.opencode.feature.transcript.list

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.motionBlurReveal
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.server.model.OpenCodeQueuedInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/** Earlier history: a delayed spinner while paging, the error with Retry, else a 1 dp spacer (iOS `TranscriptHistoryStatusView`). */
@Composable
fun TranscriptHistoryStatusView(isLoading: Boolean, error: String?, retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = TranscriptMetrics.standard),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered),
    ) {
        when {
            // Every open pages once to learn whether history exists; healthy round trips don't flash activity.
            isLoading -> DelayedProgressView(Modifier.semantics { contentDescription = "Loading earlier messages" })
            error != null -> {
                Text(error, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
                TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Retry", style = OpenCodeTextStyles.footnote)
                }
            }
            else -> Spacer(Modifier.height(1.dp).clearAndSetSemantics { invisibleToUser() })
        }
    }
}

/** A spinner that only appears once a wait has lasted long enough to notice (iOS `DelayedProgressView`, 400 ms). */
@Composable
fun DelayedProgressView(modifier: Modifier = Modifier, delayMillis: Long = 400) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
        isVisible = true
    }
    CircularProgressIndicator(modifier.size(20.dp).alpha(if (isVisible) 1f else 0f), strokeWidth = 2.dp)
}

/** Tunables for the empty and loading session state (iOS `EmptyStateTunables`, OC-430), baked defaults. */
object EmptyStateTunables {
    const val breathPeriod = 1.7
    const val breathMinOpacity = 0.45
    const val breathMinScale = 0.94f
    const val breathSettle = 0.34
    const val reducedLoadingOpacity = 0.5
    val revealSpring = MotionSpring(0.42, 0.0)
    const val revealBlur = 8f
    const val revealRise = 6f
    const val subtitleDelay = 0.04
    const val exitFade = 0.22
    const val glyphSize = 48f
    const val glyphSpacing = 14f
    const val subtitleSpacing = 6f
    const val verticalOffset = 0f
}

/** iOS `EmptyGlyphBreath`: phase, position and velocity survive a loading/reduced-motion change. */
data class EmptyGlyphBreath(
    val isActive: Boolean = false,
    val reduceMotion: Boolean = false,
    val start: Double = 0.0,
    val phase: Double = 0.0,
    val offset: Double = 0.0,
    val offsetVelocity: Double = 0.0,
) {
    private fun target(time: Double): Pair<Double, Double> {
        if (!isActive) return 0.0 to 0.0
        if (reduceMotion) return 1.0 to 0.0
        val omega = 2 * PI / max(0.2, EmptyStateTunables.breathPeriod)
        val x = omega * (phase + max(0.0, time - start))
        return (0.5 - 0.5 * cos(x)) to (0.5 * omega * sin(x))
    }

    private fun error(time: Double): Pair<Double, Double> = ai.opencode.core.motion.SpringSolver.evaluate(
        from = offset, to = 0.0, initialVelocity = offsetVelocity,
        spring = MotionSpring(if (reduceMotion) ai.opencode.core.motion.Motion.reducedFade else EmptyStateTunables.breathSettle, 0.0),
        elapsed = max(0.0, time - start),
    )

    fun excursion(time: Double): Pair<Double, Double> {
        val target = target(time)
        val error = error(time)
        return (target.first + error.first).coerceIn(0.0, 1.0) to (target.second + error.second)
    }

    fun opacity(time: Double): Float = (1 - excursion(time).first *
        (1 - if (reduceMotion) EmptyStateTunables.reducedLoadingOpacity else EmptyStateTunables.breathMinOpacity)).toFloat()

    fun scale(time: Double): Float = if (reduceMotion) 1f else 1 - excursion(time).first.toFloat() * (1 - EmptyStateTunables.breathMinScale)

    fun isSettled(time: Double): Boolean {
        if (isActive && !reduceMotion) return false
        val error = error(time)
        return abs(error.first) < 0.002 && abs(error.second) < 0.01
    }

    fun retarget(active: Boolean, reduced: Boolean, time: Double): EmptyGlyphBreath {
        val current = excursion(time)
        var next = EmptyGlyphBreath(active, reduced, time)
        if (active && !reduced) next = next.copy(phase = acos(1 - 2 * current.first) / (2 * PI) * max(0.2, EmptyStateTunables.breathPeriod))
        val target = next.target(time)
        return next.copy(offset = current.first - target.first, offsetVelocity = current.second - target.second)
    }
}

/** The empty copy's spring retargets from both its value and its velocity, including during its delay. */
data class EmptyStateMotion(
    val isLoading: Boolean,
    val showsEmptyState: Boolean,
    val reduceMotion: Boolean,
    val usesMarkLoading: Boolean = true,
    val start: Double = transcriptNow(),
    val titleFrom: Double = if (!isLoading && showsEmptyState) 1.0 else 0.0,
    val titleVelocity: Double = 0.0,
    val subtitleFrom: Double = titleFrom,
    val subtitleVelocity: Double = 0.0,
    val breath: EmptyGlyphBreath = EmptyGlyphBreath(isLoading && !usesMarkLoading, reduceMotion, start),
) {
    private val target: Double get() = if (!isLoading && showsEmptyState) 1.0 else 0.0
    fun text(time: Double, subtitle: Boolean = false): Pair<Double, Double> {
        val from = if (subtitle) subtitleFrom else titleFrom
        val velocity = if (subtitle) subtitleVelocity else titleVelocity
        val delay = if (subtitle && !reduceMotion && target > 0.5) EmptyStateTunables.subtitleDelay else 0.0
        val elapsed = max(0.0, time - start) - delay
        if (elapsed <= 0) return from.coerceIn(0.0, 1.0) to velocity
        val result = ai.opencode.core.motion.SpringSolver.evaluate(
            from = from, to = target, initialVelocity = velocity,
            spring = if (reduceMotion) MotionSpring(ai.opencode.core.motion.Motion.reducedFade, 0.0) else EmptyStateTunables.revealSpring,
            elapsed = elapsed,
        )
        return result.first.coerceIn(0.0, 1.0) to result.second
    }

    fun isSettled(time: Double): Boolean = breath.isSettled(time) && listOf(text(time), text(time, true)).all {
        abs(it.first - target) < 0.002 && abs(it.second) < 0.01
    }

    fun retarget(loading: Boolean, empty: Boolean, reduced: Boolean, time: Double): EmptyStateMotion {
        if (isLoading == loading && showsEmptyState == empty && reduceMotion == reduced) return this
        val title = text(time)
        val subtitle = text(time, true)
        return copy(isLoading = loading, showsEmptyState = empty, reduceMotion = reduced, start = time,
            titleFrom = title.first, titleVelocity = title.second, subtitleFrom = subtitle.first, subtitleVelocity = subtitle.second,
            breath = breath.retarget(loading && !usesMarkLoading, reduced, time))
    }
}

/** iOS `MarkLoadingTunables` (`mark.loading.*`). */
object MarkLoadingTunables {
    const val appearDelay = 0.3
    const val minimumTime = 0.4
    const val fadeIn = 0.2
    const val fadeOut = 0.15
}

/**
 * Presentation time only (iOS `MarkLoadingTiming`). A null start is a cached transcript: no
 * loading treatment. Times in seconds on the list's clock.
 */
data class MarkLoadingTiming(
    val start: Double?,
    val delay: Double = MarkLoadingTunables.appearDelay,
    val minimum: Double = MarkLoadingTunables.minimumTime,
    val fadeIn: Double = MarkLoadingTunables.fadeIn,
    val fadeOut: Double = MarkLoadingTunables.fadeOut,
) {
    fun opacity(now: Double): Double {
        val start = start ?: return 0.0
        val progress = min(1.0, max(0.0, (now - start - delay) / fadeIn))
        return progress * progress * (3 - 2 * progress)
    }

    /** Null means content arrived before the indicator appeared. Never delay it. */
    fun handoffDelay(now: Double): Double? {
        val start = start ?: return null
        if (now <= start + delay) return null
        return max(0.0, start + delay + max(minimum, fadeIn) - now)
    }

    fun isSettled(now: Double): Boolean {
        val start = start ?: return true
        return now >= start + delay + fadeIn
    }
}

/** A cosine pulse from full down to the breath trough and back; Reduce Motion holds a steady dim (iOS `LoadingGlyphPulse`). */
object LoadingGlyphPulse {
    fun excursion(elapsed: Double?): Double {
        val e = elapsed ?: return 0.0
        val period = max(0.2, EmptyStateTunables.breathPeriod)
        return 0.5 - 0.5 * cos(max(0.0, e) * 2 * PI / period)
    }

    fun opacity(elapsed: Double?, reducedMotion: Boolean): Double {
        if (reducedMotion) return EmptyStateTunables.reducedLoadingOpacity
        return 1 - excursion(elapsed) * (1 - EmptyStateTunables.breathMinOpacity)
    }

    fun scale(elapsed: Double?, reducedMotion: Boolean): Float {
        if (reducedMotion) return 1f
        return 1 - excursion(elapsed).toFloat() * (1 - EmptyStateTunables.breathMinScale)
    }
}

/** The list's monotonic clock in seconds (iOS `AnimationTimeScale.referenceNow`). */
fun transcriptNow(): Double = ai.opencode.core.tuning.AnimationTimeScale.shared.now()

/**
 * The chat-bubbles glyph, pulsing while waiting inside the delayed fade (iOS `MarkLoadingView`).
 * [isHidden] pauses it (a background scene, a hidden list, an off-screen row).
 */
@Composable
fun MarkLoadingView(loadingSince: Double?, isHidden: Boolean = false, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val timing = remember(loadingSince) { MarkLoadingTiming(loadingSince) }
    var now by remember { mutableDoubleStateOf(transcriptNow()) }
    var settled by remember(loadingSince) { mutableStateOf(false) }
    val paused = isHidden || loadingSince == null || (settled && reduceMotion)
    LaunchedEffect(paused, loadingSince) {
        if (paused) return@LaunchedEffect
        motionFrameLoop {
            now = transcriptNow()
            if (timing.isSettled(now)) settled = true
            MotionFrameAction.Continue
        }
    }
    val glyph = with(LocalDensity.current) { (EmptyStateTunables.glyphSize * fontScale).dp }
    OpenCodeIcon(
        "bubble.left.and.bubble.right",
        contentDescription = null,
        modifier = modifier.size(glyph).graphicsLayer {
            val elapsed = loadingSince?.let { now - it - timing.delay }
            val scale = LoadingGlyphPulse.scale(elapsed, reduceMotion)
            scaleX = scale
            scaleY = scale
            alpha = (timing.opacity(now) * LoadingGlyphPulse.opacity(elapsed, reduceMotion)).toFloat()
        },
        tint = OpenCodeTheme.colors.secondaryText,
    )
}

/**
 * The empty and loading session state (iOS `TranscriptEmptyView`): the pulsing mark while a load
 * shows, the bubble glyph above “No messages yet” once it settles empty, revealed with
 * [motionBlurReveal] on the reveal spring (subtitle 0.04 s later). Centred in the fitted viewport
 * height by the list.
 */
@Composable
fun TranscriptEmptyView(
    isLoading: Boolean,
    showsEmptyState: Boolean,
    loadingSince: Double?,
    pulsePaused: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val showsText = !isLoading && showsEmptyState
    var motion by remember { mutableStateOf(EmptyStateMotion(isLoading, showsEmptyState, reduceMotion)) }
    var now by remember { mutableDoubleStateOf(transcriptNow()) }
    LaunchedEffect(isLoading, showsEmptyState, reduceMotion, pulsePaused) {
        now = transcriptNow()
        motion = motion.retarget(isLoading, showsEmptyState, reduceMotion, now)
        if (pulsePaused || motion.isSettled(now)) return@LaunchedEffect
        motionFrameLoop {
            now = transcriptNow()
            if (motion.isSettled(now)) MotionFrameAction.Stop else MotionFrameAction.Continue
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(vertical = TranscriptMetrics.standard),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!showsText) {
                MarkLoadingView(
                    loadingSince = loadingSince,
                    isHidden = pulsePaused,
                    modifier = Modifier.semantics { if (isLoading) contentDescription = "Loading session" else invisibleToUser() },
                )
            } else {
                val glyph = with(LocalDensity.current) { (EmptyStateTunables.glyphSize * fontScale).dp }
                OpenCodeIcon(
                    "bubble.left.and.bubble.right",
                    contentDescription = null,
                    modifier = Modifier.size(glyph).graphicsLayer {
                        alpha = motion.breath.opacity(now)
                        scaleX = motion.breath.scale(now)
                        scaleY = scaleX
                    },
                    tint = OpenCodeTheme.colors.secondaryText,
                )
            }
            Spacer(Modifier.height(EmptyStateTunables.glyphSpacing.dp))
            Text(
                "No messages yet",
                modifier = Modifier.motionBlurReveal(motion.text(now).first.toFloat(), EmptyStateTunables.revealBlur.dp, EmptyStateTunables.revealRise.dp, reduceMotion)
                    .then(if (!showsText) Modifier.clearAndSetSemantics { } else Modifier),
                style = OpenCodeTextStyles.title2.copy(fontWeight = FontWeight.Bold),
                color = OpenCodeTheme.colors.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(EmptyStateTunables.subtitleSpacing.dp))
            Text(
                "Start the conversation below.",
                modifier = Modifier.motionBlurReveal(motion.text(now, true).first.toFloat(), EmptyStateTunables.revealBlur.dp, EmptyStateTunables.revealRise.dp, reduceMotion)
                    .then(if (!showsText) Modifier.clearAndSetSemantics { } else Modifier),
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Pending compaction (iOS `TranscriptQueuedInputView`); plain pending messages render through the
 * user message row, and pending notices fold into Used N groups.
 */
@Composable
fun TranscriptQueuedInputView(input: OpenCodeQueuedInput, modifier: Modifier = Modifier) {
    if (!input.isCompaction) return
    // Admitted but not started; the durable row takes over while running.
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Compaction queued" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.standard),
    ) {
        val line = OpenCodeTheme.colors.border
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OpenCodeIcon("diamond", contentDescription = null, modifier = Modifier.size(11.dp), tint = OpenCodeTheme.colors.tertiaryText)
            Text("Compaction queued", style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.tertiaryText)
        }
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}
