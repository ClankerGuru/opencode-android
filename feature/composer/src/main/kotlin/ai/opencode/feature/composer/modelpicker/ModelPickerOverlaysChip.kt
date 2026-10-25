package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.ui.excludedFromDrawerPan
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Port of iOS `FusedModelChip`: the single composer chip, `"Opus 5.5 High"`, the short model
 * name in primary text and the level dimmed ([FusedModelPickerTunables.levelAlpha]), an
 * optional provider logo ahead of the name ([FusedModelPickerTunables.chipProviderLogo], off),
 * and a vertical drag raising the level stack above the chip; a sideways drag scrubs the level
 * in place (28 dp a stop); a tap opens the list.
 *
 * [composerTop] is the composer's top edge in root px (iOS `attachmentMenuAnchors.composer.minY`).
 */
@Composable
fun FusedModelChip(
    state: ModelPickerState,
    anchors: ModelPickerAnchors,
    composerTop: Float,
    onEvent: (ModelPickerEvent) -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val shortName = state.currentModelShortName
    val levelLabel = state.currentChipLevelLabel
    val scale = state.currentLevelScale
    val flightActive = anchors.flight != null && !reduceMotion
    val currentState by rememberUpdatedState(state)
    val currentOnOpenList by rememberUpdatedState(onOpenList)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentComposerTop by rememberUpdatedState(composerTop)
    val enabled by rememberUpdatedState(isEnabled)

    LaunchedEffect(levelLabel == null) { if (levelLabel == null) anchors.clearChipLevelFrame() }
    DisposableEffect(Unit) { onDispose { anchors.clearChipFrames() } }

    val nameStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Row(
        modifier
            .widthIn(max = FusedModelPickerTunables.chipMax.dp)
            .heightIn(min = 48.dp)
            .animateContentSize(FusedModelPickerTunables.animation(FusedModelPickerTunables.chipWidth, reduceMotion))
            .onGloballyPositioned { if (it.size.width > 0) anchors.chipFrame = it.boundsInRoot() }
            .excludedFromDrawerPan(isEnabled)
            .pointerInput(Unit) {
                val threshold = FusedModelPickerTunables.scrubThreshold.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled) return@awaitEachGesture
                    var axis: Int? = null // 0 horizontal, 1 vertical
                    var scrubStartIdx = 0
                    var total = androidx.compose.ui.geometry.Offset.Zero
                    var lifted = false
                    while (!lifted) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        total += change.positionChange()
                        val s = currentState
                        val model = s.currentModelInfo
                        val sc = s.currentLevelScale
                        if (axis == null && hypot(total.x, total.y) > threshold) {
                            if (abs(total.y) >= abs(total.x) * 0.9f && model != null && sc != null && sc.isAdjustable) {
                                axis = 1
                                val rightEdge = if (anchors.chipLevelFrame != androidx.compose.ui.geometry.Rect.Zero) anchors.chipLevelFrame.right else anchors.chipFrame.right
                                val anchorY = currentComposerTop - 24.dp.toPx()
                                anchors.openLevelStack(
                                    kind = FusedLevelStackState.SourceKind.Chip,
                                    model = model,
                                    level = s.currentLevel,
                                    anchorX = anchors.chipFrame.left + down.position.x,
                                    anchorY = anchorY,
                                    rightEdgeX = rightEdge,
                                    tapMode = false,
                                    reduceMotion = reduceMotion,
                                )
                            } else if (abs(total.x) > abs(total.y) && sc != null && sc.isAdjustable) {
                                axis = 0
                                scrubStartIdx = sc.index(s.currentLevel)
                            }
                        }
                        if (axis == 1 && model != null && sc != null) {
                            change.consume()
                            val next = anchors.updateLevelStackDrag(-(total.y / density.density).toDouble())
                            if (next != null) currentOnEvent(ModelPickerEvent.ModelRowLevelChanged(model, sc.level(next)))
                        } else if (axis == 0 && model != null && sc != null) {
                            change.consume()
                            val nextIdx = sc.scrubIndex(scrubStartIdx, (total.x / density.density).toDouble(), step = 28.0)
                            val nextLevel = sc.level(nextIdx)
                            if (nextLevel != s.currentLevel) currentOnEvent(ModelPickerEvent.ModelRowLevelChanged(model, nextLevel))
                        }
                        if (!change.pressed) {
                            lifted = true
                            // A tap is the chip's: the composer behind it mustn't also take it (and raise the keyboard).
                            if (axis == null && hypot(total.x, total.y) <= threshold) change.consume()
                        }
                    }
                    if (anchors.levelStack?.kind == FusedLevelStackState.SourceKind.Chip) {
                        anchors.closeLevelStack(commit = true, reduceMotion = reduceMotion)
                    } else if (lifted && axis == null && hypot(total.x, total.y) <= threshold) {
                        currentOnOpenList()
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name: “Choose model”, its value the short name; a tap opens the list.
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .weight(1f, fill = false)
                .clearAndSetSemantics {
                    contentDescription = "Choose model"
                    stateDescription = shortName
                    role = Role.Button
                    onClick { if (enabled) currentOnOpenList(); true }
                }
                .testTag("composer-model"),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val model = state.currentModelInfo
            if (FusedModelPickerTunables.chipProviderLogo && model != null) {
                ProviderIcon(model.providerID, model.canonicalProviderID, size = 15.dp, tint = OpenCodeTheme.colors.secondaryText)
            }
            AnimatedContent(
                targetState = shortName,
                transitionSpec = { fadeIn(FusedModelPickerTunables.animation(FusedModelPickerTunables.chipRoll, reduceMotion)) togetherWith fadeOut(FusedModelPickerTunables.animation(FusedModelPickerTunables.chipRoll, reduceMotion)) },
                label = "chip-name",
            ) { name ->
                Text(
                    name,
                    style = nameStyle,
                    color = OpenCodeTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .alpha(if (flightActive) 0f else 1f)
                        .onGloballyPositioned { if (it.size.width > 0) anchors.chipNameFrame = it.boundsInRoot() },
                )
            }
        }

        if (FusedModelPickerTunables.chipGlyph && scale != null) {
            ModelLevelGlyph(scale, state.currentLevel, size = 15.dp, color = OpenCodeTheme.colors.text)
        }

        if (levelLabel != null) {
            Row(
                Modifier
                    .widthIn(min = 48.dp)
                    .heightIn(min = 48.dp)
                    .clearAndSetSemantics {
                        contentDescription = "Thinking level"
                        stateDescription = levelLabel
                        role = Role.Button
                        onClick { if (enabled) currentOnOpenList(); true }
                        // TalkBack's adjust: one stop up or down (iOS `accessibilityAdjustableAction`).
                        if (scale != null) {
                            progressBarRangeInfo = ProgressBarRangeInfo(scale.index(state.currentLevel).toFloat(), 0f..(scale.count - 1).toFloat(), scale.count - 2)
                            setProgress { target ->
                                val model = currentState.currentModelInfo ?: return@setProgress false
                                val delta = target.roundToInt() - scale.index(currentState.currentLevel)
                                if (delta == 0) return@setProgress false
                                currentOnEvent(ModelPickerEvent.ModelRowLevelChanged(model, scale.stepped(currentState.currentLevel, if (delta > 0) 1 else -1)))
                                true
                            }
                        }
                    }
                    .testTag("composer-level"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = levelLabel,
                    transitionSpec = { fadeIn(FusedModelPickerTunables.animation(FusedModelPickerTunables.chipRoll, reduceMotion)) togetherWith fadeOut(FusedModelPickerTunables.animation(FusedModelPickerTunables.chipRoll, reduceMotion)) },
                    label = "chip-level",
                ) { label ->
                    Text(
                        label,
                        style = nameStyle,
                        color = OpenCodeTheme.colors.text.copy(alpha = FusedModelPickerTunables.levelAlpha.toFloat()),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .alpha(if (flightActive) 0f else 1f)
                            .onGloballyPositioned {
                                if (it.size.width > 0) {
                                    val frame = it.boundsInRoot()
                                    anchors.chipLevelFrame = frame
                                    anchors.levelControl = frame
                                }
                            },
                    )
                }
            }
        }

        if (FusedModelPickerTunables.chipChevron) {
            OpenCodeIcon(SFSymbol.ChevronDown, null, size = 12.dp, tint = OpenCodeTheme.colors.secondaryText)
        }
    }
}
