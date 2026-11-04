package ai.opencode.core.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.unit.Dp

/**
 * Blurs applied while content moves: SwiftUI's `.blur(radius:)` inside a transition (iOS
 * `BlurFade`, `MotionBlurReveal`, `MorphReveal`, the pickers' and menus' pop blurs, the
 * streaming text's focus sweep, caption words).
 *
 * Android divergence: Material motion moves content with opacity, scale and offset and never
 * blurs it, so these blurs are off. Callers keep iOS's blur maths (and its ported tests) so the
 * two codebases still diff side by side; the effect is switched off here, in one place. It also
 * saves an offscreen render pass on every animated frame.
 *
 * Backdrop blurs (the dimmed content behind the composer morph, the model list's edges) are not
 * transition blurs and don't go through here.
 */
object TransitionBlur {
    /** Whether transition blurs draw. Off on Android. */
    const val enabled: Boolean = false
}

/** A [TransitionBlur] on this content. Draws nothing on Android. */
@Suppress("UNUSED_PARAMETER", "UnusedReceiverParameter")
fun Modifier.transitionBlur(radius: Dp): Modifier = this

/** [transitionBlur] for a radius read in the draw phase. The lambda is never called. */
@Suppress("UNUSED_PARAMETER", "UnusedReceiverParameter")
fun Modifier.transitionBlur(radius: () -> Float): Modifier = this

/** A [TransitionBlur] as a `graphicsLayer` render effect: always none on Android. */
@Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
fun transitionBlurEffect(radiusPx: Float): RenderEffect? = null
