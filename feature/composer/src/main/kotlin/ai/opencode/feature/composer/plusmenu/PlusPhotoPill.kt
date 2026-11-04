package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.PickerTunables
import ai.opencode.feature.composer.PlusMenuTunables
import ai.opencode.core.motion.transitionBlur
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * iOS `PhotoPillTunables`, the parts the Compose pill uses. iOS ships its UIKit control
 * (`composer.photoPill.uikit`, `PlusPhotoPillUIKit.swift`) whose width spring, prefix exit and
 * count roll this pill's one progress stands in for; the Tuning Panel's systemTint/tintOpacity
 * toggles stay at their defaults (an accent fill over the surface, fully opaque).
 */
object PhotoPillTunables {
    /** The outgoing prefix's least scale as it leaves (and the incoming one's as it enters). */
    const val PREFIX_SCALE = 0.96f
}

/**
 * The photo sheet's trailing pill (iOS `PlusPhotoPill`): one control and one surface across zero,
 * one and several picks. Every part of the label and the capsule's width follow one animated
 * progress (the pick count) on [PickerTunables.photoAdd], so they move together on every frame
 * and reverse together when a pick is undone mid-spring: “All Photos” → “Add 1 Photo” →
 * “Add 2 Photos”. A tap adds (picks) or expands the sheet (none); a long press offers All Photos
 * (with picks) and, under limited access, Select More Photos….
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlusPhotoPill(
    count: Int,
    isLimited: Boolean,
    onAllPhotos: () -> Unit,
    onSelectMore: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(count.toFloat()) }
    LaunchedEffect(count, reduceMotion) {
        val spec = if (reduceMotion) tween(180, easing = Motion.EaseInOut) else PickerTunables.photoAdd.spec<Float>()
        progress.animateTo(count.toFloat(), spec)
    }
    val title = when (count) {
        0 -> "All Photos"
        1 -> "Add 1 Photo"
        else -> "Add $count Photos"
    }
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = count > 0 || isLimited
    val tap = { if (count > 0) onAdd() else onAllPhotos() }
    val accent = OpenCodeTheme.colors.accent
    val idleFill = MaterialTheme.colorScheme.surfaceContainerHighest
    val idleText = OpenCodeTheme.colors.text
    val p = progress.value.coerceIn(0f, 1f)
    Box(modifier) {
        Surface(
            shape = OpenCodeShape.capsule,
            // The label turns white and the fill accent over the first pick, not at its start.
            color = lerp(idleFill, accent, p),
            contentColor = lerp(idleText, Color.White, p),
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
            modifier = Modifier
                .height(PlusPhotoSheetFootDefaults.HEIGHT.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClick = tap,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                )
                .openCodeTapTarget(PlusPhotoSheetFootDefaults.HEIGHT.dp)
                .clearAndSetSemantics {
                    contentDescription = title
                    role = Role.Button
                    testTag = if (count > 0) "plus-photo-add" else "plus-photo-all-photos"
                    onClick { tap(); true }
                    if (hasMenu) onLongClick(label = "More Options") { menuOpen = true; true }
                },
        ) {
            Box(Modifier.padding(horizontal = PlusMenuTunables.ALL_PHOTOS_PADDING.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                PhotoPillLabel(count = count, progress = { progress.value }, reduceMotion = reduceMotion)
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

/**
 * iOS `PhotoPillLabelLayout`: All / Add N, Photo and the plural s from the animated count. The
 * prefix's width moves from All's to Add N's over the first pick, and the s takes |count − 1| of
 * its width (gone at one pick), so the label's width, and the capsule around it, is continuous
 * at every frame. Both prefixes end where Photo starts. Only layout and layers read [progress].
 */
@Composable
private fun PhotoPillLabel(count: Int, progress: () -> Float, reduceMotion: Boolean) {
    val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold)
    val blurPx = with(LocalDensity.current) { 3.dp.toPx() }
    Layout(
        content = {
            Text("All ", style = style, maxLines = 1, modifier = Modifier.prefixSwap(progress, incoming = false, reduceMotion, blurPx))
            Row(Modifier.prefixSwap(progress, incoming = true, reduceMotion, blurPx)) {
                Text("Add ", style = style, maxLines = 1)
                // Equal-width digits keep the capsule still while the count rolls.
                RollingNumber(max(1, count), style = style)
                Text(" ", style = style, maxLines = 1)
            }
            Text("Photo", style = style, maxLines = 1)
            Text(
                "s",
                style = style,
                maxLines = 1,
                // Wiped by the same fraction as its width, so it never hangs past Photo or the capsule.
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
                        val width = (placeable.width * PhotoPillLabelMath.suffixFraction(progress())).roundToInt()
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    }
                    .clipToBounds()
                    .graphicsLayer { alpha = max(0f, (PhotoPillLabelMath.suffixFraction(progress()) - 0.4f) / 0.6f) },
            )
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val all = measurables[0].measure(loose)
        val add = measurables[1].measure(loose)
        val word = measurables[2].measure(loose)
        val suffix = measurables[3].measure(loose)
        val fraction = progress().coerceIn(0f, 1f)
        val prefix = all.width + (add.width - all.width) * fraction
        val width = (prefix + word.width + suffix.width).roundToInt()
        val height = maxOf(all.height, add.height, word.height, suffix.height)
        layout(width, height) {
            fun y(h: Int) = (height - h) / 2
            all.place((prefix - all.width).roundToInt(), y(all.height))
            add.place((prefix - add.width).roundToInt(), y(add.height))
            word.place(prefix.roundToInt(), y(word.height))
            suffix.place((prefix + word.width).roundToInt(), y(suffix.height))
        }
    }
}

/** All and Add N cross on the same frame, each blurring, scaling and fading with the first pick's progress. */
private fun Modifier.prefixSwap(progress: () -> Float, incoming: Boolean, reduceMotion: Boolean, blurPx: Float): Modifier =
    graphicsLayer {
        val visibility = PhotoPillLabelMath.prefixVisibility(progress(), incoming)
        val scale = if (reduceMotion) 1f else PhotoPillTunables.PREFIX_SCALE + (1f - PhotoPillTunables.PREFIX_SCALE) * visibility
        scaleX = scale
        scaleY = scale
        alpha = visibility
    }.then(if (reduceMotion) Modifier else Modifier.transitionBlur { (1f - PhotoPillLabelMath.prefixVisibility(progress(), incoming)) * blurPx })

/** The pill label's progress math (iOS `PhotoPillPrefixSwap` / `PhotoPillSuffixReveal`). */
internal object PhotoPillLabelMath {
    fun prefixVisibility(progress: Float, incoming: Boolean): Float {
        val p = progress.coerceIn(0f, 1f)
        return if (incoming) p else 1f - p
    }

    /** The share of the plural s shown: gone at one pick. */
    fun suffixFraction(progress: Float): Float = minOf(abs(progress - 1f), 1f)
}
