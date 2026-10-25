package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.composer.ComposerSendTransition
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.user.SendLanding
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Port of the flight half of iOS `ComposerSendTransition` (`beginLanding`, `stepLanding`,
// `flyIntoPill`, `dissolvePrepared`, `finishLanding`) and of the list's `SendFlight`
// (`TranscriptListViewController+SendFlights.swift`): the composer's words fly from the field
// into the new user bubble while the follower glides the list up to it, one motion, because the
// flight lands on the bubble wherever the list has carried it this frame. iOS draws snapshots in
// the window; here one overlay spans the transcript and the composer and draws the composer's
// captured words ([ComposerSendTransition.Prepared]) and the bubble's recorded layers
// (`:feature:transcript`'s [SendLanding] markers).

/**
 * A send's flight from the composer into its bubble (iOS `SendFlight` + `ComposerSendTransition.Flight`).
 * The clock starts once the bubble has been laid out (iOS starts it as the cell is found).
 */
private class SendFlight(val itemID: String, val source: ComposerSendTransition.Prepared) {
    /** The flight's ω, captured at its start (iOS `capturedOmega`): about 0.45 s. */
    val omega: Double = TranscriptTunables.sendFlightOmega
    var elapsed: Double = 0.0
    var started = false
    var waitingFrames = 0
    var progress: Float = 0f
    /** The bubble's last known place (window px); a bubble scrolled away keeps it. */
    var destination: Rect? = null

    /** iOS `timeout`: `max(0.8, 8 / ω)`. */
    val timeout: Double get() = max(0.8, 8.0 / max(1.0, omega))

    /** iOS `progress(timestamp:targetTimestamp:)`: critically damped, null once it has landed. */
    fun advance(dt: Double): Float? {
        elapsed += dt
        val p = 1 - (1 + omega * elapsed) * exp(-omega * elapsed)
        if (p >= 0.995 || elapsed >= timeout) return null
        return p.toFloat()
    }
}

/** A send that doesn't fly: its words fade where they are (iOS `dissolvePrepared`), or shrink into the queued pill (`flyIntoPill`). */
private class FadingSend(val source: ComposerSendTransition.Prepared, val pill: Rect?) {
    val progress = Animatable(0f)
}

/**
 * The session's send flights (iOS `scrollEdgeBridge.sendTransition`'s window snapshots). A plain
 * object: it owns per-frame motion, and the reducer never hears of it.
 */
@Stable
internal class SessionSendFlights(
    private val transition: ComposerSendTransition,
    private val landing: SendLanding,
    private val scope: CoroutineScope,
) {
    private val flights = mutableStateListOf<SendFlight>()
    private val fading = mutableStateListOf<FadingSend>()

    /** Bumped every flight frame so the overlay redraws. */
    var frame by mutableIntStateOf(0)
        private set

    val isFlying: Boolean get() = flights.isNotEmpty()

    /** The overlay's place in the window (px), to draw window rects in its own space. */
    var coordinates: LayoutCoordinates? = null

    /**
     * iOS `landSentMessage`. [composerFrame] is the composer's place (window px) for a send the
     * composer didn't capture (dictation's ↑, a send that waited for uploads), [layOut] lays its
     * words out as the composer does. Returns true when it flies into its bubble.
     */
    fun land(
        send: SendLanding.Send,
        reduceMotion: Boolean,
        composerFrame: Rect?,
        queuedPillFrame: Rect?,
        density: Float,
        layOut: (String, Float) -> androidx.compose.ui.text.TextLayoutResult,
    ): Boolean {
        val text = send.text
        if ((send.flies || send.isQueued) && !transition.isPrepared && !reduceMotion && composerFrame != null &&
            composerFrame != Rect.Zero && text != null
        ) {
            transition.prepare(
                text = text,
                composer = composerFrame,
                expandedTopInsetPx = ComposerTunables.expandedTopInset * density,
                contentGutterPx = TranscriptMetrics.contentGutter.value * density,
                measure = layOut,
            )
        }
        // A queued send goes into the pill above the composer.
        if (send.isQueued) {
            if (reduceMotion) transition.cancelPrepared() else transition.takePrepared()?.let { fade(it, pill = queuedPillFrame ?: fallbackPill(it, density)) }
            return false
        }
        if (!send.flies) {
            transition.takePrepared()?.let { fade(it, pill = null) }
            return false
        }
        val itemID = send.itemID
        if (itemID == null || !send.hasRow || !transition.isPrepared || reduceMotion) {
            transition.cancelPrepared()
            return false
        }
        val source = transition.takePrepared() ?: return false
        // A later send to the same bubble replaces its flight.
        flights.removeAll { it.itemID == itemID }
        landing.hide(itemID)
        flights += SendFlight(itemID, source)
        return true
    }

    /** iOS `flyIntoPill`'s target before the pill has appeared: trailing the row above the composer. */
    private fun fallbackPill(source: ComposerSendTransition.Prepared, density: Float): Rect? {
        val width = coordinates?.size?.width?.toFloat() ?: return null
        val origin = coordinates?.positionInWindow() ?: Offset.Zero
        val right = origin.x + width - 20 * density
        return Rect(right - 110 * density, source.bounds.top - 56 * density, right, source.bounds.top - 22 * density)
    }

    private fun fade(source: ComposerSendTransition.Prepared, pill: Rect?) {
        val send = FadingSend(source, pill)
        fading += send
        scope.launch {
            try {
                // iOS: 0.4 s critically damped spring into the pill; a plain 0.18 s ease-out fade otherwise.
                send.progress.animateTo(1f, if (pill != null) MotionSpring(0.4).spec() else Motion.easeOut(0.18))
            } finally {
                fading -= send
            }
        }
    }

    /** One frame of every flight (iOS `stepSendFlights`). */
    fun step(dt: Double) {
        val landed = mutableListOf<SendFlight>()
        for (flight in flights) {
            val marker = landing.marker(flight.itemID)
            if (marker != null && marker.bounds.width > 0f) flight.destination = marker.bounds
            if (!flight.started) {
                if (flight.destination == null) {
                    // The bubble hasn't been laid out yet: a few frames for the follower to bring it.
                    flight.waitingFrames += 1
                    if (flight.waitingFrames > MAXIMUM_WAITING_FRAMES) landed += flight
                    continue
                }
                flight.started = true
                continue
            }
            val progress = flight.advance(dt)
            if (progress == null) landed += flight else flight.progress = progress
        }
        landed.forEach(::finish)
        frame += 1
    }

    /** Lands [flight] now: its row shows again where it is (iOS `finishLanding`). */
    private fun finish(flight: SendFlight) {
        flights.remove(flight)
        if (flights.none { it.itemID == flight.itemID }) landing.show(flight.itemID)
        // A flight that never found its bubble fades where it is.
        if (!flight.started) fade(flight.source, pill = null)
    }

    /**
     * A capture no send took (the list never heard of it): it fades where it is rather than
     * linger over the composer.
     */
    fun dissolveIfStill(prepared: ComposerSendTransition.Prepared) {
        if (transition.prepared === prepared) transition.takePrepared()?.let { fade(it, pill = null) }
    }

    /** Everything, flights included (the screen is going away; iOS `cancel()`). */
    fun cancel() {
        transition.cancelPrepared()
        flights.clear()
        fading.clear()
        landing.showAll()
    }

    fun draw(scope: DrawScope, density: Float) {
        val origin = coordinates?.takeIf { it.isAttached }?.positionInWindow() ?: return
        // The next send's words, captured as they left the field: they stay where they were until they fly.
        transition.prepared?.let { scope.drawWords(it, it.bounds, 1f, origin) }
        for (send in fading) {
            val p = send.progress.value
            val pill = send.pill
            if (pill == null) {
                scope.drawWords(send.source, send.source.bounds, 1 - p, origin)
            } else {
                val from = send.source.bounds
                val scale = max(0.2f, min(1f, pill.height / max(from.height, 1f) * 0.6f))
                val center = lerp(from.center, pill.center, p)
                val s = 1 + (scale - 1) * p
                val size = Size(from.width * s, from.height * s)
                scope.drawWords(send.source, Rect(center - Offset(size.width / 2, size.height / 2), size), 1 - p, origin)
            }
        }
        val gutter = TranscriptMetrics.contentGutter.value * density
        val vertical = TranscriptMetrics.bubbleVertical.value * density
        for (flight in flights) {
            val destination = flight.destination
            if (destination == null) {
                // Waiting for the bubble's first layout: the words stay where they left the field.
                scope.drawWords(flight.source, flight.source.bounds, 1f, origin)
                continue
            }
            val p = if (flight.started) flight.progress else 0f
            val from = flight.source.bounds
            // The words shrink into the bubble's text as the bubble grows out of them.
            val text = Rect(destination.left + gutter, destination.top + vertical, destination.right - gutter, destination.bottom - vertical)
            scope.drawWords(flight.source, ComposerSendTransition.interpolate(from, text, p), 1 - p, origin)
            val bubble = landing.marker(flight.itemID)
            if (bubble != null) {
                val landingRect = ComposerSendTransition.interpolate(
                    Rect(from.left - gutter, from.top - vertical, from.right + gutter, from.bottom + vertical), destination, p,
                )
                bubble.layer.alpha = p
                scope.withTransform({
                    translate(landingRect.left - origin.x, landingRect.top - origin.y)
                    scale(landingRect.width / max(destination.width, 1f), landingRect.height / max(destination.height, 1f), Offset.Zero)
                }) { drawLayer(bubble.layer) }
            }
            // What sits above the bubble in the message itself (a sent image) fades in on top of its landing place.
            val surfaces = landing.marker(flight.itemID + SendLanding.SURFACES_SUFFIX)
            if (surfaces != null && surfaces.bounds.top < destination.top - 1) {
                val band = destination.top - surfaces.bounds.top
                surfaces.layer.alpha = p
                scope.withTransform({ translate(surfaces.bounds.left - origin.x, surfaces.bounds.top - origin.y) }) {
                    clipRect(0f, 0f, surfaces.bounds.width, band) { drawLayer(surfaces.layer) }
                }
            }
            // A steering row's label settles down into place over the flight's last half.
            val label = landing.marker(flight.itemID + SendLanding.LABEL_SUFFIX)
            if (label != null && label.bounds.height > 1) {
                val chrome = ComposerSendTransition.chromeProgress(p)
                val rise = TranscriptTunables.promotionLabelRise * density * (1 - chrome)
                label.layer.alpha = chrome
                scope.withTransform({ translate(label.bounds.left - origin.x, label.bounds.top - origin.y - rise) }) { drawLayer(label.layer) }
            }
        }
    }

    companion object {
        /** How long a flight waits for its bubble's first layout before it fades where it is. */
        const val MAXIMUM_WAITING_FRAMES = 6
    }
}

/** How long a capture waits for the list to take its send. */
private const val UNCLAIMED_CAPTURE_MILLIS = 1_000L

private fun lerp(from: Offset, to: Offset, p: Float) = Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)

/** The captured words stretched into [target] (window px), like iOS's resizable snapshot. */
private fun DrawScope.drawWords(source: ComposerSendTransition.Prepared, target: Rect, alpha: Float, origin: Offset) {
    val layout = source.layout ?: return
    if (alpha <= 0f || source.bounds.width <= 0f || source.bounds.height <= 0f) return
    withTransform({
        translate(target.left - origin.x, target.top - origin.y)
        scale(target.width / source.bounds.width, target.height / source.bounds.height, Offset.Zero)
    }) {
        clipRect(0f, 0f, source.bounds.width, source.bounds.height) {
            drawText(layout, topLeft = source.origin - source.bounds.topLeft, alpha = alpha)
        }
    }
}

/**
 * Hosts the send flights over the transcript and the composer (iOS's window snapshots). Mounted
 * by `SessionView` above the composer; it registers itself as the list's [SendLanding] host.
 * Under Reduce Motion nothing flies: the composer captures nothing and sends arrive as rows.
 */
@Composable
internal fun SessionSendFlightOverlay(host: SessionComposerHost, bridge: SessionScrollEdgeBridge, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val flights = remember(host, bridge) { SessionSendFlights(host.sendTransition, bridge.sendLanding, scope) }
    val reduceMotion by rememberUpdatedState(rememberReduceMotion())
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = ComposerTunables.textStyle.textStyle.copy(color = OpenCodeTheme.colors.text)
    val currentStyle by rememberUpdatedState(style)

    SideEffect {
        bridge.sendLanding.isAwaitingSend = { host.sendTransition.isPrepared }
        bridge.sendLanding.onSend = { send ->
            val coordinates = flights.coordinates
            val composer = host.attachmentAnchors.composer.takeIf { it != Rect.Zero }?.let { root ->
                // Anchors are root px; the flight works in the window's.
                val shift = coordinates?.takeIf { it.isAttached }?.findRootCoordinates()?.positionInWindow() ?: Offset.Zero
                root.translate(shift)
            }
            flights.land(
                send = send,
                reduceMotion = reduceMotion,
                composerFrame = composer,
                queuedPillFrame = host.queuedPillFrame,
                density = density.density,
                layOut = { text, width ->
                    measurer.measure(
                        text,
                        currentStyle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 4,
                        constraints = Constraints(maxWidth = width.roundToInt().coerceAtLeast(1)),
                    )
                },
            )
        }
    }
    DisposableEffect(flights) {
        onDispose {
            bridge.sendLanding.onSend = null
            bridge.sendLanding.isAwaitingSend = { false }
            flights.cancel()
        }
    }
    val prepared = host.sendTransition.prepared
    LaunchedEffect(flights, prepared) {
        if (prepared == null) return@LaunchedEffect
        delay(UNCLAIMED_CAPTURE_MILLIS)
        flights.dissolveIfStill(prepared)
    }
    LaunchedEffect(flights, flights.isFlying) {
        if (!flights.isFlying) return@LaunchedEffect
        var previous = 0L
        while (flights.isFlying) {
            withFrameNanos { now ->
                val raw = if (previous == 0L) 0.0 else (now - previous) / 1e9
                previous = now
                flights.step(AnimationTimeScale.shared.scaledDelta(raw))
            }
        }
    }
    Spacer(
        modifier
            .onGloballyPositioned { flights.coordinates = it }
            .drawBehind {
                @Suppress("UNUSED_VARIABLE") val frame = flights.frame
                flights.draw(this, density.density)
            },
    )
}
