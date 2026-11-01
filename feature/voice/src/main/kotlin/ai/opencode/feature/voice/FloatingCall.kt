package ai.opencode.feature.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.Duration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Floating Call (Experimental, D-60 option 2, OC-316): the composer's phone button breaks out
// into an orb that floats above the whole screen, bottom right over the composer, through the
// drawer swipe and session switches, while the composer stays a composer. The orb can be
// dragged anywhere and docked into the screen's edge. Tapping it grows the same surface into a
// control panel. Off, the call takes the composer's place as before ([VoiceCallComposer]).
//
// Android: iOS's Liquid Glass orb is a Material 3 tonal surface (no blur or glass imitation),
// so the edge's own glass ("bead") is a plain surface capsule beside the orb rather than a
// melting glass merge.

/** Whether the orb is a bare orb that expands on tap, or a slightly wider capsule that keeps End one tap away. */
@Suppress("EnumEntryName")
enum class FloatingCallStyle { orb, capsule }

/** Where the orb rests: floating clear of the edges, or melted into one. */
@Suppress("EnumEntryName")
enum class FloatingCallDock { float, edge }

/**
 * iOS `FloatingCallTunables`, baked at their defaults (the tuning panel is not ported); each
 * keeps its tuning key. Lengths are points, i.e. dp. The presets (`call.float.style`,
 * `call.feel`: Tides, Twin Vortex, …) only change these values in the panel.
 */
object FloatingCallTunables {
    // Orb
    /** `call.orb.style` (Orb style). */
    val orbStyle = FloatingCallOrbStyle.tides
    /** `call.orb.size` (Orb size, 40…84). */
    const val orbSize = 58f
    /** `call.orb.warm` (Your colour (warm) intensity). */
    const val warm = 1.0
    /** `call.orb.cool` (Agent colour (cool) intensity). */
    const val cool = 1.0
    /** `call.orb.saturation` (Aurora saturation). */
    const val saturation = 1.15
    /** `call.orb.goo` (Liquid gooeyness). */
    const val goo = 0.11
    /** `call.orb.idleSpeed` (Idle motion speed). */
    const val idleSpeed = 0.45
    /** `call.orb.speakingGain` (Speaking motion gain). */
    const val speakingGain = 1.0
    /** `call.orb.thinkingSpeed` (Thinking spin speed). */
    const val thinkingSpeed = 1.0
    /** `call.orb.glow` (Glow spill). */
    const val glow = 0.55

    // Dot-matrix takes
    /** `call.dots.across` (Dots across). */
    const val dotsAcross = 9f
    /** `call.dots.fill` (Dot size). */
    const val dotFill = 0.92f
    /** `call.dots.body` (Force body). */
    const val dotBody = 1f
    /** `call.dots.base` (Base fill). */
    const val dotBase = 0.4f

    // Placement and break-out
    /** `call.float.kind` (Shape). */
    val style = FloatingCallStyle.orb
    /** `call.float.capsuleWidth` (Capsule width). */
    const val capsuleWidth = 116f
    /** `call.float.sideInset` (Rest inset from the side). */
    const val sideInset = 18f
    /** `call.float.composerGap` (Rest gap above the composer). */
    const val composerGap = 12f
    /** `call.float.liftScale` (Lift scale). */
    const val liftScale = 1.35f
    /** `call.float.liftShadow` (Lift shadow radius, pt). Android draws it as tonal elevation ([liftElevation]). */
    const val liftShadow = 22f
    /** `call.float.restShadow` (Rest shadow radius, pt). Android draws it as tonal elevation ([restElevation]). */
    const val restShadow = 10f
    /** `call.float.liftHold` (Lift before moving (s)). */
    const val liftHold = 0.14

    // Edge
    /** `call.edge.mode` (Rest). */
    val dock = FloatingCallDock.float
    /** `call.edge.overlap` (Into the edge). */
    const val overlap = 10f
    /** `call.edge.stretch` (Docked height). */
    const val stretch = 1.4f
    /** `call.edge.squash` (Docked width). */
    const val squash = 0.82f
    /** `call.edge.merge` (Glass merge distance). */
    const val merge = 26f
    /** `call.edge.beadWidth` (Edge glass width). */
    const val beadWidth = 12f
    /** `call.edge.beadLength` (Edge glass length). */
    const val beadLength = 130f
    /** `call.edge.snapDistance` (Docks within). */
    const val snapDistance = 64f

    // Panel
    /** `call.panel.buttonSize` (Button size). */
    const val buttonSize = 50f
    /** `call.panel.strip` (Strip). */
    val strip = FloatingCallStripStyle.scope
    /** `call.panel.stripHeight` (Strip height). */
    const val stripHeight = 44f
    /** `call.panel.width` (Panel width). */
    const val panelWidth = 408f
    /** `call.panel.captionsHeight` (Captions height). */
    const val captionsHeight = 84f

    // Springs
    /** `call.float.lift` (Lift spring). */
    val lift = MotionSpring(0.26, 0.22)
    /** `call.float.breakout` (Break-out spring). */
    val breakout = MotionSpring(0.55, 0.16)
    /** `call.panel.expand` (Expand spring). */
    val expand = MotionSpring(0.42, 0.08)
    /** `call.edge.drag` (Drag and dock spring). */
    val drag = MotionSpring(0.45, 0.2)
    /** `call.edge.grab` (Under the finger spring). */
    val grab = MotionSpring(0.16, 0.0)
    /** `call.float.follow` (Follow the composer spring). */
    val follow = MotionSpring(0.35, 0.0)
    /** `call.float.leave` (End spring). */
    val leave = MotionSpring(0.32, 0.0)

    /** [liftShadow] as Material elevation: a shadow's blur radius is about twice its elevation. */
    val liftElevation = (liftShadow / 2).dp
    /** [restShadow] as Material elevation. */
    val restElevation = (restShadow / 2).dp
}

/**
 * iOS `FloatingCallTunables.labStyle/labOrb/labEdge/labScenes` and `floating-call-forced`
 * (`-lab-floating-call-style capsule`, `-lab-floating-orb aurora`, `-lab-floating-edge YES`,
 * `-lab-floating-scenes YES`, `-floating-call-forced YES`): UI tests and captures pick a look.
 * Debug builds only, through [VoiceLabEnvironment]: `adb shell setprop
 * debug.opencode.lab_floating_call_style capsule`, `…lab_floating_orb aurora`,
 * `…lab_floating_edge 1`, `…lab_floating_scenes 1`, `…floating_call_forced 1`.
 */
data class FloatingCallLab(
    val style: FloatingCallStyle? = null,
    val orb: FloatingCallOrbStyle? = null,
    val edge: Boolean = false,
    val scenes: Boolean = false,
) {
    companion object {
        val none = FloatingCallLab()

        /** Reads the properties (a `getprop` each, cached): call off the main thread. */
        fun read(): FloatingCallLab = FloatingCallLab(
            style = VoiceLabEnvironment.string("LAB_FLOATING_CALL_STYLE")?.let { raw -> FloatingCallStyle.entries.firstOrNull { it.name == raw } },
            orb = VoiceLabEnvironment.string("LAB_FLOATING_ORB")?.let(FloatingCallOrbStyle::fromRawValue),
            edge = VoiceLabEnvironment.flag("LAB_FLOATING_EDGE"),
            scenes = VoiceLabEnvironment.flag("LAB_FLOATING_SCENES"),
        )
    }
}

/** Where the orb rests: a side, and a height (`null` follows the composer), floating or docked (iOS `FloatingCallPlace`). */
data class FloatingCallPlace(
    val side: Side = Side.trailing,
    val y: Float? = null,
    val docked: Boolean = false,
) {
    @Suppress("EnumEntryName")
    enum class Side { leading, trailing }
}

/**
 * Where the floating call starts and rests, and the playground's hold on the experiment (iOS
 * `FloatingCallCenter`). The session screen reports its call button and composer, in window
 * coordinates in dp ([floatingCallButton], [floatingCallComposer]); the overlay reads them.
 * One per process, behind its dependency key; main thread only.
 */
class FloatingCallCenter(readsLabHooks: Boolean = false) {
    /** A panel or dock request: the target and a counter bumped on each request. */
    data class Request(val value: Boolean, val count: Int)

    /** The composer's call button circle, as last laid out. */
    var callButton: Rect? = null

    /**
     * Where the call button was when it was tapped: the break-out starts there. Taken once; a
     * call started any other way appears at its rest. Session sets it from [callButton] when
     * the call button starts a call.
     */
    var breakoutFrom: Rect? = null

    /**
     * The composer and what stands above it (pills, the jump button), as last laid out with the
     * session card at rest. The keyboard raises it.
     */
    var composer by mutableStateOf<Rect?>(null)

    /** The playground's Start Floating Call turns the experiment on for its run. */
    var forced by mutableStateOf(false)

    /** Playground scenes stand in for the call's voices and state. */
    var scene by mutableStateOf<FloatingCallScene?>(null)

    /** Bumped by the playground to open or close the panel. */
    var panelRequest by mutableStateOf(Request(false, 0))
        private set

    /** Bumped by the playground to dock the orb into the edge or float it free. */
    var dockRequest by mutableStateOf(Request(false, 0))
        private set

    /** Where the orb rests for this run, once dragged. */
    var place: FloatingCallPlace? = null

    /** The overlay's width, to tell a composer at rest from one sliding with the drawer. */
    var screenWidth: Float = 0f

    init {
        // `-floating-call-forced YES`: a debug build opened straight into the playground. The
        // property is read off the main thread (a `getprop`).
        if (readsLabHooks) {
            Thread {
                if (VoiceLabEnvironment.flag("FLOATING_CALL_FORCED")) Handler(Looper.getMainLooper()).post { forced = true }
            }.start()
        }
    }

    fun isOn(stored: Boolean): Boolean = forced || ExperimentalFeature.floatingCall.isActive(stored)

    /** Kept only while the card rests shut: the composer slides with the drawer, and the orb stays where it was. */
    fun noteComposer(frame: Rect) {
        if (frame.width <= 0 || !(screenWidth == 0f || abs(frame.center.x - screenWidth / 2) < 2)) return
        if (composer != frame) composer = frame
    }

    fun requestPanel(open: Boolean) {
        panelRequest = Request(open, panelRequest.count + 1)
    }

    fun requestDock(docked: Boolean) {
        dockRequest = Request(docked, dockRequest.count + 1)
    }

    companion object Key : DependencyKey<FloatingCallCenter> {
        override val liveValue: FloatingCallCenter by lazy { FloatingCallCenter(readsLabHooks = true) }
        override val testValue: FloatingCallCenter get() = FloatingCallCenter()
        val shared: FloatingCallCenter get() = dependency(Key)
    }
}

/** Reports this element (the session's composer and what stands above it) to [FloatingCallCenter.noteComposer]. */
fun Modifier.floatingCallComposer(): Modifier = this then FloatingCallFrameElement(FloatingCallFrameKind.composer)

/** Reports this element (the composer's call button circle) as [FloatingCallCenter.callButton]. */
fun Modifier.floatingCallButton(): Modifier = this then FloatingCallFrameElement(FloatingCallFrameKind.callButton)

private enum class FloatingCallFrameKind { composer, callButton }

private data class FloatingCallFrameElement(val kind: FloatingCallFrameKind) : ModifierNodeElement<FloatingCallFrameNode>() {
    override fun create() = FloatingCallFrameNode(kind)
    override fun update(node: FloatingCallFrameNode) { node.kind = kind }
    override fun InspectorInfo.inspectableProperties() { name = "floatingCallFrame" }
}

/** Reports its element's window bounds in dp, as iOS's screen-space frames. */
private class FloatingCallFrameNode(var kind: FloatingCallFrameKind) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val density = currentValueOf(LocalDensity).density
        val bounds = coordinates.boundsInWindow()
        val frame = Rect(bounds.left / density, bounds.top / density, bounds.right / density, bounds.bottom / density)
        val center = FloatingCallCenter.shared
        when (kind) {
            FloatingCallFrameKind.composer -> center.noteComposer(frame)
            FloatingCallFrameKind.callButton -> center.callButton = frame
        }
    }
}

/** The overlay's stages (iOS `FloatingCallSurface.Stage`). */
@Suppress("EnumEntryName")
enum class FloatingCallSurfaceStage { seed, lifted, resting, panel, leaving }

/**
 * The floating call's geometry, in the overlay's coordinates (dp): where the orb rests, where
 * the panel opens, the frame of each stage, the edge's bead, and where a drag lands (iOS
 * `FloatingCallSurface`'s geometry). A plain value: the overlay rebuilds it as the screen, the
 * composer or the captions change.
 */
data class FloatingCallLayout(
    val screenWidth: Float,
    val screenHeight: Float,
    /** The top of the composer (iOS `composerTop`), or `null` before the session reported one. */
    val composerMinY: Float?,
    val orbDiameter: Float,
    val style: FloatingCallStyle,
    val showsCaptions: Boolean,
) {
    val topLimit: Float get() = 124f

    /**
     * The bottom of the space the orb may rest in: above the composer, its pills and the jump
     * button, and the keyboard when it raises them.
     */
    val composerTop: Float get() = composerMinY ?: (screenHeight - 34 - CallTrayComposerMetrics.barHeight)

    val restWidth: Float get() = if (style == FloatingCallStyle.capsule) max(orbDiameter, FloatingCallTunables.capsuleWidth) else orbDiameter
    val restHeight: Float get() = orbDiameter

    fun restFrame(place: FloatingCallPlace): Rect {
        var width = restWidth
        var height = restHeight
        if (place.docked) {
            width *= FloatingCallTunables.squash
            height *= FloatingCallTunables.stretch
        }
        val bottom = composerTop - FloatingCallTunables.composerGap - height
        val y = min(max(place.y?.let { it - height / 2 } ?: bottom, topLimit), bottom)
        val x = if (place.docked) {
            if (place.side == FloatingCallPlace.Side.trailing) screenWidth - width + FloatingCallTunables.overlap else -FloatingCallTunables.overlap
        } else {
            if (place.side == FloatingCallPlace.Side.trailing) screenWidth - FloatingCallTunables.sideInset - width else FloatingCallTunables.sideInset
        }
        return Rect(x, y, x + width, y + height)
    }

    val panelHeight: Float
        get() {
            val button = max(44f, FloatingCallTunables.buttonSize)
            return 8 + 48 + 8 + FloatingCallTunables.stripHeight + (if (showsCaptions) FloatingCallTunables.captionsHeight + 8 else 0f) + 14 + button + 14
        }

    fun panelFrame(place: FloatingCallPlace): Rect {
        val rest = restFrame(place)
        val width = min(screenWidth - 24, FloatingCallTunables.panelWidth)
        val height = panelHeight
        val x = if (place.side == FloatingCallPlace.Side.trailing) screenWidth - 12 - width else 12f
        // It grows from the orb: up from an orb in the lower half, down from one in the upper.
        val grows = if (rest.center.y > screenHeight / 2) rest.bottom - height else rest.top
        val y = min(max(grows, topLimit), composerTop - FloatingCallTunables.composerGap - height)
        val top = max(topLimit, y)
        return Rect(x, top, x + width, top + height)
    }

    /** The frame of [stage]; [drag] is the finger's translation while carrying the orb. */
    fun frame(stage: FloatingCallSurfaceStage, place: FloatingCallPlace, drag: Offset?, seedFrame: Rect?): Rect {
        val rest = restFrame(place)
        return when (stage) {
            FloatingCallSurfaceStage.seed -> seedFrame ?: rest.inset(rest.width * 0.2f, rest.height * 0.2f)
            FloatingCallSurfaceStage.lifted -> {
                val seed = seedFrame ?: rest
                val scale = FloatingCallTunables.liftScale
                seed.inset(-seed.width * (scale - 1) / 2, -seed.height * (scale - 1) / 2)
            }
            FloatingCallSurfaceStage.resting -> {
                if (drag == null) return rest
                // Under the finger: floating size, wherever it's carried.
                val floating = restFrame(place.copy(docked = false))
                val cx = rest.center.x + drag.x
                val cy = rest.center.y + drag.y
                Rect(cx - floating.width / 2, cy - floating.height / 2, cx + floating.width / 2, cy + floating.height / 2)
            }
            FloatingCallSurfaceStage.panel -> panelFrame(place)
            FloatingCallSurfaceStage.leaving -> rest.inset(rest.width * 0.25f, rest.height * 0.25f)
        }
    }

    fun cornerRadius(stage: FloatingCallSurfaceStage, frame: Rect): Float {
        val radius = if (stage == FloatingCallSurfaceStage.panel) 28f else min(frame.width, frame.height) / 2
        // iOS `GlassShape.clamped`: never more than half the shorter side.
        return min(max(0f, radius), max(0f, min(frame.width, frame.height)) / 2)
    }

    /** How close the orb is to its nearer side edge, 0 (far) to 1 (touching or docked). */
    fun edgeProximity(frame: Rect, stage: FloatingCallSurfaceStage, place: FloatingCallPlace, dragging: Boolean): Pair<FloatingCallPlace.Side, Float> {
        if (stage == FloatingCallSurfaceStage.resting && !dragging && place.docked) return place.side to 1f
        if (stage != FloatingCallSurfaceStage.resting || !dragging) return place.side to 0f
        val leading = frame.left
        val trailing = screenWidth - frame.right
        val side = if (leading < trailing) FloatingCallPlace.Side.leading else FloatingCallPlace.Side.trailing
        val distance = max(0f, min(leading, trailing))
        return side to max(0f, 1 - distance / FloatingCallTunables.snapDistance)
    }

    /**
     * The edge's surface, beside the orb: grows as it nears the bezel. Far from the edge it
     * waits off screen, past the merge distance, so it slides in and out with the orb rather
     * than appearing or vanishing.
     */
    fun beadFrame(frame: Rect, stage: FloatingCallSurfaceStage, place: FloatingCallPlace, dragging: Boolean): Rect {
        val (side, amount) = edgeProximity(frame, stage, place, dragging)
        val width = FloatingCallTunables.beadWidth * amount
        val length = FloatingCallTunables.beadLength * (0.4f + 0.6f * amount)
        val away = max(0f, 1 - amount * 3) * (FloatingCallTunables.merge + 40)
        val x = (if (side == FloatingCallPlace.Side.trailing) screenWidth - width + away else -away) + (if (side == FloatingCallPlace.Side.trailing) 0f else -20f)
        val y = frame.center.y - length / 2
        return Rect(x, y, x + width + 20, y + length)
    }

    /**
     * Where a drag ends ([translation] where the finger lifted, [predicted] where its fling
     * would carry it): the nearer side of the predicted point, docked when the dock is the edge
     * or it landed within half the snap distance of an edge, and following the composer again
     * when it lands near its usual height.
     */
    fun settle(place: FloatingCallPlace, translation: Offset, predicted: Offset): FloatingCallPlace {
        val start = restFrame(place)
        val predictedX = start.center.x + predicted.x
        val predictedY = start.center.y + predicted.y
        val landedX = start.center.x + translation.x
        val side = if (predictedX < screenWidth / 2) FloatingCallPlace.Side.leading else FloatingCallPlace.Side.trailing
        val distance = min(landedX, screenWidth - landedX) - restWidth / 2
        val docked = FloatingCallTunables.dock == FloatingCallDock.edge || distance < FloatingCallTunables.snapDistance * 0.5f
        val resting = composerTop - FloatingCallTunables.composerGap - restHeight / 2
        val y = min(max(predictedY, topLimit + restHeight / 2), resting)
        // Near its usual height it follows the composer again.
        return place.copy(side = side, docked = docked, y = if (abs(y - resting) < 28) null else y)
    }

    companion object {
        /** The strip's top in the panel: below the 8 + 48 dp header and an 8 dp gap. */
        const val stripTop = 8f + 48f + 8f

        /**
         * SwiftUI's `predictedEndTranslation`: the fling's projection at UIKit's normal
         * deceleration (0.998 per ms): `velocity / 1000 · 0.998 / (1 − 0.998)`, about half a
         * second of the release velocity.
         */
        const val flingProjection = 0.998f / (1 - 0.998f) / 1000f
    }
}

/** CGRect's `insetBy(dx:dy:)`. */
private fun Rect.inset(dx: Float, dy: Float): Rect = Rect(left + dx, top + dy, right - dx, bottom - dy)

/** One animation of the overlay's surface: what `withAnimation` carried on iOS. */
private sealed interface FloatingMove {
    data class Spring(val spring: MotionSpring) : FloatingMove
    data class EaseInOut(val seconds: Double) : FloatingMove

    /** iOS `withAnimation(nil)`: no animation. */
    data object Instant : FloatingMove

    fun <T> spec(): AnimationSpec<T> = when (this) {
        is Spring -> spring.spec()
        is EaseInOut -> Motion.easeInOut(seconds)
        Instant -> snap()
    }
}

/**
 * The floating call, over the whole window: the orb (or capsule) resting above the composer,
 * its panel, and the break-out out of the composer's call button. One surface morphs between
 * them (iOS `FloatingCallOverlay`).
 *
 * Hosting: Session (or Drawer, which keeps the call over the drawer swipe) places it over
 * everything, filling the whole window edge to edge (not inset by the system bars), while the
 * call shows and `FloatingCallCenter.shared.isOn(shared(experimental(.floatingCall)))` or the
 * Living Orb experiment is on. Report the composer with [floatingCallComposer] and its call
 * button with [floatingCallButton]; set `FloatingCallCenter.shared.breakoutFrom =
 * callButton` when the call button starts a call. Touches outside the orb pass through.
 *
 * @param store the call.
 */
@Composable
fun FloatingCallOverlay(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        FloatingCallSurface(store, screenWidth = maxWidth.value, screenHeight = maxHeight.value)
    }
}

/** The overlay at the window's size, in its coordinates (iOS `FloatingCallSurface`). */
@Composable
private fun FloatingCallSurface(store: VoiceStoreOf, screenWidth: Float, screenHeight: Float) {
    val reduceMotion = rememberReduceMotion()
    val dark = OpenCodeTheme.colors.isDark
    val density = LocalDensity.current.density
    val haptics = remember { dependency(HapticsClient) }
    val center = remember { FloatingCallCenter.shared }
    val scope = rememberCoroutineScope()
    val livingOrbSetting by remember { shared(experimental(ExperimentalFeature.livingOrb)).flow }.collectAsState()
    val usesLivingOrb = ExperimentalFeature.livingOrb.isActive(livingOrbSetting)
    val orbDiameter = if (usesLivingOrb) WispOrbTunables.size.value.toFloat() else FloatingCallTunables.orbSize

    val phase by store.observe { it.phase }
    val isReconnecting by store.observe { it.isReconnecting }
    val targetNeedsInput by store.observe { it.targetNeedsInput }
    val isWorking by store.observe { it.isWorking }
    val storeStatus by store.observe { it.statusText }
    val sessionTitle by store.observe { it.sessionTitle }
    val isMuted by store.observe { it.isMuted }
    val isAssistantSpeaking by store.observe { it.isAssistantSpeaking }
    val scopeStage by store.observe { it.scopeStage }

    var lab by remember { mutableStateOf(FloatingCallLab.none) }
    val style = if (usesLivingOrb) FloatingCallStyle.orb else lab.style ?: FloatingCallTunables.style
    val orbStyle = lab.orb ?: FloatingCallTunables.orbStyle
    val defaultDocked = lab.edge || FloatingCallTunables.dock == FloatingCallDock.edge

    // Whether the break-out starts from the composer's call button: a fresh call tapped there.
    val breakout = remember {
        val state = store.state
        val from = center.breakoutFrom
        if (state.phase == VoiceFeature.State.Phase.Connecting && state.connectedAt == null && !reduceMotion) from else null
    }
    var stage by remember { mutableStateOf(if (breakout != null) FloatingCallSurfaceStage.seed else FloatingCallSurfaceStage.resting) }
    var seedFrame by remember { mutableStateOf(breakout) }
    var place by remember { mutableStateOf(center.place ?: FloatingCallPlace(docked = defaultDocked)) }
    var drag by remember { mutableStateOf<Offset?>(null) }
    var showsCaptions by remember { mutableStateOf(false) }
    var tagText by remember { mutableStateOf<String?>(null) }
    // Controls are mounted only while their state shows or folds away, so TalkBack never
    // reaches the hidden state's controls. The drawings (the face, the panel's scope) stay
    // mounted, so opening and closing never builds one mid-animation; the hidden one stops
    // drawing once it has faded.
    var panelMounted by remember { mutableStateOf(false) }
    var restMounted by remember { mutableStateOf(true) }
    var scopeMounted by remember { mutableStateOf(false) }
    var panelAwake by remember { mutableStateOf(false) }
    var faceAwake by remember { mutableStateOf(true) }
    val jobs = remember { FloatingCallJobs() }

    val geometry = FloatingCallLayout(screenWidth, screenHeight, center.composer?.top, orbDiameter, style, showsCaptions)

    // The surface's frame and corner, animated with the move that changed them (iOS
    // `withAnimation`); otherwise following the composer as the keyboard raises it.
    val pending = remember { arrayOfNulls<FloatingMove>(1) }
    fun move(next: FloatingMove, change: () -> Unit) {
        pending[0] = next
        change()
    }
    val followMove: FloatingMove = if (reduceMotion) FloatingMove.EaseInOut(0.2) else FloatingMove.Spring(FloatingCallTunables.follow)
    val dragMove: FloatingMove = if (reduceMotion) FloatingMove.EaseInOut(0.2) else FloatingMove.Spring(FloatingCallTunables.drag)
    val expandMove: FloatingMove = if (reduceMotion) FloatingMove.EaseInOut(0.2) else FloatingMove.Spring(FloatingCallTunables.expand)

    val targetFrame = geometry.frame(stage, place, drag, seedFrame)
    val targetRadius = geometry.cornerRadius(stage, targetFrame)
    val frameAnim = remember { Animatable(targetFrame, Rect.VectorConverter) }
    val radiusAnim = remember { Animatable(targetRadius) }
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(targetFrame, targetRadius) {
        val next = pending[0] ?: followMove
        pending[0] = null
        if (next == FloatingMove.Instant) {
            frameAnim.snapTo(targetFrame)
            radiusAnim.snapTo(targetRadius)
            return@LaunchedEffect
        }
        launch { radiusAnim.animateTo(targetRadius, next.spec()) }
        frameAnim.animateTo(targetFrame, next.spec())
    }

    fun hideTag() {
        if (tagText == null) return
        tagText = null
    }

    fun showTag(text: String) {
        jobs.tag?.cancel()
        tagText = text
        jobs.tag = scope.launch {
            delay(2_200)
            hideTag()
        }
    }

    /** Once the surface has settled, the hidden state's controls leave and its drawing stops. */
    fun settleHidden() {
        jobs.awake?.cancel()
        jobs.awake = scope.launch {
            delay(600)
            val open = stage == FloatingCallSurfaceStage.panel
            faceAwake = !open
            panelAwake = open
            restMounted = !open
            panelMounted = open
        }
    }

    fun expand() {
        if (store.state.phase.isTerminal || stage == FloatingCallSurfaceStage.panel || drag != null) return
        jobs.breakout?.cancel()
        haptics.play(Haptic.tap)
        hideTag()
        panelAwake = true
        move(expandMove) {
            // Mounted in the same frame, the controls fade in with it.
            panelMounted = true
            scopeMounted = true
            stage = FloatingCallSurfaceStage.panel
        }
        settleHidden()
    }

    fun collapse() {
        if (stage != FloatingCallSurfaceStage.panel) return
        faceAwake = true
        move(expandMove) {
            restMounted = true
            stage = FloatingCallSurfaceStage.resting
        }
        settleHidden()
    }

    fun leave() {
        jobs.breakout?.cancel()
        jobs.awake?.cancel()
        faceAwake = true
        hideTag()
        val leaveMove = if (reduceMotion) FloatingMove.EaseInOut(0.2) else FloatingMove.Spring(FloatingCallTunables.leave)
        move(leaveMove) {
            restMounted = true
            stage = FloatingCallSurfaceStage.leaving
        }
        scope.launch { opacity.animateTo(0f, if (reduceMotion) Motion.easeOut(0.2) else FloatingCallTunables.leave.spec()) }
    }

    // Appeared.
    LaunchedEffect(Unit) {
        center.screenWidth = screenWidth
        launch {
            val read = withContext(Dispatchers.IO) { FloatingCallLab.read() }
            lab = read
            if (read.scenes) {
                while (isActive && !store.state.phase.isTerminal) {
                    val scenes = if (usesLivingOrb) {
                        listOf(FloatingCallScene.idle, FloatingCallScene.listening, FloatingCallScene.thinking, FloatingCallScene.agentSpeaking)
                    } else {
                        FloatingCallScene.entries
                    }
                    for (scene in scenes) {
                        center.scene = scene
                        delay(2_500)
                    }
                }
            }
        }
        jobs.mount = launch {
            // After the break-out has landed, while the orb just rests.
            delay(1_200)
            scopeMounted = true
        }
        center.breakoutFrom = null
        if (breakout == null) {
            // Carried over (a switch), or Reduce Motion: it appears where it rests.
            opacity.animateTo(1f, Motion.easeOut(if (reduceMotion) 0.2 else 0.25))
            return@LaunchedEffect
        }
        opacity.snapTo(1f)
        jobs.breakout = launch {
            // The button's surface lifts out of the composer (raised: bigger, with more
            // shadow), then the same shape moves up into its rest and grows into the orb, one
            // spring retargeting the other.
            yield()
            move(FloatingMove.Spring(FloatingCallTunables.lift)) { stage = FloatingCallSurfaceStage.lifted }
            delay((FloatingCallTunables.liftHold * 1000).toLong())
            if (stage != FloatingCallSurfaceStage.lifted) return@launch
            move(if (reduceMotion) FloatingMove.Instant else FloatingMove.Spring(FloatingCallTunables.breakout)) { stage = FloatingCallSurfaceStage.resting }
        }
    }
    LaunchedEffect(screenWidth) { center.screenWidth = screenWidth }
    LaunchedEffect(phase.isTerminal) { if (phase.isTerminal) leave() }
    val lastTitle = remember { arrayOf(sessionTitle) }
    LaunchedEffect(sessionTitle) {
        // The call followed the screen to another session: say where it talks now.
        val title = sessionTitle
        val changed = title != lastTitle[0]
        lastTitle[0] = title
        if (changed && !store.state.phase.isTerminal) showTag("Now talking in $title")
    }
    val lastPanelRequest = remember { intArrayOf(center.panelRequest.count) }
    LaunchedEffect(Unit) {
        snapshotFlow { center.panelRequest }.collect { request ->
            if (request.count == lastPanelRequest[0]) return@collect
            lastPanelRequest[0] = request.count
            if (request.value) expand() else collapse()
        }
    }
    val lastDockRequest = remember { intArrayOf(center.dockRequest.count) }
    LaunchedEffect(Unit) {
        snapshotFlow { center.dockRequest }.collect { request ->
            if (request.count == lastDockRequest[0]) return@collect
            lastDockRequest[0] = request.count
            collapse()
            move(dragMove) { place = place.copy(docked = request.value, side = FloatingCallPlace.Side.trailing) }
            center.place = place
        }
    }
    LaunchedEffect(defaultDocked) {
        if (center.place != null || place.docked == defaultDocked) return@LaunchedEffect
        move(dragMove) { place = place.copy(docked = defaultDocked) }
    }

    val mood = when (phase) {
        VoiceFeature.State.Phase.Connecting -> FloatingCallMood.connecting
        VoiceFeature.State.Phase.Ended, is VoiceFeature.State.Phase.Failed, VoiceFeature.State.Phase.Dropped -> FloatingCallMood.ended
        VoiceFeature.State.Phase.Live, is VoiceFeature.State.Phase.EndingAfterGoodbye -> when {
            isReconnecting -> FloatingCallMood.connecting
            targetNeedsInput -> FloatingCallMood.needsInput
            isWorking -> FloatingCallMood.thinking
            else -> FloatingCallMood.live
        }
    }
    val statusText = when (center.scene) {
        FloatingCallScene.needsInput -> "Needs your input"
        FloatingCallScene.thinking -> "Working"
        FloatingCallScene.agentSpeaking -> "Speaking"
        FloatingCallScene.listening -> "Listening"
        FloatingCallScene.bothSpeaking -> "Both speaking"
        FloatingCallScene.idle -> "Idle"
        null -> storeStatus
    }

    val rest = geometry.restFrame(place)
    val panel = geometry.panelFrame(place)
    val lifted = stage == FloatingCallSurfaceStage.seed || stage == FloatingCallSurfaceStage.lifted || drag != null
    val elevation by animateFloatAsState(
        (if (lifted) FloatingCallTunables.liftElevation else FloatingCallTunables.restElevation).value,
        FloatingCallTunables.lift.spec(),
        label = "floatingCallElevation",
    )
    val surfaceColor = VoiceCallSurfaceDefaults.color

    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                isTraversalGroup = true
                if (stage == FloatingCallSurfaceStage.panel) dismiss { collapse(); true }
            },
    ) {
        if (stage == FloatingCallSurfaceStage.panel) {
            // Outside the panel: a tap folds it back into the orb.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { collapse() } }
                    .clearAndSetSemantics {},
            )
        }
        // Light from the face spills onto what's behind the surface, as Siri's does.
        if (!usesLivingOrb && FloatingCallTunables.glow > 0 && stage != FloatingCallSurfaceStage.panel && orbStyle != FloatingCallOrbStyle.dots) {
            val colors = when {
                // The pearls' own light, subtle like the dots.
                orbStyle.dotTake != null -> listOf(Color(0.98f, 0.9f, 0.78f), Color(0.78f, 0.86f, 1.0f))
                orbStyle == FloatingCallOrbStyle.aurora -> FloatingCallPalette.aurora(dark)
                else -> FloatingCallPalette.warm(dark) + FloatingCallPalette.cool(dark)
            }
            Box(
                Modifier
                    .placeAt { frameAnim.value.let { f -> Rect(center = f.center, radius = f.height * 1.25f / 2) } }
                    .graphicsLayer {
                        val f = frameAnim.value
                        val blur = f.height * 0.45f * density
                        renderEffect = if (blur > 0) BlurEffect(blur, blur, TileMode.Decal) else null
                        alpha = (FloatingCallTunables.glow * 0.5).toFloat() * opacity.value
                    }
                    .background(Brush.sweepGradient(colors + colors[0]), CircleShape)
                    .clearAndSetSemantics {},
            )
        }
        val tag = tagText
        AnimatedVisibility(
            visible = tag != null && stage == FloatingCallSurfaceStage.resting && drag == null,
            enter = fadeIn(if (reduceMotion) Motion.easeOut(0.2) else MotionSpring(0.3, 0.2).spec()) +
                scaleIn(if (reduceMotion) Motion.easeOut(0.2) else MotionSpring(0.3, 0.2).spec(), initialScale = 0.92f, transformOrigin = TransformOrigin(if (place.side == FloatingCallPlace.Side.trailing) 1f else 0f, 0.5f)),
            exit = fadeOut(Motion.easeOut(0.2)) + scaleOut(Motion.easeOut(0.2), targetScale = 0.92f, transformOrigin = TransformOrigin(if (place.side == FloatingCallPlace.Side.trailing) 1f else 0f, 0.5f)),
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints(maxWidth = (280 * density).roundToInt()))
                val target = geometry.frame(stage, place, drag, seedFrame)
                val x = if (place.side == FloatingCallPlace.Side.trailing) (target.left - 10) * density - placeable.width else (target.right + 10) * density
                val y = target.center.y * density - placeable.height / 2f
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
            },
        ) {
            FloatingCallTag(tagText ?: "")
        }
        // The edge's own surface: the orb meets it as it nears the screen's edge.
        Box(
            Modifier
                .placeAt { geometry.beadFrame(frameAnim.value, stage, place, drag != null) }
                .graphicsLayer { alpha = if (opacity.value > 0f) opacity.value else 0f }
                .background(surfaceColor, CircleShape)
                .clearAndSetSemantics {},
        )
        // The surface: orb, capsule or panel, one shape.
        Box(
            Modifier
                .placeAt { frameAnim.value }
                .graphicsLayer {
                    alpha = opacity.value
                    shadowElevation = elevation * density
                    shape = RoundedCornerShape(radiusAnim.value * density)
                    clip = true
                }
                .background(surfaceColor)
                // Not a control: a touch on the surface stays on it (its buttons own their targets).
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // Resting or opening, the face stays laid out at its rest and the panel grows over
            // it; lifting, leaving or under the finger it rides with the surface instead of
            // wiping through it.
            val rides = drag != null || stage == FloatingCallSurfaceStage.seed || stage == FloatingCallSurfaceStage.lifted || stage == FloatingCallSurfaceStage.leaving
            fun restBox(): Rect = if (rides) frameAnim.value.let { Rect(0f, 0f, it.width, it.height) } else rest.translate(-frameAnim.value.left, -frameAnim.value.top)

            // The composer's call button, lifting out of it.
            val seedAlpha by animateFloatAsState(
                when (stage) { FloatingCallSurfaceStage.seed -> 1f; FloatingCallSurfaceStage.lifted -> 0.55f; else -> 0f },
                if (reduceMotion) snap() else FloatingCallTunables.breakout.spec(),
                label = "floatingCallSeed",
            )
            if (seedAlpha > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .alpha(seedAlpha)
                        .background(OpenCodeTheme.colors.text, CircleShape)
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(SFSymbol.PhoneFill, null, tint = MaterialTheme.colorScheme.background, size = 20.dp)
                }
            }

            // The face clears out fast as the panel opens, so it never sits under End, and comes
            // back just behind the panel folding away.
            val restShown = stage == FloatingCallSurfaceStage.resting || stage == FloatingCallSurfaceStage.leaving || stage == FloatingCallSurfaceStage.lifted
            val restAlpha by animateFloatAsState(
                if (restShown) 1f else 0f,
                when {
                    reduceMotion -> snap()
                    stage == FloatingCallSurfaceStage.panel -> Motion.easeOut(0.14)
                    else -> Motion.easeOut(0.2, delaySeconds = 0.06)
                },
                label = "floatingCallRest",
            )
            Box(
                Modifier
                    .placeAt { restBox() }
                    .alpha(restAlpha)
                    .clearAndSetSemantics {},
            ) {
                RestContent(
                    layer = RestLayer.face,
                    style = style,
                    place = place,
                    dragging = drag != null,
                    orbDiameter = orbDiameter,
                    face = {
                        if (usesLivingOrb) {
                            LivingOrbView(
                                LivingOrbConfig(
                                    mood = mood,
                                    isMuted = isMuted,
                                    isSpeaking = isAssistantSpeaking,
                                    scene = center.scene,
                                ),
                                Modifier.fillMaxSize(),
                                isHidden = !faceAwake,
                            )
                        } else {
                            FloatingCallFace(orbStyle, mood, isMuted, Modifier.fillMaxSize(), scene = center.scene, isHidden = !faceAwake)
                        }
                    },
                    controls = {},
                )
            }
            if (restMounted) {
                Box(
                    Modifier
                        .placeAt { restBox() }
                        .alpha(restAlpha),
                ) {
                    RestContent(
                        layer = RestLayer.controls,
                        style = style,
                        place = place,
                        dragging = drag != null,
                        orbDiameter = orbDiameter,
                        face = {},
                        controls = {
                            OrbControls(
                                isMuted = isMuted,
                                statusText = statusText,
                                reduceMotion = reduceMotion,
                                onTap = { expand() },
                                onDragChanged = { translation ->
                                    if (stage == FloatingCallSurfaceStage.resting) {
                                        if (drag == null) hideTag()
                                        // A short spring under the finger: picking up a docked
                                        // orb grows it back to its floating size instead of
                                        // snapping.
                                        move(if (reduceMotion) FloatingMove.Instant else FloatingMove.Spring(FloatingCallTunables.grab)) { drag = translation }
                                    }
                                },
                                onDragEnded = { translation, predicted ->
                                    if (stage == FloatingCallSurfaceStage.resting && drag != null) {
                                        val next = geometry.settle(place, translation, predicted)
                                        if (next.docked != place.docked || next.side != place.side) haptics.play(Haptic.selection)
                                        move(dragMove) {
                                            place = next
                                            drag = null
                                        }
                                        center.place = next
                                    }
                                },
                            )
                        },
                        onEnd = { store.send(VoiceFeature.Action.EndPressed) },
                    )
                }
            }

            val panelShown = stage == FloatingCallSurfaceStage.panel
            val panelAlpha by animateFloatAsState(
                if (panelShown) 1f else 0f,
                when {
                    reduceMotion -> snap()
                    panelShown -> FloatingCallTunables.expand.spec()
                    else -> Motion.easeOut(0.14)
                },
                label = "floatingCallPanel",
            )
            if (scopeMounted) {
                Box(
                    Modifier
                        .placeAt {
                            val f = frameAnim.value
                            Rect(panel.left - f.left, panel.top - f.top + FloatingCallLayout.stripTop, panel.right - f.left, panel.top - f.top + FloatingCallLayout.stripTop + FloatingCallTunables.stripHeight)
                        }
                        .alpha(panelAlpha)
                        .clearAndSetSemantics {},
                ) {
                    when (FloatingCallTunables.strip) {
                        FloatingCallStripStyle.scope -> DotScopeView(DotScopeMode.Panel(scopeStage), Modifier.fillMaxSize(), isHidden = !panelAwake)
                        FloatingCallStripStyle.matching -> FloatingCallFace(
                            style = if (orbStyle == FloatingCallOrbStyle.dots || orbStyle == FloatingCallOrbStyle.activity) FloatingCallOrbStyle.vortex else orbStyle,
                            mood = mood,
                            isMuted = isMuted,
                            modifier = Modifier.fillMaxSize(),
                            scene = center.scene,
                            cornerFraction = 0f,
                            isHidden = !panelAwake,
                        )
                    }
                }
            }
            if (panelMounted) {
                Box(
                    Modifier
                        .placeAt { panel.translate(-frameAnim.value.left, -frameAnim.value.top) }
                        .alpha(panelAlpha)
                        .then(if (panelShown) Modifier else Modifier.semantics { hideFromAccessibility() }),
                ) {
                    FloatingCallPanelContent(
                        store = store,
                        status = statusText,
                        showsCaptions = showsCaptions,
                        enabled = panelShown,
                        reduceMotion = reduceMotion,
                        onToggleCaptions = {
                            if (!showsCaptions) haptics.play(Haptic.tap)
                            move(expandMove) { showsCaptions = !showsCaptions }
                        },
                        onMinimize = { collapse() },
                    )
                }
            }
        }
    }
}

/** The overlay's cancellable tasks (iOS `breakoutTask`, `tagTask`, `awakeTask`, `mountTask`). */
private class FloatingCallJobs {
    var breakout: Job? = null
    var tag: Job? = null
    var awake: Job? = null
    var mount: Job? = null
}

/**
 * Measures the element at [rect]'s size (dp, read at layout time so animated rects never
 * recompose) and places it at [rect]'s origin within its parent.
 */
private fun Modifier.placeAt(rect: () -> Rect): Modifier = layout { measurable, _ ->
    val r = rect()
    val w = (max(0f, r.width) * density).roundToInt()
    val h = (max(0f, r.height) * density).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(0, 0) { placeable.place(IntOffset((r.left * density).roundToInt(), (r.top * density).roundToInt())) }
}

private enum class RestLayer { face, controls }

/**
 * The resting orb in two layers laid out alike: the face (drawn always) and, over it, what you
 * touch (mounted only while resting).
 */
@Composable
private fun RestContent(
    layer: RestLayer,
    style: FloatingCallStyle,
    place: FloatingCallPlace,
    dragging: Boolean,
    orbDiameter: Float,
    face: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    onEnd: () -> Unit = {},
) {
    // Docked, the face sits in the part of the surface still on screen.
    val tucked = if (place.docked && !dragging) FloatingCallTunables.overlap else 0f
    val trailing = place.side == FloatingCallPlace.Side.trailing
    @Composable
    fun orbLayer(modifier: Modifier) {
        Box(modifier.padding(start = if (trailing) 0.dp else tucked.dp, end = if (trailing) tucked.dp else 0.dp)) {
            when (layer) {
                // The face fills the surface to its edge; the surface's shape clips it.
                RestLayer.face -> face()
                RestLayer.controls -> controls()
            }
        }
    }
    when (style) {
        FloatingCallStyle.orb -> orbLayer(Modifier.fillMaxSize())
        FloatingCallStyle.capsule -> {
            // End sits toward the screen's middle, clear of the edge it rests on.
            val diameter = min(40f, orbDiameter - 12)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                @Composable
                fun end(modifier: Modifier) {
                    when (layer) {
                        RestLayer.face -> Spacer(modifier.size(44.dp))
                        RestLayer.controls -> Box(modifier) { FloatingCallEndButton(diameter, onEnd) }
                    }
                }
                if (trailing) end(Modifier.padding(start = ((orbDiameter - diameter) / 2 - 2).dp))
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    orbLayer(Modifier.width(orbDiameter.dp).fillMaxSize())
                }
                if (!trailing) end(Modifier.padding(end = ((orbDiameter - diameter) / 2 - 2).dp))
            }
        }
    }
}

/** The resting orb's touch layer: tap to expand, drag to carry it, and the mute badge. */
@Composable
private fun OrbControls(
    isMuted: Boolean,
    statusText: String,
    reduceMotion: Boolean,
    onTap: () -> Unit,
    onDragChanged: (Offset) -> Unit,
    onDragEnded: (translation: Offset, predicted: Offset) -> Unit,
) {
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val tap by rememberUpdatedState(onTap)
    val dragChanged by rememberUpdatedState(onDragChanged)
    val dragEnded by rememberUpdatedState(onDragEnded)
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates[0] = it }
            .pointerInput(Unit) {
                // SwiftUI `DragGesture(minimumDistance: 8, coordinateSpace: .global)` with
                // `onTapGesture`: under 8 dp of travel it's a tap. Translations are in window
                // coordinates, since the orb moves under the finger.
                val slop = 8.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun window(position: Offset): Offset = coordinates[0]?.takeIf { it.isAttached }?.localToWindow(position) ?: position
                    val start = window(down.position)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, start)
                    var dragging = false
                    var translation = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val point = window(change.position)
                        if (!change.pressed) {
                            if (dragging) {
                                val velocity = tracker.calculateVelocity()
                                val v = Offset(velocity.x, velocity.y) / density
                                dragEnded(translation, translation + v * FloatingCallLayout.flingProjection)
                            } else {
                                tap()
                            }
                            change.consume()
                            break
                        }
                        tracker.addPosition(change.uptimeMillis, point)
                        val delta = point - start
                        if (!dragging && delta.getDistance() >= slop) dragging = true
                        if (dragging) {
                            translation = delta / density
                            dragChanged(translation)
                            change.consume()
                        }
                    }
                }
            }
            .semantics {
                contentDescription = "Voice call"
                stateDescription = statusText
                role = Role.Button
                onClick(label = "Shows the call’s controls") { tap(); true }
            }
            .testTag("floating-call-orb"),
    ) {
        AnimatedVisibility(
            visible = isMuted,
            enter = scaleIn(if (reduceMotion) Motion.easeOut(0.2) else MotionSpring(0.3, 0.25).spec(), initialScale = 0.4f) + fadeIn(if (reduceMotion) Motion.easeOut(0.2) else MotionSpring(0.3, 0.25).spec()),
            exit = scaleOut(if (reduceMotion) Motion.easeOut(0.2) else MotionSpring(0.3, 0.25).spec(), targetScale = 0.4f) + fadeOut(if (reduceMotion) Motion.easeOut(0.2) else MotionSpring(0.3, 0.25).spec()),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.MicSlashFill, null, tint = OpenCodeTheme.colors.text, size = 12.dp)
            }
        }
    }
}

/** The capsule's End: a red circle, its target at least 48 dp. */
@Composable
private fun FloatingCallEndButton(diameter: Float, onEnd: () -> Unit) {
    Box(
        Modifier
            .clickable(role = Role.Button, onClickLabel = null, onClick = onEnd)
            .openCodeTapTarget()
            .semantics { contentDescription = "End voice call" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(diameter.dp).background(VoiceCallSurfaceDefaults.red, CircleShape), contentAlignment = Alignment.Center) {
            OpenCodeIcon(SFSymbol.PhoneDownFill, null, tint = Color.White, size = 20.dp)
        }
    }
}

/** The panel: the session tag, the duration, captions and minimize; the strip's room; the controls. */
@Composable
private fun FloatingCallPanelContent(
    store: VoiceStoreOf,
    status: String,
    showsCaptions: Boolean,
    enabled: Boolean,
    reduceMotion: Boolean,
    onToggleCaptions: () -> Unit,
    onMinimize: () -> Unit,
) {
    val sessionTitle by store.observe { it.sessionTitle }
    val isMuted by store.observe { it.isMuted }
    val isLive by store.observe { it.phase == VoiceFeature.State.Phase.Live }
    val connectedAt by store.observe { it.connectedAt }
    val button = FloatingCallTunables.buttonSize
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .padding(start = 18.dp, end = 8.dp, top = 8.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Talking in", style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText)
                Text(sessionTitle, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FloatingCallDuration(connectedAt, status, Modifier.padding(end = 4.dp))
            FloatingCallHeaderButton(
                symbol = if (showsCaptions) SFSymbol.CaptionsBubbleFill else SFSymbol.CaptionsBubble,
                label = if (showsCaptions) "Hide Captions" else "Show Captions",
                tint = if (showsCaptions) OpenCodeTheme.colors.text else OpenCodeTheme.colors.secondaryText,
                enabled = enabled,
                onClick = onToggleCaptions,
            )
            FloatingCallHeaderButton(SFSymbol.ChevronDown, "Minimize", OpenCodeTheme.colors.secondaryText, enabled, onMinimize)
        }
        // Room for the fuller strip (`stripTop`): both voices across the panel's full width,
        // edge to edge inside the surface.
        Spacer(Modifier.padding(top = 8.dp).height(FloatingCallTunables.stripHeight.dp))
        AnimatedVisibility(
            visible = showsCaptions,
            enter = fadeIn(if (reduceMotion) snap() else FloatingCallTunables.expand.spec()),
            exit = fadeOut(if (reduceMotion) snap() else FloatingCallTunables.expand.spec()),
        ) {
            FloatingCallCaptions(
                store,
                Modifier
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                    .height(FloatingCallTunables.captionsHeight.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        // One row of equal circles on one baseline; names only for TalkBack.
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 14.dp)) {
            val canAct = enabled
            FloatingCallControl(if (isMuted) "Unmute" else "Mute", if (isMuted) SFSymbol.MicSlashFill else SFSymbol.MicFill, button, isOn = isMuted, isEnabled = canAct && isLive) { store.send(VoiceFeature.Action.MuteTapped) }
            FloatingCallControl("Pause", SFSymbol.PauseFill, button, isEnabled = canAct, isPlaceholder = true) {}
            FloatingCallControl("Camera", SFSymbol.VideoFill, button, isEnabled = canAct, isPlaceholder = true) {}
            FloatingCallControl("Add", SFSymbol.Plus, button, isEnabled = canAct, isPlaceholder = true) {}
            FloatingCallControl("End voice call", SFSymbol.PhoneDownFill, button, isEnabled = canAct, isDestructive = true) { store.send(VoiceFeature.Action.EndPressed) }
        }
    }
}

@Composable
private fun FloatingCallHeaderButton(symbol: SFSymbol, label: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(symbol, null, tint = tint)
    }
}

/** The tag beside the orb naming the session the call talks to. */
@Composable
private fun FloatingCallTag(text: String) {
    Box(
        Modifier
            .height(36.dp)
            .background(VoiceCallSurfaceDefaults.color, CircleShape)
            .padding(horizontal = 14.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium), color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One of the panel's controls: an equal circle, centred in an equal share of the row, so every
 * glyph sits on one line.
 *
 * @param isPlaceholder shown so the layout can be judged; needs plumbing that isn't built yet.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.FloatingCallControl(
    name: String,
    symbol: SFSymbol,
    diameter: Float,
    isOn: Boolean = false,
    isEnabled: Boolean = true,
    isDestructive: Boolean = false,
    isPlaceholder: Boolean = false,
    action: () -> Unit,
) {
    val active = !isPlaceholder && isEnabled
    // Mute turning on, or every control dimming as the call ends, eases rather than flipping.
    val dim by animateFloatAsState(if (isPlaceholder || !isEnabled) 0.35f else 1f, Motion.easeOut(0.18), label = "floatingCallControlDim")
    val text = OpenCodeTheme.colors.text
    val background = MaterialTheme.colorScheme.background
    val fill by androidx.compose.animation.animateColorAsState(
        if (isDestructive) VoiceCallSurfaceDefaults.red else if (isOn) text else VoiceCallSurfaceDefaults.tertiaryFill,
        Motion.easeOut(0.18),
        label = "floatingCallControlFill",
    )
    val tint = if (isDestructive) Color.White else if (isOn) background else text
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = max(48f, diameter).dp)
            .clickable(enabled = active, role = Role.Button, onClick = action)
            .semantics {
                contentDescription = name
                if (isPlaceholder) stateDescription = "Not available in this prototype"
                if (!active) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter.dp)
                .alpha(dim)
                .background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(symbol, animationSpec = Motion.easeOut(0.18), label = "floatingCallControlGlyph") { shown ->
                OpenCodeIcon(shown, null, tint = tint, size = (diameter * 0.38f + 4).dp)
            }
        }
    }
}

/** How long the call has run, or what it's doing before it connects. */
@Composable
private fun FloatingCallDuration(connectedAt: java.time.Instant?, status: String, modifier: Modifier = Modifier) {
    val date = remember { dependency(DateClient) }
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connectedAt) {
        if (connectedAt == null) return@LaunchedEffect
        while (true) {
            val millis = Duration.between(connectedAt, date.now()).toMillis()
            seconds = max(0L, millis / 1000)
            delay(1_000 - Math.floorMod(millis, 1_000L))
        }
    }
    val text = if (connectedAt == null) status else "%d:%02d".format(seconds / 60, seconds % 60)
    Text(
        text,
        style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
        color = OpenCodeTheme.colors.secondaryText,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "Call duration"
            stateDescription = text
        },
    )
}

/** The call's last few lines: yours in secondary, the agent's in primary. */
@Composable
private fun FloatingCallCaptions(store: VoiceStoreOf, modifier: Modifier = Modifier) {
    val lines by store.observe { it.captionTargets.takeLast(3) }
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black, startY = 0f, endY = size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            .testTag("floating-call-captions"),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
    ) {
        for (line in lines) {
            androidx.compose.runtime.key(line.id) {
                Text(
                    line.text,
                    style = OpenCodeTextStyles.subheadline,
                    color = if (line.isUser) OpenCodeTheme.colors.secondaryText else OpenCodeTheme.colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = if (line.isUser) "You: ${line.text}" else line.text },
                )
            }
        }
    }
}
