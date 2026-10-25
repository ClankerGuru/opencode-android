package ai.opencode.feature.settings.servers

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberReduceMotion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** iOS `ServerPulseTunables` (`servers.pulse.*`), baked defaults. */
object ServerPulseTunables {
    const val height = 116f
    const val line = 1.5f
    const val period = 1.2
    const val beats = 3.0
    const val glow = 1.0
    const val trail = 1.4
    const val grid = 20f
}

/**
 * Port of iOS `Settings/ServerPulseScope.swift`: a server's live pulse on its details page, a monitor
 * trace on a dark scope. A pen sweeps left to right redrawing a steady beat; the phosphor behind it
 * glows and decays. Green while the server answers, a quiet grey beat while it's checked, a flat dim
 * line otherwise. The frame loop runs only while it beats, the screen is resumed and Reduce Motion
 * is off; otherwise it draws once.
 */
@Composable
fun ServerPulseScope(health: ServerTitleFeature.Health?, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val beating = health == null || health == ServerTitleFeature.Health.connected
    val running = beating && !reduceMotion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val time = remember { mutableDoubleStateOf(System.nanoTime() / 1e9) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = System.nanoTime() / 1e9
        motionFrameLoop { tick ->
            time.doubleValue = start + tick.now
            MotionFrameAction.Continue
        }
    }
    val shape = RoundedCornerShape(26.dp)
    val trace = ServerPulseTrace(health, beating, still = !running)
    Box(
        modifier
            .fillMaxWidth()
            .height(ServerPulseTunables.height.dp)
            .clip(shape)
            .background(Color(0.045f, 0.045f, 0.045f))
            .border(0.5.dp, Color.White.copy(alpha = 0.07f), shape)
            .semantics {
                contentDescription = "Connection pulse"
                stateDescription = health?.label ?: "Checking"
            },
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            drawGrid()
            trace.draw(this, time.doubleValue)
        }
    }
}

/** Phosphor green, the same in light and dark: the scope is always dark. */
val ServerPulsePhosphor = Color(0.27f, 0.88f, 0.54f)

private fun DrawScope.drawGrid() {
    val step = ServerPulseTunables.grid.dp.toPx()
    val columns = (size.width / step).toInt()
    val rows = (size.height / step).toInt()
    val x0 = (size.width - columns * step) / 2
    val y0 = (size.height - rows * step) / 2
    val radius = 0.75.dp.toPx()
    val color = Color.White.copy(alpha = 0.08f)
    for (i in 0..columns) for (j in 0..rows) drawCircle(color, radius, Offset(x0 + i * step, y0 + j * step))
}

/** One frame of the trace. Pure, so the beat's shape is testable. */
data class ServerPulseTrace(val health: ServerTitleFeature.Health?, val beating: Boolean, val still: Boolean) {
    private val color: Color
        get() = when (health) {
            ServerTitleFeature.Health.connected -> ServerPulsePhosphor
            null -> Color.White.copy(alpha = 0.55f)
            else -> Color.White.copy(alpha = 0.24f)
        }

    fun draw(scope: DrawScope, time: Double) = with(scope) {
        val width = size.width.toDouble()
        val beats = ServerPulseTunables.beats
        val beatWidth = width / beats
        val line = ServerPulseTunables.line.dp.toPx().toDouble()
        val baseline = size.height * 0.64
        val amplitude = baseline - line * 3
        val connected = health == ServerTitleFeature.Health.connected
        val gain = if (connected) 1.0 else 0.35
        fun point(x: Double): Offset {
            val y = if (beating) beat((x / beatWidth) % 1.0) * gain else 0.0
            return Offset(x.toFloat(), (baseline - y * amplitude).toFloat())
        }
        val xs = ArrayList<Double>()
        var x = 0.0
        while (x <= width) { xs += x; x += 1.0 }
        for (k in 0 until beats.toInt()) qrsCorners.forEach { xs += (k + it.first) * beatWidth }
        xs.sort()
        val path = Path()
        point(xs[0]).let { path.moveTo(it.x, it.y) }
        for (i in 1 until xs.size) point(xs[i]).let { path.lineTo(it.x, it.y) }

        val pen = if (still) 1.0 else pen(time, beats, ServerPulseTunables.period)
        val edge = 24.dp.toPx() / width
        fun alpha(u: Double): Double {
            val ends = smoothstep(0.0, edge, u) * smoothstep(0.0, edge, 1 - u)
            return if (still) ends else ends * brightness(u, pen, ServerPulseTunables.trail)
        }
        val locations = ArrayList<Double>()
        for (i in 0..48) locations += i / 48.0
        if (!still) { locations += max(0.0, pen - 0.002); locations += min(1.0, pen + 0.002) }
        locations.sort()
        val base = color
        fun shading(opacity: Double): Brush = Brush.horizontalGradient(
            *locations.map { it.toFloat() to base.copy(alpha = (base.alpha * alpha(it) * opacity).toFloat().coerceIn(0f, 1f)) }.toTypedArray(),
            startX = 0f,
            endX = width.toFloat(),
        )
        val glow = ServerPulseTunables.glow * (if (connected) 1.0 else 0.4)
        fun stroke(w: Double) = Stroke(width = w.toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Phosphor: a wide faint bloom and a tighter halo under the crisp line, all one path.
        if (glow > 0) {
            drawPath(path, shading(0.09 * glow), style = stroke(line * 6))
            drawPath(path, shading(0.22 * glow), style = stroke(line * 2.6))
        }
        drawPath(path, shading(1.0), style = stroke(line))
        if (still) return@with
        val head = point(pen * width)
        val fade = smoothstep(0.0, edge, pen) * smoothstep(0.0, edge, 1 - pen)
        for ((radius, opacity) in listOf(line * 4 to 0.18 * glow, line * 2.2 to 0.35 * glow, line * 1.25 to 1.0)) {
            if (opacity <= 0) continue
            drawCircle(base.copy(alpha = (base.alpha * opacity * fade).toFloat().coerceIn(0f, 1f)), radius.toFloat(), head)
        }
    }

    companion object {
        val qrsCorners: List<Pair<Double, Double>> = listOf(0.34 to 0.0, 0.37 to -0.14, 0.41 to 1.0, 0.45 to -0.34, 0.48 to 0.0)

        /** One beat over a unit width: a small P wave, the spike, a T wave, flat otherwise (-1…1, up). */
        fun beat(x: Double): Double {
            fun bump(center: Double, width: Double, height: Double): Double {
                val d = (x - center) / width
                return height * exp(-d * d)
            }
            var y = bump(0.18, 0.035, 0.12) + bump(0.64, 0.06, 0.2)
            for (i in 0 until qrsCorners.size - 1) {
                val (x0, y0) = qrsCorners[i]
                val (x1, y1) = qrsCorners[i + 1]
                if (x >= x0 && x < x1) {
                    val u = (x - x0) / (x1 - x0)
                    y += y0 + (y1 - y0) * u
                }
            }
            return y
        }

        /** The pen's distance across the width (0…1) at a time. */
        fun pen(time: Double, beats: Double, period: Double): Double = (time / (beats * period)) % 1.0

        /**
         * How lit the phosphor is at `x` (0…1 across) with the pen at `pen`: full just behind the pen,
         * decaying with age, gone just ahead of it so the old sweep never meets the new one.
         */
        fun brightness(x: Double, pen: Double, decay: Double): Double {
            var age = pen - x
            if (age < 0) age += 1
            return (1 - age).pow(decay) * (1 - smoothstep(0.86, 0.94, age))
        }

        fun smoothstep(a: Double, b: Double, x: Double): Double {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
    }
}
