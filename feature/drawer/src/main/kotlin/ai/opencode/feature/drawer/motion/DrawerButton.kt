package ai.opencode.feature.drawer.motion

import ai.opencode.core.motion.transitionBlur

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.rememberHighContrast
import ai.opencode.feature.session.DrawerBadge
import ai.opencode.feature.session.InAppNotice
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Port of `OpenCode/Drawer/DrawerButton.swift`: the session header's drawer button, its badge
 * dot's Brighten arrival (OC-431), and the In-App Notice capsule it grows into.
 *
 * Used by the session header: `:feature:session` sits below `:feature:drawer`, so Home passes
 * [DrawerButton] into the session as a slot rather than the session importing it.
 */

/** Developer Mode → Tuning → Session → Drawer Dot Arrival (iOS `DrawerDotTunables`; baked defaults). */
object DrawerDotTunables {
    const val BRIGHTEN_DURATION = 0.6
    const val BRIGHTEN_FLASH = 0.85
    const val BRIGHTEN_GLOW = 5.5
}

/** Instantaneous visual pose of the drawer button's badge dot (iOS `DrawerDotPose`). */
data class DrawerDotPose(
    val isVisible: Boolean = false,
    val opacity: Double = 0.0,
    val scale: Double = 1.0,
    val flash: Double = 0.0,
    val glowOpacity: Double = 0.0,
    val glowRadius: Double = 0.0,
    val glowScale: Double = 1.0,
) {
    companion object {
        val hidden = DrawerDotPose()
        val resting = DrawerDotPose(isVisible = true, opacity = 1.0, scale = 1.0)
    }
}

/**
 * Continuous, retargetable state machine for the dot's Brighten arrival and removal (iOS
 * `DrawerDotArrival`). Initial load starts settled ([initial]), so opening or restoring a
 * session while another is already unread or waiting never replays the entrance. Times in
 * seconds on one monotonic clock.
 */
data class DrawerDotArrival(
    val targetBadge: DrawerBadge? = null,
    val displayedBadge: DrawerBadge? = null,
    val reduceMotion: Boolean = false,
    val parameters: Parameters = Parameters(),
    val startTime: Double = 0.0,
    val duration: Double = 0.0,
    val isEntering: Boolean = false,
    val isSettledState: Boolean = true,
    /** Pose captured when a transition was interrupted mid-flight. */
    val anchorPose: DrawerDotPose = DrawerDotPose.hidden,
    val hasAnchor: Boolean = false,
) {
    data class Parameters(
        val brightenDuration: Double = DrawerDotTunables.BRIGHTEN_DURATION,
        val brightenFlash: Double = DrawerDotTunables.BRIGHTEN_FLASH,
        val brightenGlow: Double = DrawerDotTunables.BRIGHTEN_GLOW,
    )

    fun isSettled(time: Double): Boolean {
        if (isSettledState || duration <= 0) return true
        return time - startTime >= duration - 1e-9
    }

    val settleSeconds: Double get() = if (isSettledState) 0.0 else max(0.0, duration)

    fun pose(time: Double): DrawerDotPose {
        if (isSettled(time)) return if (targetBadge != null) DrawerDotPose.resting else DrawerDotPose.hidden
        val elapsed = max(0.0, time - startTime)
        val u = min(1.0, max(0.0, if (duration > 0) elapsed / duration else 1.0))

        if (!isEntering) {
            val s = smoothstep(u)
            val opacity = anchorPose.opacity * (1 - s)
            if (!(opacity > 1e-4 || anchorPose.glowOpacity * (1 - s) > 1e-4)) return DrawerDotPose.hidden
            return DrawerDotPose(
                isVisible = true,
                opacity = opacity,
                scale = if (reduceMotion) 1.0 else anchorPose.scale + (0.75 - anchorPose.scale) * s,
                flash = anchorPose.flash * (1 - s),
                glowOpacity = if (reduceMotion) 0.0 else anchorPose.glowOpacity * (1 - s),
                glowRadius = anchorPose.glowRadius,
                glowScale = anchorPose.glowScale,
            )
        }

        if (reduceMotion) {
            val s = smoothstep(u)
            val startOpacity = if (hasAnchor) anchorPose.opacity else 0.0
            return DrawerDotPose(isVisible = true, opacity = startOpacity + (1 - startOpacity) * s, scale = 1.0)
        }

        val fadeIn = smoothstep(min(1.0, elapsed / 0.09))
        val envelope = brightenEnvelope(u)
        val flash = parameters.brightenFlash * envelope
        var raw = DrawerDotPose(
            isVisible = true,
            opacity = fadeIn,
            scale = 1.0,
            flash = flash,
            glowOpacity = 0.78 * flash,
            glowRadius = parameters.brightenGlow * (0.65 + 0.35 * envelope),
            glowScale = 1 + 0.55 * envelope,
        )
        if (hasAnchor && anchorPose.isVisible) {
            // Carry on smoothly from a partial mid-exit pose without snapping back to 0.
            val blend = smoothstep(min(1.0, u / 0.35))
            raw = raw.copy(
                opacity = max(raw.opacity, anchorPose.opacity * (1 - blend) + raw.opacity * blend),
                scale = anchorPose.scale * (1 - blend) + raw.scale * blend,
            )
        }
        return raw
    }

    /**
     * Retargets to [nextBadge] at [time], preserving continuity if interrupted mid-flight. Plays
     * the arrival only when the dot first appears (`null → badge`), never while already showing.
     */
    fun retargeted(nextBadge: DrawerBadge?, reduceMotion: Boolean, parameters: Parameters = Parameters(), time: Double): DrawerDotArrival {
        val currentPose = pose(time)
        if (nextBadge == null) {
            if (!(currentPose.isVisible && currentPose.opacity > 1e-3)) return initial(null)
            return DrawerDotArrival(
                targetBadge = null,
                displayedBadge = displayedBadge ?: targetBadge ?: DrawerBadge.Unread,
                reduceMotion = reduceMotion,
                parameters = parameters,
                startTime = time,
                duration = if (reduceMotion) REDUCED_FADE_DURATION else EXIT_DURATION,
                isEntering = false,
                isSettledState = false,
                anchorPose = currentPose,
                hasAnchor = true,
            )
        }
        // Already showing (or mid-arrival): update the badge colour in place without re-firing.
        if (targetBadge != null) return copy(targetBadge = nextBadge, displayedBadge = nextBadge)

        val dur = if (reduceMotion) REDUCED_FADE_DURATION else max(0.1, parameters.brightenDuration)
        val interruptingExit = !isSettled(time) && !isEntering && currentPose.isVisible
        return DrawerDotArrival(
            targetBadge = nextBadge,
            displayedBadge = nextBadge,
            reduceMotion = reduceMotion,
            parameters = parameters,
            startTime = time,
            duration = dur,
            isEntering = true,
            isSettledState = dur <= 0,
            anchorPose = if (interruptingExit) currentPose else DrawerDotPose.hidden,
            hasAnchor = interruptingExit,
        )
    }

    companion object {
        const val REDUCED_FADE_DURATION = 0.18
        const val EXIT_DURATION = 0.22

        fun initial(badge: DrawerBadge?): DrawerDotArrival = DrawerDotArrival(
            targetBadge = badge,
            displayedBadge = badge,
            isEntering = badge != null,
            isSettledState = true,
            anchorPose = if (badge != null) DrawerDotPose.resting else DrawerDotPose.hidden,
        )

        fun smoothstep(u: Double): Double {
            val x = min(1.0, max(0.0, u))
            return x * x * (3 - 2 * x)
        }

        /** Fast ignition to 1.0 at u = 0.12 followed by a C1-smooth cooling curve to 0 at u = 1.0. */
        private fun brightenEnvelope(u: Double): Double {
            val x = min(1.0, max(0.0, u))
            val peak = 0.12
            if (x <= peak) return smoothstep(x / peak)
            val v = (x - peak) / (1 - peak)
            val rem = 1 - v
            return rem * rem * (1 + 2 * v)
        }
    }
}

/** Cross-fades the label when another event updates it in place (iOS `.id(shown.events.map { "\($0.sessionID)|\($0.kind)" })`). */
private val InAppNotice.identity: String get() = events.joinToString(",") { "${it.sessionID}|${it.kind}" }

/** iOS `.orange` / `.blue` (systemOrange / systemBlue), light and dark. */
private object DrawerBadgeColors {
    fun orange(isDark: Boolean) = if (isDark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    fun blue(isDark: Boolean) = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
}

@Composable
private fun isDarkTheme(): Boolean = OpenCodeTheme.colors.isDark

/** The capsule's grow (iOS `.spring(duration: 0.5, bounce: 0.12)`). */
private val growSpring = MotionSpring(0.5, 0.12)
/** The capsule's fold (iOS `.smooth(duration: 0.42)`). */
private val foldSpring = MotionSpring(0.42, 0.0)
/** In-place notice update (iOS `.smooth(duration: 0.3)`). */
private val updateSpring = MotionSpring(0.3, 0.0)
/** Reduce Motion cross-fade (iOS `.easeInOut(duration: 0.25)`). */
private const val FADE_SECONDS = 0.25

/**
 * The session header's drawer button (iOS `DrawerButton`). With In-App Notices on, it grows into
 * a capsule that names another session's update (“Upload tests · Needs your input”), then folds
 * back into its dot. The glyph still opens the drawer; the label opens what it names.
 *
 * @param maxWidth how wide the capsule may grow: up to the header's trailing controls.
 * @param size the button's face (iOS 44 pt → 48 dp, Android's minimum target).
 * @param onNoticeTapped the label was tapped: open what it names.
 */
@Composable
fun DrawerButton(
    badge: DrawerBadge?,
    notice: InAppNotice?,
    maxWidth: Dp,
    onOpenSessions: () -> Unit,
    onNoticeTapped: (InAppNotice.Target) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = OpenCodeMinTapTarget,
) {
    val reducedMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    /** What the label shows, kept while it folds away. */
    var shown by remember { mutableStateOf<InAppNotice?>(null) }
    var expanded by remember { mutableStateOf(false) }
    /** The label's natural width in px, measured off screen. */
    var labelWidthPx by remember { mutableIntStateOf(0) }
    /** How much of the label the capsule uncovers (dp); springs with the capsule. */
    val reveal = remember { Animatable(0f) }
    var foldGeneration by remember { mutableIntStateOf(0) }

    val labelFrame = max(0f, min(with(density) { labelWidthPx.toDp().value }, maxWidth.value - size.value))

    LaunchedEffect(notice) {
        foldGeneration += 1
        val generation = foldGeneration
        if (notice != null) {
            shown = notice
            expanded = true
            if (!reducedMotion) reveal.animateTo(labelFrame, growSpring.spec())
        } else if (expanded) {
            expanded = false
            if (!reducedMotion) {
                reveal.animateTo(0f, foldSpring.spec())
            } else {
                kotlinx.coroutines.delay(motionMillis(FADE_SECONDS).toLong())
            }
            if (!expanded && generation == foldGeneration) shown = null
        } else {
            shown = null
        }
    }
    // The label measured a new width: the capsule grows to it (iOS `onGeometryChange` → `grow`).
    LaunchedEffect(labelWidthPx) {
        if (expanded && !reducedMotion) scope.launch { reveal.animateTo(labelFrame, growSpring.spec()) }
    }
    // The header gave it more or less room: the capsule takes it at once (iOS `onChange(of: maxWidth)`).
    LaunchedEffect(maxWidth) {
        if (expanded && !reducedMotion) scope.launch { reveal.snapTo(labelFrame) }
    }

    val dotBadge = if (notice == null && shown == null) badge else null
    Box(modifier.size(size), contentAlignment = Alignment.CenterStart) {
        // Off-screen measure of the label's natural width.
        val measured = notice ?: shown
        if (measured != null) {
            Box(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { alpha = 0f }
                    .clearAndSetSemantics {},
            ) {
                DrawerNoticeLabel(measured, Modifier.onSizeChanged { labelWidthPx = it.width })
            }
        }
        if (reducedMotion) {
            // Cross-fades in place: the capsule over the button, at full width.
            DrawerButtonCapsule(
                width = size,
                size = size,
                badge = badge,
                shown = null,
                expanded = false,
                labelFrame = labelFrame.dp,
                reducedMotion = true,
                hiddenFromAccessibility = expanded,
                onOpenSessions = onOpenSessions,
                onNoticeTapped = onNoticeTapped,
            )
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(motionMillis(FADE_SECONDS), easing = Motion.EaseInOut)),
                exit = fadeOut(tween(motionMillis(FADE_SECONDS), easing = Motion.EaseInOut)),
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
            ) {
                DrawerButtonCapsule(
                    width = size + labelFrame.dp,
                    size = size,
                    badge = badge,
                    shown = shown,
                    expanded = true,
                    labelFrame = labelFrame.dp,
                    reducedMotion = true,
                    hiddenFromAccessibility = false,
                    onOpenSessions = onOpenSessions,
                    onNoticeTapped = onNoticeTapped,
                )
            }
        } else {
            DrawerButtonCapsule(
                width = null,
                widthDp = { size.value + reveal.value },
                size = size,
                badge = badge,
                shown = shown,
                expanded = expanded,
                labelFrame = labelFrame.dp,
                reducedMotion = false,
                hiddenFromAccessibility = false,
                onOpenSessions = onOpenSessions,
                onNoticeTapped = onNoticeTapped,
            )
        }
        // Outside the clipped capsule so the arrival glow never clips.
        Box(Modifier.size(size), contentAlignment = Alignment.TopEnd) {
            DrawerBadgeDot(dotBadge)
        }
    }
}

@Composable
private fun DrawerButtonCapsule(
    width: Dp?,
    size: Dp,
    badge: DrawerBadge?,
    shown: InAppNotice?,
    expanded: Boolean,
    labelFrame: Dp,
    reducedMotion: Boolean,
    hiddenFromAccessibility: Boolean,
    onOpenSessions: () -> Unit,
    onNoticeTapped: (InAppNotice.Target) -> Unit,
    widthDp: (() -> Float)? = null,
) {
    // Takes the button's place in the header; the label grows over the title, at its own width.
    val widthModifier = if (width != null) {
        Modifier.requiredWidth(width)
    } else {
        Modifier.wrapContentWidth(Alignment.Start, unbounded = true).layoutWidth { widthDp?.invoke() ?: size.value }
    }
    // Android divergence: at rest the button is a plain top-app-bar icon (no glass circle); the
    // capsule's fill shows only while it grows into a notice.
    val fill by animateColorAsState(
        targetValue = if (shown != null && expanded) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        animationSpec = if (expanded) growSpring.spec() else foldSpring.spec(),
        label = "drawer-button-fill",
    )
    Surface(
        shape = OpenCodeShape.capsule,
        color = fill,
        contentColor = OpenCodeTheme.colors.text,
        modifier = widthModifier
            .height(size)
            .then(if (hiddenFromAccessibility) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
        Row(Modifier.fillMaxHeight().clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(size)
                    .clickable(role = Role.Button, onClickLabel = null, onClick = onOpenSessions)
                    .semantics {
                        contentDescription = "Open sessions"
                        stateDescription = when (badge) {
                            DrawerBadge.Attention -> "A session needs your input"
                            DrawerBadge.Unread -> "Unread sessions"
                            null -> ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.Line3Horizontal, contentDescription = null)
            }
            if (shown != null) {
                val labelAlpha by animateFloatAsState(
                    targetValue = if (expanded) 1f else 0f,
                    animationSpec = if (expanded) growSpring.spec() else foldSpring.spec(),
                    label = "drawer-notice-alpha",
                )
                Box(
                    Modifier
                        .width(labelFrame)
                        .fillMaxHeight()
                        .graphicsLayer { alpha = labelAlpha }
                        .then(
                            if (expanded) {
                                Modifier
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = if (shown.target == InAppNotice.Target.Drawer) "Open sessions" else "Open the session",
                                        onClick = { onNoticeTapped(shown.target) },
                                    )
                                    .semantics { contentDescription = shown.announcement }
                            } else {
                                Modifier.semantics { hideFromAccessibility() }
                            },
                        )
                        .testTag("drawer-notice"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    AnimatedContent(
                        targetState = shown,
                        contentKey = { it.identity },
                        transitionSpec = {
                            val spec: FiniteAnimationSpec<Float> = if (reducedMotion) {
                                tween<Float>(motionMillis(FADE_SECONDS), easing = Motion.EaseInOut)
                            } else {
                                updateSpring.spec()
                            }
                            fadeIn(spec) togetherWith fadeOut(spec)
                        },
                        label = "drawer-notice",
                    ) { model ->
                        DrawerNoticeLabel(model, Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                    }
                }
            }
        }
    }
}

/** Lays the element out at [widthDp] (read at layout, so springing it never recomposes). */
private fun Modifier.layoutWidth(widthDp: () -> Float): Modifier = layout { measurable, constraints ->
    val width = widthDp().dp.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(width, placeable.height) { placeable.place(0, 0) }
}

/** “Upload tests · Needs your input”: orange while it needs you, quiet when done (iOS `DrawerNoticeLabel`). */
@Composable
fun DrawerNoticeLabel(notice: InAppNotice, modifier: Modifier = Modifier) {
    val isDark = isDarkTheme()
    val orange = DrawerBadgeColors.orange(isDark)
    val colors = OpenCodeTheme.colors
    Row(
        modifier.padding(start = 2.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(8.dp)) {
            drawCircle(if (notice.needsInput) orange else DrawerBadgeColors.blue(isDark))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            notice.title,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text("·", style = OpenCodeTextStyles.subheadline, color = colors.tertiaryText)
        Spacer(Modifier.width(6.dp))
        Text(
            notice.status,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = if (notice.needsInput) FontWeight.SemiBold else FontWeight.Normal),
            color = if (notice.needsInput) orange else colors.secondaryText,
            maxLines = 1,
        )
    }
}

/**
 * The drawer button's dot (iOS `DrawerBadgeDot`): another session needs input (orange) or is
 * unread (blue). Arrives at full size over-bright with a soft glow halo and settles to its
 * normal colour. The pose is read in the draw block, so the arrival never recomposes.
 */
@Composable
fun DrawerBadgeDot(badge: DrawerBadge?, modifier: Modifier = Modifier) {
    val reducedMotion = rememberReduceMotion()
    val highContrast = rememberHighContrast()
    val isDark = isDarkTheme()
    val background = MaterialTheme.colorScheme.background
    var arrival by remember { mutableStateOf(DrawerDotArrival.initial(badge)) }
    var didMount by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val now = remember { mutableDoubleStateOf(monotonicSeconds()) }

    LaunchedEffect(badge) {
        if (!didMount) {
            didMount = true
            arrival = DrawerDotArrival.initial(badge)
            moving = false
            return@LaunchedEffect
        }
        val time = monotonicSeconds()
        arrival = arrival.retargeted(badge, reducedMotion, DrawerDotArrival.Parameters(), time)
        moving = !arrival.isSettled(time)
        // iOS `TimelineView(.animation)`: one frame clock while it moves.
        while (moving) {
            withFrameNanos { now.doubleValue = it / 1e9 }
            if (arrival.isSettled(now.doubleValue)) moving = false
        }
    }

    val displayed = arrival.displayedBadge ?: badge
    // A 10 dp slot, offset (-3, 3) into the button's corner; the halo draws outside it unclipped.
    Canvas(modifier.offset(x = (-3).dp, y = 3.dp).size(10.dp)) {
        val pose = if (moving) arrival.pose(now.doubleValue) else if (badge != null) DrawerDotPose.resting else DrawerDotPose.hidden
        if (!pose.isVisible || displayed == null) return@Canvas
        val base = if (displayed == DrawerBadge.Attention) DrawerBadgeColors.orange(isDark) else DrawerBadgeColors.blue(isDark)
        val radius = size.minDimension / 2
        if (pose.glowOpacity > 0.001) {
            val glowRadius = (if (highContrast) max(1.5, pose.glowRadius * 0.45) else pose.glowRadius).dp.toPx()
            val glowAlpha = min(1.0, pose.glowOpacity * if (highContrast) 1.15 else 1.0).toFloat()
            val core = radius * pose.glowScale.toFloat()
            // A soft disc standing in for SwiftUI's blurred circle.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to base.copy(alpha = glowAlpha),
                    (core / (core + glowRadius)).coerceIn(0f, 1f) to base.copy(alpha = glowAlpha * 0.6f),
                    1f to base.copy(alpha = 0f),
                    center = center,
                    radius = core + glowRadius,
                ),
                radius = core + glowRadius,
            )
            if (highContrast) {
                drawCircle(base, radius = core, alpha = min(1.0, pose.glowOpacity * 0.9).toFloat(), style = Stroke(1.4.dp.toPx()))
            }
        }
        val scaled = radius * pose.scale.toFloat()
        // `.brightness(flash * 0.28)` then a white flash overlay.
        val bright = lerp(base, Color.White, (pose.flash * 0.28).toFloat().coerceIn(0f, 1f))
        val flashed = lerp(bright, Color.White, min(1.0, pose.flash * if (highContrast) 0.88 else 0.78).toFloat().coerceIn(0f, 1f))
        drawCircle(flashed, radius = scaled, alpha = pose.opacity.toFloat())
        if (highContrast) {
            drawCircle(background, radius = scaled - 0.625.dp.toPx(), alpha = pose.opacity.toFloat(), style = Stroke(1.25.dp.toPx()))
        }
    }
}

private fun monotonicSeconds(): Double = System.nanoTime() / 1e9

/**
 * The header's title while the drawer button's label covers it (iOS `steppingAside`): fades and
 * blurs out before the label reaches it, comes back once it has folded past, and stops taking
 * touches and TalkBack focus while aside.
 */
fun Modifier.steppingAside(aside: Boolean, reducedMotion: Boolean): Modifier = composed {
    val spec = when {
        reducedMotion -> tween<Float>(motionMillis(FADE_SECONDS), easing = Motion.EaseInOut)
        aside -> tween(motionMillis(0.18), easing = Motion.EaseOut)
        // `.smooth(duration: 0.35).delay(0.12)`
        else -> MotionSpring(0.35, 0.0).animation(reduceMotion = false, delaySeconds = 0.12)
    }
    val progress by animateFloatAsState(if (aside) 1f else 0f, spec, label = "stepping-aside")
    val blurred = if (!reducedMotion && progress > 0.001f) Modifier.transitionBlur((6 * progress).dp) else Modifier
    this
        .then(blurred)
        .graphicsLayer { alpha = 1 - progress }
        .then(
            if (aside) {
                Modifier
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
            } else {
                Modifier
            },
        )
}

/** Seconds → whole milliseconds for `tween` (core's `Motion.millis` is internal to `:core`). */
internal fun motionMillis(seconds: Double): Int = kotlin.math.round(seconds * 1000).toInt()
