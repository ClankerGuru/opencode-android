package ai.opencode.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * The OpenCode desktop's floating surfaces (`packages/ui` popover, dialog, dock:
 * `--surface-raised-stronger-non-alpha`): a flat fill a step off the canvas, a hairline ring and,
 * for things that float over content, a small shadow. No tonal elevation.
 *
 * Material components pick most of this up from the theme ([toColorScheme] maps
 * `surfaceContainerLow`/`surfaceContainer` here for the curated themes, so bottom sheets, menus
 * and tooltips are floating surfaces); call sites use these for what Material can't express
 * (borders, the dialog's fill).
 */
object OpenCodeSurfaces {
    /** Dialogs and popovers (desktop dialog ~10–12 px). */
    val dialogShape: Shape = RoundedCornerShape(OpenCodeRadius.panel)

    /** Popovers, menus and floating panels. */
    val popoverShape: Shape = RoundedCornerShape(OpenCodeRadius.card)

    /** The hairline every floating surface wears. */
    val hairline: Dp = 1.dp

    /** A small shadow under surfaces that float over content (desktop `--v2-elevation-raised`). */
    val raisedShadow: Dp = 1.5.dp

    /** Popovers and menus (desktop `--shadow-md`). */
    val popoverShadow: Dp = 4.dp

    /** The floating surface's fill: white in light, a step above the canvas in dark. */
    val color: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.floating

    /** The floating surface's hairline. */
    val border: BorderStroke
        @Composable @ReadOnlyComposable get() = BorderStroke(hairline, OpenCodeTheme.colors.border)
}

/**
 * The floating fill for these colours: the canvas in light (desktop popovers are white), the
 * code band in dark (one step above the canvas). The System theme keeps Material's container.
 */
val OpenCodeColors.floating: Color
    get() = if (isDark) codeBand.copy(alpha = 1f) else canvas

/** A hairline ring in [shape] (desktop `0 0 0 1px border-base`). */
@Composable
fun Modifier.openCodeHairline(shape: Shape): Modifier =
    border(OpenCodeSurfaces.hairline, OpenCodeTheme.colors.border, shape)

/**
 * Material's [AlertDialog] as the desktop's dialog: floating fill, hairline ring, 12 dp corners,
 * no tonal elevation, a 16 sp medium title and 14 sp body. Semantics, buttons and dismissal are
 * Material's, unchanged.
 */
@Composable
fun OpenCodeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val shape = OpenCodeSurfaces.dialogShape
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.openCodeHairline(shape),
        dismissButton = dismissButton,
        icon = icon,
        title = title?.let { content -> { ProvideTextStyle(OpenCodeTextStyles.headline) { content() } } },
        text = text?.let { content -> { ProvideTextStyle(OpenCodeTextStyles.subheadline) { content() } } },
        shape = shape,
        containerColor = OpenCodeSurfaces.color,
        tonalElevation = 0.dp,
        properties = properties,
    )
}
