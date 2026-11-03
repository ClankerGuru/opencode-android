package ai.opencode.feature.session

import ai.opencode.core.experiments.ModelPickerStyle
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.composer.ComposerMorph
import ai.opencode.feature.composer.ComposerMorphGeometry
import ai.opencode.feature.composer.ComposerMorphSurface
import ai.opencode.feature.composer.ComposerMorphTunables
import ai.opencode.feature.composer.modelpicker.ComposerFocusRestore
import ai.opencode.feature.composer.modelpicker.FusedLabelFlight
import ai.opencode.feature.composer.modelpicker.FusedLabelFlightOverlay
import ai.opencode.feature.composer.modelpicker.FusedLevelStackOverlay
import ai.opencode.feature.composer.modelpicker.FusedLevelStackState
import ai.opencode.feature.composer.modelpicker.FusedModelPickerPanel
import ai.opencode.feature.composer.modelpicker.FusedModelPickerTunables
import ai.opencode.feature.composer.modelpicker.FusedRowMenuOverlay
import ai.opencode.feature.composer.modelpicker.GrowingPanel
import ai.opencode.feature.composer.modelpicker.LevelScrubberTunables
import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.feature.composer.modelpicker.ModelListDetent
import ai.opencode.feature.composer.modelpicker.ModelListScrollEdge
import ai.opencode.feature.composer.modelpicker.ModelPickerAnchors
import ai.opencode.feature.composer.modelpicker.ModelPickerEvent
import ai.opencode.feature.composer.modelpicker.ModelPickerSheet
import ai.opencode.feature.composer.modelpicker.ModelPickerSheetContent
import ai.opencode.feature.composer.modelpicker.ModelPickerState
import ai.opencode.feature.composer.modelpicker.ModelPickerTunables
import ai.opencode.feature.composer.modelpicker.OnePickerCatalog
import ai.opencode.feature.composer.modelpicker.OnePickerController
import ai.opencode.feature.composer.modelpicker.OnePickerGlassBackdrop
import ai.opencode.feature.composer.modelpicker.OnePickerSheetHost
import ai.opencode.feature.composer.modelpicker.OnePickerSimulation
import ai.opencode.feature.composer.modelpicker.OnePickerSurface
import ai.opencode.feature.composer.modelpicker.OnePickerTunables
import ai.opencode.feature.composer.modelpicker.PlainModelPickerSheet
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Port of iOS `Session/SessionView+ModelPicker.swift`: the session's glue for :feature:composer's
// model pickers. The pickers read a `ModelPickerState` built from `SessionFeature.State` and send
// `ModelPickerEvent`s, which map to the `SessionFeature` actions of the same names. Today shows
// `PlainModelPickerSheet`, Sheet `ModelPickerSheet` (both in `ModelPickerDetents`' medium/large
// sheet). Grow from Composer: the composer's `FusedModelChip` opens the model list as the sheet the
// composer morphs into ([SessionModelPanels], `modelSheetSurface`: `ComposerMorphSurface` hosting
// `FusedModelPickerPanel`), with the level stack, the row menu and the label flight over the screen.
// The list grown inside the composer's own surface (`modelListExpansion`, `ModelListStyle.grow`) is
// a Tuning Panel preset on iOS (`modelPicker.list.style`, default `.sheet`); Android has no tuning
// store, so `ModelPickerTunables.activeListStyle` is always `.sheet` and only that path is hosted.

/** iOS's picker inputs on `SessionFeature.State`, as the composer's pickers read them. */
internal fun SessionFeature.State.modelPickerState(
    favorites: Set<String> = favoriteModelIDs.value,
    recents: List<String> = recentModelIDs.value,
    variants: Map<String, String> = modelVariants.value,
    displayNames: Map<String, ModelDisplayNames> = modelDisplayNamesByServer.value,
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
    modelDisplayNames = displayNames[server.persistenceID] ?: ModelDisplayNames(),
)

/** The `SessionFeature` action a picker event stands for; null when none does. */
internal fun modelPickerAction(event: ModelPickerEvent): SessionFeature.Action? = when (event) {
    is ModelPickerEvent.ModelPicked -> SessionFeature.Action.ModelPicked(event.model)
    is ModelPickerEvent.ModelPickedWithLevel -> SessionFeature.Action.ModelPickedWithLevel(event.model, event.level, event.ticked)
    is ModelPickerEvent.ModelLevelPicked -> SessionFeature.Action.ModelLevelPicked(event.model, event.level)
    is ModelPickerEvent.ModelRowLevelChanged -> SessionFeature.Action.ModelRowLevelChanged(event.model, event.level)
    is ModelPickerEvent.VariantPicked -> SessionFeature.Action.VariantPicked(event.variant)
    is ModelPickerEvent.ToggleFavoriteModel -> SessionFeature.Action.ToggleFavoriteModel(event.model)
    is ModelPickerEvent.FilterChanged -> SessionFeature.Action.Binding(BindingAction.ModelFilter(event.text))
    // Never sent; iOS 8069f6e9 removed the reducer action as dead.
    ModelPickerEvent.ModelSearchSubmitted -> null
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
    val displayNames by remember(store) { store.state.modelDisplayNamesByServer.flow }.collectAsState(store.state.modelDisplayNamesByServer.value)
    return remember(inputs, favorites, recents, variants, visibility, hideModels, displayNames) {
        store.state.modelPickerState(favorites, recents, variants, displayNames)
    }
}

/**
 * The model picker, shown by `SessionView` while `activePicker == model` (iOS `modelPicker`):
 * Model Picker › Today shows the plain picker, › Sheet the New Model Picker sheet, › One Picker
 * `OnePickerSheetHost` (only while a call or a docked request covers the composer; otherwise the
 * route opens One Picker out of the composer instead, iOS `activeSheetPicker`), and › Grow from
 * Composer `ModelPickerSheetContent` (reached from the session sheet's model row; the composer's
 * chip opens the morphing list instead). Dismissing it sets `activePicker` to null; however it
 * goes, its search is cleared (`modelListClosed`).
 */
@Composable
fun SessionModelPicker(store: StoreOf<SessionFeature.State, SessionFeature.Action>) {
    val styleSetting by remember { shared(ModelPickerStyle.key) }.flow.collectAsState()
    val state = rememberModelPickerState(store)
    val onEvent: (ModelPickerEvent) -> Unit = { event -> modelPickerAction(event)?.let(store::send) }
    when (ModelPickerStyle.active(styleSetting)) {
        ModelPickerStyle.today -> PlainModelPickerSheet(state, onEvent)
        ModelPickerStyle.sheet -> ModelPickerSheet(state, onEvent)
        ModelPickerStyle.onePicker -> {
            // iOS `activeSheetPicker`: with the composer on screen the route opens One Picker out
            // of it ([SessionModelPickerOverlays]), so no sheet shows meanwhile.
            val panels = LocalSessionComposerHost.current?.modelPanels
            if (panels?.canPresentOnePickerMorph != true) {
                OnePickerSheetHost(
                    state = state,
                    onEvent = onEvent,
                    onDismiss = { store.send(SessionFeature.Action.Binding(BindingAction.ActivePicker(null))) },
                    onPickModel = { model, level -> store.send(SessionFeature.Action.ModelPickedWithLevel(model, level)) },
                )
            }
        }
        ModelPickerStyle.growFromComposer -> ModelPickerSheetContent(state, onEvent)
    }
}

/**
 * The model panels grown out of the composer (iOS `SessionView`'s `modelList`, `modelSheet`,
 * `levelPanel` and their `@State`s, with `openModelList`, `closeModelList`, `openModelSheet`,
 * `closeModelSheet`, `dragModelSheet`, `settleModelSheet`, `chooseModelListDetent`,
 * `pickFusedModel`, `restoreFocusAfterModelList`, `openLevelPanel`/`closeLevelPanel`,
 * `closeOthersForModelPanel` and `closeModelPanels`). A plain object, as on iOS (per-frame motion
 * stays out of reducers); [SessionModelPickerOverlays] creates it and hands it to the composer
 * through [SessionComposerHost.modelPanels]. Sheet tops are root px; drags dp.
 */
@Stable
internal class SessionModelPanels(
    private val scope: CoroutineScope,
    private val store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    private val host: SessionComposerHost,
    val anchors: ModelPickerAnchors,
) {
    val modelList = GrowingPanel(scope)
    val levelPanel = GrowingPanel(scope)
    val modelSheet = ComposerMorph(scope)
    private val focusRestore = ComposerFocusRestore(scope)

    var modelListIsSheet by mutableStateOf(false)
        private set
    var modelListDetent by mutableStateOf(ModelListDetent.Medium)
        private set
    /** iOS `modelListGrown`: the rows show once the glass has started to grow. */
    var modelListGrown by mutableStateOf(false)
        private set
    /** iOS `modelSearchHoldsKeyboard`: Search Models has the keyboard. */
    var modelSearchHoldsKeyboard by mutableStateOf(false)
    private var modelListRestoresFocus = false
    /** Where the sheet grew from, and where it folds back onto (root px). */
    var modelSheetSource by mutableStateOf(Rect.Zero)
        private set
    var modelSheetLandsOn by mutableStateOf<Rect?>(null)
        private set
    /** iOS `modelSheetTop` (root px), animated as iOS's `withAnimation` changes it. */
    val modelSheetTop = Animatable(0f)
    private var modelSheetCompactTop = 0f

    // The screen around the panels, kept current by [SessionModelPickerOverlays].
    var usesNewModelPicker = false
    var reducedMotion = false
    var density = 1f
    var ceilingPx = 0f
    var keyboardPx = 0f
    var safeBottomPx = 0f
    var talkBackOn = false
    var sceneActive = true

    /** iOS `onePicker`: One Picker's controller (Experimental › Model Picker › One Picker). */
    val onePicker = OnePickerController()

    /** iOS `usesOnePicker`. */
    var usesOnePicker by mutableStateOf(false)

    /** One Picker's space: the session's root box, its origin in root px. */
    var spaceOrigin = Offset.Zero
    /** iOS `headerHeight + 8` in One Picker's space: how high it may grow. */
    var onePickerCeiling = 0.0

    /** iOS `sessionSheetComposer`: where the focused composer sat as the session sheet opened (One Picker's space). */
    var sessionSheetComposer: Rect? = null

    /** iOS `canPresentOnePickerMorph` (`voiceStore == nil && !docksRequest`): the composer is on screen to morph from. */
    val canPresentOnePickerMorph: Boolean get() = !host.callHoldsComposer && !store.state.isAnsweringRequest

    /** iOS `OnePickerComposerFade`: the composer fades as One Picker's glass grows out of it. */
    val composerOpacity: Float
        get() = if (usesOnePicker && onePicker.isShown) onePicker.motion.pose.composerOpacity.toFloat().coerceIn(0f, 1f) else 1f

    /** iOS `composerPanelCovers` (its model half): a panel grown out of the composer stands over what sits above it. */
    val covers: Boolean get() = modelList.isOpen || levelPanel.isOpen || (usesOnePicker && onePicker.isOpen)

    /** The composer stays expanded while the model controls' panels are drawn (iOS `composerPresentation`). */
    val keepsComposerExpanded: Boolean get() = modelList.isShown || levelPanel.isShown || (usesOnePicker && onePicker.isShown)

    /** iOS `composerSwipeYields`' model half: an overlay or panel owns downward drags. */
    val swipeYields: Boolean
        get() = anchors.isOverlayActive || levelPanel.isShown || modelList.isShown || modelSheet.isShown ||
            (usesOnePicker && onePicker.isShown)

    /** iOS `allowsMergedComposerGlass`' model half: nothing grown out of the composer is drawn. */
    val holdsComposerGlass: Boolean
        get() = modelList.isOpen || modelList.isShown ||
            (levelPanel.isOpen && LevelScrubberTunables.activeStyle.coversComposer) ||
            (usesNewModelPicker && (modelSheet.isOpen || modelSheet.isShown)) ||
            (usesOnePicker && (onePicker.isOpen || onePicker.isShown))

    /** The composer is behind the list or the sheet: hidden from TalkBack (iOS `accessibilityHidden`). */
    val hidesComposer: Boolean get() = modelList.isOpen || modelSheet.isOpen || (usesOnePicker && onePicker.isOpen)

    private val picker: ModelPickerState get() = store.state.modelPickerState()

    private val ceiling: Float get() = ceilingPx

    private fun frozenSectionsNow() = picker.fusedModelSections(
        recentLimit = FusedModelPickerTunables.recents.roundToInt(),
        includeAllModels = FusedModelPickerTunables.allModels == 1,
    )

    private fun <T> morphAnimation(opening: Boolean): FiniteAnimationSpec<T> =
        FusedModelPickerTunables.animation(if (opening) FusedModelPickerTunables.open else FusedModelPickerTunables.close, reducedMotion)

    private fun animateTop(to: Float, spec: FiniteAnimationSpec<Float>?) {
        scope.launch { if (spec == null) modelSheetTop.snapTo(to) else modelSheetTop.animateTo(to, spec) }
    }

    /**
     * Where the sheet's top rests: the composer's top with the keyboard up; with it down, as high
     * as the composer would stand over the keyboard or `mediumMax` (470 dp), so the sheet shows
     * Favorites, 3 Recent and 1 peeking All Models row.
     */
    fun modelSheetTop(composer: Rect): Float {
        val keyboard = keyboardPx
        val software = keyboard > 120 * density
        val focused = host.messageFieldFocused
        if (focused && software) return composer.top
        val minLift = max(ComposerMorphTunables.leastLift, FusedModelPickerTunables.mediumMax - max(composer.height / density.toDouble(), 56.0)).toFloat() * density
        val lift = if (software) keyboard - safeBottomPx else minLift
        val bar = if (focused) max(keyboard - safeBottomPx, 0f) else 0f
        return composer.top - max(lift - bar, 0f)
    }

    /** VoiceOver's (TalkBack's) grabber chose a rest. */
    fun chooseModelListDetent(detent: ModelListDetent) {
        if (!modelList.isOpen || detent == modelListDetent) return
        if (!modelListIsSheet) return
        val compactTop = if (modelSheetCompactTop > 0) modelSheetCompactTop else modelSheetTop(host.attachmentAnchors.composer)
        val spec = FusedModelPickerTunables.animation<Float>(FusedModelPickerTunables.resize, reducedMotion)
        modelListDetent = detent
        animateTop(if (detent == ModelListDetent.Large) ceiling else compactTop, spec)
        modelSheet.animateDrag(0f, FusedModelPickerTunables.animation(FusedModelPickerTunables.resize, reducedMotion))
        host.announce(detent.accessibilityValue)
    }

    fun pickFusedModel(model: OpenCodeModel, level: String?, levelSourceRect: Rect?, ticked: Boolean = false) {
        anchors.frozenSections = frozenSectionsNow()
        store.send(SessionFeature.Action.ModelPickedWithLevel(model, level, ticked))
        anchors.startFlight(
            direction = FusedLabelFlight.Direction.Close,
            model = model,
            level = level,
            levelSourceRect = levelSourceRect,
            reduceMotion = reducedMotion,
        )
        closeModelList()
    }

    private fun openModelSheet() {
        closeOthersForModelPanel()
        levelPanel.reset()
        anchors.resetFusedState()
        modelListIsSheet = true
        modelListRestoresFocus = host.messageFieldFocused
        val composer = host.attachmentAnchors.composer
        val compactTop = modelSheetTop(composer)
        val initialDetent = if (talkBackOn) ModelListDetent.Large else ModelListDetent.Medium
        val top = if (initialDetent == ModelListDetent.Large) ceiling else compactTop
        // Reopened mid-fold, it grows back from where it is; otherwise its rest is set, not animated.
        if (!modelSheet.isShown) modelSheetSource = composer
        modelSheetCompactTop = compactTop
        modelListDetent = initialDetent
        animateTop(top, null)
        val hadKeyboard = host.messageFieldFocused
        val openAnim = morphAnimation<Float>(opening = true)
        // iOS rides the keyboard's curve with the bottom edge (`DockedKeyboard.animation`); Android
        // can't share the IME's motion, so the edge keeps the open spring.
        modelSheet.armThenOpen(
            animation = openAnim,
            edge = null,
            contentDelay = FusedModelPickerTunables.contentDelay,
            prepare = {
                modelList.open()
                modelListGrown = true
            },
            launch = {
                if (hadKeyboard) host.dismissComposerKeyboard()
                anchors.startFlight(
                    direction = FusedLabelFlight.Direction.Open,
                    model = picker.currentModelInfo,
                    level = picker.currentLevel,
                    reduceMotion = reducedMotion,
                )
            },
            opened = {
                modelSheet.after((FusedModelPickerTunables.open.duration * 1000).roundToInt().milliseconds) {
                    if (modelList.isOpen) store.send(SessionFeature.Action.ModelListOpened)
                }
            },
        )
    }

    /**
     * The sheet folds back onto the composer. The message field takes the keyboard back first if
     * it had it as the sheet opened (straight from Search Models when that has it), so the keyboard
     * and the fold arrive together; otherwise a keyboard Search Models raised goes down.
     */
    private fun closeModelSheet() {
        if (anchors.frozenSections == null) anchors.frozenSections = frozenSectionsNow()
        anchors.closeLevelStack(reduceMotion = reducedMotion)
        anchors.closeRowMenu(reduceMotion = reducedMotion)
        if (anchors.flight == null) {
            anchors.startFlight(
                direction = FusedLabelFlight.Direction.Close,
                model = picker.currentModelInfo,
                level = picker.currentLevel,
                reduceMotion = reducedMotion,
            )
        }
        val restores = modelListRestoresFocus
        val searchHad = modelSearchHoldsKeyboard
        modelListRestoresFocus = false
        val settle = if (reducedMotion) 220 else (ComposerMorphTunables.settle * 1000).roundToInt()
        modelList.close(reducedMotion, drawnFor = settle.milliseconds)
        modelSearchHoldsKeyboard = false
        val animation = morphAnimation<Float>(opening = false)
        val landing = if (modelSheetSource == Rect.Zero) null else modelSheetSource
        if (!restores && !searchHad) {
            modelSheetLandsOn = landing
            return modelSheet.fold(animation, reduceMotion = reducedMotion)
        }
        val screen = store.state.taskIdentity
        modelSheetLandsOn = landing
        modelSheet.launchThenFold(
            animation = animation,
            reduceMotion = reducedMotion,
            launch = {
                if (restores) {
                    val raised = restoreFocusAfterModelList(screen)
                    if (!raised && searchHad) host.dismissComposerKeyboard()
                    if (!raised) modelSheetLandsOn = null
                    // Once more after the focus change settles (a pick from Search Models' Enter);
                    // cancelled if anything else takes the composer.
                    focusRestore.schedule(50.milliseconds) { restoreFocusAfterModelList(screen) }
                } else {
                    host.dismissComposerKeyboard()
                }
            },
            edge = { null },
        )
    }

    /** A finger on the sheet's grabber: it stretches up toward the ceiling or draws down toward closing (dp). */
    fun dragModelSheet(translation: Double) {
        if (!modelSheet.isOpen) return
        val maxUp = max(modelSheetTop.value - ceiling, 0f) / density
        val t = translation.toFloat()
        modelSheet.drag = if (t >= 0) {
            t
        } else {
            val up = -t
            if (up <= maxUp) t else -(maxUp + min(24f, (up - maxUp) * 0.2f))
        }
    }

    /**
     * Let go: drawn up, it expands to full screen; drawn far enough or thrown down, it collapses
     * or closes; otherwise it springs back (dp, dp/s).
     */
    fun settleModelSheet(predicted: Double?, velocity: Double) {
        if (!modelSheet.isOpen || modelSheet.drag == 0f) return
        val compactTop = if (modelSheetCompactTop > 0) modelSheetCompactTop else modelSheetTop(host.attachmentAnchors.composer)
        val drag = modelSheet.drag.toDouble()
        val end = predicted ?: drag
        val resize = FusedModelPickerTunables.animation<Float>(FusedModelPickerTunables.resize, reducedMotion)
        if (modelListDetent == ModelListDetent.Medium && (drag < -36 || (velocity < -450 && end < -20))) {
            modelListDetent = ModelListDetent.Large
            animateTop(ceiling, resize)
            modelSheet.animateDrag(0f, resize)
            return
        }
        if (modelListDetent == ModelListDetent.Large && drag > 36 && drag < (compactTop - ceiling) / density + 120 && velocity < 1600) {
            modelListDetent = ModelListDetent.Medium
            animateTop(compactTop, resize)
            modelSheet.animateDrag(0f, resize)
            return
        }
        if (ComposerMorph.closes(translation = drag, predicted = end, velocity = velocity)) return closeModelList()
        modelSheet.animateDrag(0f, if (reducedMotion) null else FusedModelPickerTunables.open.spec())
    }

    fun openModelList() {
        if (modelList.isOpen) return
        // `ModelListStyle.grow` (the list grown inside the composer's surface) is a Tuning Panel
        // preset on iOS; Android's list style is always the sheet.
        openModelSheet()
    }

    /** The list folds back into the composer (iOS `closeModelList`). */
    fun closeModelList() {
        if (!modelList.isOpen) return
        if (modelListIsSheet) closeModelSheet()
    }

    /**
     * The message field takes the keyboard back from the model list, unless something else took
     * the composer meanwhile (another screen, a panel or menu, a request, dictation, a call).
     */
    fun restoreFocusAfterModelList(screen: SessionFeature.State.TaskIdentity): Boolean {
        val state = store.state
        if (state.taskIdentity != screen || !sceneActive || modelList.isOpen || onePicker.isOpen || levelPanel.isShown ||
            host.isAttachmentMenuOpen || host.callHoldsComposer || state.isAnsweringRequest ||
            state.dictationHold != null || state.isDictating || state.isPreparingDictation
        ) return false
        host.focusComposer()
        return true
    }

    /** iOS `openLevelPanel` (the Composer playground's level picker). */
    fun openLevelPanel() {
        val model = picker.currentModelInfo ?: return
        if (levelPanel.isOpen || picker.currentLevelScale == null) return
        closeOthersForModelPanel()
        resetModelList()
        val right = if (anchors.chipLevelFrame != Rect.Zero) anchors.chipLevelFrame.right else anchors.chipFrame.right
        val anchorY = host.attachmentAnchors.composer.top - 24 * density
        anchors.openLevelStack(
            kind = FusedLevelStackState.SourceKind.Chip,
            model = model,
            level = picker.currentLevel,
            anchorX = right - 24 * density,
            anchorY = anchorY,
            rightEdgeX = right,
            tapMode = true,
            reduceMotion = reducedMotion,
        )
        levelPanel.open()
    }

    fun closeLevelPanel() {
        anchors.closeLevelStack(reduceMotion = reducedMotion)
        levelPanel.close(reducedMotion)
    }

    /** The composer's frame in One Picker's space (dp); zero while it isn't on screen. */
    fun composerInSpace(): Rect {
        val composer = host.attachmentAnchors.composer
        if (composer.width <= 0f) return Rect.Zero
        return Rect(
            (composer.left - spaceOrigin.x) / density, (composer.top - spaceOrigin.y) / density,
            (composer.right - spaceOrigin.x) / density, (composer.bottom - spaceOrigin.y) / density,
        )
    }

    /**
     * iOS `openOnePicker(restoringFocusTo:)`: One Picker grows out of the composer, taking the
     * keyboard's place. [keyboardComposer] is the composer with its keyboard up, when One Picker
     * opens with the keyboard already down but owes it back (the session sheet). iOS first holds
     * the transcript's bottom obstruction (`holdKeyboardPlace`); the Android transcript has no
     * such hold, so the keyboard's leaving moves it as any dismissal does.
     */
    fun openOnePicker(keyboardComposer: Rect? = null) {
        if (onePicker.isOpen) return
        closeOthersForModelPanel()
        val viewport = onePicker.latestViewportInSpace
        val fallbackSource = Rect(
            Offset(viewport.left + 20, viewport.bottom - 96),
            Size(max(200f, viewport.width - 40), 56f),
        )
        val composer = composerInSpace()
        val hadKeyboard = host.messageFieldFocused
        onePicker.open(
            sourceRect = if (composer.width > 0f) composer else fallbackSource,
            viewport = viewport,
            ceiling = onePickerCeiling,
            recentModelIDs = store.state.recentModelIDs.value,
            hadKeyboard = hadKeyboard,
            restoresFocusTo = if (hadKeyboard) null else keyboardComposer,
            initialKeyboardTop = null,
            initialDetent = if (talkBackOn) ModelListDetent.Large else ModelListDetent.Medium,
            reduceMotion = reducedMotion,
            onLaunch = { if (hadKeyboard) host.dismissComposerKeyboard() },
            onOpened = { store.send(SessionFeature.Action.ModelListOpened) },
        )
    }

    /** iOS `pickOnePickerModel`. */
    fun pickOnePickerModel(model: OpenCodeModel, level: String?) {
        store.send(SessionFeature.Action.ModelPickedWithLevel(model, level))
        closeOnePicker()
    }

    /**
     * iOS `closeOnePicker`: One Picker folds back onto the composer. If the message field had the
     * keyboard as it opened, it folds onto the composer as it sat with the keyboard up and the
     * field takes the keyboard back (straight from Search Models when that has it); otherwise it
     * folds onto the resting composer, and a keyboard Search Models raised goes down with it.
     */
    fun closeOnePicker() {
        val restores = onePicker.restoresComposerFocus
        val searchHad = onePicker.searchFocused
        onePicker.restoresComposerFocus = false
        val screen = store.state.taskIdentity
        val freeze = {
            val picker = picker
            OnePickerCatalog.sections(
                models = picker.pickerModels,
                favorites = picker.favoriteModelIDs,
                recents = onePicker.recentsAtOpen,
                currentModelID = picker.currentModelInfo?.id,
                filter = picker.modelFilter,
                recentLimit = OnePickerTunables.RECENTS,
                showsAllModels = OnePickerTunables.SHOWS_ALL_MODELS,
            )
        }
        val composer = composerInSpace()
        val bottomDest = when {
            onePicker.restingBottomComposer.width > 0f -> onePicker.restingBottomComposer
            composer.width > 0f -> composer
            else -> onePicker.openedFromComposer
        }
        val keyboardUpDest = if (onePicker.openedFromComposer.width > 0f) onePicker.openedFromComposer else bottomDest
        val alreadyClosing = onePicker.simulation.phase == OnePickerSimulation.Phase.Closing

        if (restores && searchHad) {
            // Search already holds the keyboard: fold onto the keyboard-up composer and hand the
            // keyboard straight from Search to the message field, so it never drops and rises.
            onePicker.handedKeyboardToComposer = true
            if (alreadyClosing) {
                onePicker.retargetCloseDestination(keyboardUpDest, keepsKeyboard = true, matchingKeyboardSpring = false)
            } else {
                onePicker.close(keyboardUpDest, keepsKeyboard = true, matchingKeyboardSpring = false, freezeSections = freeze)
            }
            if (!restoreFocusAfterModelList(screen)) {
                onePicker.handedKeyboardToComposer = false
                onePicker.retargetCloseDestination(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = true)
                host.dismissComposerKeyboard()
            } else {
                focusRestore.schedule(50.milliseconds) { restoreFocusAfterModelList(screen) }
            }
            return
        }
        if (restores) {
            // The message field had the keyboard as One Picker opened (it's down now): start the
            // fold onto the keyboard-up composer, then raise the field's keyboard.
            if (alreadyClosing) {
                onePicker.retargetCloseDestination(keyboardUpDest, keepsKeyboard = false, matchingKeyboardSpring = true)
            } else {
                onePicker.close(keyboardUpDest, keepsKeyboard = false, matchingKeyboardSpring = true, freezeSections = freeze)
            }
            if (!restoreFocusAfterModelList(screen)) {
                onePicker.retargetCloseDestination(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = false)
            } else {
                focusRestore.schedule(50.milliseconds) { restoreFocusAfterModelList(screen) }
            }
            return
        }
        if (searchHad) {
            // The message field didn't have the keyboard: Search's keyboard retreats in step as
            // One Picker folds onto the resting composer.
            if (alreadyClosing) {
                onePicker.retargetCloseDestination(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = true)
            } else {
                onePicker.close(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = true, freezeSections = freeze)
            }
            host.dismissComposerKeyboard()
            return
        }
        if (!alreadyClosing) {
            onePicker.close(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = false, freezeSections = freeze)
        }
    }

    /** The attachment menu gives way to a model panel. */
    fun closeOthersForModelPanel() {
        focusRestore.cancel()
        if (host.isAttachmentMenuOpen) host.closeAttachmentMenu()
    }

    /** A session switch, a call, a request, or dictation taking the composer closes what grew out of it, at once. */
    fun closeModelPanels() {
        focusRestore.cancel()
        modelListRestoresFocus = false
        modelSearchHoldsKeyboard = false
        anchors.resetFusedState()
        onePicker.reset()
        resetModelList()
        levelPanel.reset()
    }

    /** The list gone at once, so the next opening grows from the composer. */
    private fun resetModelList() {
        anchors.resetFusedState()
        modelList.reset()
        modelSheet.reset()
    }

    /** The message field took the keyboard back from Search Models. */
    fun messageFieldFocused() {
        modelSearchHoldsKeyboard = false
    }
}

/**
 * Grow from Composer's surfaces over the session (iOS `SessionView`'s `modelSheetSurface`,
 * `FusedLevelStackOverlay`, `FusedRowMenuOverlay` and `FusedLabelFlightOverlay` overlays): the
 * model list as the sheet the composer morphs into, the level stack the chip or a row raises, a
 * held row's menu, and the label's flight. Also creates the screen's [SessionModelPanels] and
 * hands it to the composer, and closes the panels when the style leaves Grow from Composer.
 */
@Composable
internal fun SessionModelPickerOverlays(
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    anchors: ModelPickerAnchors,
    headerHeight: Float,
    composerFrame: Rect,
) {
    val host = LocalSessionComposerHost.current ?: return
    val scope = rememberCoroutineScope()
    val panels = remember(store, anchors) { SessionModelPanels(scope, store, host, anchors) }
    val styleSetting by remember { shared(ModelPickerStyle.key) }.flow.collectAsState()
    val grows = ModelPickerStyle.active(styleSetting) == ModelPickerStyle.growFromComposer
    val usesOnePicker = ModelPickerStyle.active(styleSetting) == ModelPickerStyle.onePicker
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val sceneActive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val statusTop = WindowInsets.statusBars.getTop(density)
    val keyboard = WindowInsets.ime.getBottom(density)
    val navigation = WindowInsets.navigationBars.getBottom(density)
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val screenTop = screenOrigin.y
    // iOS `headerHeight + 8`, in One Picker's space (the session's box, under the status bar).
    val onePickerCeiling = statusTop / density.density + headerHeight + 8.0

    SideEffect {
        panels.usesNewModelPicker = grows
        panels.usesOnePicker = usesOnePicker
        panels.spaceOrigin = screenOrigin
        panels.onePickerCeiling = onePickerCeiling
        panels.reducedMotion = reduceMotion
        panels.density = density.density
        panels.ceilingPx = screenTop + statusTop + (headerHeight + ModelPickerTunables.headerGap.toFloat()) * density.density
        panels.keyboardPx = keyboard.toFloat()
        panels.safeBottomPx = navigation.toFloat()
        panels.talkBackOn = accessibility?.isTouchExplorationEnabled == true
        panels.sceneActive = sceneActive
        host.modelPanels = panels
    }
    DisposableEffect(panels) { onDispose { if (host.modelPanels === panels) host.modelPanels = null } }
    // iOS `onChange(of: modelPickerStyleSetting)`: leaving One Picker resets it, and leaving both
    // Grow from Composer and One Picker closes their panels.
    LaunchedEffect(grows, usesOnePicker) {
        if (!usesOnePicker) panels.onePicker.reset()
        if (!grows && !usesOnePicker) panels.closeModelPanels()
    }
    // iOS `onChange(of: scenePhase)`: leaving the foreground settles a drag the system took
    // mid-way and closes the level picker.
    LaunchedEffect(sceneActive) {
        if (sceneActive) return@LaunchedEffect
        panels.onePicker.settleInteraction()
        if (grows && panels.levelPanel.isOpen) panels.closeLevelPanel()
    }
    // One Picker opening out of the composer taps, like the attachment menu (iOS `.haptic(.tap)`).
    HapticEffect(Haptic.tap, panels.onePicker.isOpen) { _, open -> open }
    // Folded away (settled, or reset at once), its search is cleared (iOS `onChange(of: onePicker.isShown)`).
    val onePickerShown = panels.onePicker.isShown
    var onePickerWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(onePickerShown) {
        if (onePickerWasShown && !onePickerShown) store.send(SessionFeature.Action.ModelListClosed)
        onePickerWasShown = onePickerShown
    }
    // iOS `onChange(of: store.activePicker)`: with the composer on screen, the model route (the
    // header, the session sheet's Model row) opens One Picker out of it instead of a sheet. iOS
    // remembers the focused composer as the session sheet opens (`showSessionInfo`), so One
    // Picker's close hands the keyboard back to it.
    val activePicker by store.observe { it.activePicker }
    var previousPicker by remember { mutableStateOf(activePicker) }
    LaunchedEffect(activePicker) {
        val previous = previousPicker
        previousPicker = activePicker
        if (previous == activePicker) return@LaunchedEffect
        if (activePicker == SessionFeature.State.ActivePicker.session) {
            val composer = panels.composerInSpace()
            panels.sessionSheetComposer = if (host.messageFieldFocused && composer.width > 0f) composer else null
        }
        if (activePicker == SessionFeature.State.ActivePicker.model && usesOnePicker && panels.canPresentOnePickerMorph) {
            store.send(SessionFeature.Action.Binding(BindingAction.ActivePicker(null)))
            panels.openOnePicker(
                keyboardComposer = if (previous == SessionFeature.State.ActivePicker.session) panels.sessionSheetComposer else null,
            )
        }
        if (activePicker != SessionFeature.State.ActivePicker.session) panels.sessionSheetComposer = null
    }
    // iOS `onChange(of: taskIdentity)`: another screen in place closes what grew out of the composer.
    val taskIdentity by store.observe { it.taskIdentity }
    var seenIdentity by remember { mutableStateOf(taskIdentity) }
    LaunchedEffect(taskIdentity) {
        if (seenIdentity == taskIdentity) return@LaunchedEffect
        seenIdentity = taskIdentity
        panels.closeModelPanels()
    }
    // iOS: the message field taking the keyboard back from Search Models.
    LaunchedEffect(host.messageFieldFocused) { if (host.messageFieldFocused) panels.messageFieldFocused() }

    Box(Modifier.fillMaxSize().onGloballyPositioned { screenOrigin = it.boundsInRoot().topLeft })
    if (usesOnePicker) {
        val state = rememberModelPickerState(store)
        OnePickerSurface(
            state = state,
            controller = panels.onePicker,
            ceiling = onePickerCeiling,
            liveComposer = panels.composerInSpace(),
            onEvent = { event -> modelPickerAction(event)?.let(store::send) },
            onHideKeyboard = host::dismissComposerKeyboard,
            onDismiss = panels::closeOnePicker,
            onPickModelAndClose = panels::pickOnePickerModel,
        )
        return
    }
    if (!grows) return
    val state = rememberModelPickerState(store)
    val onEvent: (ModelPickerEvent) -> Unit = { event -> modelPickerAction(event)?.let(store::send) }

    // Always mounted while it can open, so opening grows it from the composer.
    val composer = host.attachmentAnchors.composer
    val source = panels.modelSheetSource
    val column = if (source == Rect.Zero) composer else source
    val showsRows = panels.modelListGrown || reduceMotion
    ComposerMorphSurface(
        morph = panels.modelSheet,
        // It grows from the composer as it was, and folds onto it as it is.
        source = composer,
        sourceRadiusDp = ComposerTunables.cornerRadius.toDouble(),
        target = { screen, keyboardTop ->
            ComposerMorphGeometry.sheet(
                top = panels.modelSheetTop.value,
                column = if (screen.width > 600 * density.density) column else null,
                screen = screen,
                keyboardTop = keyboardTop,
                ceiling = panels.ceilingPx,
                density = density.density,
            )
        },
        onDismiss = panels::closeModelList,
        openedFrom = if (source == Rect.Zero) null else source,
        landsOn = panels.modelSheetLandsOn,
        contentHasKeyboard = panels.modelSearchHoldsKeyboard,
        stretchesUpward = true,
        backdropDim = FusedModelPickerTunables.backdropDim,
    ) {
        FusedModelPickerPanel(
            state = state,
            anchors = anchors,
            isOpen = panels.modelList.isOpen,
            onEvent = onEvent,
            onPickModel = { model, level, rect, ticked -> panels.pickFusedModel(model, level, rect, ticked) },
            onDismiss = panels::closeModelList,
            showsRows = showsRows,
            onSearchHoldsKeyboard = { panels.modelSearchHoldsKeyboard = it },
            detent = panels.modelListDetent,
            onDrag = panels::dragModelSheet,
            onDragEnded = { predicted, velocity -> panels.settleModelSheet(predicted, velocity) },
            onDragCancelled = { panels.settleModelSheet(null, 0.0) },
            onDetent = panels::chooseModelListDetent,
            // The sheet grows only by its grabber: the rows scroll, and a pull down from their top closes it.
            onScrollEdge = { if (it == ModelListScrollEdge.PulledDown) panels.closeModelList() },
        )
    }
    FusedLevelStackOverlay(
        anchors = anchors,
        headerHeight = headerHeight.dp,
        composerFrame = composerFrame,
        onPickLevel = { stack, level, wordRect ->
            when (stack.kind) {
                FusedLevelStackState.SourceKind.Row -> panels.pickFusedModel(stack.model, level, wordRect)
                FusedLevelStackState.SourceKind.Chip -> {
                    // The chip's stack sets the level in place; the list isn't open.
                    store.send(SessionFeature.Action.ModelRowLevelChanged(stack.model, level))
                    panels.closeLevelPanel()
                }
            }
        },
        onDismiss = { stack -> if (stack.kind == FusedLevelStackState.SourceKind.Chip) panels.closeLevelPanel() },
    )
    FusedRowMenuOverlay(
        state = state,
        anchors = anchors,
        composerFrame = composerFrame,
        onEvent = onEvent,
        onPickLevel = { model, level -> panels.pickFusedModel(model, level, null) },
    )
    FusedLabelFlightOverlay(anchors)
}

/**
 * iOS `OnePickerGlassBackdropModifier`: One Picker's surface, mounted over the transcript and
 * under the composer, so the composer's controls and the picker's rows draw over it.
 */
@Composable
internal fun SessionOnePickerBackdrop(host: SessionComposerHost, modifier: Modifier = Modifier) {
    val panels = host.modelPanels ?: return
    if (!panels.usesOnePicker || !panels.canPresentOnePickerMorph) return
    OnePickerGlassBackdrop(panels.onePicker, modifier)
}

/**
 * The composer under a model panel: it fades as One Picker's glass grows out of it (iOS
 * `OnePickerComposerFade`) and is hidden from TalkBack while a panel stands over it (iOS
 * `accessibilityHidden`).
 */
internal fun Modifier.modelPanelsComposer(host: SessionComposerHost): Modifier = composed {
    val hides = host.modelPanels?.hidesComposer == true
    graphicsLayer { alpha = host.modelPanels?.composerOpacity ?: 1f }
        .then(if (hides) Modifier.clearAndSetSemantics {} else Modifier)
}

