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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
fun transcriptNow(): Double = System.nanoTime() / 1e9

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
    val elapsed = loadingSince?.let { now - it - timing.delay }
    val glyph = with(LocalDensity.current) { (EmptyStateTunables.glyphSize * fontScale).dp }
    OpenCodeIcon(
        "bubble.left.and.bubble.right",
        contentDescription = null,
        modifier = modifier.size(glyph).graphicsLayer {
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
    val title = remember { Animatable(if (showsText) 1f else 0f) }
    val subtitle = remember { Animatable(if (showsText) 1f else 0f) }
    LaunchedEffect(showsText, reduceMotion) {
        val target = if (showsText) 1f else 0f
        val spec = if (reduceMotion) {
            androidx.compose.animation.core.tween<Float>(200)
        } else {
            spring(dampingRatio = 1f, stiffness = EmptyStateTunables.revealSpring.stiffness())
        }
        kotlinx.coroutines.coroutineScope {
            launch { title.animateTo(target, spec) }
            launch {
                if (!reduceMotion && target > 0.5f) delay((EmptyStateTunables.subtitleDelay * 1000).toLong())
                subtitle.animateTo(target, spec)
            }
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
                    modifier = Modifier.size(glyph),
                    tint = OpenCodeTheme.colors.secondaryText,
                )
            }
            Spacer(Modifier.height(EmptyStateTunables.glyphSpacing.dp))
            Text(
                "No messages yet",
                modifier = Modifier.motionBlurReveal(title.value, EmptyStateTunables.revealBlur.dp, EmptyStateTunables.revealRise.dp, reduceMotion),
                style = OpenCodeTextStyles.title2.copy(fontWeight = FontWeight.Bold),
                color = OpenCodeTheme.colors.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(EmptyStateTunables.subtitleSpacing.dp))
            Text(
                "Start the conversation below.",
                modifier = Modifier.motionBlurReveal(subtitle.value, EmptyStateTunables.revealBlur.dp, EmptyStateTunables.revealRise.dp, reduceMotion),
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Stiffness of a critically damped `(duration, bounce = 0)` spring: ω² with ω = 2π / duration. */
private fun MotionSpring.stiffness(): Float {
    val omega = 2 * PI / max(0.01, duration)
    return (omega * omega).toFloat()
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
