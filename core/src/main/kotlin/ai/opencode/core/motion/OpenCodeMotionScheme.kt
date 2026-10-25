package ai.opencode.core.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable

/**
 * Material 3's [MotionScheme] filled with iOS timings, so Material components (sheets,
 * buttons, FAB menus, switches) move like the iOS app:
 *
 * | M3 spec | iOS constant |
 * | --- | --- |
 * | fastSpatial | [Motion.microPop] (0.26 s, 0.22) |
 * | defaultSpatial | [Motion.panelOpen] (0.34 s, 0.12) |
 * | slowSpatial | [Motion.morphOpen] (0.42 s, 0.12) |
 * | fastEffects | critically damped 0.14 s ([Motion.fadeFast]) |
 * | defaultEffects | critically damped 0.22 s ([Motion.fadeStandard]) |
 * | slowEffects | critically damped 0.32 s ([Motion.fadeSlow]) |
 *
 * Under Reduce Motion every spec becomes iOS's 0.2 s ease-in-out cross-fade.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Immutable
class OpenCodeMotionScheme(private val reduceMotion: Boolean = false) : MotionScheme {
    private fun <T> spec(spring: MotionSpring): FiniteAnimationSpec<T> = spring.animation(reduceMotion)

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spec(Motion.microPop)
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spec(Motion.panelOpen)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spec(Motion.morphOpen)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spec(MotionSpring(Motion.fadeFast))
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spec(MotionSpring(Motion.fadeStandard))
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = spec(MotionSpring(Motion.fadeSlow))

    override fun equals(other: Any?): Boolean = other is OpenCodeMotionScheme && other.reduceMotion == reduceMotion
    override fun hashCode(): Int = reduceMotion.hashCode()
}
