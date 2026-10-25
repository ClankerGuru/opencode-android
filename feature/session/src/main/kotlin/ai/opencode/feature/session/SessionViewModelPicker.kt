package ai.opencode.feature.session

import ai.opencode.core.experiments.ModelPickerStyle
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.composer.modelpicker.FusedLabelFlightOverlay
import ai.opencode.feature.composer.modelpicker.FusedLevelStackOverlay
import ai.opencode.feature.composer.modelpicker.FusedLevelStackState
import ai.opencode.feature.composer.modelpicker.ModelPickerAnchors
import ai.opencode.feature.composer.modelpicker.ModelPickerEvent
import ai.opencode.feature.composer.modelpicker.ModelPickerSheet
import ai.opencode.feature.composer.modelpicker.ModelPickerState
import ai.opencode.feature.composer.modelpicker.PlainModelPickerSheet
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

// Port of iOS `Session/SessionView+ModelPicker.swift`: the session's glue for :feature:composer's
// model pickers. The pickers read a `ModelPickerState` built from `SessionFeature.State` and send
// `ModelPickerEvent`s, which map to the `SessionFeature` actions of the same names. Today shows
// `PlainModelPickerSheet`, Sheet `ModelPickerSheet` (both in `ModelPickerDetents`' medium/large
// sheet). Grow from Composer: the composer's `FusedModelChip` (tap, level scrub and the level
// stack over the composer: `FusedLevelStackOverlay`, `FusedLabelFlightOverlay`); its model list
// opens as the Sheet picker, since the list grown out of the composer's glass
// (`FusedModelPickerPanel`, `modelListExpansion`) isn't hosted here.

/** iOS's picker inputs on `SessionFeature.State`, as the composer's pickers read them. */
internal fun SessionFeature.State.modelPickerState(
    favorites: Set<String> = favoriteModelIDs.value,
    recents: List<String> = recentModelIDs.value,
    variants: Map<String, String> = modelVariants.value,
): ModelPickerState = ModelPickerState(
    models = models,
    currentModel = currentModel,
    favoriteModelIDs = favorites,
    recentModelIDs = recents,
    modelVariants = variants,
    modelFilter = modelFilter,
    isLoadingModels = isLoadingModels,
    modelsLoadError = modelsLoadError,
    modelVisibility = modelVisibility,
    showsAllModels = showsAllModels,
)

/** The `SessionFeature` action a picker event stands for. */
internal fun modelPickerAction(event: ModelPickerEvent): SessionFeature.Action = when (event) {
    is ModelPickerEvent.ModelPicked -> SessionFeature.Action.ModelPicked(event.model)
    is ModelPickerEvent.ModelPickedWithLevel -> SessionFeature.Action.ModelPickedWithLevel(event.model, event.level, event.ticked)
    is ModelPickerEvent.ModelLevelPicked -> SessionFeature.Action.ModelLevelPicked(event.model, event.level)
    is ModelPickerEvent.ModelRowLevelChanged -> SessionFeature.Action.ModelRowLevelChanged(event.model, event.level)
    is ModelPickerEvent.VariantPicked -> SessionFeature.Action.VariantPicked(event.variant)
    is ModelPickerEvent.ToggleFavoriteModel -> SessionFeature.Action.ToggleFavoriteModel(event.model)
    is ModelPickerEvent.FilterChanged -> SessionFeature.Action.Binding(BindingAction.ModelFilter(event.text))
    ModelPickerEvent.ModelSearchSubmitted -> SessionFeature.Action.ModelSearchSubmitted
    ModelPickerEvent.ShowAllModelsToggled -> SessionFeature.Action.ShowAllModelsToggled
    ModelPickerEvent.ModelListOpened -> SessionFeature.Action.ModelListOpened
    ModelPickerEvent.ModelListClosed -> SessionFeature.Action.ModelListClosed
    ModelPickerEvent.Dismiss -> SessionFeature.Action.Binding(BindingAction.ActivePicker(null))
}

/** What the picker's state is built from, observed narrowly so a streaming reply doesn't redraw it. */
private data class PickerInputs(
    val models: List<OpenCodeModel>,
    val currentModel: OpenCodeModelRef?,
    val modelFilter: String,
    val isLoadingModels: Boolean,
    val modelsLoadError: String?,
    val showsAllModels: Boolean,
    val serverID: String,
)

/**
 * The picker's state from the session, recomputed when the session's picker inputs or the
 * shared favorites, recents, remembered levels or model visibility change.
 */
@Composable
internal fun rememberModelPickerState(store: StoreOf<SessionFeature.State, SessionFeature.Action>): ModelPickerState {
    val inputs by store.observe {
        PickerInputs(it.models, it.currentModel, it.modelFilter, it.isLoadingModels, it.modelsLoadError, it.showsAllModels, it.server.persistenceID)
    }
    val favorites by remember(store) { store.state.favoriteModelIDs.flow }.collectAsState(store.state.favoriteModelIDs.value)
    val recents by remember(store) { store.state.recentModelIDs.flow }.collectAsState(store.state.recentModelIDs.value)
    val variants by remember(store) { store.state.modelVariants.flow }.collectAsState(store.state.modelVariants.value)
    val visibility by remember(store) { store.state.modelVisibilityByServer.flow }.collectAsState(store.state.modelVisibilityByServer.value)
    val hideModels by remember(store) { store.state.hideModelsExperiment.flow }.collectAsState(store.state.hideModelsExperiment.value)
    return remember(inputs, favorites, recents, variants, visibility, hideModels) {
        store.state.modelPickerState(favorites, recents, variants)
    }
}

/**
 * The model picker, shown by `SessionView` while `activePicker == model`: Model Picker › Today
 * shows the plain picker, › Sheet (and › Grow from Composer's list) the New Model Picker sheet.
 * Dismissing it sets `activePicker` to null; however it goes, its search is cleared
 * (`modelListClosed`).
 */
@Composable
fun SessionModelPicker(store: StoreOf<SessionFeature.State, SessionFeature.Action>) {
    val styleSetting by remember { shared(ModelPickerStyle.key) }.flow.collectAsState()
    val state = rememberModelPickerState(store)
    val onEvent: (ModelPickerEvent) -> Unit = { store.send(modelPickerAction(it)) }
    when (ModelPickerStyle.active(styleSetting)) {
        ModelPickerStyle.today -> PlainModelPickerSheet(state, onEvent)
        ModelPickerStyle.sheet, ModelPickerStyle.growFromComposer -> ModelPickerSheet(state, onEvent)
    }
}

/**
 * Grow from Composer's overlays over the session (iOS `SessionView`'s `FusedLevelStackOverlay`
 * and `FusedLabelFlightOverlay`): the level stack the chip raises, and the label's flight. A
 * level picked in the chip's stack is set in place (`.modelRowLevelChanged`).
 */
@Composable
internal fun SessionModelPickerOverlays(
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    anchors: ModelPickerAnchors,
    headerHeight: Float,
    composerFrame: androidx.compose.ui.geometry.Rect,
) {
    val styleSetting by remember { shared(ModelPickerStyle.key) }.flow.collectAsState()
    if (ModelPickerStyle.active(styleSetting) != ModelPickerStyle.growFromComposer) return
    FusedLevelStackOverlay(
        anchors = anchors,
        headerHeight = headerHeight.dp,
        composerFrame = composerFrame,
        onPickLevel = { stack, level, _ ->
            when (stack.kind) {
                // The chip's stack sets the level in place; the list isn't open.
                FusedLevelStackState.SourceKind.Chip -> store.send(SessionFeature.Action.ModelRowLevelChanged(stack.model, level))
                FusedLevelStackState.SourceKind.Row -> store.send(SessionFeature.Action.ModelPickedWithLevel(stack.model, level))
            }
        },
    )
    FusedLabelFlightOverlay(anchors)
}
