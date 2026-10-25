package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of iOS `FusedLevelStackOverlay`: the vertical level stack, right-aligned to the tapped
 * level's trailing edge, the current level at the tapped height, higher levels above and lower
 * below, a soft capsule on the chosen one. Mount it full-screen over the panel and composer.
 *
 * Partial: the feathered local blur behind the stack is a dim band (no blur imitation).
 *
 * [composerFrame] is the composer's frame in root px; [headerHeight] the session header's.
 */
@Composable
fun FusedLevelStackOverlay(
    anchors: ModelPickerAnchors,
    headerHeight: Dp,
    composerFrame: Rect,
    onPickLevel: (stack: FusedLevelStackState, level: String?, wordRect: Rect?) -> Unit,
    onDismiss: (FusedLevelStackState) -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var sizePx by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                sizePx = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())
            },
    ) {
        // Mounted until its fold clears it, so closing animates.
        val stack = anchors.levelStack ?: return@Box
        if (sizePx.width <= 0f) return@Box
        val alpha = anchors.levelStackAlpha.value
        val px = { dp: Double -> with(density) { dp.dp.toPx() } }
        val gap = px(FusedModelPickerTunables.stackGap)
        val wordSize = FusedModelPickerTunables.wordSize
        val count = stack.scale.count
        val rightX = min(max(stack.rightEdgeX - origin.x, px(130.0)), sizePx.width - px(22.0))
        val footY = max(composerFrame.bottom - origin.y, sizePx.height - px(40.0))
        val cy = clampedCenterY(
            anchorY = stack.anchorY - origin.y,
            startIndex = stack.startIndex,
            count = count,
            gap = gap,
            ceiling = with(density) { headerHeight.toPx() } + px(24.0),
            foot = footY - px(20.0),
        )
        val yOf = { i: Double -> cy - ((i - stack.startIndex) * gap).toFloat() }
        val topY = yOf((count - 1).toDouble()) - px(34.0)
        val bottomY = yOf(0.0) + px(34.0)
        val drawn = stack.rawPosition + anchors.levelStackSettle.value
        val dismiss = {
            if (!stack.isClosing) {
                anchors.closeLevelStack(commit = false, reduceMotion = reduceMotion)
                onDismiss(stack)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = if (reduceMotion) 1f else 0.96f + 0.04f * alpha
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(rightX / max(sizePx.width, 1f), cy / max(sizePx.height, 1f))
                }
                .semantics {
                    paneTitle = "Thinking level"
                    stateDescription = ModelLevelScale.label(stack.scale.level(stack.currentIndex))
                    if (stack.isClosing) invisibleToUser()
                    dismiss { dismiss(); true }
                }
                .testTag("level-panel"),
        ) {
            if (stack.tapMode && !stack.isClosing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() }
                        .clearAndSetSemantics {},
                )
            }
            // The stack's backdrop: a dim band fading at its ends (iOS: a feathered progressive blur).
            val bandWidth = px(176.0)
            val bandLeft = max(0f, rightX - bandWidth + px(18.0))
            val frameW = max(sizePx.width - bandLeft, px(120.0))
            val frameH = max(bottomY - topY, px(60.0))
            Box(
                Modifier
                    .offset(bandLeft, (topY + bottomY) / 2 - frameH / 2)
                    .size(with(density) { frameW.toDp() }, with(density) { frameH.toDp() })
                    .graphicsLayer { this.alpha = alpha }
                    .background(Color.Black.copy(alpha = if (dark) 0.35f else 0.16f), RoundedCornerShape(28.dp))
                    .clearAndSetSemantics {},
            )
            // The soft capsule on the chosen level.
            val activeLabel = ModelLevelScale.label(stack.scale.level(stack.currentIndex))
            val estWidth = max(activeLabel.length * (wordSize * 0.56) + 28, 68.0)
            val capsuleH = px(34.0)
            Box(
                Modifier
                    .offset(rightX - px(estWidth) + px(12.0), yOf(drawn) - capsuleH / 2)
                    .size(estWidth.dp, 34.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(OpenCodeTheme.colors.text.copy(alpha = if (dark) 0.16f else 0.11f), RoundedCornerShape(50))
                    .border(0.5.dp, OpenCodeTheme.colors.text.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .clearAndSetSemantics {},
            )
            val buttonH = max(gap, px(34.0))
            val stackLeading = max(0f, rightX - px(96.0) + px(12.0))
            for (index in (0 until count).reversed()) {
                val level = stack.scale.level(index)
                val label = ModelLevelScale.label(level)
                val wordDim = FusedModelPickerTunables.wordDim
                val weight = max(0.0, 1 - abs(index - drawn))
                val wordOpacity = (wordDim + (1 - wordDim) * weight).toFloat()
                val isPickedFlightSource = anchors.flight?.direction == FusedLabelFlight.Direction.Close && stack.isClosing && index == stack.currentIndex
                Box(
                    Modifier
                        .offset(stackLeading, yOf(index.toDouble()) - buttonH / 2)
                        .size(96.dp, with(density) { buttonH.toDp() })
                        .clickable(enabled = !stack.isClosing, role = Role.Button) {
                            if (stack.isClosing) return@clickable
                            val rect = anchors.stackWordFrames[index]
                            anchors.setLevelStackIndex(index)
                            anchors.closeLevelStack(commit = true, reduceMotion = reduceMotion)
                            // The pick's own action plays the selection, when it changes anything.
                            onPickLevel(stack, level, rect)
                        }
                        .semantics {
                            contentDescription = label
                            selected = index == stack.currentIndex
                        }
                        .testTag("model-stack-level-${level ?: "default"}")
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        label,
                        style = TextStyle(fontSize = wordSize.sp, fontWeight = if (index == stack.currentIndex) FontWeight.SemiBold else FontWeight.Medium),
                        color = OpenCodeTheme.colors.text,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .graphicsLayer { this.alpha = if (isPickedFlightSource) 0f else alpha * wordOpacity }
                            .onGloballyPositioned { if (it.size.width > 0) anchors.stackWordFrames[index] = it.boundsInRoot() },
                    )
                }
            }
        }
    }
}

private fun Modifier.offset(x: Float, y: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, placeable.height) { placeable.place(x.roundToInt(), y.roundToInt()) }
}

private fun clampedCenterY(anchorY: Float, startIndex: Int, count: Int, gap: Float, ceiling: Float, foot: Float): Float {
    var cy = anchorY
    val top = cy - (count - 1 - startIndex) * gap
    val bottom = cy + startIndex * gap
    if (top < ceiling) cy += ceiling - top
    if (bottom > foot) cy -= bottom - foot
    return cy
}

/**
 * Port of iOS `FusedRowMenuOverlay`: the small menu that pops from a held row (0.45 s): Add /
 * Remove from Favorites, then every level with a checkmark on the current one. Mount it
 * full-screen; a tap outside closes it. An M3 surface (iOS: glass); its arrival blur is dropped.
 */
@Composable
fun FusedRowMenuOverlay(
    state: ModelPickerState,
    anchors: ModelPickerAnchors,
    composerFrame: Rect,
    onEvent: (ModelPickerEvent) -> Unit,
    onPickLevel: (model: OpenCodeModel, level: String?) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var sizePx by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                sizePx = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())
            },
    ) {
        // Mounted until its fold clears it, so closing animates.
        val menu = anchors.rowMenu ?: return@Box
        if (sizePx.width <= 0f) return@Box
        val alpha = anchors.rowMenuAlpha.value
        val px = { dp: Double -> with(density) { dp.dp.toPx() } }
        val isFav = menu.model.id in state.favoriteModelIDs
        val remembered = state.rememberedLevel(menu.model)
        val levels = menu.scale.levels
        val menuW = px(230.0)
        val menuH = px(44.0 + (if (levels.size > 1) 9.0 + levels.size * 40.0 else 0.0) + 12.0)
        val known = menu.rowFrame != Rect.Zero
        val row = menu.rowFrame.translate(-origin.x, -origin.y)
        val x = if (known) min(max(row.right - menuW, px(16.0)), sizePx.width - menuW - px(16.0)) else sizePx.width - menuW - px(16.0)
        val foot = max(composerFrame.bottom - origin.y, sizePx.height - px(24.0))
        val rawY = if (known) row.bottom + px(6.0) else px(260.0)
        val y = if (rawY + menuH > foot - px(8.0)) max(px(60.0), row.top - menuH - px(6.0)) else rawY
        val above = known && y < row.top
        val close = { anchors.closeRowMenu(reduceMotion) }

        if (!menu.isClosing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
                    .clearAndSetSemantics {},
            )
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .offset(max(0f, x), max(0f, y))
                .width(230.dp)
                .graphicsLayer {
                    val s = if (reduceMotion) 1f else 0.5f + 0.5f * alpha
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin((menuW - px(30.0)) / menuW, if (above) 1f else 0f)
                    this.alpha = alpha
                }
                .semantics {
                    paneTitle = "${menu.model.shortName} options"
                    if (menu.isClosing) invisibleToUser()
                    dismiss { close(); true }
                }
                .testTag("model-row-menu"),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                MenuRow(
                    height = 44.dp,
                    testTag = "model-menu-favorite",
                    onClick = {
                        onEvent(ModelPickerEvent.ToggleFavoriteModel(menu.model))
                        close()
                    },
                ) {
                    OpenCodeIcon(if (isFav) SFSymbol.StarSlash else SFSymbol.StarFill, null, size = 18.dp, modifier = Modifier.width(20.dp))
                    Text(if (isFav) "Remove from Favorites" else "Add to Favorites", style = OpenCodeTextStyles.body)
                }
                if (levels.size > 1) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    for (lv in levels.reversed()) {
                        val isSelected = lv == remembered
                        MenuRow(
                            height = 40.dp,
                            testTag = "model-menu-level-${lv ?: "default"}",
                            selected = isSelected,
                            onClick = {
                                close()
                                onPickLevel(menu.model, lv)
                            },
                        ) {
                            Box(Modifier.width(20.dp)) {
                                if (isSelected) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small)
                            }
                            Text(ModelLevelScale.label(lv), style = OpenCodeTextStyles.body, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    height: Dp,
    testTag: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(max(height.value, 48f).dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .testTag(testTag)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(Modifier.weight(1f))
    }
}

/**
 * Port of iOS `FusedLabelFlightOverlay` / `FusedLabelFlightBody`: the model name and level word
 * flying between the composer chip and the row, reading live target frames so each lands with
 * no snap. Mount it full-screen; draws nothing without a flight. TalkBack hidden.
 */
@Composable
fun FusedLabelFlightOverlay(anchors: ModelPickerAnchors) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clearAndSetSemantics {},
    ) {
        val flight = anchors.flight ?: return@Box
        val t = anchors.flightProgress.value
        val clamped = t.coerceIn(0f, 1f)
        val nameSize = FusedModelPickerTunables.nameSize.toFloat()
        val open = flight.direction == FusedLabelFlight.Direction.Open
        val fromSize = if (open) 15f else nameSize
        val toSize = if (open) nameSize else 15f
        val fontSize = fromSize + (toSize - fromSize) * clamped
        val liftPx = with(density) { 6.dp.toPx() }
        val px = { dp: Float -> with(density) { dp.dp.toPx() } }

        val liveRowName = anchors.rowNameFrames[flight.modelID]
        val targetName = if (open) liveRowName ?: flight.fromName.translate(px(12f), -px(160f))
        else anchors.chipNameFrame.takeIf { it != Rect.Zero } ?: flight.fromName
        val namePt = FusedPickerMotionTrace.labelPoint(flight.fromName, targetName, t, flight.direction, liftPx)
        FlyingLabel(flight.modelShortName, fontSize, OpenCodeTheme.colors.text, namePt - origin)

        val levelLabel = flight.levelLabel
        if (levelLabel != null) {
            val fromLv = flight.fromLevel ?: flight.fromName.translate(flight.fromName.width + px(8f), 0f)
            val targetLv = if (open) anchors.rowLevelFrames[flight.modelID] ?: targetName.translate(px(180f), 0f)
            else anchors.chipLevelFrame.takeIf { it != Rect.Zero } ?: targetName.translate(targetName.width + px(6f), 0f)
            val levelPt = FusedPickerMotionTrace.labelPoint(fromLv, targetLv, t, flight.direction, liftPx)
            FlyingLabel(levelLabel, fontSize, OpenCodeTheme.colors.text.copy(alpha = FusedModelPickerTunables.levelAlpha.toFloat()), levelPt - origin)
        }
    }
}

/** A label whose leading edge sits at [point].x and vertical centre at [point].y (overlay px). */
@Composable
private fun BoxScope.FlyingLabel(text: String, fontSize: Float, color: Color, point: Offset) {
    Layout(content = {
        Text(text, style = TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.Medium), color = color, maxLines = 1, softWrap = false)
    }) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(IntOffset(point.x.roundToInt(), (point.y - placeable.height / 2f).roundToInt()))
        }
    }
}
