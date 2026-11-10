package ai.opencode.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Android's minimum touch target (Material / Android accessibility), the counterpart of iOS's 44 pt. */
val OpenCodeMinTapTarget: Dp = 48.dp

/**
 * The one owner of a control's hit shape (iOS `openCodeTapTarget(minSize:accessibility:)`,
 * `TapTarget.swift`): makes the element at least [minSize] (never below 48 dp) in both
 * directions with its content centred, so what lights up, what takes the tap and what
 * TalkBack outlines are one rectangle.
 *
 * Put it **after** `clickable`/`toggleable` in the chain (inner), so the clickable node
 * measures the enlarged rectangle:
 *
 * ```kotlin
 * Box(Modifier.clickable(onClickLabel = "Close", role = Role.Button) { … }.openCodeTapTarget()) {
 *     OpenCodeIcon(SFSymbol.Xmark, contentDescription = null, size = 18.dp)
 * }
 * ```
 *
 * It only sets a minimum: size the visible face yourself (`Modifier.size`) before it. Align
 * visible glyphs, not hit frames. A view faded to 0 must also stop taking touches and leave
 * the accessibility tree.
 *
 * @param accessibility `true` merges the content into one TalkBack node covering the whole
 *   target. Pass `false` when an enclosing control already pins its own accessibility node
 *   (iOS passes `false` for the composer's shared trailing control).
 */
fun Modifier.openCodeTapTarget(minSize: Dp = OpenCodeMinTapTarget, accessibility: Boolean = true): Modifier {
    val minimum = max(OpenCodeMinTapTarget.value, minSize.value).dp
    val target = this.layout { measurable, constraints ->
        val min = minimum.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = max(placeable.width, min).coerceIn(constraints.minWidth, constrainMax(constraints.maxWidth))
        val height = max(placeable.height, min).coerceIn(constraints.minHeight, constrainMax(constraints.maxHeight))
        layout(width, height) {
            placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }
    return if (accessibility) target.semantics(mergeDescendants = true) {} else target
}

private fun constrainMax(max: Int) = if (max == Constraints.Infinity) Int.MAX_VALUE else max

/** Visual style of [OpenCodeIconButton]. */
enum class OpenCodeIconButtonStyle {
    /** Bare glyph (toolbar icons; iOS plain buttons inside a shared glass bar). */
    Standard,

    /**
     * A standalone control (iOS `openCodeGlassCircle`). On this branch it is the desktop's
     * neutral icon button: a rounded square on the base surface with a hairline ring, or a
     * filled rounded square when tinted. The name is kept so call sites match iOS.
     */
    Circle,
}

/**
 * An icon control with the 48 dp target built in: iOS `openCodeGlassCircle(_:tint:)` and the
 * plain icon buttons around it. Liquid Glass maps to a Material tonal surface; there is no
 * blur or glass imitation.
 *
 * @param contentDescription the control's name (“Close”, “Choose model”); state goes in
 *   semantics, not the label.
 * @param size the visible face (iOS default 44 → 48 dp); the target is never smaller than 48 dp.
 * @param tint container colour for [OpenCodeIconButtonStyle.Circle] (iOS glass tint); the
 *   glyph colour follows it.
 */
@Composable
fun OpenCodeIconButton(
    symbol: SFSymbol,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: OpenCodeIconButtonStyle = OpenCodeIconButtonStyle.Circle,
    size: Dp = OpenCodeMinTapTarget,
    iconSize: Dp = OpenCodeIconSize.medium,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val face = modifier.size(max(size.value, OpenCodeMinTapTarget.value).dp)
    when (style) {
        OpenCodeIconButtonStyle.Standard -> IconButton(onClick = onClick, modifier = face, enabled = enabled) {
            OpenCodeIcon(symbol, contentDescription = contentDescription, size = iconSize)
        }
        OpenCodeIconButtonStyle.Circle -> {
            val container = tint ?: OpenCodeSurfaces.neutralFill
            OutlinedIconButton(
                onClick = onClick,
                modifier = face,
                enabled = enabled,
                shape = RoundedCornerShape(OpenCodeRadius.card),
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = container,
                    contentColor = tint?.highContrastForeground() ?: OpenCodeTheme.colors.text,
                ),
                border = if (tint == null) BorderStroke(1.dp, OpenCodeTheme.colors.border) else null,
            ) {
                OpenCodeIcon(symbol, contentDescription = contentDescription, size = iconSize)
            }
        }
    }
}
