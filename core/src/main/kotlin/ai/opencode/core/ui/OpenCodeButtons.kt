package ai.opencode.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A button's role (iOS `ButtonRole`). Red is only for [Destructive]. */
enum class OpenCodeButtonRole { Normal, Destructive }

object OpenCodeButtonDefaults {
    /**
     * Height every peer button shares: the 48 dp touch target (denser than iOS's 50 pt `.large`,
     * still well above the desktop's 32 px). Hierarchy comes from style, never size.
     */
    val height: Dp = 48.dp

    val contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

    /** The desktop's button radius (`--radius-md`-ish 8 at phone size), not a capsule. */
    val shape: Shape = RoundedCornerShape(OpenCodeRadius.fill)
}

/**
 * The primary action: Next, Send, Connect, Done (iOS `openCodePrimaryButton()`:
 * `.glassProminent`, large). A Material filled button in the theme accent, or red for a
 * [OpenCodeButtonRole.Destructive] action.
 *
 * Pair with [OpenCodeSecondaryButton] at the same width; both share
 * [OpenCodeButtonDefaults.height].
 */
@Composable
fun OpenCodePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = if (role == OpenCodeButtonRole.Destructive) {
        val red = OpenCodeTheme.colors.destructive
        // systemRed in every theme; iOS labels destructive prominent buttons white.
        ButtonDefaults.buttonColors(containerColor = red, contentColor = Color.White)
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = OpenCodeButtonDefaults.height),
        enabled = enabled,
        shape = OpenCodeButtonDefaults.shape,
        colors = colors,
        contentPadding = OpenCodeButtonDefaults.contentPadding,
        content = content,
    )
}

/** [OpenCodePrimaryButton] with a text label. */
@Composable
fun OpenCodePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
) = OpenCodePrimaryButton(onClick, modifier, enabled, role) { ButtonLabel(text) }

/**
 * The neutral companion: Back, Skip, Cancel, Not Now (iOS `openCodeSecondaryButton()`:
 * `.glass`, large). A Material tonal button on the neutral `secondaryContainer`; a
 * [OpenCodeButtonRole.Destructive] secondary keeps the neutral fill with red text.
 */
@Composable
fun OpenCodeSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
    content: @Composable RowScope.() -> Unit,
) {
    // Desktop neutral button: the base surface with a hairline, not a tonal fill.
    val container = MaterialTheme.colorScheme.surface
    val colors = if (role == OpenCodeButtonRole.Destructive) {
        ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = OpenCodeTheme.colors.destructive)
    } else {
        ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = OpenCodeTheme.colors.text)
    }
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = OpenCodeButtonDefaults.height),
        enabled = enabled,
        shape = OpenCodeButtonDefaults.shape,
        border = BorderStroke(1.dp, OpenCodeTheme.colors.border),
        colors = colors,
        contentPadding = OpenCodeButtonDefaults.contentPadding,
        content = content,
    )
}

/** [OpenCodeSecondaryButton] with a text label. */
@Composable
fun OpenCodeSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: OpenCodeButtonRole = OpenCodeButtonRole.Normal,
) = OpenCodeSecondaryButton(onClick, modifier, enabled, role) { ButtonLabel(text) }

@Composable
private fun ButtonLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
