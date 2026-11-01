@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.min
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// Port of iOS `Composer/ModelPicker/FusedModelRow.swift` (split out of ModelPickerOverlays in 17a991b1).

// MARK: - Fused Model Row

/** iOS `FusedLevelGesturePhase`. */
enum class FusedLevelGesturePhase { Began, Changed, Ended, Cancelled }

/** A short hold on the level raises its stack to drag through (s). */
private const val LEVEL_HOLD_DURATION = 0.2

/**
 * Port of iOS `FusedModelRow`. A tap picks the model at its remembered level; held still for
 * [FusedModelPickerTunables.holdToFavorite] it opens the row menu (`.threshold`). On the level:
 * a quick still tap raises the stack to choose from (`.tap`); a short still hold
 * ([LEVEL_HOLD_DURATION]) raises it to drag through (`.tap`, then `.selection` per stop, and the
 * release picks); a sideways drag scrubs in place and the release picks; a vertical drag that
 * starts there scrolls the rows.
 */
@Composable
internal fun FusedModelRow(
    state: ModelPickerState,
    model: OpenCodeModel,
    inFavorites: Boolean,
    isDuplicateInAllModels: Boolean,
    isPrimaryFlightRow: Boolean,
    showsProvider: Boolean,
    focusOpacity: Double,
    anchors: ModelPickerAnchors,
    isOpen: Boolean,
    onEvent: (ModelPickerEvent) -> Unit,
    onPick: (level: String?, levelSourceRect: Rect?, ticked: Boolean) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val haptics = remember { dependency(HapticsClient) }
    val isCurrent = state.currentModelInfo?.id == model.id
    val isFavorite = model.id in state.favoriteModelIDs
    val scale = remember(model.variants) { ModelLevelScale(model.variants) }
    val rememberedLevel = state.rememberedLevel(model)
    val provider = ModelProviderGroup.canonicalVendorTitle(model.providerID)
    var rowFrame by remember { mutableStateOf(Rect.Zero) }
    var nameFrame by remember { mutableStateOf(Rect.Zero) }
    var levelFrame by remember { mutableStateOf(Rect.Zero) }
    val alpha by animateFloatAsState(focusOpacity.toFloat(), FusedModelPickerTunables.animation(FusedModelPickerTunables.scrubIn, reduceMotion), label = "row-focus")
    val currentOpen by rememberUpdatedState(isOpen)
    val currentState by rememberUpdatedState(state)

    val rowID = when {
        inFavorites -> "model-favorite-${model.id}"
        isDuplicateInAllModels -> "model-all-${model.id}"
        else -> "model-row-${model.id}"
    }
    val levelID = when {
        inFavorites -> "model-favorite-level-${model.id}"
        isDuplicateInAllModels -> "model-all-level-${model.id}"
        else -> "model-row-level-${model.id}"
    }

    fun syncFlightFrames() {
        if (rowFrame != Rect.Zero) anchors.rowFrames[model.id] = rowFrame
        if (nameFrame != Rect.Zero) anchors.rowNameFrames[model.id] = nameFrame
        if (levelFrame != Rect.Zero) anchors.rowLevelFrames[model.id] = levelFrame
    }

    val isFlying = anchors.flight?.modelID == model.id && !reduceMotion
    val modalOverlayOpen = anchors.isOverlayActive
    val favTitle = if (isFavorite) "Remove from Favorites" else "Add to Favorites"
    // iOS `FusedRowButtonStyle`: no highlight, the label at 0.6 while pressed; the press's start
    // is where the list stood, so a hold that rode a scroll doesn't count.
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    var pressScrollStart by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { if (it is PressInteraction.Press) pressScrollStart = anchors.listScrollTravel }
    }
    val holdSlopPx = with(LocalDensity.current) { HOLD_SLOP.dp.toPx() }
    val baseConfig = LocalViewConfiguration.current
    // The row's hold is `holdToFavorite` (0.45 s), not the system's long-press timeout.
    val holdConfig = remember(baseConfig) {
        object : ViewConfiguration by baseConfig {
            override val longPressTimeoutMillis: Long = (FusedModelPickerTunables.holdToFavorite * 1000).roundToInt().toLong()
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .onGloballyPositioned {
                if (it.size.width > 0) {
                    rowFrame = it.unclippedBoundsInRoot()
                    if (isPrimaryFlightRow) anchors.rowFrames[model.id] = rowFrame
                }
            }
            .heightIn(min = FusedModelPickerTunables.rowHeight.dp)
            .background(
                if (isCurrent && FusedModelPickerTunables.current == 1) OpenCodeTheme.colors.text.copy(alpha = 0.09f) else Color.Transparent,
                RoundedCornerShape(max(8.0, FusedModelPickerTunables.radius - FusedModelPickerTunables.padding).dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalViewConfiguration provides holdConfig) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = FusedModelPickerTunables.rowHeight.dp)
                    .combinedClickable(
                        interactionSource = interactions,
                        indication = null,
                        enabled = !modalOverlayOpen,
                        hapticFeedbackEnabled = false,
                        role = Role.Button,
                        onClick = {
                            if (!isOpen || anchors.isOverlayActive) return@combinedClickable
                            syncFlightFrames()
                            onPick(rememberedLevel, null, false)
                        },
                        onLongClick = {
                            // The row moves with the finger while the list scrolls, so its press
                            // can't see it: the list's own movement rules a hold out.
                            val rodeScroll = anchors.listIsScrolling || abs(anchors.listScrollTravel - pressScrollStart) > holdSlopPx
                            if (!currentOpen || anchors.isOverlayActive || rodeScroll) return@combinedClickable
                            syncFlightFrames()
                            haptics.play(Haptic.threshold)
                            anchors.openRowMenu(model, rowFrame, reduceMotion)
                        },
                    )
                    .graphicsLayer { this.alpha = if (pressed) 0.6f else 1f }
                    .padding(start = 12.dp, end = 8.dp)
                    .clearAndSetSemantics {
                        if (modalOverlayOpen) invisibleToUser()
                        contentDescription = model.name
                        stateDescription = if (isFavorite) "$provider, Favorite" else provider
                        selected = isCurrent
                        role = Role.Button
                        onClick { if (isOpen) { syncFlightFrames(); onPick(rememberedLevel, null, false) }; true }
                        customActions = listOf(CustomAccessibilityAction(favTitle) { onEvent(ModelPickerEvent.ToggleFavoriteModel(model)); true })
                    }
                    .testTag(rowID),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (FusedModelPickerTunables.icons) {
                    ProviderIcon(
                        model.providerID, model.canonicalProviderID,
                        size = FusedModelPickerTunables.iconSize.dp,
                        tint = if (isCurrent) OpenCodeTheme.colors.text else OpenCodeTheme.colors.secondaryText,
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.shortName,
                        style = TextStyle(fontSize = FusedModelPickerTunables.nameSize.sp, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal),
                        color = OpenCodeTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .alpha(if (isFlying) 0f else 1f)
                            .onGloballyPositioned {
                                if (it.size.width > 0) {
                                    nameFrame = it.unclippedBoundsInRoot()
                                    if (isPrimaryFlightRow) anchors.rowNameFrames[model.id] = nameFrame
                                }
                            },
                    )
                    if (showsProvider) {
                        Text(provider, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
                    }
                }
                if (!scale.isAdjustable && isCurrent && FusedModelPickerTunables.current == 2) {
                    OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small, tint = OpenCodeTheme.colors.text)
                }
            }
        }
        if (scale.isAdjustable) {
            LevelZone(
                model = model,
                scale = scale,
                rememberedLevel = rememberedLevel,
                anchors = anchors,
                isOpen = isOpen,
                isFlying = isFlying,
                levelID = levelID,
                onLevelFrame = {
                    levelFrame = it
                    if (isPrimaryFlightRow) anchors.rowLevelFrames[model.id] = it
                },
                levelFrame = { levelFrame },
                rowFrame = { rowFrame },
                syncFlightFrames = ::syncFlightFrames,
                currentState = { currentState },
                haptics = haptics,
                reduceMotion = reduceMotion,
                onEvent = onEvent,
                onPick = onPick,
            )
        }
    }
}

@Composable
private fun LevelZone(
    model: OpenCodeModel,
    scale: ModelLevelScale,
    rememberedLevel: String?,
    anchors: ModelPickerAnchors,
    isOpen: Boolean,
    isFlying: Boolean,
    levelID: String,
    onLevelFrame: (Rect) -> Unit,
    levelFrame: () -> Rect,
    rowFrame: () -> Rect,
    syncFlightFrames: () -> Unit,
    currentState: () -> ModelPickerState,
    haptics: HapticsClient,
    reduceMotion: Boolean,
    onEvent: (ModelPickerEvent) -> Unit,
    onPick: (String?, Rect?, Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val levelAlpha = FusedModelPickerTunables.levelAlpha.toFloat()
    val isStackOpenOnRow = anchors.levelStack?.kind == FusedLevelStackState.SourceKind.Row && anchors.levelStack?.model?.id == model.id
    val blocksLevelTouches = anchors.isRowMenuActive ||
        (anchors.isLevelStackActive && (anchors.levelStack?.tapMode == true || anchors.levelStack?.model?.id != model.id))
    val currentOpen by rememberUpdatedState(isOpen)
    val currentBlocks by rememberUpdatedState(blocksLevelTouches)
    val currentRemembered by rememberUpdatedState(rememberedLevel)
    val label = ModelLevelScale.label(rememberedLevel)
    val hidden = isFlying || isStackOpenOnRow
    var zoneFrame by remember { mutableStateOf(Rect.Zero) }

    fun openStack(anchorX: Float?, tapMode: Boolean) {
        syncFlightFrames()
        val lf = levelFrame()
        val rf = rowFrame()
        val rightX = if (lf != Rect.Zero) lf.right else rf.right - with(density) { 18.dp.toPx() }
        val centerY = if (lf != Rect.Zero) lf.center.y else rf.center.y
        anchors.openLevelStack(
            kind = FusedLevelStackState.SourceKind.Row,
            model = model,
            level = currentRemembered,
            anchorX = anchorX ?: (rightX - with(density) { 24.dp.toPx() }),
            anchorY = centerY,
            rightEdgeX = rightX,
            tapMode = tapMode,
            reduceMotion = reduceMotion,
        )
    }

    fun openStackByTap() {
        if (!currentOpen || anchors.isOverlayActive) return
        haptics.play(Haptic.tap)
        openStack(null, tapMode = true)
    }

    Row(
        Modifier
            .heightIn(min = FusedModelPickerTunables.rowHeight.dp)
            .onGloballyPositioned { zoneFrame = it.unclippedBoundsInRoot() }
            .pointerInput(model.id) {
                val slop = HOLD_SLOP.dp.toPx()
                val scrubSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentOpen || currentBlocks) return@awaitEachGesture
                    // Phase 1: a quick still lift is a tap; a sideways move scrubs; a vertical one scrolls.
                    var total = Offset.Zero
                    val early: String? = withTimeoutOrNull((LEVEL_HOLD_DURATION * 1000).toLong()) {
                        var outcome: String? = null
                        while (outcome == null) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                outcome = "cancel"
                            } else if (!change.pressed) {
                                outcome = if (hypot(total.x, total.y) <= slop) "tap" else "cancel"
                            } else {
                                total += change.positionChange()
                                if (hypot(total.x, total.y) > 4.dp.toPx() && abs(total.x) <= abs(total.y) * 1.2f) {
                                    outcome = "vertical"
                                } else if (abs(total.x) > scrubSlop) {
                                    outcome = "horizontal"
                                }
                            }
                        }
                        outcome
                    }
                    when (early) {
                        "tap" -> openStackByTap()
                        "vertical", "cancel" -> Unit
                        "horizontal" -> {
                            // Scrub: every max(wordGap + 8, 24) dp is one stop; the release picks.
                            if (anchors.isOverlayActive) return@awaitEachGesture
                            val step = max(FusedModelPickerTunables.wordGap + 8, 24.0)
                            val startIdx = scale.index(currentRemembered)
                            var ticked = false
                            var ended = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) { ended = true; break }
                                change.consume()
                                total += change.positionChange()
                                val next = scale.level(scale.scrubIndex(startIdx, (total.x / density.density).toDouble(), step))
                                if (next != currentState().rememberedLevel(model)) {
                                    onEvent(ModelPickerEvent.ModelRowLevelChanged(model, next))
                                    ticked = true
                                }
                            }
                            // Let go, the scrubbed level is picked; cancelled, it stays remembered.
                            if (ended && currentOpen) {
                                syncFlightFrames()
                                onPick(currentState().rememberedLevel(model), null, ticked)
                            }
                        }
                        else -> {
                            // Held still, not riding a scroll: the stack rises to drag through.
                            if (anchors.listIsScrolling || anchors.isOverlayActive) return@awaitEachGesture
                            haptics.play(Haptic.tap)
                            openStack(zoneFrame.takeIf { it != Rect.Zero }?.let { it.left + down.position.x }, tapMode = false)
                            var stackDragged = false
                            var ticked = false
                            var startY = 0f
                            var dy = 0f
                            var ended = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) { ended = true; break }
                                change.consume()
                                dy += change.positionChange().y
                                if (abs(dy - startY) > FusedModelPickerTunables.scrubThreshold.dp.toPx()) {
                                    if (!stackDragged) anchors.setLevelStackTapMode(false)
                                    stackDragged = true
                                }
                                if (stackDragged && anchors.updateLevelStackDrag(-(dy / density.density).toDouble()) != null) {
                                    haptics.play(Haptic.selection)
                                    ticked = true
                                }
                            }
                            val stack = anchors.levelStack
                            if (ended && currentOpen && stackDragged && stack != null && stack.model.id == model.id && !stack.isClosing) {
                                syncFlightFrames()
                                val chosenIdx = stack.currentIndex
                                val wordRect = anchors.stackWordFrames[chosenIdx]
                                anchors.closeLevelStack(commit = true, reduceMotion = reduceMotion)
                                onPick(scale.level(chosenIdx), wordRect, ticked)
                            } else if (stack != null && stack.model.id == model.id && !stack.isClosing) {
                                // Lifted without dragging, the stack stays up to tap a level; a
                                // hold the system cancelled folds it away.
                                if (ended) anchors.setLevelStackTapMode(true) else anchors.closeLevelStack(commit = false, reduceMotion = reduceMotion)
                            }
                        }
                    }
                }
            }
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .clearAndSetSemantics {
                if (anchors.isOverlayActive) invisibleToUser()
                contentDescription = "${model.shortName} thinking level"
                stateDescription = label
                role = Role.Button
                onClick { openStackByTap(); true }
                progressBarRangeInfo = ProgressBarRangeInfo(scale.index(rememberedLevel).toFloat(), 0f..(scale.count - 1).toFloat(), max(scale.count - 2, 0))
                setProgress { target ->
                    val delta = target.roundToInt() - scale.index(currentRemembered)
                    if (delta == 0) return@setProgress false
                    onEvent(ModelPickerEvent.ModelRowLevelChanged(model, scale.stepped(currentRemembered, if (delta > 0) 1 else -1)))
                    true
                }
            }
            .testTag(levelID),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = TextStyle(fontSize = FusedModelPickerTunables.nameSize.sp),
            color = OpenCodeTheme.colors.text.copy(alpha = levelAlpha),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .alpha(if (hidden) 0f else 1f)
                .onGloballyPositioned { if (it.size.width > 0) onLevelFrame(it.unclippedBoundsInRoot()) },
        )
        OpenCodeIcon(
            SFSymbol.ChevronUpChevronDown, null, size = 12.dp,
            tint = OpenCodeTheme.colors.text.copy(alpha = levelAlpha * 0.85f),
            modifier = Modifier.alpha(if (hidden) 0f else 1f),
        )
    }
}

/**
 * The element's bounds in root px, not clipped by its ancestors: the morph's glass clips the
 * list while it grows, and `boundsInRoot` would collapse a row it hasn't reached yet to its
 * edge, so a label flight aimed at it would jump there.
 */
private fun androidx.compose.ui.layout.LayoutCoordinates.unclippedBoundsInRoot(): Rect =
    Rect(positionInRoot(), androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))
