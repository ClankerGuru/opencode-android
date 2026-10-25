package ai.opencode.feature.composer.modelpicker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.ui.rememberHighContrast
import androidx.compose.material3.LocalContentColor
import kotlin.math.max
import kotlin.math.min

/**
 * Port of iOS `ModelLevelGlyph` (`ModelPickerOverlays.swift`): the thinking level as an open
 * arc ([LevelScrubberTunables.arcSpan], 240°) whose lit share is the level. Level j of n fills
 * j/n; the default fills to the middle, dimmed. Drawn with a Canvas. Decorative (TalkBack
 * hidden): its control carries the label and value.
 */
@Composable
fun ModelLevelGlyph(
    scale: ModelLevelScale,
    level: String?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    iconStyle: LevelIconStyle = LevelScrubberTunables.iconStyle,
    color: Color = LocalContentColor.current,
) {
    val count = max(scale.count, 1)
    val index = scale.index(level)
    val pose = LevelArc.pose(index, count)
    val needle = iconStyle == LevelIconStyle.NeedleGauge
    // iOS `colorSchemeContrast == .increased` → Android contrast ≥ medium.
    val increased = rememberHighContrast()
    val track = if (needle) 1.0 else if (increased) LevelArc.TRACK_OPACITY * 1.7 else LevelArc.TRACK_OPACITY
    val span = LevelScrubberTunables.arcSpan
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val strokePx = LevelArc.stroke(size.value.toDouble(), needle).toFloat() * density
        LevelArc.draw(this, fill = 1.0, span = span, color = color.copy(alpha = color.alpha * track.toFloat()), stroke = strokePx)
        LevelArc.draw(this, fill = pose.fill, span = span, color = color.copy(alpha = color.alpha * min(pose.lit, 1.0).toFloat()), stroke = strokePx)
    }
}

/** Port of iOS `LevelArc` (`Shape`): the arc's geometry. */
object LevelArc {
    const val TRACK_OPACITY = 0.28
    val spring = MotionSpring(0.32, 0.22)

    data class Pose(val fill: Double, val lit: Double)

    fun pose(index: Int, count: Int): Pose {
        val levels = max(count - 1, 1)
        if (index <= 0) return Pose((levels + 1).toDouble() / (2 * levels).toDouble(), 0.45)
        return Pose(min(index, levels).toDouble() / levels.toDouble(), 1.0)
    }

    /** Stroke width in dp for a glyph [size] dp across. */
    fun stroke(size: Double, needle: Boolean): Double = LevelScrubberTunables.arcStroke * size / 20 * (if (needle) 0.7 else 1.0)

    fun center(size: Size): Offset = Offset(size.width / 2, size.height * 0.55f)

    fun radius(size: Size): Float = min(size.width, size.height) * 0.37f

    /** Degrees from 3 o'clock, clockwise (y down), as iOS's `start(span:)` in a flipped space. */
    fun start(span: Double): Double = 90 + (360 - span) / 2

    fun draw(scope: DrawScope, fill: Double, span: Double, color: Color, stroke: Float) {
        val clamped = fill.coerceIn(0.0, 1.0)
        if (clamped <= 0.001) return
        val center = center(scope.size)
        val r = radius(scope.size)
        scope.drawArc(
            color = color,
            startAngle = start(span).toFloat(),
            sweepAngle = (span * clamped).toFloat(),
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
