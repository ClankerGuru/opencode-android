package ai.opencode.core.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable

/**
 * The [MotionScheme] Material components (sheets, buttons, menus, switches, FAB menus) move
 * with: Material 3 Expressive's own springs ([MotionScheme.expressive]), so they feel like the
 * rest of Android. Android divergence: iOS has no such scheme; its custom views keep their iOS
 * springs through [Motion].
 *
 * Under Reduce Motion every spec becomes iOS's 0.2 s ease-in-out cross-fade.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Immutable
class OpenCodeMotionScheme(private val reduceMotion: Boolean = false) : MotionScheme {
    private val expressive = MotionScheme.expressive()

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.fastSpatialSpec()

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.defaultSpatialSpec()

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.slowSpatialSpec()

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.fastEffectsSpec()

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.defaultEffectsSpec()

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.slowEffectsSpec()

    override fun equals(other: Any?): Boolean = other is OpenCodeMotionScheme && other.reduceMotion == reduceMotion
    override fun hashCode(): Int = reduceMotion.hashCode()
}
