package ai.opencode.feature.voice

import androidx.compose.runtime.getValue
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

enum class CallTrayBelowMode { lift, slide, home, edge }

enum class CallTrayMorph { staged, linear }

enum class CallTraySeedHandoff { end, fade }

enum class CallTrayCallButtonStyle { disc, icon }

enum class CallTrayEndButtonStyle { red, icon, white }

enum class CallTrayMuteStyle { icon, disc }

enum class CallTrayScanStart { widen, tap }

enum class CallTrayWidenCurve { shoot, spring }

enum class CallTrayGlassEmerge { droplet, materialize }

/**
 * The composer's own metrics the Call Tray lays itself out against (iOS `ComposerTunables`,
 * `Session/ComposerViews.swift`). Composer is a module above voice, so the tray keeps the iOS
 * defaults here; Composer should use the same values (dp = iOS pt).
 */
object CallTrayComposerMetrics {
    /** `composer.size.barHeight`. */
    const val barHeight = 56f

    /** `composer.size.cornerRadius`. */
    const val cornerRadius = 28f

    /** `composer.spacing.innerHorizontal`. */
    const val innerHorizontal = 6f

    /** `composer.controls.buttonDiameter`: the Send/Call circle. */
    const val buttonDiameter = 34f

    /** `composer.controls.trailingGap`: the Send/Call circle's trailing gap. */
    const val trailingGap = 11f

    /** `composer.controls.micScale`: the mic glyph's point size. */
    const val micScale = 17f

    /** `composer.spacing.keyboardGap`: the composer and tray stand this far above the keyboard. */
    const val keyboardGap = 8f

    /** `ComposerTunables.micSymbol`: the composer's mic glyph, which the tray's Mute wears (OC-473). */
    const val micSymbol = "mic"
}

/**
 * Tunables for the Call Tray presentation (`ExperimentalFeature.callTray`, OC-401, D-104
 * option 2): the call button stays under the thumb and spins 495° clockwise into the red End
 * button while the composer dips slightly and lifts off it, and the tray splits from the
 * composer's bottom edge and widens out to the left. Rides the session card at full width in
 * the drawer.
 *
 * iOS `Tunable`s baked at their defaults (the Tuning Panel and its presets aren't ported);
 * each keeps its iOS key. Lengths are dp (iOS pt), times seconds.
 */
object CallTrayTunables {
    /** `call.tray.below` */ val below = CallTrayBelowMode.lift
    /** `call.tray.composerDip` */ const val composerDip = 3f
    /** `call.tray.tuck` */ const val tuck = 0f

    /** `call.tray.height` */ const val height = 56f
    /** `call.tray.gap` */ const val gap = 8f
    /** `call.tray.cornerRadius` */ const val cornerRadius = 28f

    /** `call.tray.liftOffset` */ const val liftOffset = 6f
    /** `call.tray.liftScale` */ const val liftScale = 1.18f
    /** `call.tray.liftHold` */ const val liftHold = 0.15
    /** `call.tray.liftShadow` */ const val liftShadow = 14f

    /** `call.tray.scopeLeadingInset` */ const val scopeLeadingInset = 12f
    /** `call.tray.scopeFade` */ const val scopeFade = 24f

    /** `call.tray.scopeEdgeCurve`: how closely the scope's leading fade follows the tray's rounded end (0 straight, 1 the curve). */
    const val scopeEdgeCurve = 1f

    /** `call.tray.scanReach` */ const val scanReach = 1f

    /** `call.tray.muteGap`: the gap on each side of Mute's glyph, to End's disc and to the scope ([CallTrayBarLayout]). */
    const val muteGap = 24f

    /** `call.tray.muteStyle` */ val muteStyle = CallTrayMuteStyle.icon

    /** `call.tray.transcriptHeight` */ const val transcriptHeight = 176f
    /** `call.tray.transcriptGap` */ const val transcriptGap = 8f

    /** `call.tray.morph` */ val morph = CallTrayMorph.staged
    /** `call.tray.seedHandoff` */ val seedHandoff = CallTraySeedHandoff.end
    /** `call.tray.openStagger` */ const val openStagger = 0.24
    /** `call.tray.closeStagger` */ const val closeStagger = 0.10

    /** `call.tray.spinDegrees` */ const val spinDegrees = 495.0
    /** `call.tray.spin.duration` */ const val spinDuration = 0.70
    /** `call.tray.spinTrail` */ const val spinTrail = 0f
    /** `call.tray.spinShutter` */ const val spinShutter = 0.035
    /** `call.tray.iconBlur` */ const val iconBlur = 0f
    /** `call.tray.iconDrift` */ const val iconDrift = 0f

    /** `call.tray.contentBlur` */ const val contentBlur = 4f
    /** `call.tray.contentScale` */ const val contentScale = 0.92f
    /** `call.tray.contentStagger` */ const val contentStagger = 0.12f
    /** `call.tray.contentTravel` */ const val contentTravel = 14f

    /** `call.tray.callButton` */ val callButton = CallTrayCallButtonStyle.disc
    /** `call.tray.endButton` */ val endButton = CallTrayEndButtonStyle.red
    /** `call.tray.handoffOverlap` */ const val handoffOverlap = 1.0f
    /** `call.tray.discDissolve` */ const val discDissolve = 0.32

    /** `call.tray.scanStart` */ val scanStart = CallTrayScanStart.widen
    /** `call.tray.widen.curve` */ val widenCurve = CallTrayWidenCurve.shoot
    /** `call.tray.widen.bounce` */ const val widenBounce = 0.0
    /** `call.tray.widen.duration` */ const val widenDuration = 0.50
    /** `call.tray.widen.windup` */ const val windup = 5f
    /** `call.tray.widen.windupDuration` */ const val windupDuration = 0.09
    /** `call.tray.widen.stretch` */ const val stretch = 8f
    /** `call.tray.widen.punch` */ const val widenPunch = 3.5f
    /** `call.tray.widen.peak` */ const val widenPeak = 0.50f
    /** `call.tray.widen.squash` */ const val squash = 0.50f

    /** `call.tray.glass.emerge` */ val glassEmerge = CallTrayGlassEmerge.droplet
    /** `call.tray.glass.spacing` */ const val glassSpacing = 8f
    /** `call.tray.glass.energize` */ const val glassEnergize = 0.50f

    /** `call.tray.lift` */ val lift = MotionSpring(0.24, 0.0)
    /** `call.tray.open` */ val open = MotionSpring(0.50, 0.0)
    /** `call.tray.close` */ val close = MotionSpring(0.36, 0.0)
    /** `call.tray.transcriptExpand` */ val transcriptExpand = MotionSpring(0.40, 0.0)
    /** `call.tray.transcriptCollapse` */ val transcriptCollapse = MotionSpring(0.30, 0.0)

    /**
     * The session layout's reserve (`height + gap`) flips once on open and once on close,
     * animated with the open/close spring; `null` under Reduce Motion (it snaps). iOS scales
     * it by `AnimationTimeScale` (a lab hook; 1 here).
     */
    fun <T> reserveAnimation(opening: Boolean, reducedMotion: Boolean = false): FiniteAnimationSpec<T>? {
        if (reducedMotion) return null
        return (if (opening) open else close).spec()
    }
}

/**
 * The glass's current bounds own the scope mask. Its fixed-size canvas begins at the cap, so
 * an older frame can never slide away from that edge.
 */
@Composable
fun CallTrayScopeSurface(
    mode: DotScopeMode,
    isHidden: Boolean,
    renderer: DotScopeRenderer?,
    width: Dp,
    height: Dp,
    canvasWidth: Dp,
    cornerRadius: Dp,
    opacity: Float,
    showsPoorConnection: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    // A weak connection quiets the scope so its note reads cleanly.
    val poor by animateFloatAsState(
        if (showsPoorConnection) 0.25f else 1f,
        Motion.easeInOut(Motion.fadeSlow, reduceMotion = reducedMotion),
        label = "scopePoorConnection",
    )
    Box(
        modifier
            .size(max(1f, width.value).dp, max(1f, height.value).dp)
            .clip(RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.CenterStart,
    ) {
        DotScopeView(
            mode = mode,
            isHidden = isHidden,
            renderer = renderer,
            modifier = Modifier
                .width(canvasWidth)
                .fillMaxHeight()
                .graphicsLayer { alpha = opacity * poor },
        )
    }
}

/** Presentation of the bar's individual controls during the call unfold (iOS `CallTrayPartModifier`). */
fun Modifier.callTrayPart(look: CallTrayController.PartLook, reducedMotion: Boolean): Modifier = graphicsLayer {
    val blur = if (!reducedMotion && look.blur > 0.05f && look.opacity > 0.02f && look.opacity < 0.98f) look.blur else 0f
    scaleX = look.scale
    scaleY = look.scale
    translationX = look.offsetX * density
    alpha = look.opacity
    renderEffect = if (blur > 0f) BlurEffect(blur * density, blur * density, TileMode.Decal) else null
}

/**
 * Where Mute sits between the scope and End (OC-473): the mic glyph keeps the same gap to
 * End's disc as to the scope, measured to where its dots have faded halfway. Mute and End keep
 * their slots; only the spacing between them and the scope's trailing inset move.
 *
 * iOS slots are 44 pt; Android's are its 48 dp touch targets, so the same math keeps the
 * drawn gaps ([muteGap] each side of the glyph) exact.
 */
data class CallTrayBarLayout(
    /** The gap on each side of the mic glyph ([CallTrayTunables.muteGap]). */
    val muteGap: Float,
    /** The mic glyph's drawn width. */
    val glyphWidth: Float,
    /** The tray's trailing padding ([CallTrayComposerMetrics.innerHorizontal]). */
    val trailingPadding: Float,
    /** The scope's edge fade ([CallTrayTunables.scopeFade]). */
    val scopeFade: Float,
) {
    /** Spacing between the Mute and End slots. Never negative, so the slots never overlap. */
    val buttonSpacing: Float get() = max(0f, muteGap - (slot - disc) / 2 - (slot - glyphWidth) / 2)

    /** Mute's centre, measured from the tray's trailing edge. */
    val muteCenterFromTrailing: Float get() = trailingPadding + slot + buttonSpacing + slot / 2

    /** The gap from the mic glyph to End's disc as laid out. */
    val endSideGap: Float get() = muteCenterFromTrailing - glyphWidth / 2 - (trailingPadding + (slot + disc) / 2)

    /** Where the scope's dots reach zero, from the trailing edge, so they fade halfway one [endSideGap] before the glyph. */
    val scopeTrailingInset: Float get() = muteCenterFromTrailing + glyphWidth / 2 + endSideGap - scopeFade / 2

    companion object {
        const val slot = 48f
        const val disc = 40f

        fun current(glyphWidth: Float) = CallTrayBarLayout(
            muteGap = CallTrayTunables.muteGap,
            glyphWidth = glyphWidth,
            trailingPadding = CallTrayComposerMetrics.innerHorizontal,
            scopeFade = CallTrayTunables.scopeFade,
        )

        /**
         * The drawn width of the composer's mic glyph at [pointSize]. iOS measures the SF
         * Symbol; the Material Symbols `mic` drawn at the large image scale (1.4 × the point
         * size, matching SF's large `mic` height) has ink 560 of its 960 units wide.
         */
        fun micGlyphWidth(pointSize: Float): Float = micIconSize(pointSize) * (560f / 960f)

        /** The box the mic glyph is drawn in at [pointSize] (iOS `.imageScale(.large)`). */
        fun micIconSize(pointSize: Float): Float = pointSize * 1.4f
    }
}
