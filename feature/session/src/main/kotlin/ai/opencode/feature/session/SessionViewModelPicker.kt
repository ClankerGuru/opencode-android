package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.session.seams.FusedModelPickerTunables
import ai.opencode.feature.session.seams.ModelLevelScale
import ai.opencode.feature.session.seams.ModelPickerSection
import ai.opencode.feature.session.seams.ModelPickerStyle
import ai.opencode.feature.session.seams.ModelPickerTunables
import ai.opencode.feature.session.seams.modelSheetSections
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Port of iOS `Session/SessionView+ModelPicker.swift` as Android presents it: the `modelPicker`
// sheet (`activePicker == .model`) in its Today style (`plainModelPicker`) and its Sheet style
// (`ModelPickerSheet`, Composer/ModelPicker), with `ModelPickerDetents`' medium/large detents and
// flick-to-close mapped onto an M3 ModalBottomSheet's partial/expanded states. The Grow from
// Composer list/sheet morph (`modelListExpansion`, `modelSheetSurface`, the open/close/settle
// gesture machinery and focus hand-offs) is UIKit glass choreography with no Android
// counterpart; that style shows the Sheet picker here.

/**
 * The model picker, shown by `SessionView` while `activePicker == model`. Search (Return picks
 * the top match, with any level word: “sonnet high”), Favorites, Recent and provider groups, each
 * model's thinking level, and Show All / Show Fewer while models are hidden. Dismissing it sets
 * `activePicker` to null; however it goes, its search is cleared (`modelListClosed`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionModelPicker(store: StoreOf<SessionFeature.State, SessionFeature.Action>) {
    val styleSetting by remember { shared(ModelPickerStyle.key) }.flow.collectAsState()
    val style = ModelPickerStyle.active(styleSetting)
    val context = LocalContext.current
    // TalkBack opens it full (iOS: VoiceOver's grabber steps between the two; collapsing there isn't a close).
    val talkBack = remember(context) {
        (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = talkBack)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drag = remember { SheetDragProbe() }
    val close: () -> Unit = { store.send(SessionFeature.Action.Binding(BindingAction.ActivePicker(null))) }

    // Gone however it closed: its search is cleared for the next one.
    DisposableEffect(store) { onDispose { store.send(SessionFeature.Action.ModelListClosed) } }

    // `ModelPickerDetents`: dragged down from large, a flick closes it and a slower drag rests at medium.
    LaunchedEffect(sheetState, talkBack) {
        var previous = sheetState.currentValue
        snapshotFlow { sheetState.currentValue }.collect { value ->
            val flickCloses = with(density) { ModelPickerTunables.FLICK_CLOSES.dp.toPx() }
            if (previous == SheetValue.Expanded && value == SheetValue.PartiallyExpanded && !talkBack &&
                drag.downwardVelocity >= flickCloses
            ) {
                scope.launch { sheetState.hide() }.invokeOnCompletion { close() }
            }
            previous = value
        }
    }

    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        dragHandle = { Box(Modifier.then(drag.modifier)) { BottomSheetDefaults.DragHandle() } },
    ) {
        Box(Modifier.fillMaxWidth().then(drag.modifier)) {
            when (style) {
                ModelPickerStyle.today -> PlainModelPicker(store, onCancel = close)
                ModelPickerStyle.sheet, ModelPickerStyle.growFromComposer -> ModelPickerSheetContent(store, onDone = close)
            }
        }
    }
}

/**
 * iOS `SheetDragProbe`: watches drags on the sheet beside its own drag, never instead of it,
 * keeping how fast the latest one moved down (px/s), since the sheet settles a moment before the
 * drag reports its end.
 */
private class SheetDragProbe {
    var downwardVelocity by mutableFloatStateOf(0f)

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            val tracker = VelocityTracker()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed && !change.previousPressed) {
                    tracker.resetTracking()
                    downwardVelocity = 0f
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                if (change.pressed || change.previousPressed) {
                    downwardVelocity = maxOf(tracker.calculateVelocity().y, 0f)
                }
            }
        }
    }
}

// region Shared inputs

/** What the pickers read from the session, observed narrowly so a streaming reply doesn't redraw them. */
private data class PickerInputs(
    val models: List<OpenCodeModel>,
    val isLoadingModels: Boolean,
    val modelsLoadError: String?,
    val modelFilter: String,
    val showsAllModels: Boolean,
    val currentModel: OpenCodeModelRef?,
)

@Composable
private fun rememberPickerInputs(store: StoreOf<SessionFeature.State, SessionFeature.Action>): PickerInputs {
    val inputs by store.observe {
        PickerInputs(it.models, it.isLoadingModels, it.modelsLoadError, it.modelFilter, it.showsAllModels, it.currentModel)
    }
    return inputs
}

/** iOS `$store.modelFilter`. */
private fun StoreOf<SessionFeature.State, SessionFeature.Action>.setModelFilter(value: String) =
    send(SessionFeature.Action.Binding(BindingAction.ModelFilter(value)))

// endregion

// region Today (iOS `plainModelPicker`)

@Composable
private fun PlainModelPicker(store: StoreOf<SessionFeature.State, SessionFeature.Action>, onCancel: () -> Unit) {
    val inputs = rememberPickerInputs(store)
    val favoriteIDs by remember(store) { store.state.favoriteModelIDs.flow }.collectAsState()
    val visibility by remember(store) { store.state.modelVisibilityByServer.flow }.collectAsState()
    val hideModels by remember(store) { store.state.hideModelsExperiment.flow }.collectAsState()
    val derived = remember(inputs, favoriteIDs, visibility, hideModels) {
        val state = store.state
        PlainDerived(
            rows = if (state.isFilteringModels) state.filteredModels else state.favoriteModels + state.unfavoriteModels,
            filteredIsEmpty = state.filteredModels.isEmpty(),
            hiddenModelCount = state.hiddenModelCount,
            isFiltering = state.isFilteringModels,
            current = state.currentModelInfo,
            currentVariants = state.currentModelInfo?.let { model -> ModelVariant.sorted(model.variants.filter { it != "default" }).ifEmpty { null } },
        )
    }
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Model Picker" }) {
        PickerTitleBar(
            title = "Model",
            leading = { TextButton(onClick = onCancel) { Text("Cancel") } },
            trailing = { derived.currentVariants?.let { ThinkingMenu(it, inputs.currentModel, store) } },
        )
        PickerSearchField(inputs.modelFilter, "Search", store::setModelFilter) {
            store.send(SessionFeature.Action.ModelSearchSubmitted)
        }
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 240.dp)) {
            if (inputs.isLoadingModels && inputs.models.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).padding(32.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    items(derived.rows, key = { it.id }) { model ->
                        val isFavorite = model.id in favoriteIDs
                        FavoriteSwipeRow(
                            isFavorite = isFavorite,
                            favoriteLabel = if (isFavorite) "Unfavorite" else "Favorite",
                            favoriteSymbol = if (isFavorite) SFSymbol.StarSlash else SFSymbol.Star,
                            onToggleFavorite = { store.send(SessionFeature.Action.ToggleFavoriteModel(model)) },
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                        ) {
                            PlainModelRow(
                                model = model,
                                isCurrent = derived.current?.id == model.id,
                                isFavorite = isFavorite,
                                onPick = { store.send(SessionFeature.Action.ModelPicked(model)) },
                            )
                        }
                    }
                    item(key = "show-all") {
                        ShowAllModelsRow(derived.hiddenModelCount, inputs.showsAllModels) {
                            store.send(SessionFeature.Action.ShowAllModelsToggled)
                        }
                    }
                }
            }
            if (!inputs.isLoadingModels && derived.filteredIsEmpty && derived.hiddenModelCount == 0) {
                PickerEmptyState(derived.isFiltering, inputs.modelFilter, inputs.modelsLoadError) {
                    store.send(SessionFeature.Action.ModelListOpened)
                }
            }
        }
    }
}

private data class PlainDerived(
    val rows: List<OpenCodeModel>,
    val filteredIsEmpty: Boolean,
    val hiddenModelCount: Int,
    val isFiltering: Boolean,
    val current: OpenCodeModel?,
    val currentVariants: List<String>?,
)

/** iOS `thinkingMenu`: the current model's reasoning variant, Default first. */
@Composable
private fun ThinkingMenu(
    variants: List<String>,
    current: OpenCodeModelRef?,
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = current?.variant?.takeIf { it != "default" }
    val label = chosen?.let(ModelVariant::label) ?: "Default"
    Box {
        Row(
            Modifier
                .clickable(role = Role.DropdownList) { open = true }
                .openCodeTapTarget()
                .padding(horizontal = 8.dp)
                .clearAndSetSemantics {
                    contentDescription = "Thinking"
                    stateDescription = label
                    role = Role.DropdownList
                    onClick { open = true; true }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = OpenCodeTextStyles.body.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.accent)
            OpenCodeIcon(SFSymbol.ChevronUpChevronDown, contentDescription = null, size = 14.dp, tint = OpenCodeTheme.colors.accent)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val options = listOf<String?>(null) + variants
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option?.let(ModelVariant::label) ?: "Default") },
                    leadingIcon = { CheckSlot(option == chosen) },
                    onClick = {
                        open = false
                        store.send(SessionFeature.Action.VariantPicked(option))
                    },
                    modifier = Modifier.semantics { selected = option == chosen },
                )
            }
        }
    }
}

/** [icon] Model  Provider (dimmed), a star for favorites and a check on the current one. */
@Composable
private fun PlainModelRow(model: OpenCodeModel, isCurrent: Boolean, isFavorite: Boolean, onPick: () -> Unit) {
    val provider = ModelProviderGroup.title(model.providerID)
    Row(
        Modifier
            .fillMaxWidth()
            .background(OpenCodeTheme.colors.canvas)
            .clickable(role = Role.Button, onClick = onPick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 11.dp)
            .semantics(mergeDescendants = true) {
                selected = isCurrent
                if (isFavorite) stateDescription = "Favorite"
            }
            .testTag("model-row-${model.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelProviderMark(Modifier.size(20.dp))
        Text(
            model.name,
            style = OpenCodeTextStyles.body,
            color = OpenCodeTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(provider, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(0.001f).width(4.dp))
        if (isFavorite) OpenCodeIcon(SFSymbol.StarFill, contentDescription = null, size = 14.dp, tint = FavoriteYellow)
        if (isCurrent) OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.accent)
    }
}

/**
 * Stand-in for iOS `ProviderIcon` (the provider's own mark, owned by VIEW-MAIN's `SessionView`);
 * the neutral placeholder until the provider icon assets are ported.
 */
@Composable
private fun ModelProviderMark(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        OpenCodeIcon(SFSymbol.Cpu, contentDescription = null, size = 20.dp, tint = OpenCodeTheme.colors.secondaryText)
    }
}

// endregion

// region Sheet (iOS `ModelPickerSheet`)

/**
 * The Sheet style: Favorites, Recent (as it was when the sheet opened, so rows never move under a
 * finger), then each provider's models, searchable, each with its thinking level as a menu on
 * its row. Tapping a row picks that model at its level and closes the sheet; choosing a level
 * picks the model at that level and leaves the sheet open.
 */
@Composable
private fun ModelPickerSheetContent(store: StoreOf<SessionFeature.State, SessionFeature.Action>, onDone: () -> Unit) {
    val inputs = rememberPickerInputs(store)
    val recentsAtOpen = remember(store) { store.state.recentModelIDs.value }
    val favoriteIDs by remember(store) { store.state.favoriteModelIDs.flow }.collectAsState()
    val variants by remember(store) { store.state.modelVariants.flow }.collectAsState()
    val visibility by remember(store) { store.state.modelVisibilityByServer.flow }.collectAsState()
    val hideModels by remember(store) { store.state.hideModelsExperiment.flow }.collectAsState()
    val derived = remember(inputs, favoriteIDs, variants, visibility, hideModels) {
        val state = store.state
        val sections = state.modelSheetSections(recentsAtOpen, FusedModelPickerTunables.RECENTS)
        SheetDerived(
            sections = sections,
            levels = sections.flatMap { it.models }.associate { it.id to state.rememberedLevel(it) },
            hiddenModelCount = state.hiddenModelCount,
            isFiltering = state.isFilteringModels,
            currentID = state.currentModelInfo?.id,
        )
    }
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Model Picker" }) {
        PickerTitleBar(
            title = "Model",
            trailing = { TextButton(onClick = onDone) { Text("Done", fontWeight = FontWeight.SemiBold) } },
        )
        PickerSearchField(inputs.modelFilter, "Search Models", store::setModelFilter) {
            store.send(SessionFeature.Action.ModelSearchSubmitted)
        }
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 240.dp)) {
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                for (section in derived.sections) {
                    if (section.title.isNotEmpty()) {
                        item(key = "header-${section.id}", contentType = "header") { SectionHeader(section.title) }
                    }
                    items(section.models, key = { "${section.id}-${it.id}" }, contentType = { "row" }) { model ->
                        val isFavorite = model.id in favoriteIDs
                        SheetModelRow(
                            model = model,
                            kind = section.kind,
                            isCurrent = derived.currentID == model.id,
                            isFavorite = isFavorite,
                            level = derived.levels[model.id],
                            onPick = { store.send(SessionFeature.Action.ModelPickedWithLevel(model, derived.levels[model.id])) },
                            onLevel = { store.send(SessionFeature.Action.ModelLevelPicked(model, it)) },
                            onToggleFavorite = { store.send(SessionFeature.Action.ToggleFavoriteModel(model)) },
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                        )
                    }
                }
                if (derived.hiddenModelCount > 0) {
                    item(key = "show-all") {
                        Column {
                            Spacer(Modifier.heightIn(min = 16.dp))
                            ShowAllModelsRow(derived.hiddenModelCount, inputs.showsAllModels) {
                                store.send(SessionFeature.Action.ShowAllModelsToggled)
                            }
                        }
                    }
                }
            }
            if (inputs.isLoadingModels && inputs.models.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).padding(32.dp))
            } else if (derived.sections.isEmpty() && derived.hiddenModelCount == 0) {
                PickerEmptyState(derived.isFiltering, inputs.modelFilter, inputs.modelsLoadError) {
                    store.send(SessionFeature.Action.ModelListOpened)
                }
            }
        }
    }
}

private data class SheetDerived(
    val sections: List<ModelPickerSection>,
    val levels: Map<String, String?>,
    val hiddenModelCount: Int,
    val isFiltering: Boolean,
    val currentID: String?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetModelRow(
    model: OpenCodeModel,
    kind: ModelPickerSection.Kind,
    isCurrent: Boolean,
    isFavorite: Boolean,
    level: String?,
    onPick: () -> Unit,
    onLevel: (String?) -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Favorites and Recent mix providers, so their rows name the provider and carry their own identifiers.
    val prefix = when (kind) {
        ModelPickerSection.Kind.Favorites -> "model-favorite"
        ModelPickerSection.Kind.Recent -> "model-recent"
        else -> "model-row"
    }
    val mixesProviders = kind == ModelPickerSection.Kind.Favorites || kind == ModelPickerSection.Kind.Recent
    val provider = ModelProviderGroup.title(model.providerID)
    val scale = remember(model.variants) { ModelLevelScale(model.variants) }
    val favoriteMenuLabel = if (isFavorite) "Remove from Favorites" else "Add to Favorites"
    var menuOpen by remember { mutableStateOf(false) }
    FavoriteSwipeRow(
        isFavorite = isFavorite,
        favoriteLabel = if (isFavorite) "Unfavorite" else "Favorite",
        favoriteSymbol = if (isFavorite) SFSymbol.StarSlash else SFSymbol.StarFill,
        onToggleFavorite = onToggleFavorite,
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(OpenCodeTheme.colors.canvas)
                .heightIn(min = 48.dp)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(role = Role.Button, onClick = onPick, onLongClick = { menuOpen = true }, onLongClickLabel = favoriteMenuLabel)
                        .heightIn(min = 48.dp)
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
                        .clearAndSetSemantics {
                            contentDescription = model.name
                            stateDescription = if (isFavorite) "$provider, Favorite" else provider
                            selected = isCurrent
                            role = Role.Button
                            onClick { onPick(); true }
                            customActions = listOf(CustomAccessibilityAction(favoriteMenuLabel) { onToggleFavorite(); true })
                        }
                        .testTag("$prefix-${model.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.name, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (mixesProviders) {
                            Text(provider, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (isCurrent) {
                        OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.text)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(favoriteMenuLabel) },
                        leadingIcon = { OpenCodeIcon(if (isFavorite) SFSymbol.StarSlash else SFSymbol.Star, contentDescription = null, size = 20.dp) },
                        onClick = {
                            menuOpen = false
                            onToggleFavorite()
                        },
                    )
                }
            }
            if (scale.isAdjustable) LevelMenu(model, scale, level, prefix, onLevel)
        }
    }
}

/**
 * The row's thinking level as a quiet menu: the level's bars and name, then the levels to choose
 * from (iOS `Menu { Picker("Thinking Level") }`).
 */
@Composable
private fun LevelMenu(model: OpenCodeModel, scale: ModelLevelScale, level: String?, prefix: String, onLevel: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = ModelLevelScale.label(level)
    Box {
        Row(
            Modifier
                .clickable(role = Role.DropdownList) { open = true }
                .openCodeTapTarget()
                .padding(horizontal = 8.dp)
                .clearAndSetSemantics {
                    contentDescription = "${model.name} Thinking Level"
                    stateDescription = label
                    role = Role.DropdownList
                    onClick { open = true; true }
                }
                .testTag("$prefix-level-${model.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModelLevelBars(scale.variants, level ?: "", color = OpenCodeTheme.colors.secondaryText)
            Text(label, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
            OpenCodeIcon(SFSymbol.ChevronUpChevronDown, contentDescription = null, size = 13.dp, tint = OpenCodeTheme.colors.secondaryText)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Thinking Level",
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
            for (option in scale.levels) {
                DropdownMenuItem(
                    text = { Text(ModelLevelScale.label(option)) },
                    leadingIcon = { CheckSlot(option == level) },
                    trailingIcon = if (option != null) ({ ModelLevelBars(scale.variants, option) }) else null,
                    onClick = {
                        open = false
                        onLevel(option)
                    },
                    modifier = Modifier.semantics { selected = option == level },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = OpenCodeTheme.colors.secondaryText,
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenCodeTheme.colors.canvas)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

// endregion

// region Shared rows

/**
 * iOS `ShowAllModelsRow`: the foot of the pickers while models are hidden (Hide Unused Models):
 * Show All lists them until the picker closes.
 */
@Composable
private fun ShowAllModelsRow(hidden: Int, showsAll: Boolean, onToggle: () -> Unit) {
    if (hidden <= 0) return
    Column {
        HorizontalDivider(Modifier.padding(start = 20.dp), color = OpenCodeTheme.colors.border)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp, vertical = 11.dp)
                .semantics(mergeDescendants = true) { stateDescription = "$hidden hidden" }
                .testTag("model-show-all"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (showsAll) "Show Fewer Models" else "Show All Models", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.accent, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("$hidden hidden", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

/**
 * A row with iOS's trailing swipe action: dragging it left reveals Favorite (or Unfavorite) on
 * yellow; letting go past the threshold (or a full swipe) toggles it and the row springs back.
 * TalkBack gets the same as a custom action.
 */
@Composable
private fun FavoriteSwipeRow(
    isFavorite: Boolean,
    favoriteLabel: String,
    favoriteSymbol: SFSymbol,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val threshold = with(density) { 72.dp.toPx() }
    val maxReveal = with(density) { 220.dp.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(CustomAccessibilityAction(favoriteLabel) { onToggleFavorite(); true })
                if (isFavorite) stateDescription = "Favorite"
            },
    ) {
        if (offset.value < 0f) {
            Row(
                Modifier.matchParentSize().background(FavoriteYellow).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OpenCodeIcon(favoriteSymbol, contentDescription = null, size = 20.dp, tint = Color.White)
                    Text(favoriteLabel, style = OpenCodeTextStyles.caption1, color = Color.White)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val passed = offset.value <= -threshold
                            scope.launch {
                                if (passed) onToggleFavorite()
                                if (reduceMotion) offset.snapTo(0f) else offset.animateTo(0f, Motion.settle.spec())
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, Motion.settle.spec()) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + amount).coerceIn(-maxReveal, 0f)) }
                    }
                },
        ) {
            content()
        }
    }
}

/** A leading check for the chosen option in a menu, its slot kept when unchecked so labels align. */
@Composable
private fun CheckSlot(checked: Boolean) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (checked) OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 18.dp)
    }
}

@Composable
private fun PickerTitleBar(
    title: String,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading() }
        Text(
            title,
            style = OpenCodeTextStyles.headline,
            color = OpenCodeTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 104.dp).semantics { heading() },
        )
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** The picker's search: Return (the keyboard's Search) picks the top match. */
@Composable
private fun PickerSearchField(value: String, prompt: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(prompt) },
        leadingIcon = { OpenCodeIcon(SFSymbol.Magnifyingglass, contentDescription = null, size = 20.dp) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                Box(
                    Modifier.clickable(role = Role.Button, onClickLabel = "Clear text") { onValueChange("") }.openCodeTapTarget(),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(SFSymbol.XmarkCircleFill, contentDescription = "Clear text", size = 18.dp, tint = OpenCodeTheme.colors.tertiaryText)
                }
            }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }, onGo = { onSubmit() }),
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("model-search"),
    )
}

/** iOS's `ContentUnavailableView`s: no results, Couldn’t Load Models (Try Again), or No Models. */
@Composable
private fun PickerEmptyState(isFiltering: Boolean, filter: String, error: String?, onRetry: () -> Unit) {
    when {
        isFiltering -> Unavailable(SFSymbol.Magnifyingglass, "No Results for “$filter”", "Check the spelling or try a new search.")
        error != null -> Unavailable(SFSymbol.ExclamationmarkTriangle, "Couldn’t Load Models", error, "Try Again" to onRetry)
        else -> Unavailable(SFSymbol.Cpu, "No Models", "Connect a provider in OpenCode on your computer, then try again.")
    }
}

@Composable
private fun Unavailable(symbol: SFSymbol, title: String, description: String?, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().fillMaxHeight().padding(24.dp).semantics(mergeDescendants = false) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = 48.dp, tint = OpenCodeTheme.colors.secondaryText)
        Text(title, style = OpenCodeTextStyles.title3.copy(fontWeight = FontWeight.Bold), color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
        if (description != null) Text(description, style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = action.second) { Text(action.first) }
    }
}

/** iOS `.yellow` (the favorite star and swipe action). */
private val FavoriteYellow = Color(0xFFFFCC00)

// endregion
