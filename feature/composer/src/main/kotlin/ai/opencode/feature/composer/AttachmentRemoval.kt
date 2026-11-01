package ai.opencode.feature.composer

import ai.opencode.core.motion.MotionSpring
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * iOS `AttachmentRemoval`: a removed composer attachment keeps its pixels for the exit while
 * the durable attachment state changes at once; siblings reflow on the same spring. The New
 * Plus Menu's attachment row uses it (the session view wires it).
 */
object AttachmentRemoval {
    /** iOS `composer.attachments.remove` (0.22 s, no bounce). */
    val spring: MotionSpring = MotionSpring(duration = 0.22, bounce = 0.0)

    /** iOS `composer.attachments.removeBlur` (pt → dp). */
    const val BLUR: Float = 3.5f

    /** iOS `composer.attachments.removeScale`. */
    const val SCALE: Float = 0.96f

    /** The removal's spring, or the reduced fade under Reduce Motion (slow motion is in [MotionSpring.spec]). */
    fun <T> animation(reduceMotion: Boolean): FiniteAnimationSpec<T> = spring.animation(reduceMotion)

    /**
     * iOS `transition(reduceMotion:)`'s `Exit` at [exit] (0 present, 1 gone): blur and scale
     * toward [BLUR] and [SCALE] as it fades; only the fade under Reduce Motion.
     */
    fun Modifier.attachmentRemovalExit(exit: Float, reduceMotion: Boolean): Modifier {
        val p = exit.coerceIn(0f, 1f)
        if (reduceMotion) return graphicsLayer { alpha = 1f - p }
        val blurred = if (p > 0f) blur((BLUR * p).dp) else this
        return blurred.graphicsLayer {
            val scale = 1f + (SCALE - 1f) * p
            scaleX = scale
            scaleY = scale
            alpha = 1f - p
        }
    }
}
