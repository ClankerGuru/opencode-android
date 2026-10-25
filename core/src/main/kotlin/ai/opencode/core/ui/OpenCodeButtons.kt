package ai.opencode.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A button's role (iOS `ButtonRole`). Red is only for [Destructive]. */
enum class OpenCodeButtonRole { Normal, Destructive }

object OpenCodeButtonDefaults {
    /**
     * Height every peer button shares (iOS `.controlSize(.large)` ≈ 50 pt). Hierarchy comes
     * from style, never size.
     */
    val height: Dp = 52.dp

    val contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
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
    val colors = if (role == OpenCodeButtonRole.Destructive) {
        ButtonDefaults.filledTonalButtonColors(contentColor = OpenCodeTheme.colors.destructive)
    } else {
        ButtonDefaults.filledTonalButtonColors()
    }
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = OpenCodeButtonDefaults.height),
        enabled = enabled,
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
