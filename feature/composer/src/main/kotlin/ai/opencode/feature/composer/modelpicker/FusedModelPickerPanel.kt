@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.opencode.feature.composer.modelpicker

import ai.opencode.server.model.ModelSearch
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.ambiguousShortNames
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.dismiss
import kotlinx.coroutines.launch
import kotlin.math.min
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.max

/**
 * Port of iOS `FusedModelPickerPanel` (Grow from Composer, WIP): the grabber, Favorites /
 * Recent / All Models (or flat search results) and Search Models at the foot. Drags on the
 * grabber move the list between its rests ([onDrag], dp; [onDragEnded] with the predicted
 * travel and velocity, dp and dp/s); scrolling the rows up or pulling them down past their top
 * reports [onScrollEdge]. Enter in the search picks the best match at any level named in it.
 * The rows fade out at the bottom edge, and at the top once scrolled ([FusedModelPickerTunables.edgeFade]);
 * the header of the section scrolled past holds at the top over a real progressive blur of the rows.
 * Capped at iOS's `accessibility2` text size.
 */
@Composable
fun FusedModelPickerPanel(
    state: ModelPickerState,
    anchors: ModelPickerAnchors,
    isOpen: Boolean,
    onEvent: (ModelPickerEvent) -> Unit,
    onPickModel: (model: OpenCodeModel, level: String?, levelSourceRect: Rect?, ticked: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showsRows: Boolean = true,
    onSearchHoldsKeyboard: (Boolean) -> Unit = {},
    takesKeyboard: Boolean = false,
    listBottomInset: Dp = 0.dp,
    detent: ModelListDetent = ModelListDetent.Medium,
    onDrag: (Double) -> Unit = {},
    onDragEnded: (predictedTranslation: Double, velocity: Double) -> Unit = { _, _ -> },
    onDragCancelled: () -> Unit = {},
    onDetent: (ModelListDetent) -> Unit = {},
    onScrollEdge: (ModelListScrollEdge) -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    val pad = FusedModelPickerTunables.padding
    val grabSpace = FusedModelPickerTunables.grabSpace
    val sections = activeSections(state, anchors, isOpen)
    val allListed = sections.flatMap { it.models }
    val ambiguousNames = (if (state.models.isEmpty()) allListed else state.models).ambiguousShortNames
    val modalOverlayOpen = anchors.isOverlayActive
    val blocksPanelTouches = (anchors.levelStack?.tapMode == true && anchors.isLevelStackActive) || anchors.isRowMenuActive
    val searchFocus = remember { FocusRequester() }
    // Enter picked a model that's about to close the list: the search still holds the
    // keyboard until it does, so the close puts it away.
    var submitPickPending by remember { mutableStateOf(false) }

    LaunchedEffect(takesKeyboard, isOpen) { if (takesKeyboard && isOpen) runCatching { searchFocus.requestFocus() } }
    LaunchedEffect(state.modelFilter) {
        if (isOpen) {
            anchors.closeLevelStack(reduceMotion = reduceMotion)
            anchors.closeRowMenu(reduceMotion = reduceMotion)
        }
    }
    LaunchedEffect(anchors.openGeneration) {
        anchors.listScrollOffset = 0f
        anchors.listIsScrolling = false
    }

    val baseDensity = LocalDensity.current
    // iOS `.dynamicTypeSize(...DynamicTypeSize.accessibility2)`: body 17 → 28 pt at most.
    val cappedDensity = remember(baseDensity) {
        if (baseDensity.fontScale <= MAX_FONT_SCALE) baseDensity else Density(baseDensity.density, MAX_FONT_SCALE)
    }
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalDensity provides cappedDensity) {
    Column(
        modifier
            .fillMaxSize()
            .semantics {
                if (modalOverlayOpen) invisibleToUser()
                // The stack and menu are modal while up; escape (back) closes the list otherwise.
                if (isOpen && !modalOverlayOpen) dismiss { onDismiss(); true }
            }
            .testTag("model-list"),
    ) {
        Grabber(
            isOpen = isOpen,
            detent = detent,
            height = max(grabSpace + pad, 20.0).dp,
            enabled = !blocksPanelTouches,
            onDrag = onDrag,
            onDragEnded = onDragEnded,
            onDragCancelled = onDragCancelled,
            onDetent = onDetent,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (state.isLoadingModels && state.models.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (showsRows) {
                key(anchors.openGeneration) {
                    RowsList(
                        state = state,
                        anchors = anchors,
                        sections = sections,
                        ambiguousNames = ambiguousNames,
                        isOpen = isOpen,
                        blocksTouches = blocksPanelTouches,
                        onEvent = onEvent,
                        onPickModel = onPickModel,
                        onScrollEdge = onScrollEdge,
                    )
                }
            }
        }
        ModelSearchCapsuleField(
            text = state.modelFilter,
            onTextChange = { onEvent(ModelPickerEvent.FilterChanged(it)) },
            modifier = Modifier
                .padding(horizontal = (pad + 6).dp)
                .padding(top = 6.dp, bottom = max(pad, 6.0).dp + listBottomInset.coerceAtLeast(0.dp))
                .onFocusChanged { focus ->
                    if (!focus.isFocused && submitPickPending) return@onFocusChanged
                    onSearchHoldsKeyboard(focus.isFocused)
                },
            focusRequester = searchFocus,
            spacing = 8.dp,
            leadingPadding = 14.dp,
            emptyTrailingPadding = 14.dp,
            clearTrailingPadding = 2.dp,
            minHeight = FusedModelPickerTunables.searchHeight,
            imeAction = ImeAction.Go,
            onSubmit = {
                if (!isOpen) return@ModelSearchCapsuleField
                val resolved = ModelSearch.resolvePick(state.models, state.modelFilter, state.favoriteModelIDs, state.recentModelIDs)
                if (resolved != null) {
                    val level = if (resolved.hasExplicitLevel) resolved.variant else state.rememberedLevel(resolved.model)
                    onSearchHoldsKeyboard(true)
                    submitPickPending = true
                    // iOS picks on the next main-queue turn (`DispatchQueue.main.async`).
                    scope.launch {
                        submitPickPending = false
                        onPickModel(resolved.model, level, null, false)
                    }
                }
            },
        )
    }
    }
}

/** iOS caps the panel at `accessibility2` (body 28 pt over the default 17). */
private const val MAX_FONT_SCALE = 28f / 17f

/** While folding, the list stands still so Recent only reorders after close. */
private fun activeSections(state: ModelPickerState, anchors: ModelPickerAnchors, isOpen: Boolean): List<ModelPickerSection> {
    val frozen = anchors.frozenSections
    if (!isOpen && frozen != null) return frozen
    return state.fusedModelSections(
        recentLimit = roundHalfAwayFromZero(FusedModelPickerTunables.recents).toInt(),
        includeAllModels = FusedModelPickerTunables.allModels == 1,
    )
}

@Composable
private fun Grabber(
    isOpen: Boolean,
    detent: ModelListDetent,
    height: Dp,
    enabled: Boolean,
    onDrag: (Double) -> Unit,
    onDragEnded: (Double, Double) -> Unit,
    onDragCancelled: () -> Unit,
    onDetent: (ModelListDetent) -> Unit,
) {
    val density = LocalDensity.current
    val currentOpen by rememberUpdatedState(isOpen)
    val toggle = { if (isOpen) onDetent(if (detent == ModelListDetent.Large) ModelListDetent.Medium else ModelListDetent.Large) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clickable(enabled = enabled, onClick = toggle)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                // SwiftUI `DragGesture(minimumDistance: dragMinimum)`: it starts once the finger
                // has moved `dragMinimum` from where it went down, and its translation runs from there.
                val minimum = ModelPickerTunables.dragMinimum.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragging = false
                    var ended = false
                    var translation = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        translation = change.position - down.position
                        if (!change.pressed) {
                            ended = true
                            break
                        }
                        if (!dragging && hypot(translation.x, translation.y) >= minimum) dragging = true
                        if (dragging) {
                            change.consume()
                            if (currentOpen) onDrag(with(density) { translation.y.toDp().value.toDouble() })
                        }
                    }
                    if (!dragging) return@awaitEachGesture
                    if (ended) {
                        val v = with(density) { tracker.calculateVelocity().y.toDp().value.toDouble() }
                        val t = with(density) { translation.y.toDp().value.toDouble() }
                        onDragEnded(predictedEndTranslation(t, v), v)
                    } else {
                        onDragCancelled()
                    }
                }
            }
            .clearAndSetSemantics {
                contentDescription = "Sheet Grabber"
                stateDescription = detent.accessibilityValue
                role = Role.Button
                onClick(if (detent == ModelListDetent.Large) "Make the list half height" else "Make the list full height") { toggle(); true }
                progressBarRangeInfo = ProgressBarRangeInfo(if (detent == ModelListDetent.Large) 1f else 0f, 0f..1f, 0)
                setProgress { target -> onDetent(detent.adjusted(if (target > 0.5f) 1 else -1)); true }
            }
            .testTag("model-list-grabber"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 36.dp, height = 5.dp).background(OpenCodeTheme.colors.tertiaryText, RoundedCornerShape(50)))
    }
}

@Composable
private fun RowsList(
    state: ModelPickerState,
    anchors: ModelPickerAnchors,
    sections: List<ModelPickerSection>,
    ambiguousNames: Set<String>,
    isOpen: Boolean,
    blocksTouches: Boolean,
    onEvent: (ModelPickerEvent) -> Unit,
    onPickModel: (OpenCodeModel, String?, Rect?, Boolean) -> Unit,
    onScrollEdge: (ModelListScrollEdge) -> Unit,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val showHeaders = FusedModelPickerTunables.headers
    val focusModelID = anchors.levelStack?.model?.id ?: anchors.rowMenu?.model?.id
    val isFocusingRow = anchors.isOverlayActive
    val favoriteIDs = sections.firstOrNull { it.kind == ModelPickerSection.Kind.Favorites }?.models?.mapTo(HashSet()) { it.id } ?: emptySet()
    val recentIDs = sections.firstOrNull { it.kind == ModelPickerSection.Kind.Recent }?.models?.mapTo(HashSet()) { it.id } ?: emptySet()
    val currentOpen by rememberUpdatedState(isOpen)
    val currentOnScrollEdge by rememberUpdatedState(onScrollEdge)
    val rowsSource = remember { BlurSource() }
    // The header row each titled section starts with (its LazyColumn index).
    val headerIndices = remember(sections, showHeaders) {
        var index = 0
        sections.map { section ->
            val header = if (showHeaders && section.title.isNotEmpty()) index++ else -1
            index += section.models.size
            header
        }
    }
    // How far the rows are scrolled (px; iOS `contentOffset.y`): past the first row, far.
    val scrollOffset by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else Float.MAX_VALUE / 4
        }
    }

    // The rows' scroll: past `scrollUpTrigger` they take the list to its large rest.
    LaunchedEffect(listState) {
        snapshotFlow { scrollOffset to listState.isScrollInProgress }
            .collect { (px, scrolling) ->
                anchors.listScrollOffset = px
                anchors.listIsScrolling = scrolling
                if (scrolling && currentOpen && px > with(density) { ModelPickerTunables.scrollUpTrigger.dp.toPx() }) {
                    currentOnScrollEdge(ModelListScrollEdge.ScrolledUp)
                }
            }
    }
    // Pulled down past their top by `pullDownTrigger`: the list goes down a rest (or closes).
    val pull = remember {
        object : NestedScrollConnection {
            var deepest = 0f
            var startedAtTop = false
            var dragging = false
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                anchors.listScrollTravel += consumed.y
                if (source == NestedScrollSource.UserInput) {
                    if (!dragging) {
                        dragging = true
                        deepest = 0f
                        startedAtTop = !listState.canScrollBackward
                    }
                    // Scrolled into the rows, a bounce back at the top is not a pull.
                    if (listState.canScrollBackward) startedAtTop = false
                    if (available.y > 0) deepest += available.y
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                val pulled = startedAtTop && deepest > with(density) { ModelPickerTunables.pullDownTrigger.dp.toPx() }
                dragging = false
                deepest = 0f
                startedAtTop = false
                if (pulled && currentOpen) currentOnScrollEdge(ModelListScrollEdge.PulledDown)
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    // A header's top in the rows' frame (px), or null when it isn't laid out (above: −∞).
    fun headerTop(sectionIndex: Int): Float? {
        val index = headerIndices.getOrNull(sectionIndex)?.takeIf { it >= 0 } ?: return null
        val info = listState.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.index == index }?.let { return (it.offset - info.viewportStartOffset).toFloat() }
        return if (index < listState.firstVisibleItemIndex) Float.NEGATIVE_INFINITY else null
    }
    val threshold = with(density) { 2.dp.toPx() }
    val pinnedIndex by remember(listState, headerIndices) {
        derivedStateOf {
            if (scrollOffset <= threshold) return@derivedStateOf null
            sections.indices.lastOrNull { i -> headerTop(i)?.let { it < threshold } == true }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // The rows fade into the panel at the bottom, and at the top once anything's under it
            // (the pinned header fades with them, as on iOS where the mask covers its overlay).
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val edgeFade = FusedModelPickerTunables.edgeFade.dp.toPx()
                if (edgeFade > 0f) {
                    if (scrollOffset > 0.5f * density.density) {
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, edgeFade / size.height to Color.Black), blendMode = BlendMode.DstIn)
                    }
                    val bottom = min(edgeFade, 18.dp.toPx())
                    drawRect(Brush.verticalGradient((size.height - bottom) / size.height to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }
            },
    ) {
        LazyColumn(
            state = listState,
            // A raised stack or menu holds the rows still: a hold that raised the stack drags
            // through it rather than scrolling the rows away under it.
            userScrollEnabled = !isFocusingRow,
            modifier = Modifier
                .fillMaxSize()
                .blurSource(rowsSource)
                .nestedScroll(pull)
                .semantics { if (blocksTouches) invisibleToUser() },
            contentPadding = PaddingValues(horizontal = FusedModelPickerTunables.padding.dp),
        ) {
            for ((sectionIndex, section) in sections.withIndex()) {
                if (headerIndices[sectionIndex] >= 0) {
                    item(key = "header-${section.id}", contentType = "header") {
                        FusedSectionHeader(
                            section,
                            // Under its pinned copy, the row's own header steps aside.
                            hidden = { scrollOffset > 0.5f * density.density && headerTop(sectionIndex)?.let { it < threshold } == true },
                        )
                    }
                }
                val models = if (FusedModelPickerTunables.searchOrder == 1 && section.kind == ModelPickerSection.Kind.SearchResults) section.models.reversed() else section.models
                items(models, key = { "${section.id}-${it.id}" }, contentType = { "row" }) { model ->
                    val rowFocus = if (isFocusingRow && focusModelID != null && focusModelID != model.id) 1 - FusedModelPickerTunables.focusDim else 1.0
                    val isDuplicateInAllModels = section.kind == ModelPickerSection.Kind.AllModels && model.id in recentIDs
                    val isPrimaryFlightRow = section.kind != ModelPickerSection.Kind.AllModels || (model.id !in favoriteIDs && model.id !in recentIDs)
                    FusedModelRow(
                        state = state,
                        model = model,
                        inFavorites = section.kind == ModelPickerSection.Kind.Favorites,
                        isDuplicateInAllModels = isDuplicateInAllModels,
                        isPrimaryFlightRow = isPrimaryFlightRow,
                        showsProvider = model.shortName in ambiguousNames,
                        focusOpacity = rowFocus,
                        anchors = anchors,
                        isOpen = isOpen,
                        onEvent = onEvent,
                        onPick = { level, rect, ticked -> onPickModel(model, level, rect, ticked) },
                    )
                }
            }
        }
        val pinned = pinnedIndex?.let { sections.getOrNull(it) }
        if (showHeaders && pinned != null && pinned.title.isNotEmpty()) {
            Box(Modifier.fillMaxWidth()) {
                // iOS: `ProgressiveBlur(edge: .top, radius: headerBlur)`, headerHeight + 16 tall.
                ProgressiveBlur(
                    source = rowsSource,
                    profile = ProgressiveBlurProfile.Edge(top = true),
                    radius = ModelPickerTunables.headerBlur.dp,
                    modifier = Modifier.fillMaxWidth().height((FusedModelPickerTunables.headerHeight + 16).dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(FusedModelPickerTunables.headerHeight.dp)
                        .padding(horizontal = (12 + FusedModelPickerTunables.padding).dp)
                        .semantics(mergeDescendants = true) { heading() }
                        .testTag("model-pinned-header"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FusedSectionHeaderTitle(pinned.title)
                }
            }
        }
        if (sections.isEmpty() && !state.isLoadingModels) {
            ModelListEmptyState(state, includesLoadError = false, onTryAgain = { onEvent(ModelPickerEvent.ModelListOpened) })
        }
    }
}

@Composable
private fun FusedSectionHeaderTitle(title: String) {
    Text(title, style = OpenCodeTextStyles.footnote, fontWeight = FontWeight.SemiBold, color = OpenCodeTheme.colors.secondaryText)
}

@Composable
private fun FusedSectionHeader(section: ModelPickerSection, hidden: () -> Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(FusedModelPickerTunables.headerHeight.dp)
            .graphicsLayer { alpha = if (hidden()) 0f else 1f }
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { heading() }
            .testTag("model-section-${section.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FusedSectionHeaderTitle(section.title)
    }
}
