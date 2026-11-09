package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.SFSymbol
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

/** The trailing button's metrics: Material 3's 48 dp button inside the 64 dp floating toolbar. */
internal object PhotoPillMetrics {
    const val HEIGHT = 48f
    /** A text button's side padding (Material: 12 dp). */
    const val TEXT_PADDING = 12f
    /** A filled button's side padding (Material: 24 dp at this size; 20 keeps “Add 8 Photos” roomy at large text). */
    const val FILLED_PADDING = 20f
}

/**
 * The photo sheet's trailing button (iOS `PlusPhotoPillControl`): “All Photos” with no picks
 * (expands the sheet), then “Add 1 Photo” / “Add 2 Photos”. On Android it's a Material text button
 * that becomes a filled button once something is picked, as the system photo picker's Done does;
 * the filled button's width follows its label. A long press offers All Photos (with picks) and,
 * under limited access, Select More Photos….
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlusPhotoPillControl(
    count: Int,
    isLimited: Boolean,
    onAllPhotos: () -> Unit,
    onSelectMore: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = count > 0 || isLimited
    // The last count with picks, so Add keeps its label while it fades out after Add or Back.
    var shownCount by remember { mutableIntStateOf(count.coerceAtLeast(1)) }
    if (count > 0) shownCount = count
    val title = if (count == 0) "All Photos" else addTitle(count)
    val tap = { if (count > 0) onAdd() else onAllPhotos() }

    Box(
        modifier.clearAndSetSemantics {
            contentDescription = title
            role = Role.Button
            testTag = if (count > 0) "plus-photo-add" else "plus-photo-all-photos"
            onClick { tap(); true }
            if (hasMenu) onLongClick(label = "More Options") { menuOpen = true; true }
        },
    ) {
        AnimatedContent(
            targetState = count > 0,
            transitionSpec = {
                val enter = MotionRole.Swap.animation<Float>(MotionPhase.Enter, reduceMotion)
                val exit = MotionRole.Swap.animation<Float>(MotionPhase.Exit, reduceMotion)
                (fadeIn(enter) togetherWith fadeOut(exit)).using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.CenterEnd,
            label = "photo-add",
        ) { picked ->
            if (picked) {
                PhotoFootButton(
                    text = addTitle(shownCount),
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    sidePadding = PhotoPillMetrics.FILLED_PADDING,
                    onClick = tap,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                    modifier = Modifier.animateContentSize(MotionRole.Layout.animation(reduceMotion = reduceMotion)),
                )
            } else {
                PhotoFootButton(
                    text = "All Photos",
                    container = Color.Transparent,
                    content = MaterialTheme.colorScheme.primary,
                    sidePadding = PhotoPillMetrics.TEXT_PADDING,
                    onClick = tap,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (count > 0) {
                DropdownMenuItem(
                    text = { Text("All Photos") },
                    leadingIcon = { OpenCodeIcon(SFSymbol.PhotoOnRectangle, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onAllPhotos()
                    },
                )
            }
            if (isLimited) {
                DropdownMenuItem(
                    text = { Text("Select More Photos…") },
                    leadingIcon = { OpenCodeIcon(SFSymbol.PhotoBadgePlus, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onSelectMore()
                    },
                )
            }
        }
    }
}

private fun addTitle(count: Int): String = if (count == 1) "Add 1 Photo" else "Add $count Photos"

/** A Material button (filled or text) that also takes a long press, which `Button` can't. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoFootButton(
    text: String,
    container: Color,
    content: Color,
    sidePadding: Float,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = PhotoPillMetrics.HEIGHT.dp)
            .clip(OpenCodeShape.capsule)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(color = content),
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .then(if (container == Color.Transparent) Modifier else Modifier.background(container))
            .padding(horizontal = sidePadding.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
