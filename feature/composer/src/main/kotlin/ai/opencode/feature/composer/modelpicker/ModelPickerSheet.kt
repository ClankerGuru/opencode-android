@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeModel
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Port of iOS `ModelPickerSheet` (`Composer/ModelPicker/ModelPickerSheet.swift`): the model
 * picker under Model Picker › Sheet (OC-469). A stock sheet listing Favorites, Recent and then
 * each provider's models, searchable, with each model's thinking level as a menu on its row.
 * Tapping a row picks that model at its level and closes the sheet ([ModelPickerEvent.ModelPickedWithLevel]);
 * choosing a level picks the model at that level and leaves the sheet open
 * ([ModelPickerEvent.ModelLevelPicked]). The reducer plays `.selection` only when the model or
 * level changes. No custom glass, gestures or motion.
 *
 * Also here, from `Session/SessionView+ModelPicker.swift` (whose views the session area owns but
 * which compose these leaves): [ModelPickerDetentsSheet] (`ModelPickerDetents`), the Today
 * picker [PlainModelPickerSheet] (`plainModelPicker` and its **Thinking** menu) and the chip
 * ([ModelPickerChip], `SessionView+ComposerControls.swift`).
 */
@Composable
fun ModelPickerSheet(
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
) {
    // Recent as it was when the sheet opened: picks made in it land in Recent next time, so
    // rows never move under a finger.
    val recentsAtOpen = remember { state.recentModelIDs }
    val sections = state.modelSheetSections(recentsAtOpen, roundHalfAwayFromZero(FusedModelPickerTunables.recents).toInt())

    ModelPickerDetentsSheet(onClose = { onEvent(ModelPickerEvent.Dismiss) }, onClosed = { onEvent(ModelPickerEvent.ModelListClosed) }) {
        @Suppress("NAME_SHADOWING") val onEvent = closingAfterHide(onEvent)
        Column(Modifier.fillMaxSize().testTag("screen-Model Picker")) {
            ModelSheetTitleBar(
                title = "Model",
                trailing = { TextButton(onClick = { onEvent(ModelPickerEvent.Dismiss) }) { Text("Done", fontWeight = FontWeight.SemiBold) } },
            )
            ModelSearchCapsuleField(
                text = state.modelFilter,
                onTextChange = { onEvent(ModelPickerEvent.FilterChanged(it)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                minHeight = 44.0,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    for (section in sections) {
                        item(key = "header-${section.id}") { SheetSectionHeader(section.title) }
                        items(section.models, key = { "${section.id}-${it.id}" }) { model ->
                            SheetModelRow(model, section.kind, state, onEvent)
                        }
                    }
                    if (state.hiddenModelCount > 0) {
                        item(key = "show-all") {
                            ShowAllModelsRow(state.hiddenModelCount, state.showsAllModels, onToggle = { onEvent(ModelPickerEvent.ShowAllModelsToggled) })
                        }
                    }
                }
                ModelSheetEmptyState(state, isEmpty = sections.isEmpty(), onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun SheetSectionHeader(title: String) {
    if (title.isEmpty()) return
    Text(
        title,
        style = OpenCodeTextStyles.footnote,
        fontWeight = FontWeight.SemiBold,
        color = OpenCodeTheme.colors.secondaryText,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetModelRow(
    model: OpenCodeModel,
    kind: ModelPickerSection.Kind,
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
) {
    // Favorites and Recent mix providers, so their rows name the provider and carry their own identifiers.
    val prefix = when (kind) {
        ModelPickerSection.Kind.Favorites -> "model-favorite"
        ModelPickerSection.Kind.Recent -> "model-recent"
        else -> "model-row"
    }
    val mixesProviders = kind == ModelPickerSection.Kind.Favorites || kind == ModelPickerSection.Kind.Recent
    val isCurrent = state.currentModelInfo?.id == model.id
    val isFavorite = model.id in state.favoriteModelIDs
    val provider = ModelProviderGroup.title(model.providerID)
    val scale = ModelLevelScale(model.variants)
    val remembered = state.rememberedLevel(model)
    var menuOpen by remember { mutableStateOf(false) }
    val favoriteTitle = if (isFavorite) "Remove from Favorites" else "Add to Favorites"

    FavoriteSwipeBox(isFavorite = isFavorite, onToggle = { onEvent(ModelPickerEvent.ToggleFavoriteModel(model)) }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            role = Role.Button,
                            onClick = { onEvent(ModelPickerEvent.ModelPickedWithLevel(model, remembered)) },
                            onLongClick = { menuOpen = true },
                        )
                        .heightIn(min = 48.dp)
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                        .clearAndSetSemantics {
                            contentDescription = model.name
                            stateDescription = if (isFavorite) "$provider, Favorite" else provider
                            selected = isCurrent
                            role = Role.Button
                            customActions = listOf(CustomAccessibilityAction(favoriteTitle) { onEvent(ModelPickerEvent.ToggleFavoriteModel(model)); true })
                        }
                        .testTag("$prefix-${model.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(model.name, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text)
                        if (mixesProviders) {
                            Text(provider, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
                        }
                    }
                    Spacer(Modifier.weight(1f, fill = true))
                    if (isCurrent) {
                        OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small, tint = OpenCodeTheme.colors.text)
                    }
                }
                // Hold a row for Add to Favorites (iOS `contextMenu`).
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(favoriteTitle) },
                        leadingIcon = { OpenCodeIcon(if (isFavorite) SFSymbol.StarSlash else SFSymbol.Star, null) },
                        onClick = {
                            menuOpen = false
                            onEvent(ModelPickerEvent.ToggleFavoriteModel(model))
                        },
                    )
                }
            }
            if (scale.isAdjustable) {
                RowLevelMenu(
                    model = model,
                    scale = scale,
                    level = remembered,
                    testTag = "$prefix-level-${model.id}",
                    onPick = { onEvent(ModelPickerEvent.ModelLevelPicked(model, it)) },
                )
            }
        }
    }
}

/**
 * A row's level as a menu (iOS: a `Menu` around a "Thinking Level" `Picker`), labelled with its
 * level and a chevron in secondary text, never the accent (Kit: “I don't like the blue text”).
 */
@Composable
private fun RowLevelMenu(
    model: OpenCodeModel,
    scale: ModelLevelScale,
    level: String?,
    testTag: String,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = ModelLevelScale.label(level)
    Box {
        Row(
            Modifier
                .clickable(role = Role.Button) { open = true }
                .openCodeTapTarget()
                .clearAndSetSemantics {
                    contentDescription = "${model.name} Thinking Level"
                    stateDescription = label
                    role = Role.DropdownList
                }
                .testTag(testTag)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
            OpenCodeIcon(SFSymbol.ChevronUpChevronDown, null, size = 14.dp, tint = OpenCodeTheme.colors.secondaryText)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in scale.levels) {
                val selected = option == level
                DropdownMenuItem(
                    text = { Text(ModelLevelScale.label(option)) },
                    leadingIcon = {
                        Box(Modifier.width(24.dp)) {
                            if (selected) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small)
                        }
                    },
                    modifier = Modifier.semantics { this.selected = selected },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelSheetEmptyState(state: ModelPickerState, isEmpty: Boolean, onEvent: (ModelPickerEvent) -> Unit) {
    if (state.isLoadingModels && state.models.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (isEmpty && state.hiddenModelCount == 0) {
        ModelListEmptyState(state, includesLoadError = true, onTryAgain = { onEvent(ModelPickerEvent.ModelListOpened) })
    }
}

/** The sheet's inline title row: [title] centred, optional leading and trailing buttons. */
@Composable
internal fun ModelSheetTitleBar(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke() }
        Text(
            title,
            style = OpenCodeTextStyles.headline,
            color = OpenCodeTheme.colors.text,
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
        Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke() }
    }
}

/**
 * iOS `ModelPickerDetents` around a stock sheet: the Today and Sheet pickers (and the
 * Session sheet's model list) open at the partially expanded rest and drag up to full. Dragged
 * down from full, a flick at [ModelPickerTunables.flickCloses] (800 dp/s) or faster closes them
 * and a slower drag rests partially expanded (a probe beside the sheet's own drag reads the
 * release velocity, which the settled value doesn't carry). Under TalkBack the grabber still
 * steps between the two rests: collapsing there isn't a request to close.
 *
 * [onClose] asks the owner to close it (`activePicker = nil`); [onClosed] runs once it leaves
 * however it closed (iOS `onDisappear`: `.modelListClosed`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerDetentsSheet(
    onClose: () -> Unit,
    onClosed: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnClosed by rememberUpdatedState(onClosed)
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    // How fast the latest drag on the sheet was moving down (dp/s), kept as it moves.
    var downwardVelocity by remember { mutableFloatStateOf(0f) }
    val holder = remember { arrayOfNulls<androidx.compose.material3.SheetState>(1) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { target ->
            val current = holder[0]?.currentValue
            val talkBack = accessibility?.isTouchExplorationEnabled == true
            if (current == SheetValue.Expanded && target == SheetValue.PartiallyExpanded && !talkBack &&
                downwardVelocity >= ModelPickerTunables.flickCloses
            ) {
                scope.launch { holder[0]?.hide() }.invokeOnCompletion { currentOnClose() }
                false
            } else {
                true
            }
        },
    )
    holder[0] = sheetState
    DisposableEffect(Unit) { onDispose { currentOnClosed() } }

    val probe = Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed && !change.previousPressed) tracker.resetTracking()
                tracker.addPosition(change.uptimeMillis, change.position)
                val vy = tracker.calculateVelocity().y
                downwardVelocity = with(density) { maxOf(vy, 0f).toDp().value }
            }
        }
    }

    // A pick, Cancel or Done closes the sheet by removing it (`activePicker = nil`), which on
    // its own would drop it from the screen in one frame: it slides down first, then the event
    // goes (iOS dismisses the sheet with its own animation).
    var hiding by remember { mutableStateOf(false) }
    val hideThen: (() -> Unit) -> Unit = { action ->
        if (!hiding) {
            hiding = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { currentOnClose() },
        sheetState = sheetState,
        dragHandle = { Box(probe) { BottomSheetDefaults.DragHandle() } },
    ) {
        CompositionLocalProvider(LocalModelSheetHideThen provides hideThen) {
            Box(probe.fillMaxWidth()) { content() }
        }
    }
}

/** The enclosing [ModelPickerDetentsSheet]'s slide-down, run before an event that closes it. */
private val LocalModelSheetHideThen = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

/**
 * [onEvent] with the events that close the sheet (a pick, Cancel, Done) sent once it has slid
 * down. Call inside a [ModelPickerDetentsSheet]'s content.
 */
@Composable
internal fun closingAfterHide(onEvent: (ModelPickerEvent) -> Unit): (ModelPickerEvent) -> Unit {
    val hideThen = LocalModelSheetHideThen.current ?: return onEvent
    return { event ->
        when (event) {
            ModelPickerEvent.Dismiss, is ModelPickerEvent.ModelPicked, is ModelPickerEvent.ModelPickedWithLevel -> hideThen { onEvent(event) }
            else -> onEvent(event)
        }
    }
}

/**
 * The Today picker (`plainModelPicker`, the default style): a plain native **Model** sheet,
 * searchable, favorites first with a star, provider icon and name, a checkmark on the current
 * model, swipe to favorite, and a **Thinking** menu for the current model's variants.
 */
@Composable
fun PlainModelPickerSheet(
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
) {
    ModelPickerDetentsSheet(onClose = { onEvent(ModelPickerEvent.Dismiss) }, onClosed = { onEvent(ModelPickerEvent.ModelListClosed) }) {
        @Suppress("NAME_SHADOWING") val onEvent = closingAfterHide(onEvent)
        Column(Modifier.fillMaxSize().testTag("screen-Model Picker")) {
            val variants = state.currentModelInfo?.let { model ->
                ModelVariant.sorted(model.variants.filter { it != "default" }).takeIf { it.isNotEmpty() }
            }
            ModelSheetTitleBar(
                title = "Model",
                leading = { TextButton(onClick = { onEvent(ModelPickerEvent.Dismiss) }) { Text("Cancel") } },
                trailing = variants?.let { list -> { ThinkingMenu(state, list, onEvent) } },
            )
            ModelSearchCapsuleField(
                text = state.modelFilter,
                onTextChange = { onEvent(ModelPickerEvent.FilterChanged(it)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                minHeight = 44.0,
                placeholder = "Search",
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.isLoadingModels && state.models.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val models = if (state.isFilteringModels) state.filteredModels else state.favoriteModels + state.unfavoriteModels
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(models, key = { it.id }) { model -> PlainModelRow(model, state, onEvent) }
                        item(key = "show-all") {
                            ShowAllModelsRow(state.hiddenModelCount, state.showsAllModels, onToggle = { onEvent(ModelPickerEvent.ShowAllModelsToggled) })
                        }
                    }
                }
                if (!state.isLoadingModels && state.filteredModels.isEmpty() && state.hiddenModelCount == 0) {
                    ModelListEmptyState(state, includesLoadError = true, onTryAgain = { onEvent(ModelPickerEvent.ModelListOpened) })
                }
            }
        }
    }
}

/** The Today picker's **Thinking** menu: Default, then the current model's variants. */
@Composable
private fun ThinkingMenu(state: ModelPickerState, variants: List<String>, onEvent: (ModelPickerEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.currentModel?.variant?.takeIf { it != "default" }
    Box {
        Row(
            Modifier
                .clickable(role = Role.Button) { open = true }
                .openCodeTapTarget()
                .clearAndSetSemantics {
                    contentDescription = "Thinking"
                    stateDescription = current?.let(ModelVariant::label) ?: "Default"
                    role = Role.DropdownList
                }
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current?.let(ModelVariant::label) ?: "Default", fontWeight = FontWeight.SemiBold, color = OpenCodeTheme.colors.accent)
            OpenCodeIcon(SFSymbol.ChevronUpChevronDown, null, size = 12.dp, tint = OpenCodeTheme.colors.accent)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val options = listOf<String?>(null) + variants
            for (option in options) {
                val selected = option == current
                DropdownMenuItem(
                    text = { Text(option?.let(ModelVariant::label) ?: "Default") },
                    leadingIcon = { Box(Modifier.width(24.dp)) { if (selected) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small) } },
                    modifier = Modifier.semantics { this.selected = selected },
                    onClick = {
                        open = false
                        onEvent(ModelPickerEvent.VariantPicked(option))
                    },
                )
            }
        }
    }
}

/** [icon] Model  Provider (dimmed). */
@Composable
private fun PlainModelRow(model: OpenCodeModel, state: ModelPickerState, onEvent: (ModelPickerEvent) -> Unit) {
    val isCurrent = state.currentModelInfo?.id == model.id
    val isFavorite = model.id in state.favoriteModelIDs
    FavoriteSwipeBox(isFavorite = isFavorite, favoriteSymbol = SFSymbol.Star, onToggle = { onEvent(ModelPickerEvent.ToggleFavoriteModel(model)) }) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onEvent(ModelPickerEvent.ModelPicked(model)) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp)
                .semantics(mergeDescendants = true) { selected = isCurrent }
                .testTag("model-row-${model.id}"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderIcon(model.providerID, model.canonicalProviderID, size = 20.dp, tint = OpenCodeTheme.colors.secondaryText)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(
                    ModelProviderGroup.title(model.providerID),
                    style = OpenCodeTextStyles.subheadline,
                    color = OpenCodeTheme.colors.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (isFavorite) OpenCodeIcon(SFSymbol.StarFill, null, size = 14.dp, tint = FavoriteYellow)
            if (isCurrent) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small, tint = OpenCodeTheme.colors.accent)
        }
    }
}

/**
 * The composer's model chip for the Today and Sheet styles (`SessionView+ComposerControls.swift`):
 * Today reads `"<Model> ⌄"` or `"<Model> · <Level> ⌄"`; Sheet reads `"<Short name> <Level> ⌄"`
 * with the name in primary text. TalkBack: **Choose model**, its value the label (Sheet:
 * `"Cirrus 4, High"`). Secondary text, subheadline medium, ≥ 48 dp tall.
 */
@Composable
fun ModelPickerChip(
    state: ModelPickerState,
    usesModelPickerSheet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondary = OpenCodeTheme.colors.secondaryText
    Row(
        modifier
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .clearAndSetSemantics {
                contentDescription = "Choose model"
                stateDescription = if (usesModelPickerSheet) state.sheetChipAccessibilityValue else state.currentModelLabel
                role = Role.Button
            }
            .testTag("composer-model"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium)
        if (usesModelPickerSheet) {
            // New Model Picker: the short name, then its level.
            Text(state.currentModelShortName, style = style, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            state.currentChipLevelLabel?.let { Text(it, style = style, color = secondary, maxLines = 1, softWrap = false) }
        } else {
            Text(state.currentModelLabel, style = style, color = secondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
        }
        OpenCodeIcon(SFSymbol.ChevronDown, null, size = 12.dp, tint = secondary)
    }
}
