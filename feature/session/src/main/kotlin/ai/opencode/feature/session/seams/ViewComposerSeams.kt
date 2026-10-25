package ai.opencode.feature.session.seams

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.feature.composer.DictationLevels
import ai.opencode.core.ui.OpenCodeTheme
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
import kotlin.math.sin

// The stand-in the session composer's dictation bar (DictationViews.kt) still uses: the dot
// scope is :feature:voice's.

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
