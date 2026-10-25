package ai.opencode.feature.settings.servers

import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.settings.SettingsTint
import ai.opencode.server.OpenCodeServer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

/*
 * Port of iOS `Settings/ServerQuietStyle.swift` — Quiet Settings (D-117 round 3, option 2): the
 * server sheet's shared pieces. Grey icon tiles, coloured status dots instead of tinted buttons, the
 * OpenCode mark in a tile, one prominent button per page. `ServerWireframeMark` (and its geometry and
 * tunables) is unused on iOS and not ported.
 */

/**
 * A server's state as one colour: green while it answers, orange when it needs an update, red when it
 * refused us, a hollow ring while it sleeps, grey while it's being checked.
 */
enum class ServerStatusTone { live, warning, failed, off, checking }

/** The tone of [server]'s row and header. */
fun ServerTitleFeature.State.tone(server: OpenCodeServer): ServerStatusTone = when (checks[server.persistenceID]) {
    ServerTitleFeature.Health.connected -> ServerStatusTone.live
    ServerTitleFeature.Health.update -> ServerStatusTone.warning
    ServerTitleFeature.Health.signIn -> ServerStatusTone.failed
    ServerTitleFeature.Health.asleep -> ServerStatusTone.off
    null -> if (current?.persistenceID == server.persistenceID) ServerStatusTone.live else ServerStatusTone.checking
}

private val smooth = MotionSpring(0.3, 0.0)

/** The status dot: 7 dp, filled by tone, a hollow ring while asleep. */
@Composable
fun ServerStatusDot(tone: ServerStatusTone, modifier: Modifier = Modifier) {
    val dark = OpenCodeTheme.colors.isDark
    val target = when (tone) {
        ServerStatusTone.live -> SettingsTint.Green.color(dark)
        ServerStatusTone.warning -> SettingsTint.Orange.color(dark)
        ServerStatusTone.failed -> SettingsTint.Red.color(dark)
        ServerStatusTone.checking -> OpenCodeTheme.colors.tertiaryText.copy(alpha = 0.5f)
        ServerStatusTone.off -> OpenCodeTheme.colors.tertiaryText
    }
    val color by animateColorAsState(target, smooth.spec(), label = "status-dot")
    val side = with(LocalDensity.current) { (7 * fontScale).dp }
    Box(
        modifier
            .size(side)
            .clearAndSetSemantics {}
            .then(
                if (tone == ServerStatusTone.off) Modifier.border(1.5.dp, color, CircleShape)
                else Modifier.background(color, CircleShape),
            ),
    )
}

/** Settings' leading glyph: a symbol in a small rounded tile, grey unless it names an action's colour. */
@Composable
fun ServerIconTile(symbol: String, modifier: Modifier = Modifier, tint: Color? = null) {
    val scale = LocalDensity.current.fontScale
    val side = (34 * scale).dp
    val glyph = (17 * scale).dp
    val fill = tint?.copy(alpha = 0.16f) ?: tertiarySystemFill()
    Box(
        modifier
            .size(side)
            .clip(RoundedCornerShape(side * 0.29f))
            .background(fill)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = glyph, tint = tint ?: OpenCodeTheme.colors.text)
    }
}

/** iOS `Color(.tertiarySystemFill)`. */
@Composable
internal fun tertiarySystemFill(): Color =
    Color(0x76 / 255f, 0x76 / 255f, 0x80 / 255f, if (OpenCodeTheme.colors.isDark) 0.24f else 0.12f)

/** A command to run on the computer, with Copy at the row's end (“Copied” for 1.5 s). */
@Composable
fun ServerCommandRow(command: String, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_500)
        copied = false
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(command, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "command") {
            Text(
                it,
                style = OpenCodeTextStyles.body.copy(fontFamily = ai.opencode.core.ui.OpenCodeFonts.monospace),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .clickable {
                    onCopy(command)
                    copied = true
                }
                .openCodeTapTarget()
                .padding(horizontal = 4.dp)
                .semantics {
                    contentDescription = "Copy Command"
                    stateDescription = if (copied) "Copied" else command
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(copied, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }, label = "copied") {
                Text(
                    if (it) "Copied" else "Copy",
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Four rounded corners of a square, the camera's aiming frame. */
fun DrawScope.drawServerViewfinder(color: Color, lineWidth: Dp = 3.dp, cornerRadius: Dp = 22.dp, arm: Dp = 34.dp) {
    val armPx = arm.toPx()
    val r = min(cornerRadius.toPx(), armPx)
    val w = size.width
    val h = size.height
    val path = Path()
    val corners = listOf(Triple(Offset(0f, 0f), 1f, 1f), Triple(Offset(w, 0f), -1f, 1f), Triple(Offset(w, h), -1f, -1f), Triple(Offset(0f, h), 1f, -1f))
    for ((corner, dx, dy) in corners) {
        path.moveTo(corner.x, corner.y + dy * armPx)
        path.lineTo(corner.x, corner.y + dy * r)
        path.quadraticTo(corner.x, corner.y, corner.x + dx * r, corner.y)
        path.lineTo(corner.x + dx * armPx, corner.y)
    }
    drawPath(path, color, style = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round))
}

/** [drawServerViewfinder] as a view (iOS `ServerViewfinder` stroked). */
@Composable
fun ServerViewfinder(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) { drawServerViewfinder(color) }
}

/** iOS `ServerMarkTunables.tile`: the logo tile's side. */
private val markTile = 92.dp

/** The app icon (the launcher mark on its dark fill), as an app shows its icon on a welcome page. */
@Composable
fun ServerMarkTile(modifier: Modifier = Modifier) {
    val dark = OpenCodeTheme.colors.isDark
    val side = markTile * min(LocalDensity.current.fontScale, 1.4f)
    val shape = RoundedCornerShape(side * 0.225f)
    Box(
        modifier
            .size(side)
            .shadow(if (dark) 14.dp else 10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = if (dark) 0.32f else 0.12f))
            .clip(shape)
            .background(markFill)
            .then(if (dark) Modifier.border(0.5.dp, Color.White.copy(alpha = 0.1f), shape) else Modifier)
            .clearAndSetSemantics {},
    ) {
        Canvas(Modifier.size(side)) { drawOpenCodeMark() }
    }
}

private val markFill = Color(0.075f, 0.063f, 0.063f)

/**
 * The OpenCode mark from iOS `AppIcon.icon/Assets/logo.svg` (viewBox 46 46 420 420), the same paths
 * as the launcher's foreground: a white frame with a window and a grey block in its lower half.
 */
private fun DrawScope.drawOpenCodeMark() {
    val scale = size.width / 420f
    translate(-46f * scale, -46f * scale) {
        fun rect(x0: Float, y0: Float, x1: Float, y1: Float, path: Path) {
            path.moveTo(x0 * scale, y0 * scale)
            path.lineTo(x1 * scale, y0 * scale)
            path.lineTo(x1 * scale, y1 * scale)
            path.lineTo(x0 * scale, y1 * scale)
            path.close()
        }
        drawPath(Path().also { rect(192f, 224f, 320f, 352f, it) }, Color(0xFF5A5858))
        val frame = Path().apply { fillType = PathFillType.EvenOdd }
        rect(128f, 96f, 384f, 416f, frame)
        rect(192f, 160f, 320f, 352f, frame)
        drawPath(frame, Color.White)
    }
}
