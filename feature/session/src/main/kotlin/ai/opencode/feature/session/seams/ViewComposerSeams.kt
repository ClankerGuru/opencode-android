package ai.opencode.feature.session.seams

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.feature.composer.DictationLevels
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

// Stand-ins the session composer (VIEW-COMPOSER: SessionViewComposer*.kt, ComposerViews.kt,
// DictationViews.kt) still uses. The swipe rules and selection mark exist in :feature:composer
// with a different API (Offset translations; no modifier); the view step adopts them and deletes
// these. The dot scope is :feature:voice's.

// region Swipe rules (Composer/ComposerTextInput.swift)

/**
 * SEAM: real type belongs to :feature:composer (iOS `Composer/ComposerTextInput.swift`
 * `ComposerSwipeDown`); delete when that area merges. A downward swipe on the focused composer
 * dismisses the keyboard; an upward swipe on the unfocused composer pulls it up. Distances in dp.
 */
object ComposerSwipeDown {
    const val minimumDistance = 18f
    const val predictedDistance = 60f
    const val minimumFlingDistance = 10f
    const val verticalRatio = 1.3f

    /** (dx, dy) in dp; [predictedEnd] is the projected end translation (iOS `predictedEndTranslation`). */
    fun shouldDismissKeyboard(
        dx: Float,
        dy: Float,
        predictedEndY: Float = 0f,
        isFocused: Boolean,
        isHoldingMic: Boolean = false,
        isScrollingUp: Boolean = false,
    ): Boolean {
        if (!isFocused || isHoldingMic || isScrollingUp) return false
        if (dy <= 0 || abs(dy) <= abs(dx) * verticalRatio) return false
        return dy >= minimumDistance || (dy >= minimumFlingDistance && predictedEndY >= predictedDistance)
    }

    fun shouldFocusKeyboard(
        dx: Float,
        dy: Float,
        predictedEndY: Float = 0f,
        velocityY: Float? = null,
        isFocused: Boolean,
        isHoldingMic: Boolean = false,
        isScrollingDown: Boolean = false,
        minimumDistance: Float = 18f,
        minimumFlickVelocity: Float = 260f,
    ): Boolean {
        if (isFocused || isHoldingMic || isScrollingDown) return false
        if (dy >= 0 || abs(dy) <= abs(dx) * verticalRatio) return false
        val distance = -dy
        if (distance >= minimumDistance) return true
        if (distance < minimumFlingDistance) return false
        if (velocityY != null) return -velocityY >= minimumFlickVelocity
        val predictedExtra = max(0f, -(predictedEndY - dy))
        return predictedExtra >= minimumFlickVelocity * 0.15f || -predictedEndY >= max(predictedDistance, minimumDistance)
    }
}

/**
 * SEAM: real type belongs to :feature:composer (iOS `ComposerSwipeOwnership`); delete when that
 * area merges. Whether a drag over the composer belongs to a control inside it, decided once as
 * the drag first reports and kept through its end.
 */
class ComposerSwipeOwnership {
    private var started = false
    private var yields = false
    private var focusedAtStart = false

    fun yields(isFocused: Boolean, yieldsNow: () -> Boolean): Boolean {
        if (!started) {
            started = true
            focusedAtStart = isFocused
            yields = yieldsNow()
        }
        return yields
    }

    fun beganFocused(isFocused: Boolean): Boolean {
        if (!started) {
            started = true
            focusedAtStart = isFocused
        }
        return focusedAtStart
    }

    /** The drag ended: the next one decides afresh. */
    fun ended() {
        started = false
        yields = false
        focusedAtStart = false
    }
}

// endregion

// region Dot scope (Voice/DotScopeView.swift)

/**
 * SEAM: real view belongs to :feature:voice (iOS `Voice/DotScopeView.swift`, `.dictation` mode);
 * delete when that area merges. Stand-in: a row of dots between the insets whose height follows
 * [DictationLevels]; still under Reduce Motion.
 */
@Composable
fun DictationDotScope(leadingInset: Float, trailingInset: Float, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    var time by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            withFrameNanos { now ->
                time = now
                level += (DictationLevels.level - level) * 0.2f
            }
        }
    }
    val color = OpenCodeTheme.colors.text
    Canvas(modifier) {
        val start = leadingInset.dp.toPx()
        val end = size.width - trailingInset.dp.toPx()
        if (end <= start) return@Canvas
        val pitch = 6.dp.toPx()
        val radius = 1.5.dp.toPx()
        val mid = size.height / 2
        val seconds = time / 1_000_000_000.0
        var x = start
        var index = 0
        while (x <= end) {
            val wave = if (reduceMotion) 0.0 else sin(index * 0.55 + seconds * 6 * PI / 3)
            val amplitude = (2.dp.toPx() + level * 10.dp.toPx()) * wave.toFloat()
            drawCircle(color.copy(alpha = 0.55f), radius, Offset(x, mid + amplitude))
            x += pitch
            index++
        }
    }
}

// endregion

// region Selection mark (Composer/RequestDockView.swift)

/**
 * SEAM: real view belongs to :feature:composer (iOS `Composer/RequestDockView.swift`
 * `SelectionMark`); delete when that area merges. The empty or filled mark beside a choice.
 */
@Composable
fun SelectionMark(selected: Boolean, multiple: Boolean, modifier: Modifier = Modifier) {
    val symbol = when {
        selected && multiple -> SFSymbol.CheckmarkSquareFill
        selected -> SFSymbol.CheckmarkCircleFill
        multiple -> SFSymbol.Square
        else -> SFSymbol.Circle
    }
    OpenCodeIcon(
        symbol,
        contentDescription = null,
        size = 22.dp,
        tint = if (selected) OpenCodeTheme.colors.text else OpenCodeTheme.colors.tertiaryText,
        modifier = modifier,
    )
}

/** SEAM: iOS `SelectionMark.width`. */
const val SELECTION_MARK_WIDTH = 22f

/** SEAM: iOS `RequestDockView.edge` (the request panel's content inset). */
const val REQUEST_DOCK_EDGE = 20f

// endregion
