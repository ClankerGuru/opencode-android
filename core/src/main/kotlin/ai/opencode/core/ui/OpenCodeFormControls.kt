package ai.opencode.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * The desktop's switch (`packages/ui` `forms/switch`): a rounded-square track with a square
 * thumb, no checkmark, 150 ms slides, half opacity when disabled; pressing widens the thumb
 * toward the middle like the desktop's hover. Sized up for touch: a 40 × 24 dp track, 18 dp
 * thumb, inside a 48 dp target when it toggles itself.
 *
 * A drop-in for Material's `Switch`: the same [checked] / [onCheckedChange] contract (null when
 * a parent row is the toggleable, as Material's), `Role.Switch` semantics, and
 * [OpenCodeSwitchDefaults.colors] (off thumb and border at 3:1, the System theme's Material
 * colours).
 */
@Composable
fun OpenCodeSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = OpenCodeSwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()

    var toggle: Modifier = Modifier
    if (onCheckedChange != null) {
        toggle = Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
    }

    val thumbWidthTarget = if (pressed && enabled) SwitchMetrics.pressedThumbWidth else SwitchMetrics.thumbSize
    val thumbXTarget = if (checked) {
        SwitchMetrics.trackWidth - SwitchMetrics.thumbInset - thumbWidthTarget
    } else {
        SwitchMetrics.thumbInset
    }
    val duration = SwitchMetrics.ANIMATION_MILLIS
    val thumbWidth by animateDpAsState(thumbWidthTarget, tween(duration), label = "switchThumbWidth")
    val thumbX by animateDpAsState(thumbXTarget, tween(duration), label = "switchThumbX")
    val trackColor by animateColorAsState(
        if (checked) colors.checkedTrackColor else colors.uncheckedTrackColor,
        tween(duration),
        label = "switchTrack",
    )
    val borderColor by animateColorAsState(
        if (checked) colors.checkedTrackColor else colors.uncheckedBorderColor,
        tween(duration),
        label = "switchBorder",
    )
    val thumbColor by animateColorAsState(
        if (checked) colors.checkedThumbColor else colors.uncheckedThumbColor,
        tween(duration),
        label = "switchThumb",
    )

    val trackShape = SwitchMetrics.trackShape
    Box(
        modifier
            .then(toggle)
            .wrapContentSize(Alignment.Center)
            .requiredSize(SwitchMetrics.trackWidth, SwitchMetrics.trackHeight)
            .alpha(if (enabled) 1f else SwitchMetrics.DISABLED_ALPHA)
            .background(trackColor, trackShape)
            .border(OpenCodeSurfaces.hairline, borderColor, trackShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .size(thumbWidth, SwitchMetrics.thumbSize)
                .background(thumbColor, SwitchMetrics.thumbShape),
        )
    }
}

/**
 * A bottom sheet's drag handle as the desktop's resize handle (`components/resize-handle`): a
 * 40 × 4 dp pill in the muted icon colour ([OpenCodeColors.tertiaryText], 3:1 or more on the
 * sheet). Material's `DragHandle` underneath, so the sheet keeps its band, its "Drag handle"
 * label and its expand / collapse / dismiss actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeSheetHandle(modifier: Modifier = Modifier) {
    BottomSheetDefaults.DragHandle(
        modifier = modifier,
        width = OpenCodeSheetHandleDefaults.width,
        height = OpenCodeSheetHandleDefaults.height,
        shape = OpenCodeShape.capsule,
        color = OpenCodeSheetHandleDefaults.color,
    )
}

/** The handle pill's size and colour, for sheets that draw their own grabber band. */
object OpenCodeSheetHandleDefaults {
    val width: Dp = 40.dp
    val height: Dp = 4.dp

    val color: Color
        @Composable @ReadOnlyComposable get() = OpenCodeTheme.colors.tertiaryText
}

/** [OpenCodeSwitch]'s geometry: the desktop switch's proportions at touch size. */
private object SwitchMetrics {
    val trackWidth: Dp = 40.dp
    val trackHeight: Dp = 24.dp
    val thumbInset: Dp = 3.dp
    val thumbSize: Dp = 18.dp
    val pressedThumbWidth: Dp = 21.dp
    val trackShape = RoundedCornerShape(OpenCodeRadius.sm)
    val thumbShape = RoundedCornerShape(OpenCodeRadius.xs)
    const val ANIMATION_MILLIS = 150
    const val DISABLED_ALPHA = 0.5f
}
