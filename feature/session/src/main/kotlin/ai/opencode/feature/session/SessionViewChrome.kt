package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.min
import kotlin.math.roundToInt

// Port of iOS `Session/SessionView+Chrome.swift` (split out of `SessionView.swift` in 9972785b):
// the small pieces of the screen's chrome: the drawer glyph and the composer's and request bar's
// swap transitions. `ModelProviderGroup` lives in :server (`model/Project.kt`), `RequestPanelReveal`
// in :feature:composer (`RequestDockView.kt`) and `ProviderIcon` in :core (`ui/ProviderIcon.kt`).

/**
 * The drawer button's glyph: a long stroke over a short one, drawn at whole pixels so it reads at
 * full strength beside the Material Symbols around it. Scales with the body text up to 22 dp.
 */
@Composable
fun DrawerGlyph(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val width = min(18f * density.fontScale, 22f)
    val color = LocalContentColor.current
    with(density) {
        fun pixels(points: Float): Float = (points * this.density).roundToInt().toFloat()
        val stroke = pixels(2.5f)
        val gap = pixels(width * 0.3f)
        val long = pixels(width)
        val short = pixels(width * 0.6f)
        Canvas(modifier.size(long.toDp(), (stroke * 2 + gap).toDp()).clearAndSetSemantics {}) {
            val radius = CornerRadius(stroke / 2, stroke / 2)
            drawRoundRect(color, Offset.Zero, Size(long, stroke), radius)
            drawRoundRect(color, Offset(0f, stroke + gap), Size(short, stroke), radius)
        }
    }
}

/**
 * What the composer's row swaps as a request takes it or gives it back (the placeholder, the mark
 * and +, the trailing controls): the outgoing leaves a beat before the incoming arrives, so two
 * never read at once. iOS blur-replaces; Android fades with a slight scale. Reduce Motion fades.
 */
object RequestBarSwap {
    fun enter(reducedMotion: Boolean): EnterTransition {
        val spec = tween<Float>(durationMillis = if (reducedMotion) 160 else 240, delayMillis = 100, easing = Motion.EaseOut)
        return if (reducedMotion) fadeIn(spec) else fadeIn(spec) + scaleIn(spec, initialScale = 0.96f)
    }

    fun exit(reducedMotion: Boolean): ExitTransition {
        val spec = tween<Float>(durationMillis = 120, easing = EaseIn)
        return if (reducedMotion) fadeOut(spec) else fadeOut(spec) + scaleOut(spec, targetScale = 0.96f)
    }

    /** SwiftUI `.easeIn`. */
    private val EaseIn = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 1f, 1f)
}

/** The composer and the call trade places on one surface: iOS blur-replaces; a cross-fade under Reduce Motion. */
object ComposerSwap {
    fun enter(reducedMotion: Boolean): EnterTransition =
        if (reducedMotion) fadeIn(Motion.reducedFadeSpec()) else fadeIn(Motion.settle.spec()) + scaleIn(Motion.settle.spec(), initialScale = 0.96f)

    fun exit(reducedMotion: Boolean): ExitTransition =
        if (reducedMotion) fadeOut(Motion.reducedFadeSpec()) else fadeOut(Motion.settle.spec()) + scaleOut(Motion.settle.spec(), targetScale = 0.96f)
}
