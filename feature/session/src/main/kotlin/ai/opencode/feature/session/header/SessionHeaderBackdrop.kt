package ai.opencode.feature.session.header

import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * The band behind the session header, as resolved for this phone (iOS `SessionHeaderBackdrop`).
 * Place it at the top of the session screen, under the header controls and over the transcript,
 * drawing up through the status bar.
 *
 * Android mapping (no blur imitation, AGENTS.md):
 * - iOS's progressive blur ([SessionHeaderStyle.Resolved.AppBlur], and the iOS 27 system edge,
 *   which Android resolves to the same app path) is an M3 top-bar scrim: the
 *   `surfaceContainer` tone at full strength down to `fadeStart`, easing to nothing at `fadeEnd`
 *   (the same curve as iOS's blur mask). iOS revealed the blur by radius as content scrolled
 *   `fadeIn` under the header; here the scrim's opacity follows the same reveal.
 * - The wash ([SessionHeaderStyle.Appearance]) is the canvas colour at its opacity through the same
 *   stops; its optional material is a tonal surface (ultraThin → `surfaceContainerLow`, thin →
 *   `surfaceContainer`, regular → `surfaceContainerHigh`), revealed over its own `fadeIn`.
 *
 * It takes no touches and is hidden from TalkBack. Scroll is read in the draw phase, so scrolling
 * never recomposes it.
 *
 * @param backdrop the wash to draw, or null (`Resolved.backdrop`).
 * @param appBlur the edge band to draw as a scrim, or null (`Resolved.appBlur`).
 * @param safeTop the screen's top inset (status bar) above the header; 0 in an inset card.
 * @param scrolledUnderHeader how far content has scrolled under the header (dp), read per frame.
 */
@Composable
fun SessionHeaderBackdrop(
    backdrop: SessionHeaderStyle.Appearance?,
    appBlur: SessionHeaderStyle.Resolved.AppBlur?,
    safeTop: Dp,
    scrolledUnderHeader: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (backdrop == null && appBlur == null) return
    val scheme = MaterialTheme.colorScheme
    val canvas = OpenCodeTheme.colors.canvas
    val scrim = scheme.surfaceContainer
    val material = when (backdrop?.blur) {
        SessionHeaderStyle.Blur.UltraThin -> scheme.surfaceContainerLow
        SessionHeaderStyle.Blur.Thin -> scheme.surfaceContainer
        SessionHeaderStyle.Blur.Regular -> scheme.surfaceContainerHigh
        null -> Color.Transparent
    }
    val top = safeTop.value
    val bandEnd = max(
        appBlur?.let { max(it.fadeStart, it.fadeEnd) } ?: 0f,
        backdrop?.fadeEnd ?: 0f,
    )
    val height = max(top + bandEnd + SCRIM_FADE / 2, 0f)
    Spacer(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clearAndSetSemantics {}
            .drawBehind {
                val scrolled = scrolledUnderHeader()
                if (appBlur != null && appBlur.radius > 0) {
                    val end = max(appBlur.fadeStart, appBlur.fadeEnd)
                    val revealed = if (appBlur.fadeIn > 0) min(max(scrolled / appBlur.fadeIn, 0f), 1f) else 1f
                    // Without a blur, iOS's strength curve leaves text readable behind the header's
                    // controls; an opaque scrim through the header row that fades out over
                    // [SCRIM_FADE] below it hides what the blur hid (M3 scrolled top bar).
                    val bandHeight = top + end + SCRIM_FADE / 2
                    val solidEnd = max(top + end - SCRIM_FADE / 2, 0f)
                    if (revealed > 0 && bandHeight > 0) {
                        val stops = listOf(0f to scrim, solidEnd / bandHeight to scrim, 1f to scrim.copy(alpha = 0f))
                        drawBand(stops, bandHeight, revealed)
                    }
                }
                if (backdrop != null) {
                    val revealed = backdrop.revealed(scrolled).toFloat()
                    if (revealed > 0) {
                        if (backdrop.blur != null) drawBand(backdrop.stops(top, material, 1.0), top + backdrop.fadeEnd, revealed)
                        if (backdrop.opacity > 0) drawBand(backdrop.stops(top, canvas, backdrop.opacity), top + backdrop.fadeEnd, revealed)
                    }
                }
            },
    )
}

/** A vertical gradient band from the top to [heightDp], at [alpha]. */
private fun DrawScope.drawBand(stops: List<Pair<Float, Color>>, heightDp: Float, alpha: Float) {
    if (stops.size < 2 || heightDp <= 0) return
    val height = heightDp.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(*stops.toTypedArray(), startY = 0f, endY = height),
        size = Size(size.width, height),
        alpha = alpha,
    )
}

/** How far below the header row the app band's opaque scrim fades out (dp). */
private const val SCRIM_FADE = 24f
