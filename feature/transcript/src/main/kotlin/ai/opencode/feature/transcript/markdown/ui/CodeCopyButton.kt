package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.motion.Motion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTheme
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Android: a code block's Copy button, as the desktop app draws it (`markdown-copy-button` in
 * `packages/session-ui`: a 24 px ghost icon button in the block's top-right corner, always shown
 * on touch screens, a check for 2 s after copying). iOS code blocks have none.
 */
object CodeCopyButton {
    /** Desktop `COPY_FEEDBACK_MS`. */
    const val COPIED_MILLIS = 2_000L

    /** The visible square (desktop icon button `size="normal"`). */
    val faceSize = 24.dp

    val iconSize = 16.dp

    /** From the block's top and trailing edges to the square. */
    val inset = 6.dp
}

/**
 * Copies [code] (the block's source, without fences) through [onCopy]. The 48 dp target fills
 * the block's corner; the square sits [CodeCopyButton.inset] in from its edges, filled with the
 * block's colour so long lines pass under it cleanly.
 */
@Composable
fun CodeCopyButton(code: String, onCopy: (String) -> Unit) {
    val colors = OpenCodeTheme.colors
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(CodeCopyButton.COPIED_MILLIS)
        copied = false
    }
    val copy = {
        onCopy(code)
        copied = true
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = if (copied) "Copied" else "Copy code"
    val shape = RoundedCornerShape(OpenCodeRadius.xs)
    var face = Modifier
        .padding(top = CodeCopyButton.inset, end = CodeCopyButton.inset)
        .size(CodeCopyButton.faceSize)
        .background(TranscriptBandStyle.fill(colors), shape)
    if (pressed) {
        face = face.background(colors.text.copy(alpha = 0.08f), shape)
    }
    Box(
        Modifier
            .size(OpenCodeMinTapTarget)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = copy)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { copy(); true }
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(face, contentAlignment = Alignment.Center) {
            Crossfade(copied, animationSpec = Motion.easeInOut(0.2), label = "CodeCopyGlyph") { isCopied ->
                val tint = if (pressed || isCopied) colors.text else colors.secondaryText
                OpenCodeIcon(if (isCopied) "checkmark" else "square.on.square", contentDescription = null, size = CodeCopyButton.iconSize, tint = tint)
            }
        }
    }
}
