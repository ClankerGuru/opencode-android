@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.ui.ProviderIcon as CoreProviderIcon
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.unit.IntOffset
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Port of iOS `ModelListContent` (`Composer/ModelPicker/ModelListContent.swift`; D-20 round 7
 * option 1, the list round 6 option 1): “Search Models” on top, Favorites, then a section per
 * provider. No separators and no checkmark: the current model is a soft filled row in semibold,
 * and favorites carry a quiet grey star in a fixed column. Hold a row for Add to Favorites and
 * Use This Model, or swipe it to favorite. Picking only changes the composer; the model goes
 * with the next send.
 *
 * The rows run on up under the search field and fade out there (no hard edge,
 * [ModelPickerTunables.searchFade]); the header of the section scrolled past holds just below
 * the field ([ModelPickerTunables.pinnedInset]) until the next pushes it up, over a real
 * progressive blur of the rows ([ModelPickerTunables.headerBlur], reaching
 * [ModelPickerTunables.headerBlurReach] past it).
 */
@Composable
fun ModelListContent(
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
    onPick: (OpenCodeModel) -> Unit,
    modifier: Modifier = Modifier,
    searchFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    val density = LocalDensity.current
    // The search field's band at the top (px): the list scrolls on up under it.
    var searchBand by remember { mutableFloatStateOf(with(density) { 52.dp.toPx() }) }
    Box(modifier.fillMaxSize()) {
        if (state.isLoadingModels && state.models.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            ModelSectionsList(state, onEvent, onPick, searchBand = { searchBand })
        }
        ModelSearchCapsuleField(
            text = state.modelFilter,
            onTextChange = { onEvent(ModelPickerEvent.FilterChanged(it)) },
            modifier = Modifier
                .padding(horizontal = ModelPickerTunables.searchInset.dp)
                // The band is the field and its bottom gap.
                .onSizeChanged { searchBand = it.height.toFloat() }
                .padding(bottom = ModelPickerTunables.searchBottom.dp),
            focusRequester = searchFocusRequester,
            spacing = 6.dp,
            leadingPadding = 12.dp,
            minHeight = ModelPickerTunables.searchHeight,
            imeAction = ImeAction.Search,
        )
    }
}

@Composable
private fun ModelSectionsList(
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
    onPick: (OpenCodeModel) -> Unit,
    searchBand: () -> Float,
) {
    val density = LocalDensity.current
    val sections = state.modelSections
    val listState = rememberLazyListState()
    val rowsSource = remember { BlurSource() }
    var pinnedHeight by remember { mutableFloatStateOf(with(density) { 20.dp.toPx() }) }
    val pinnedInset = with(density) { ModelPickerTunables.pinnedInset.dp.toPx() }
    val sectionGap = with(density) { ModelPickerTunables.sectionGap.dp.toPx() }
    val pinnedTop = { searchBand() + pinnedInset }
    // Each section's header row (its LazyColumn index).
    val headerIndices = remember(sections) {
        var index = 0
        sections.map { section -> index.also { index += 1 + section.models.size } }
    }
    // How far the rows are scrolled (iOS `contentOffset.y + contentInsets.top`).
    val isPinning by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0.5f * density.density }
    }

    // A header label's top in the list's frame (px): above the visible rows −∞, below them null.
    fun labelTop(sectionIndex: Int): Float? {
        val index = headerIndices.getOrNull(sectionIndex) ?: return null
        val info = listState.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.index == index }?.let {
            return (it.offset - info.viewportStartOffset).toFloat() + if (sectionIndex == 0) pinnedInset else sectionGap
        }
        return if (index < listState.firstVisibleItemIndex) Float.NEGATIVE_INFINITY else null
    }
    val pinnedIndex by remember(listState, headerIndices) {
        derivedStateOf {
            if (!isPinning) return@derivedStateOf null
            val threshold = pinnedTop() - 0.5f
            sections.indices.lastOrNull { i -> labelTop(i)?.let { it < threshold } == true }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .blurSource(rowsSource)
                // The rows fade out under the search field.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = searchBand() * ModelPickerTunables.searchFade.toFloat()
                    if (fade > 0f) {
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black), blendMode = BlendMode.DstIn)
                    }
                },
            contentPadding = PaddingValues(top = with(density) { searchBand().toDp() }, bottom = 12.dp),
        ) {
            for ((index, section) in sections.withIndex()) {
                item(key = "header-${section.id}", contentType = "header") {
                    ModelListSectionHeader(
                        section,
                        isFirst = index == 0,
                        // Once it reaches the pinned header's place, the pinned copy takes over.
                        hidden = { isPinning && labelTop(index)?.let { it < pinnedTop() - 0.5f } == true },
                    )
                }
                items(section.models, key = { "${section.id}-${it.id}" }, contentType = { "row" }) { model ->
                    ModelListRow(model, inFavorites = section.kind == ModelPickerSection.Kind.Favorites, state, onEvent, onPick)
                }
            }
        }
        val pinned = pinnedIndex
        if (pinned != null && pinned in sections.indices) {
            // One soft blur from the top edge down past the pinned header, fading out with no line.
            ProgressiveBlur(
                source = rowsSource,
                profile = ProgressiveBlurProfile.Edge(top = true),
                radius = ModelPickerTunables.headerBlur.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { (pinnedTop() + pinnedHeight).toDp() } + ModelPickerTunables.headerBlurReach.dp),
            )
            // Clipped at the search field: a pushed label slides away under it.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = with(density) { searchBand().toDp() })
                    .clipToBounds(),
            ) {
                Box(
                    Modifier
                        .offset {
                            val next = labelTop(pinned + 1) ?: Float.POSITIVE_INFINITY
                            IntOffset(0, min(0f, next - (pinnedTop() + pinnedHeight + 10.dp.toPx())).roundToInt())
                        }
                        .padding(top = ModelPickerTunables.pinnedInset.dp, bottom = 6.dp)
                        .onSizeChanged { pinnedHeight = it.height.toFloat() - with(density) { (ModelPickerTunables.pinnedInset + 6).dp.toPx() } }
                        .semantics(mergeDescendants = true) { heading() }
                        .testTag("model-pinned-header"),
                ) {
                    ModelListSectionHeaderLabel(sections[pinned])
                }
            }
        }
        if (sections.isEmpty() && !state.isLoadingModels) {
            ModelListEmptyState(state, includesLoadError = true, onTryAgain = { onEvent(ModelPickerEvent.ModelListOpened) })
        }
    }
}

@Composable
private fun ModelListSectionHeaderLabel(section: ModelPickerSection) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ModelPickerTunables.rowLeading.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val kind = section.kind
        if (kind is ModelPickerSection.Kind.Provider) {
            ProviderIcon(kind.id, section.models.firstOrNull()?.canonicalProviderID, size = 16.dp, tint = OpenCodeTheme.colors.secondaryText)
        }
        Text(section.title, style = OpenCodeTextStyles.footnote, fontWeight = FontWeight.SemiBold, color = OpenCodeTheme.colors.secondaryText)
    }
}

@Composable
private fun ModelListSectionHeader(section: ModelPickerSection, isFirst: Boolean, hidden: () -> Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            // The first rests exactly where the pinned copy would sit.
            .padding(top = if (isFirst) ModelPickerTunables.pinnedInset.dp else ModelPickerTunables.sectionGap.dp, bottom = 4.dp)
            .graphicsLayer { alpha = if (hidden()) 0f else 1f }
            .semantics(mergeDescendants = true) {
                heading()
                if (hidden()) invisibleToUser()
            }
            .testTag("model-section-${section.id}"),
    ) {
        ModelListSectionHeaderLabel(section)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelListRow(
    model: OpenCodeModel,
    inFavorites: Boolean,
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
    onPick: (OpenCodeModel) -> Unit,
) {
    val isCurrent = state.currentModelInfo?.id == model.id
    val isFavorite = model.id in state.favoriteModelIDs
    val provider = ModelProviderGroup.title(model.providerID)
    val favoriteTitle = if (isFavorite) "Remove from Favorites" else "Add to Favorites"
    var menuOpen by remember { mutableStateOf(false) }
    FavoriteSwipeBox(isFavorite = isFavorite, tint = FavoriteOrange, onToggle = { onEvent(ModelPickerEvent.ToggleFavoriteModel(model)) }) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    // iOS `listRowBackground`: the whole cell (its row padding too), inset 10 at the sides.
                    .padding(horizontal = 10.dp)
                    .background(
                        if (isCurrent) OpenCodeTheme.colors.text.copy(alpha = ModelPickerTunables.highlight.toFloat()) else Color.Transparent,
                        RoundedCornerShape(ModelPickerTunables.highlightRadius.dp),
                    )
                    .padding(vertical = ModelPickerTunables.rowPadding.dp)
                    .combinedClickable(role = Role.Button, onClick = { onPick(model) }, onLongClick = { menuOpen = true })
                    .heightIn(min = ModelPickerTunables.rowHeight.dp)
                    .padding(start = (ModelPickerTunables.rowLeading - 10).dp, end = (ModelPickerTunables.rowTrailing - 10).dp)
                    .clearAndSetSemantics {
                        contentDescription = model.name
                        stateDescription = if (isFavorite) "$provider, Favorite" else provider
                        selected = isCurrent
                        role = Role.Button
                        customActions = listOf(CustomAccessibilityAction(favoriteTitle) { onEvent(ModelPickerEvent.ToggleFavoriteModel(model)); true })
                    }
                    .testTag(if (inFavorites) "model-favorite-${model.id}" else "model-row-${model.id}"),
                horizontalArrangement = Arrangement.spacedBy(ModelPickerTunables.rowSpacing.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Favorites mix providers, so they say whose; a provider's section already has.
                if (inFavorites) {
                    ProviderIcon(model.providerID, model.canonicalProviderID, size = 20.dp, tint = OpenCodeTheme.colors.secondaryText)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        model.name,
                        style = OpenCodeTextStyles.body,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = OpenCodeTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (inFavorites) {
                        Text(provider, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // A fixed column, so a star never moves anything when it comes or goes.
                Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                    if (isFavorite) OpenCodeIcon(SFSymbol.StarFill, null, size = 12.dp, tint = OpenCodeTheme.colors.tertiaryText)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(favoriteTitle) },
                    leadingIcon = { OpenCodeIcon(if (isFavorite) SFSymbol.StarSlash else SFSymbol.Star, null) },
                    onClick = {
                        menuOpen = false
                        onEvent(ModelPickerEvent.ToggleFavoriteModel(model))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Use This Model") },
                    leadingIcon = { OpenCodeIcon(SFSymbol.Checkmark, null) },
                    onClick = {
                        menuOpen = false
                        onPick(model)
                    },
                )
            }
        }
    }
}

/** iOS `ModelPickerSheetContent`: the list in a sheet, for the Session sheet's Model row. */
@Composable
fun ModelPickerSheetContent(
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
) {
    // Gone (picked, cancelled, swiped away): its search is cleared, as the composer's list
    // clears its own, so neither carries into the other.
    ModelPickerDetentsSheet(onClose = { onEvent(ModelPickerEvent.Dismiss) }, onClosed = { onEvent(ModelPickerEvent.ModelListClosed) }) {
        @Suppress("NAME_SHADOWING") val onEvent = closingAfterHide(onEvent)
        Column(Modifier.fillMaxSize().testTag("screen-Model Picker")) {
            ModelSheetTitleBar(
                title = "Model",
                leading = { TextButton(onClick = { onEvent(ModelPickerEvent.Dismiss) }) { Text("Cancel") } },
            )
            // The reducer plays the pick's haptic and closes the sheet.
            ModelListContent(state, onEvent, onPick = { onEvent(ModelPickerEvent.ModelPicked(it)) })
        }
    }
}

// MARK: - Shared Model List Search, Pinned Header & Empty State

/**
 * iOS `[ModelPickerSection].pinnedSectionIndex`: the index of the last section whose header has
 * scrolled above [threshold], ignoring any stale header records after the first header still
 * below it.
 */
fun List<ModelPickerSection>.pinnedSectionIndex(headerTops: Map<String, Float>, scrollOffset: Float, threshold: Float): Int? {
    fun top(id: String): Float? = headerTops[id]?.let { it - scrollOffset }
    val firstBelow = indexOfFirst { s -> top(s.id)?.let { it >= threshold } == true }.takeIf { it >= 0 } ?: size
    return subList(0, firstBelow).indexOfLast { (top(it.id) ?: Float.POSITIVE_INFINITY) < threshold }.takeIf { it >= 0 }
}

/** iOS `ModelSearchCapsuleField`: a magnifier, “Search Models”, and a Clear button while there's text, in a tertiary-fill capsule. */
@Composable
fun ModelSearchCapsuleField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    spacing: Dp = 6.dp,
    leadingPadding: Dp = 12.dp,
    emptyTrailingPadding: Dp = 12.dp,
    clearTrailingPadding: Dp = 0.dp,
    minHeight: Double = ModelPickerTunables.searchHeight,
    imeAction: ImeAction = ImeAction.Search,
    placeholder: String = "Search Models",
    onSubmit: () -> Unit = {},
) {
    val textColor = OpenCodeTheme.colors.text
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .background(OpenCodeTheme.colors.inlineCodeFill, CircleShape)
            .padding(start = leadingPadding, end = if (text.isEmpty()) emptyTrailingPadding else clearTrailingPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIcon(SFSymbol.Magnifyingglass, null, size = 20.dp, tint = OpenCodeTheme.colors.secondaryText)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(placeholder, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = OpenCodeTextStyles.body.copy(color = textColor),
                cursorBrush = SolidColor(OpenCodeTheme.colors.accent),
                // Autocorrection stays on: its suggestions keep the keyboard the message field's height.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                    .semantics { contentDescription = placeholder }
                    .testTag("model-search"),
            )
        }
        if (text.isNotEmpty()) {
            Box(
                Modifier
                    .clickable(role = Role.Button) { onTextChange("") }
                    .openCodeTapTarget()
                    .semantics { contentDescription = "Clear" },
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.XmarkCircleFill, null, size = 20.dp, tint = OpenCodeTheme.colors.tertiaryText)
            }
        }
    }
}

/**
 * iOS `ModelListEmptyState`: the search's empty state, else **Couldn’t Load Models** with the
 * error and **Try Again** (when [includesLoadError]), else **No Models**.
 */
@Composable
fun ModelListEmptyState(
    state: ModelPickerState,
    includesLoadError: Boolean = true,
    onTryAgain: () -> Unit,
) {
    val error = state.modelsLoadError
    when {
        state.isFilteringModels -> ContentUnavailable(
            symbol = SFSymbol.Magnifyingglass,
            title = "No Results for “${state.modelFilter}”",
            description = "Check the spelling or try a new search.",
        )
        includesLoadError && error != null -> ContentUnavailable(
            symbol = SFSymbol.ExclamationmarkTriangle,
            title = "Couldn’t Load Models",
            description = error,
            action = { OpenCodePrimaryButton(text = "Try Again", onClick = onTryAgain) },
        )
        else -> ContentUnavailable(
            symbol = SFSymbol.Cpu,
            title = "No Models",
            description = "Connect a provider in OpenCode on your computer, then try again.",
        )
    }
}

/** iOS `ContentUnavailableView`: a large glyph, a title and a description, centred. */
@Composable
internal fun ContentUnavailable(
    symbol: SFSymbol,
    title: String,
    description: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OpenCodeIcon(symbol, null, size = 44.dp, tint = OpenCodeTheme.colors.secondaryText)
        Spacer(Modifier.size(12.dp))
        Text(title, style = OpenCodeTextStyles.title3, fontWeight = FontWeight.Bold, color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        Text(description, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.size(16.dp))
            action()
        }
    }
}

/** iOS's `.yellow` swipe tint (Sheet and Today pickers). */
internal val FavoriteYellow = Color(0xFFFFCC00)

/** iOS's `.orange` swipe tint (the composer's model list). */
internal val FavoriteOrange = Color(0xFFFF9500)

/**
 * A trailing swipe that favorites or unfavorites the row (iOS `swipeActions(edge: .trailing,
 * allowsFullSwipe: true)`): swiped past the threshold it toggles and springs back.
 */
@Composable
internal fun FavoriteSwipeBox(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    tint: Color = FavoriteYellow,
    /** The Favorite action's glyph (the Sheet and composer lists `star.fill`, Today's `star`). */
    favoriteSymbol: SFSymbol = SFSymbol.StarFill,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(tint).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeIcon(if (isFavorite) SFSymbol.StarSlash else favoriteSymbol, null, size = 20.dp, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(if (isFavorite) "Unfavorite" else "Favorite", color = Color.White, style = OpenCodeTextStyles.subheadline)
            }
        },
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onToggle()
            scope.launch { swipe.reset() }
        },
    ) {
        Box(Modifier.background(BottomSheetDefaults.ContainerColor)) { content() }
    }
}

/**
 * The provider's mark: iOS `ProviderIcon` (`Session/SessionView.swift`), the `provider-<id>`
 * template image (its own, else its canonical provider's, else a Console connection's upstream,
 * else a neutral placeholder), drawn in [tint]. Decorative (TalkBack hidden).
 */
@Composable
internal fun ProviderIcon(providerID: String, canonical: String?, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    CoreProviderIcon(providerID, modifier.size(size), canonical, tint)
}

/**
 * Decodes the picker's provider marks into the image cache before the picker first opens, so
 * its rows draw them on their first frame instead of popping in as each SVG decodes (and the
 * decoder's classes load off the opening). Starts a beat after it appears; each distinct mark
 * (current, favourites, then the rest, at most [PROVIDER_PREWARM_LIMIT]) is composed once at the
 * largest size the picker draws, transparent, taking no room and outside semantics.
 */
@Composable
internal fun ProviderIconPrewarm(state: ModelPickerState) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROVIDER_PREWARM_DELAY_MILLIS)
        started = true
    }
    if (!started) return
    val marks = remember(state.models, state.modelVisibility, state.currentModel, state.favoriteModelIDs) {
        val current = state.currentModelInfo
        val visible = state.pickerModels
        (listOfNotNull(current) + visible.filter { it.id in state.favoriteModelIDs } + visible)
            .distinctBy { CoreProviderIcon.assetName(it.providerID, it.canonicalProviderID) }
            .take(PROVIDER_PREWARM_LIMIT)
    }
    Layout(
        content = {
            for (model in marks) {
                key(model.providerID, model.canonicalProviderID) {
                    CoreProviderIcon(model.providerID, Modifier, model.canonicalProviderID)
                }
            }
        },
        modifier = Modifier
            .graphicsLayer { alpha = 0f }
            .clearAndSetSemantics {},
    ) { measurables, _ ->
        val side = PROVIDER_PREWARM_SIDE.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(side, side)) }
        layout(0, 0) { placeables.forEach { it.place(0, 0) } }
    }
}

/** The largest provider mark the pickers draw (the sheets' rows). */
private val PROVIDER_PREWARM_SIDE = 20.dp
private const val PROVIDER_PREWARM_LIMIT = 24
private const val PROVIDER_PREWARM_DELAY_MILLIS = 900L
