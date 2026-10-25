package ai.opencode.feature.session

import ai.opencode.feature.composer.DictationLatency
import ai.opencode.feature.composer.DictationDrag
import ai.opencode.feature.composer.RequestBar
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.composer.AttachmentMenuAnchors
import ai.opencode.feature.composer.AttachmentMenuItem
import ai.opencode.feature.composer.AttachmentMenuPress
import ai.opencode.feature.composer.AutocompleteFlightOverlay
import ai.opencode.feature.composer.AutocompleteMotionController
import ai.opencode.feature.composer.AutocompleteMotionDriver
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.composer.ComposerAutocompletePanel
import ai.opencode.feature.composer.ComposerAutocompleteState
import ai.opencode.feature.composer.ComposerAutocompleteTextBridge
import ai.opencode.feature.composer.ComposerFileSource
import ai.opencode.feature.composer.ComposerImageSource
import ai.opencode.feature.composer.ComposerSwipeDown
import ai.opencode.feature.composer.ComposerSwipeOwnership
import ai.opencode.feature.composer.ComposerTextInput
import ai.opencode.feature.composer.RequestDockContext
import ai.opencode.feature.composer.RequestDockView
import ai.opencode.feature.composer.RequestPanelFit
import ai.opencode.feature.composer.RequestPanelReveal
import ai.opencode.feature.composer.SyncComposerAutocomplete
import ai.opencode.feature.composer.autocompleteComposerAnchor
import ai.opencode.feature.composer.requestPanelCap
import ai.opencode.server.model.PastedText
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.experiments.ExperimentalCallStyle
import ai.opencode.core.experiments.ModelPickerStyle
import ai.opencode.feature.composer.QueuedMessagesSheet
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.feature.voice.CallTrayController
import ai.opencode.feature.voice.CallTrayHost
import ai.opencode.feature.voice.VoiceCallComposer
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceCallSurface
import ai.opencode.feature.voice.VoiceStoreOf
import ai.opencode.feature.voice.hiddenUnderVoiceTranscript
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// Port of iOS `Session/SessionView+Composer.swift`: the composer area (pills, banners, the composer's
// surface, the dictation bar) and its presentation rules. The controls live in
// `SessionViewComposerControls.kt`.

// region Host

/**
 * What the screen around the composer shares with it (iOS reads these from `SessionView`'s other
 * extensions: the transcript's scroll edge, the attachment menu, the model picker's panels).
 * VIEW-MAIN and VIEW-PICKERS set them through [LocalSessionComposerHost]; every field defaults to
 * the plain behaviour, so [SessionComposer] works without a host. State-backed: setting a field
 * recomposes only what reads it.
 */
@Stable
class SessionComposerHost {
    /** iOS `scrollEdgeBridge.showsJumpButton`. */
    var showsJumpButton by mutableStateOf(false)
    /** iOS `scrollEdgeBridge.jumpRequest += 1`. */
    var onJumpToLatest: () -> Unit by mutableStateOf({})
    /** Where [BackgroundWorkPresentation] draws the capsule (iOS `backgroundActivityAnchor()`). */
    var backgroundWorkAnchor: BackgroundWorkAnchor? by mutableStateOf(null)
    /** Catch Up's card: no project list, no swipe-up focus, its own side clearance. */
    var isCatchUpCard by mutableStateOf(false)
    /** iOS `toggleAttachmentMenu` (VIEW-PICKERS); null opens nothing. */
    var onPlusTapped: (() -> Unit)? by mutableStateOf(null)
    /** The attachment menu is open (iOS `attachmentMenu != .closed`): + shows open, the composer stays expanded. */
    var isAttachmentMenuOpen by mutableStateOf(false)
    /** iOS `imageImporter.isLoading || fileImporter.isLoading`. */
    var isImportingAttachments by mutableStateOf(false)
    /** iOS `pendingAttachmentsRow` (VIEW-PICKERS): shown above the field when the composer has attachments. */
    var pendingAttachmentsRow: (@Composable () -> Unit)? by mutableStateOf(null)
    /** Model Picker › Grow from Composer: the chip's level stack and label flight (iOS `modelPickerAnchors`). */
    var modelPickerAnchors: ai.opencode.feature.composer.modelpicker.ModelPickerAnchors? by mutableStateOf(null)
    /** A panel grown out of the composer (model list, level panel) stands over what sits above it. */
    var composerPanelCovers by mutableStateOf(false)
    /** The model list or level panel is shown: the composer stays expanded under it. */
    var keepsComposerExpanded by mutableStateOf(false)
    /** A control outside this file owns downward drags now (iOS `composerSwipeYields`' panels). */
    var swipeYields by mutableStateOf(false)
    /** The screen's imports (iOS `imageImporter`/`fileImporter`): pastes and drops on the field start them. */
    var importer: SessionAttachmentImporter? by mutableStateOf(null)
    /**
     * iOS `scrollEdgeBridge.sendTransition`: the field's words captured as a send leaves, for the
     * screen's send flight (`SessionSendFlightOverlay`).
     */
    val sendTransition = ai.opencode.feature.composer.ComposerSendTransition()
    /** iOS `scrollEdgeBridge.queuedPillFrame`: the queued pill (window px) while it shows. */
    var queuedPillFrame: Rect? = null
    /** iOS `attachmentMenuAnchors`: the + button, the composer, the menu's rows (root px). */
    val attachmentAnchors = AttachmentMenuAnchors()
    /** iOS `attachmentMenuPress`; set by the screen's attachment menu. */
    var attachmentPress: AttachmentMenuPress? by mutableStateOf(null)
    /** The rows a slide from + can reach: the menu's, only while it rests open. */
    var attachmentMenuRows: Map<AttachmentMenuItem, androidx.compose.ui.geometry.Rect> by mutableStateOf(emptyMap())
    /** iOS `chooseAttachment`: a row chosen by sliding from + and releasing on it. */
    var onAttachmentChoose: (AttachmentMenuItem) -> Unit by mutableStateOf({})
    /** iOS `closeAttachmentMenu`: a request docking closes the menu. */
    var closeAttachmentMenu: () -> Unit = {}
    /**
     * The screen's height between the top safe area and the keyboard (or the bottom safe area),
     * and the header's height, both dp: the request panel's cap (iOS `requestPanelInputs.room`, `headerHeight`).
     */
    var availableHeight by mutableFloatStateOf(0f)
    var headerHeight by mutableFloatStateOf(0f)

    /** The message field has the keyboard (iOS `messageFieldFocused`); the field follows it. */
    var messageFieldFocused by mutableStateOf(false)
        internal set
    internal var focusAction: () -> Unit = {}
    internal var dismissAction: () -> Unit = {}

    /** iOS `focusComposer()`. */
    fun focusComposer() = focusAction()

    /** iOS `dismissComposerKeyboard()`. */
    fun dismissComposerKeyboard() = dismissAction()
}

/** The screen's [SessionComposerHost]; null: the composer makes its own. */
val LocalSessionComposerHost = compositionLocalOf<SessionComposerHost?> { null }

// endregion

// region View state

/** iOS `SessionView`'s composer `@State`s, owned by [SessionComposer]. */
@Stable
internal class SessionComposerViewState {
    var showsQueuedMessages by mutableStateOf(false)
    var dictationDrag by mutableStateOf(DictationDrag())
    /** Root px (iOS `dictationRowSpace`). */
    var dictationMicFrame by mutableStateOf(Rect.Zero)
    var dictationBarFrame by mutableStateOf(Rect.Zero)
    var dictationRetention by mutableStateOf(DictationHoldRetention())
    var dictationHoldVisuals by mutableStateOf(DictationHoldVisuals())
    /** The composer's controls as a press began, kept until its recording ends. */
    var heldComposerPresentation by mutableStateOf<ComposerPresentation?>(null)
    var holdGuides: Job? = null
    /** The text field's bounds in root px. */
    var textFieldBounds by mutableStateOf(Rect.Zero)
    var surfaceBounds by mutableStateOf(Rect.Zero)
    val capsuleSwipeOwnership = ComposerSwipeOwnership()
    var handledFocusRequest by mutableStateOf<Pair<Int, Int>?>(null)
    /** iOS `autocompleteController` / `autocompleteBridge`. */
    val autocompleteController = AutocompleteMotionController()
    val autocompleteBridge = ComposerAutocompleteTextBridge()
    /** The composer surface's size (dp), for the autocomplete simulation. */
    var surfaceSize by mutableStateOf(androidx.compose.ui.geometry.Size.Zero)
    /** The composer area's height (dp), for the request panel's cap. */
    var areaHeight by mutableFloatStateOf(0f)
    /** iOS `requestPanelHeight`: the panel's measured height (dp), and what the surface shows of it. */
    var requestPanelHeight by mutableFloatStateOf(0f)
    val requestPanelEasing = RequestPanelFit.Easing()
}

/** iOS `composerPresentation`'s tuple. */
data class ComposerPresentation(val expanded: Boolean, val controls: ComposerControls)

/** What the composer reads from [SessionFeature.State], compared as one value so it recomposes only on change. */
internal data class ComposerSnapshot(
    val draft: String,
    val isDraft: Boolean,
    val isRunning: Boolean,
    val hasPromptContent: Boolean,
    val canSendPrompt: Boolean,
    val hasPendingRequests: Boolean,
    val isUploadingFiles: Boolean,
    val isWaitingForUploads: Boolean,
    val hasFailedFiles: Boolean,
    val hasNonLocationAttachments: Boolean,
    val wantsLocationAttachment: Boolean,
    val dictationHold: DictationFeature.Hold?,
    val dictationPhase: DictationFeature.Phase,
    val isDictating: Boolean,
    val isFinishingDictation: Boolean,
    val isPreparingDictation: Boolean,
    val dictationError: String?,
    val isAnsweringRequest: Boolean,
    val requestBar: RequestBar?,
    val takesPasteAttachments: Boolean,
    val hasSessionOrProject: Boolean,
    val isInterrupting: Boolean,
    val isVoiceAvailable: Boolean,
    val hasCall: Boolean,
    val callOwnsAudio: Boolean,
    val canStartCallFromTap: Boolean,
    val isCovered: Boolean,
    val taskIdentity: SessionFeature.State.TaskIdentity,
    val navigationID: Int,
    val modelLabel: String,
    val modelShortName: String,
    val chipLevelLabel: String?,
    val backgroundAgents: Int,
    val backgroundShells: Int,
    val queuedMessages: List<OpenCodeQueuedInput>,
    val settledRequestLoadError: String?,
    val canChooseProject: Boolean,
    val recentProjects: List<OpenCodeProject>,
    val draftProject: OpenCodeProject?,
    val dockResponding: Boolean,
    val composerFocusRequest: Int,
) {
    val hasBackgroundWork: Boolean get() = backgroundAgents > 0 || backgroundShells > 0

    companion object {
        fun of(state: SessionFeature.State): ComposerSnapshot {
            val model = state.currentModelInfo
            val dock = state.dockRequestID
            return ComposerSnapshot(
                draft = state.draft,
                isDraft = state.isDraft,
                isRunning = state.isRunning,
                hasPromptContent = state.hasPromptContent,
                canSendPrompt = state.canSendPrompt,
                hasPendingRequests = state.hasPendingRequests,
                isUploadingFiles = state.isUploadingFiles,
                isWaitingForUploads = state.isWaitingForUploads,
                hasFailedFiles = state.pendingFiles.any { it.isFailed },
                hasNonLocationAttachments = state.pendingAttachments.isNotEmpty() || state.pendingFiles.isNotEmpty(),
                wantsLocationAttachment = state.pendingLocation != null || state.isLocating,
                dictationHold = state.dictationHold,
                dictationPhase = state.dictationPhase,
                isDictating = state.isDictating,
                isFinishingDictation = state.isFinishingDictation,
                isPreparingDictation = state.isPreparingDictation,
                dictationError = state.dictationError,
                isAnsweringRequest = state.isAnsweringRequest,
                requestBar = state.requestBar,
                takesPasteAttachments = state.takesPasteAttachments,
                hasSessionOrProject = state.session != null || state.draftProject != null,
                isInterrupting = state.isInterrupting,
                isVoiceAvailable = state.isVoiceAvailable,
                hasCall = state.voice != null,
                callOwnsAudio = state.callOwnsAudio,
                canStartCallFromTap = state.canStartCallFromTap,
                isCovered = state.isCovered,
                taskIdentity = state.taskIdentity,
                navigationID = state.navigationID,
                modelLabel = state.currentModelLabel,
                modelShortName = model?.shortName ?: state.currentModelName,
                chipLevelLabel = chipLevelLabel(state),
                backgroundAgents = state.activeSubagents.size,
                backgroundShells = state.backgroundShells.size,
                queuedMessages = state.queuedInputRows.filter { it.waitsInQueuedPill },
                settledRequestLoadError = state.settledRequestLoadError,
                canChooseProject = state.canChooseProject,
                recentProjects = state.recentProjects,
                draftProject = state.draftProject,
                dockResponding = dock != null && dock in state.respondingRequestIDs,
                composerFocusRequest = state.composerFocusRequest,
            )
        }
    }
}

// endregion

/**
 * iOS `composerArea`: the pill row (background work, queued messages, jump to latest), the request
 * load error banner, a draft's recent projects (an overlay above, never moving the transcript's
 * insets) and the composer itself, with its dictation bar.
 *
 * Embedded by `SessionView` at the bottom of the screen; it applies its own side margins, top 6 dp
 * and bottom gap (keyboard gap when focused). The screen supplies IME and navigation-bar insets.
 */
@Composable
fun SessionComposer(
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    modifier: Modifier = Modifier,
    callStore: VoiceStoreOf? = null,
    callTray: CallTrayController? = null,
) {
    val host = LocalSessionComposerHost.current ?: remember { SessionComposerHost() }
    val view = remember { SessionComposerViewState() }
    val snapshot by store.observe { ComposerSnapshot.of(it) }
    val returnKeySends by remember(store) { store.state.returnKeySends.flow }.collectAsState(store.state.returnKeySends.value)
    val modelPickerStyle by remember { shared(ModelPickerStyle.key).flow }.collectAsState(shared(ModelPickerStyle.key).value)
    val agentSelection by remember { shared(ExperimentalFeature.agentSelection.key).flow }
        .collectAsState(false)
    val autocompleteSetting by remember { shared(ExperimentalFeature.composerAutocomplete.key).flow }.collectAsState(false)
    val usesAutocomplete = ExperimentalFeature.composerAutocomplete.isActive(autocompleteSetting)
    val autocomplete by store.observe { it.autocomplete }
    val reducedMotion = rememberReduceMotion()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val callPresentation = rememberCallPresentation(callTray)
    val floatsCall = callPresentation == ExperimentalCallStyle.floating
    val usesCallTray = callPresentation == ExperimentalCallStyle.tray
    val callShowsCard by remember(callStore) { callStore?.let { call -> call.states.map { it.phase.showsCard } } ?: flowOf(false) }
        .collectAsState(callStore?.state?.phase?.showsCard ?: false)
    val callShowsCaptions by remember(callStore) { callStore?.let { call -> call.states.map { it.showsCaptions } } ?: flowOf(false) }
        .collectAsState(callStore?.state?.showsCaptions ?: false)

    val env = SessionComposerEnv(
        store = store,
        host = host,
        view = view,
        s = snapshot,
        reducedMotion = reducedMotion,
        returnKeySends = returnKeySends,
        modelPickerStyle = ModelPickerStyle.active(modelPickerStyle),
        agentSelection = ExperimentalFeature.agentSelection.isActive(agentSelection),
        usesAutocomplete = usesAutocomplete,
        autocomplete = autocomplete,
        focus = {
            // The field follows `messageFieldFocused` (iOS `focusComposer()`); the keyboard comes up even if it already had focus.
            host.messageFieldFocused = true
            keyboard?.show()
        },
        dismissKeyboard = {
            host.messageFieldFocused = false
            focusManager.clearFocus()
            keyboard?.hide()
        },
        scope = scope,
        callStore = callStore,
        // iOS `voiceStore`: the bar takes the composer's place unless the call floats, rides the
        // Call Tray, or shows a card.
        voiceStore = if (floatsCall || usesCallTray || callShowsCard) null else callStore,
        floatsCall = floatsCall,
        callShowsCaptions = callShowsCaptions,
        callTray = if (usesCallTray) callTray else null,
        callOwnsAudio = callStore != null && !callShowsCard,
    )

    // iOS `attachComposerAutocomplete`: the controller follows the reducer's state and the composer's size.
    AutocompleteMotionDriver(view.autocompleteController)
    SyncComposerAutocomplete(
        state = autocomplete,
        isEnabled = usesAutocomplete,
        controller = view.autocompleteController,
        bridge = view.autocompleteBridge,
        composerWidth = view.surfaceSize.width.toDouble(),
        composerHeight = view.surfaceSize.height.toDouble(),
        reducedMotion = reducedMotion,
        send = { store.send(Action.Autocomplete(it)) },
    )
    host.focusAction = env.focus
    host.dismissAction = env.dismissKeyboard

    // A share or quote continued in OpenCode lands with the keyboard up (iOS `composerFocusRequest`,
    // handled here because the composer owns the field). Catch Up's cards never take it.
    LaunchedEffect(snapshot.navigationID, snapshot.composerFocusRequest) {
        val request = snapshot.navigationID to snapshot.composerFocusRequest
        if (host.isCatchUpCard || snapshot.composerFocusRequest <= 0 || request == view.handledFocusRequest) return@LaunchedEffect
        view.handledFocusRequest = request
        env.focus()
    }
    // iOS `resetDictationPresentation()` and the hold's `onChange`.
    val recordingNow = snapshot.isPreparingDictation || snapshot.isDictating || snapshot.dictationHold != null
    LaunchedEffect(snapshot.taskIdentity) {
        view.dictationRetention = view.dictationRetention.syncIdentity(snapshot.taskIdentity, snapshot.dictationHold, recordingNow)
        if (snapshot.dictationHold == null) {
            view.dictationHoldVisuals = DictationHoldVisuals()
            view.heldComposerPresentation = null
        }
    }
    LaunchedEffect(snapshot.dictationHold) {
        val hold = snapshot.dictationHold
        view.dictationRetention = view.dictationRetention.holdChanged(hold, snapshot.taskIdentity, recordingNow)
        if (hold == null) {
            view.dictationHoldVisuals = DictationHoldVisuals()
            view.heldComposerPresentation = null
        }
    }
    // The leaving bar is removed once its fade has finished.
    val leaving = snapshot.dictationHold == null && view.dictationRetention.leavingHold(snapshot.taskIdentity) != null
    LaunchedEffect(snapshot.taskIdentity, leaving) {
        if (!leaving) return@LaunchedEffect
        delay(DictationBarPresenceDuration)
        if (store.state.dictationHold == null) view.dictationRetention = view.dictationRetention.copy(leavingHold = null)
    }
    // The fold takes 0.28 s (0.2 s under Reduce Motion): controls hidden under the bar leave once it settles.
    val recording = snapshot.dictationHold != null
    LaunchedEffect(snapshot.taskIdentity, recording) {
        val settled = view.dictationRetention.settledRecording(recording, snapshot.taskIdentity)
        if (recording == settled) return@LaunchedEffect
        view.setSettledRecording(null, snapshot)
        delay(320)
        view.setSettledRecording(recording, snapshot)
    }

    env.ComposerArea(modifier)

    if (view.showsQueuedMessages) {
        QueuedMessagesSheet(
            messages = snapshot.queuedMessages,
            onSendNow = { store.send(Action.PendingInputSendNowTapped(it)) },
            onCopy = { store.send(Action.CopyTextTapped(it)) },
            onCancel = { store.send(Action.PendingInputCancelTapped(it)) },
            onClose = { view.showsQueuedMessages = false },
            // A call's delegation shows the voice agent's words, as the transcript does.
            displayText = { ai.opencode.feature.transcript.user.VoiceHandoff.parse(it)?.displayText ?: it },
        )
    }
}

/**
 * iOS `currentChipLevelLabel` (`ModelPickerSections.swift`): the level word (“High”, “Default”), or
 * null when the current model has no adjustable variants. Computed here so the composer doesn't
 * depend on the picker's level scale.
 */
private fun chipLevelLabel(state: SessionFeature.State): String? {
    val model = state.currentModelInfo ?: return null
    if (model.variants.none { it != "default" }) return null
    val level = state.currentModel?.variant?.takeIf { it != "default" } ?: return "Default"
    return ai.opencode.server.model.ModelVariant.label(level)
}

internal fun SessionComposerViewState.setSettledRecording(value: Boolean?, s: ComposerSnapshot) {
    val recording = s.isPreparingDictation || s.isDictating || s.dictationHold != null
    dictationRetention = dictationRetention.syncIdentity(s.taskIdentity, s.dictationHold, recording).copy(settledRecording = value)
}

/** Everything the composer's parts read, gathered once per composition (iOS `SessionView`'s members). */
internal class SessionComposerEnv(
    val store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    val host: SessionComposerHost,
    val view: SessionComposerViewState,
    val s: ComposerSnapshot,
    val reducedMotion: Boolean,
    val returnKeySends: Boolean,
    val modelPickerStyle: ModelPickerStyle,
    val agentSelection: Boolean,
    val usesAutocomplete: Boolean,
    val autocomplete: ComposerAutocompleteState,
    val focus: () -> Unit,
    val dismissKeyboard: () -> Unit,
    val scope: kotlinx.coroutines.CoroutineScope,
    /** iOS `callStore`: the screen's call, or its root's. */
    val callStore: VoiceStoreOf? = null,
    /** iOS `voiceStore`: the call whose bar stands in the composer's place. */
    val voiceStore: VoiceStoreOf? = null,
    /** iOS `floatsCall`. */
    val floatsCall: Boolean = false,
    /** The call shows its transcript (iOS `callStore?.showsCaptions`). */
    val callShowsCaptions: Boolean = false,
    /** iOS `usesCallTray ? callTray : nil`: Home's Call Tray motion while the call style is the tray. */
    val callTray: CallTrayController? = null,
    /** iOS `callOwnsAudio`: the screen's call (its own or its root's) owns audio. */
    val callOwnsAudio: Boolean = false,
) {
    /** iOS `usesCallTray` (`callPresentation == .tray`). */
    val usesCallTray: Boolean get() = callTray != null

    /** iOS `holdsCallTrayButton`: the tray's disc holds the call button's place. */
    val holdsCallTrayButton: Boolean get() = usesCallTray && !s.isCovered && callTray?.holdsCallButton == true

    /**
     * iOS `showsCallTranscript`: the call's open transcript covers what stands above the composer.
     * With the tray, a request's panel holding the composer keeps it shut.
     */
    val showsCallTranscript: Boolean
        get() = callShowsCaptions && (!usesCallTray || (!docksRequest && view.requestPanelHeight <= 0f))

    /** iOS `hasCall`: the screen's own call or its root's. */
    val hasCall: Boolean get() = s.hasCall || callStore != null

    val messageFieldFocused: Boolean get() = host.messageFieldFocused

    /** iOS `settledRecording`. */
    val settledRecording: Boolean? get() = view.dictationRetention.settledRecording(s.dictationHold != null, s.taskIdentity)

    /** The recording's hold, or the last one while its bar fades out. */
    val shownDictationHold: DictationFeature.Hold? get() = view.dictationRetention.shownHold(s.dictationHold, s.taskIdentity)

    fun dictationPose(hold: DictationFeature.Hold) = DictationBarPose(
        hold = hold,
        phase = s.dictationPhase,
        drag = view.dictationDrag,
        reducedMotion = reducedMotion,
        visuals = view.dictationHoldVisuals,
    )

    /** iOS `canSend`: mid-run sends steer; Send stays available while files upload. */
    val canSend: Boolean get() = s.hasPromptContent && !s.hasFailedFiles

    private val hasComposerAttachments: Boolean get() = s.hasNonLocationAttachments || s.wantsLocationAttachment

    /** iOS `docksRequest`: answering a request, its panel grows out of the composer (never under a call). */
    val docksRequest: Boolean get() = voiceStore == null && s.isAnsweringRequest

    /** iOS `showsVoiceControl`: Voice is set up and no call is running. */
    val showsVoiceControl: Boolean get() = s.isVoiceAvailable && !hasCall

    /** iOS `holdingStopStartsCall`: mid-run, holding Stop starts the call the empty composer would offer. */
    val holdingStopStartsCall: Boolean get() = showsVoiceControl && !s.hasPromptContent && s.isRunning

    /** iOS `composerPresentation`. */
    val composerPresentation: ComposerPresentation
        get() {
            // A held mic keeps the composer exactly as it was: the touch belongs to the mic button,
            // which must stay mounted in place until it lifts.
            val holding = s.dictationHold != null
            val held = view.heldComposerPresentation
            if (holding && held != null) return held
            val dictationActive = (s.isDictating || s.isFinishingDictation) && !holding
            // Answering a request, the field stays on the control row; a draft keeps its control
            // row showing, so the model is at hand.
            val expanded = !s.isAnsweringRequest && (
                s.isDraft || messageFieldFocused || dictationActive || s.draft.isNotEmpty() ||
                    hasComposerAttachments || s.dictationError != null || host.keepsComposerExpanded || host.isAttachmentMenuOpen
                )
            val controls = ComposerControls.make(
                isDictating = s.isDictating && !holding,
                isFinishing = s.isFinishingDictation && !holding,
                isRunning = s.isRunning,
                canSend = canSend,
                expanded = expanded,
                canCall = (showsVoiceControl || (usesCallTray && (callOwnsAudio || holdsCallTrayButton))) && !s.hasPromptContent,
            )
            // With the Call Tray, the mic stays in its slot and animates out and back in with
            // `CallTrayController.composerMicLook`.
            if (usesCallTray && (callOwnsAudio || holdsCallTrayButton) && controls.trailing == ComposerTrailingControl.Mic) {
                return ComposerPresentation(
                    expanded,
                    controls.copy(
                        trailing = ComposerTrailingControl.Send(enabled = false),
                        secondaryMic = if (expanded) ComposerControls.MicPlacement.leading else ComposerControls.MicPlacement.trailing,
                    ),
                )
            }
            return ComposerPresentation(expanded, controls)
        }

    /** iOS `sendMessage(_:)`. */
    fun sendMessage(action: Action = Action.SendTapped) {
        if (action == Action.ReturnKeyTapped && !canSend) {
            store.send(action)
            return
        }
        val state = store.state
        if (!state.canSendPrompt || state.hasPendingRequests) return
        // A send that waits for uploads lands later; the flight would capture now.
        val flies = !state.isUploadingFiles && (state.session != null || state.draftProject != null)
        if (flies) host.sendTransition.prepare(reduceMotion = reducedMotion)
        // The list lands it: on its bubble, its dim steering row, or the pill.
        val generation = state.sendGeneration
        store.send(action)
        // Nothing went (a failed upload): the capture mustn't linger.
        if (flies && store.state.sendGeneration == generation) host.sendTransition.cancelPrepared()
    }

    /** iOS `showsDraftProjects`: a draft lists its recent projects above the composer until its first send leaves. */
    val showsDraftProjects: Boolean get() = s.canChooseProject && !host.isCatchUpCard

    /** iOS `isComposingDraft`: composing, the list folds down to the chosen project. */
    val isComposingDraft: Boolean
        get() {
            if (s.hasCall) return false
            return messageFieldFocused || s.hasPromptContent || s.dictationHold != null || s.isDictating ||
                s.isFinishingDictation || host.isAttachmentMenuOpen || host.keepsComposerExpanded
        }

    /** iOS `composerSwipeYields(from:)`: a touch another control owns. */
    fun composerSwipeYields(start: Offset?): Boolean {
        if (Companion.composerSwipeYields(dictationHold = s.dictationHold, attachmentMenuOpen = host.isAttachmentMenuOpen, panelsShown = host.swipeYields)) return true
        if (start == null) return false
        val mic = view.dictationMicFrame
        return mic != Rect.Zero && mic.inflate(4f).contains(start)
    }

    /** iOS `composerSwipeUpBandContains(_:)` (root px). */
    fun composerSwipeUpBandContains(point: Offset): Boolean {
        val rect = view.surfaceBounds
        if (rect == Rect.Zero || !rect.contains(point) || composerSwipeYields(point)) return false
        val text = view.textFieldBounds
        if (text != Rect.Zero) {
            if (composerPresentation.expanded && s.requestBar == null) return point.y >= text.top && point.y <= text.bottom
            return point.x >= text.left && point.x <= text.right
        }
        return false
    }

    /** iOS `focusComposerFromSwipe()` (`performSwipeUpFocus`). */
    fun focusComposerFromSwipe() {
        if (messageFieldFocused || host.isCatchUpCard || composerSwipeYields(null) || s.isPreparingDictation || s.isDictating) return
        store.send(Action.ComposerSwipedUp)
    }

    /** iOS `beginDictationHold()`: a new recording's hold starts from nothing, before its bar mounts. */
    fun beginDictationHold() {
        DictationLatency.mark("press_began")
        val hold = store.state.dictationHold
        if (hold == null) view.heldComposerPresentation = composerPresentation
        if (hold == null || store.state.isFinishingDictation) view.dictationHoldVisuals = DictationHoldVisuals()
        store.send(Action.micPressBegan)
        // Counted from the touch: a hold grows the circle and offers the lock target about 0.1 s
        // in, and a quick tap usually locks before then and never shows them.
        view.holdGuides?.cancel()
        view.holdGuides = scope.launch {
            delay(90)
            val current = store.state.dictationHold ?: return@launch
            if (current.isLocked || store.state.isFinishingDictation) return@launch
            view.dictationHoldVisuals = view.dictationHoldVisuals.copy(showsGuides = true)
        }
    }

    /** The row has faded out under the dictation bar. */
    val foldsComposerControls: Boolean get() = s.dictationHold != null && settledRecording == true

    /** A held mic owns its finger until it lifts; then it folds like the rest. */
    val foldsHeldMic: Boolean
        get() {
            val hold = s.dictationHold ?: return false
            return foldsComposerControls && (hold.isLocked || s.isFinishingDictation)
        }

    companion object {
        /**
         * iOS `SessionView.composerSwipeYields(...)`: a touch another control owns (a held mic, the
         * attachment menu, a panel grown out of the composer): a downward drag there is that
         * control's, never the keyboard's dismissal.
         */
        fun composerSwipeYields(
            dictationHold: DictationFeature.Hold? = null,
            attachmentMenuOpen: Boolean = false,
            panelsShown: Boolean = false,
        ): Boolean = dictationHold != null || attachmentMenuOpen || panelsShown
    }
}

// region Area

@Composable
private fun SessionComposerEnv.ComposerArea(modifier: Modifier) {
    val pillGap = ComposerTunables.pillGap
    val focused = messageFieldFocused
    val horizontalMargin = ComposerTunables.horizontalMargin
    val bottom by androidx.compose.animation.core.animateDpAsState(
        (if (focused) ComposerTunables.keyboardGap else ComposerTunables.bottomGap).dp,
        if (reducedMotion) tween(0) else MotionSpring(0.25).spec(),
        label = "composerBottomGap",
    )
    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { view.areaHeight = with(density) { it.height.toDp().value } }
            .padding(horizontal = horizontalMargin.dp)
            .padding(top = 6.dp, bottom = bottom),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(pillGap.dp)) {
            HiddenUnderComposerPanel(host.composerPanelCovers, Modifier.hiddenUnderVoiceTranscript(showsCallTranscript)) { ComposerPills() }
            val error = s.settledRequestLoadError
            if (error != null) {
                HiddenUnderComposerPanel(host.composerPanelCovers, Modifier.hiddenUnderVoiceTranscript(showsCallTranscript)) {
                    ComposerBanner(
                        symbol = SFSymbol.ExclamationmarkTriangleFill,
                        iconTint = androidx.compose.ui.graphics.Color(0xFFFF9500),
                        title = "Couldn’t Load Requests",
                        detail = error,
                        action = "Retry",
                        onClick = { store.send(Action.refreshRequestsTapped) },
                        modifier = Modifier.semantics { testTag = "request-load-error" },
                    )
                }
            }
            ComposerCapsule()
        }
        // Without pills, the jump button floats where their row would be, so showing it never
        // changes the transcript's insets.
        if (!s.hasBackgroundWork && s.queuedMessages.isEmpty()) {
            HiddenUnderComposerPanel(
                host.composerPanelCovers,
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = -(48 + pillGap).dp),
            ) {
                // The call's open transcript covers where it would float.
                JumpToLatestPill(isShown = host.showsJumpButton && !showsCallTranscript, onClick = host.onJumpToLatest)
            }
        }
        // A draft's recent projects stand above the composer as an overlay, so folding them, or
        // their leaving on the first send, never changes the transcript's insets.
        DraftProjectsOverlay(Modifier.align(Alignment.TopCenter))
    }
}

/** iOS `hiddenUnderComposerPanel(_:)`: a panel grown out of the composer stands over this. */
@Composable
private fun HiddenUnderComposerPanel(covered: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (covered) 0f else 1f, Motion.easeOut(Motion.fadeFast), label = "underPanel")
    Box(modifier.alpha(alpha).then(if (covered) Modifier.clearAndSetSemantics { }.blockTouches() else Modifier)) { content() }
}

@Composable
private fun SessionComposerEnv.DraftProjectsOverlay(modifier: Modifier) {
    // The list leaving (smooth 0.38 s), and folding down to its chosen row and back.
    val enter = if (reducedMotion) fadeIn(Motion.easeInOut(0.2)) else fadeIn(MotionSpring(0.38).spec())
    val exit = if (reducedMotion) fadeOut(Motion.easeInOut(0.2)) else fadeOut(MotionSpring(0.38).spec())
    // A zero-height edge at the area's top that the list stands on, a stack's spacing above.
    Box(
        modifier
            .fillMaxWidth()
            .height(0.dp)
            .wrapContentHeight(Alignment.Bottom, unbounded = true)
            .offset(y = -ComposerTunables.pillGap.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible = showsDraftProjects, enter = enter, exit = exit) {
            HiddenUnderComposerPanel(host.composerPanelCovers, Modifier.hiddenUnderVoiceTranscript(showsCallTranscript)) {
                DraftProjectList(
                    identity = s.taskIdentity,
                    projects = s.recentProjects,
                    chosen = s.draftProject,
                    isComposing = isComposingDraft,
                    onPick = { store.send(Action.DraftProjectTapped(it)) },
                    onAll = { store.send(Action.ProjectButtonTapped) },
                )
            }
        }
    }
}

// endregion

// region Pills

/**
 * iOS `composerPillStack`: one row (background work on the left; queued messages and the jump
 * button on the right), stacking only when the row can't fit (large text).
 */
@Composable
private fun SessionComposerEnv.ComposerPills() {
    val hasActivity = s.hasBackgroundWork
    val hasQueued = s.queuedMessages.isNotEmpty()
    if (!hasActivity && !hasQueued) return
    val fontScale = LocalDensity.current.fontScale
    // iOS `ViewThatFits`: the row unless large text makes it overflow.
    val stacks = fontScale >= 1.8f
    val showsJump = host.showsJumpButton
    if (!stacks) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (hasActivity) BackgroundActivity()
            Spacer(Modifier.weight(1f))
            // Beside the queued pill the jump slot carries its own gap, so the queued pill slides over as it closes.
            Row(verticalAlignment = Alignment.CenterVertically) {
                QueuedActivity(hasQueued)
                JumpToLatestPill(isShown = showsJump, onClick = host.onJumpToLatest, collapsesSlot = hasQueued)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasActivity) Box(Modifier.fillMaxWidth()) { BackgroundActivity() }
            if (hasQueued || showsJump) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QueuedActivity(hasQueued)
                    JumpToLatestPill(isShown = showsJump, onClick = host.onJumpToLatest, collapsesSlot = hasQueued)
                }
            }
        }
    }
}

/** iOS `backgroundActivity`: the capsule's place in the pill row; [BackgroundWorkPresentation] draws it. */
@Composable
private fun SessionComposerEnv.BackgroundActivity() {
    val anchor = host.backgroundWorkAnchor
    BackgroundWorkCapsuleSpace(
        agents = s.backgroundAgents,
        shells = s.backgroundShells,
        modifier = if (anchor != null) Modifier.backgroundActivityAnchor(anchor) else Modifier,
    )
}

/** iOS `queuedActivity`: delivered or sent now, a message lifts out toward the transcript. */
@Composable
private fun SessionComposerEnv.QueuedActivity(hasQueued: Boolean) {
    val count = s.queuedMessages.size
    DisposableEffect(Unit) { onDispose { host.queuedPillFrame = null } }
    AnimatedVisibility(
        visible = hasQueued,
        enter = if (reducedMotion) fadeIn(Motion.easeInOut(0.2)) else scaleIn(MotionSpring(0.35, 0.15).spec(), initialScale = 0.92f) + fadeIn(MotionSpring(0.35, 0.15).spec()),
        exit = if (reducedMotion) fadeOut(Motion.easeInOut(0.2)) else slideOutVertically(MotionSpring(0.35, 0.15).spec()) { -it } + fadeOut(MotionSpring(0.35, 0.15).spec()),
    ) {
        ComposerPillLabel(
            title = "$count queued",
            rolls = true,
            onClick = { view.showsQueuedMessages = true },
            modifier = Modifier
                // A queued send's words shrink into it (iOS `scrollEdgeBridge.queuedPillFrame`).
                .onGloballyPositioned { host.queuedPillFrame = it.boundsInWindow() }
                .coveredByBackgroundWork()
                .semantics {
                    contentDescription = if (count == 1) "1 queued message" else "$count queued messages"
                    testTag = "queued-messages"
                },
        ) {
            OpenCodeIcon(SFSymbol.TextBubble, contentDescription = null, size = 17.dp, modifier = Modifier.offset(y = ComposerTunables.queuedGlyphDrop.dp))
        }
    }
}

// endregion

// region Capsule

/**
 * iOS `composerCapsule`: the composer on its surface, the swipes that raise or dismiss the
 * keyboard, and the hold layer above it. Android: the surface is an M3 tonal container.
 */
@Composable
private fun SessionComposerEnv.ComposerCapsule() {
    // A pending request's panel grows up out of the composer on its surface; with Composer
    // Autocomplete on (and no panel or call), the suggestions grow there instead.
    val showsAutocomplete = usesAutocomplete && !docksRequest && view.requestPanelHeight <= 0f && voiceStore == null
    // The composer and a call share one place: starting or ending a call swaps what stands there,
    // + becoming Mute and the call button End, and the call's transcript grows out of its surface.
    // iOS `.callTrayHost(…)`: in the Call Tray style the composer stays and the tray splits out
    // beneath it. Android draws the composer's own surface, so the tray never merges it.
    CallTrayHost(
        controller = callTray,
        store = callStore,
        isEnabled = usesCallTray && !s.isCovered,
        isFocused = messageFieldFocused,
        reducedMotion = reducedMotion,
        allowsMergedComposerGlass = false,
        isRequestDocked = docksRequest || view.requestPanelHeight > 0f,
        onExpandTranscript = {
            host.closeAttachmentMenu()
            host.modelPickerAnchors?.resetFusedState()
        },
    ) {
        AnimatedContent(
            targetState = voiceStore,
            contentKey = { it != null },
            transitionSpec = {
                val spec: androidx.compose.animation.core.FiniteAnimationSpec<Float> = if (reducedMotion) Motion.easeInOut(0.2) else MotionSpring(0.32).spec()
                fadeIn(spec) togetherWith fadeOut(spec) using SizeTransform(clip = false)
            },
            label = "composerCallSwap",
        ) { call ->
            if (call != null) {
                VoiceCallSurface(call, Modifier.fillMaxWidth()) { VoiceCallComposer(call) }
            } else {
                ComposerSurface(showsAutocomplete)
            }
        }
    }
}

/** The composer's own surface: a request's panel, autocomplete, the composer and the dictation hold layer. */
@Composable
private fun SessionComposerEnv.ComposerSurface(showsAutocomplete: Boolean) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(this)
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    view.surfaceBounds = it.boundsInRoot()
                    host.attachmentAnchors.composer = view.surfaceBounds
                }
                .composerSwipes({ current }, density.density)
                .semantics { testTag = "session-composer" },
            shape = RoundedCornerShape(ComposerTunables.cornerRadius.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                RequestPanel()
                if (showsAutocomplete) {
                    ComposerAutocompletePanel(
                        controller = view.autocompleteController,
                        onSelectRow = { store.send(Action.Autocomplete(ComposerAutocompleteAction.RowChosen(it))) },
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .autocompleteComposerAnchor(view.autocompleteBridge)
                        .onSizeChanged { size ->
                            view.surfaceSize = with(density) { androidx.compose.ui.geometry.Size(size.width.toDp().value, size.height.toDp().value) }
                        },
                ) { Composer() }
            }
        }
        if (usesAutocomplete && view.autocompleteController.currentFrame.flights.isNotEmpty()) {
            AutocompleteFlightOverlay(view.autocompleteController, Modifier.matchParentSize())
        }
        // Above the surface, so its edge never crosses the hold circle.
        val hold = shownDictationHold
        if (hold != null) {
            DictationBarPresence(isLeaving = s.dictationHold == null, modifier = Modifier.matchParentSize()) {
                DictationHoldLayer(
                    pose = dictationPose(hold),
                    micFrame = view.dictationMicFrame,
                    barFrame = view.dictationBarFrame,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * iOS `requestPanel(isShown:)` with the room the session keeps for it: the docked request's
 * [RequestDockView], capped so the composer stays under the header ([RequestPanelFit]), on a
 * band of the composer's surface that grows with the panel's measured height and folds back
 * when it leaves (after its content has faded, `RequestPanelReveal.glass`). Changes the cap
 * made (the keyboard, the room) follow at once; the panel's own content changes ease.
 */
@Composable
private fun SessionComposerEnv.RequestPanel() {
    val docks = docksRequest
    val dock by store.observe { it.requestDock }
    val context by store.observe { state ->
        if (!state.isAnsweringRequest && state.dockRequestID == null) {
            RequestDockContext(currentSessionID = state.session?.id, draftBecameSessionID = state.draftBecameSessionID, isVisible = state.isVisible)
        } else {
            RequestDockContext(
                currentSessionID = state.session?.id,
                draftBecameSessionID = state.draftBecameSessionID,
                familySessions = state.familySessions,
                transcripts = listOf(state.items) + state.familyTranscriptCache.values,
                isVisible = state.isVisible,
                isDictating = state.hasLiveDictation || state.dictationHold != null,
            )
        }
    }
    val chrome = RequestPanelFit.chrome(composer = view.areaHeight, capsule = view.surfaceBounds.height / LocalDensity.current.density, bar = view.surfaceSize.height)
    val cap = RequestPanelFit.panelCap(room = host.availableHeight, header = host.headerHeight, chrome = chrome)
    val room = RequestPanelFit.room(measured = if (docks) view.requestPanelHeight else 0f, cap = cap)
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    var opened by remember { mutableStateOf(false) }

    // The keyboard never carries over: the message's into an answer, or an answer's back into the message.
    var lastDocks by remember { mutableStateOf(docks) }
    LaunchedEffect(docks) {
        if (lastDocks == docks) return@LaunchedEffect
        lastDocks = docks
        host.messageFieldFocused = false
        if (docks) host.closeAttachmentMenu()
        if (docks && usesCallTray && callStore?.state?.showsCaptions == true) callStore.send(VoiceFeature.Action.CaptionsDismissed)
    }
    LaunchedEffect(room, docks) {
        val eases = view.requestPanelEasing.eases(room, cap, keyboardUp = messageFieldFocused)
        when {
            reducedMotion || (!eases && docks && opened) -> shown.snapTo(room)
            docks && !opened -> {
                opened = room > 0f
                shown.animateTo(room, RequestPanelReveal.glass(opening = true))
            }
            docks -> shown.animateTo(room, MotionSpring(0.3).spec())
            else -> {
                opened = false
                // Folded and faded: the band goes once the content has sunk away.
                shown.animateTo(0f, RequestPanelReveal.glass(opening = false))
            }
        }
    }
    if (shown.value <= 0.5f && !docks && dock.dockRequestID == null) {
        // Nothing lingers: unmounted so a leaving panel can't keep the room.
        if (view.requestPanelHeight != 0f) SideEffect { view.requestPanelHeight = 0f }
        return
    }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(shown.value.dp)
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .requestPanelCap(cap?.dp)
                .onSizeChanged { size ->
                    // A leaving panel keeps its height while the surface folds over it.
                    val height = with(density) { size.height.toDp().value }
                    if (docks && store.state.dockRequestID != null && height > 0f) view.requestPanelHeight = height
                },
        ) {
            RequestDockView(
                dock = dock,
                context = context,
                isKeyboardUp = messageFieldFocused,
                send = { store.send(Action.RequestDock(it)) },
            )
        }
    }
}

/**
 * The composer's swipes (iOS's two `simultaneousGesture(DragGesture)`s): a downward drag that began
 * focused dismisses the keyboard; an upward one over the field's band while unfocused raises it.
 * Read in the final pass, so controls that consumed the touch (a held mic, +) keep it.
 */
private fun Modifier.composerSwipes(envNow: () -> SessionComposerEnv, density: Float): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val env = envNow()
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val ownership = env.view.capsuleSwipeOwnership
        ownership.ended()
        val origin = env.view.surfaceBounds.topLeft
        val start = down.position + origin
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var acted = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val translation = (change.position - down.position) / density
                if (!acted && abs(translation.x) + abs(translation.y) >= 10f) {
                    val velocity = tracker.calculateVelocity()
                    // UIKit's projection at the normal deceleration rate (≈ v × 0.5 s).
                    val predicted = translation + Offset(velocity.x, velocity.y) / density * 0.5f
                    val focused = env.messageFieldFocused
                    val beganFocused = ownership.beganFocused(start, focused)
                    val yields = ownership.yields(start, focused) { env.composerSwipeYields(start) || change.isConsumed }
                    if (beganFocused) {
                        if (ComposerSwipeDown.shouldDismissKeyboard(translation, predicted, isFocused = focused, isHoldingMic = yields)) {
                            acted = true
                            env.dismissKeyboard()
                        }
                    } else if (env.composerSwipeUpBandContains(start) &&
                        // Over the field itself, the field's own swipe raises the keyboard.
                        !env.view.textFieldBounds.contains(start) &&
                        ComposerSwipeDown.shouldFocusKeyboard(
                            translation,
                            predicted,
                            isFocused = focused,
                            isHoldingMic = yields,
                            minimumDistance = ComposerTunables.swipeUpDistance,
                            minimumFlickVelocity = ComposerTunables.swipeUpVelocity,
                        )
                    ) {
                        acted = true
                        env.focusComposerFromSwipe()
                    }
                }
                if (!change.pressed) break
            }
        } finally {
            ownership.ended()
        }
    }
}

// endregion

// region Composer

/** iOS `composer`: the fold layout of attachments, the field and its controls, and status lines. */
@Composable
private fun SessionComposerEnv.Composer() {
    val (composerExpanded, composerControls) = composerPresentation
    val recording = s.dictationHold != null
    val bar = s.requestBar
    val density = LocalDensity.current
    // Dictating folds the composer to its control row: the edge glides down over what's above it.
    val fold by animateFloatAsState(
        if (recording) 1f else 0f,
        // Under Reduce Motion the height changes at once and only opacity fades.
        if (reducedMotion) tween(0) else MotionSpring(0.28).spec(),
        label = "composerFold",
    )
    val rowAlpha by animateFloatAsState(
        if (recording) 0f else 1f,
        if (recording) Motion.easeOut(Motion.fadeFast, reduceMotion = reducedMotion)
        else Motion.easeOut(Motion.fadeStandard, if (reducedMotion) 0.0 else Motion.contentDelay, reducedMotion),
        label = "composerRowFade",
    )
    val bodyLine = with(density) { OpenCodeTextStyles.body.lineHeight.toDp().value }
    val top = if (composerExpanded && !recording) 0f else ComposerTunables.innerVertical
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ComposerTunables.cornerRadius.dp))
            .pointerInput(recording) {
                // Folded, the field is out of sight: a tap beside the bar mustn't raise the keyboard.
                detectTapGestures {
                    if (!recording) focus()
                }
            }
            .then(if (reducedMotion) Modifier else Modifier.animateContentSize(MotionSpring(0.25).spec()))
            .padding(horizontal = ComposerTunables.innerHorizontal.dp)
            .padding(top = top.dp, bottom = ComposerTunables.innerVertical.dp)
            .semantics {
                // Answering, escape from the bar's controls dismisses as the panel's × does.
                if (s.isAnsweringRequest) {
                    customActions = listOf(CustomAccessibilityAction("Dismiss") { store.send(Action.requestDismissTapped); true })
                }
            },
    ) {
        ComposerFoldLayout(foldProgress = fold) {
            val row = host.pendingAttachmentsRow
            if (row != null && (s.hasNonLocationAttachments || s.wantsLocationAttachment)) {
                Box(
                    Modifier
                        .composerFolds()
                        .alpha(rowAlpha)
                        .then(if (recording) Modifier.clearAndSetSemantics { }.blockTouches() else Modifier),
                ) { row() }
            }
            Box {
                ComposerLayout(
                    expanded = composerExpanded,
                    inline = bar?.let { ComposerLayoutInline(hasLeading = it is RequestBar.Answer, lineHeight = bodyLine) },
                    input = {
                        Box(
                            Modifier
                                .alpha(rowAlpha)
                                .then(if (recording) Modifier.clearAndSetSemantics { } else Modifier),
                        ) {
                            ComposerInput(composerExpanded, recording, bar)
                        }
                    },
                    leading = {
                        if (bar != null) {
                            AnswerLeadingControls(bar, recording, rowAlpha)
                        } else {
                            ComposerLeadingControls(composerExpanded, composerControls.secondaryMic, recording, rowAlpha)
                        }
                    },
                    trailing = {
                        // Everything at the trailing edge travels as one group: Send/Stop rightmost, dictation to its left.
                        Box(
                            Modifier
                                .alpha(rowAlpha)
                                // The bar replaces these, the run's Stop included.
                                .then(if (recording) Modifier.clearAndSetSemantics { } else Modifier),
                        ) {
                            val dictation = composerControls.dictation
                            when {
                                dictation != null -> DictationTrailingControls(dictation)
                                bar != null -> AnswerTrailingControls(bar)
                                else -> Row(
                                    horizontalArrangement = Arrangement.spacedBy(ComposerTunables.trailingSpacing.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Collapsed mid-run: the trailing control is Stop, so dictation keeps a home beside it.
                                    if (composerControls.secondaryMic == ComposerControls.MicPlacement.trailing) SecondaryMicButton()
                                    TrailingButton(composerControls.trailing)
                                }
                            }
                        }
                    },
                )
                val hold = shownDictationHold
                if (hold != null) {
                    DictationBarPresence(isLeaving = !recording, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        var barOrigin by remember { mutableStateOf(Offset.Zero) }
                        val mic = view.dictationMicFrame
                        val micCenter = if (mic == Rect.Zero || view.dictationBarFrame == Rect.Zero) {
                            null
                        } else {
                            Offset((mic.center.x - barOrigin.x) / density.density, (mic.center.y - barOrigin.y) / density.density)
                        }
                        DictationScopeBar(
                            pose = dictationPose(hold),
                            // The bar's ↑ answers the request; it never steers.
                            isRunning = s.isRunning && bar == null,
                            micCenter = micCenter,
                            // The words land in the draft; the keyboard stays as it was.
                            onStop = { store.send(Action.dictationInsertTapped) },
                            onSend = { store.send(if (it == ai.opencode.server.model.OpenCodeDelivery.Queue) Action.dictationQueueTapped else Action.dictationSendTapped) },
                            modifier = Modifier
                                .fillMaxWidth()
                                // Centred on the 44 dp control row (the bar's targets are 48 dp).
                                .offset(y = 2.dp)
                                .onGloballyPositioned {
                                    view.dictationBarFrame = it.boundsInRoot()
                                    barOrigin = view.dictationBarFrame.topLeft
                                },
                        )
                    }
                }
            }
            if (s.isPreparingDictation && !recording) {
                Row(
                    Modifier.composerFolds().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Preparing dictation…", style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
                }
            } else {
                val error = s.dictationError
                if (error != null) {
                    Text(
                        error,
                        style = OpenCodeTextStyles.footnote,
                        color = OpenCodeTheme.colors.destructive,
                        modifier = Modifier.composerFolds().padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }
}

/**
 * The placeholder over the field: one at a time, hidden without animation when the draft has
 * text, so a programmatic insert (Quote, Share, paste) never cross-fades “Message” over the words.
 * The field is :feature:composer's [ComposerTextInput], bound to the draft and to
 * [SessionComposerHost.messageFieldFocused].
 */
@Composable
private fun SessionComposerEnv.ComposerInput(expanded: Boolean, recording: Boolean, bar: RequestBar?) {
    val placeholder = bar?.placeholder ?: "Message"
    Box(Modifier.onGloballyPositioned { coordinates ->
        val surface = view.surfaceBounds
        val bounds = coordinates.boundsInRoot()
        view.textFieldBounds = if (surface == Rect.Zero) Rect.Zero else bounds
    }) {
        if (s.draft.isEmpty()) {
            Text(
                placeholder,
                style = ComposerTunables.textStyle.textStyle,
                color = OpenCodeTheme.colors.tertiaryText,
                maxLines = 1,
                modifier = Modifier
                    .padding(top = if (expanded) ComposerTunables.expandedTopInset.dp else 0.dp)
                    .clearAndSetSemantics { },
            )
        }
        val takesPaste = s.takesPasteAttachments
        ComposerTextInput(
            text = s.draft,
            // Folded while dictating: keys typed into the hidden field don't reach the draft.
            onTextChange = { new -> onDraftEdited(new, recording) },
            isFocused = host.messageFieldFocused,
            onFocusedChange = { host.messageFieldFocused = it },
            expanded = expanded,
            textStyle = ComposerTunables.textStyle.textStyle,
            topInset = ComposerTunables.expandedTopInset.dp,
            onImagePaste = { uris ->
                // An answer or a reason takes text, never an attachment.
                if (store.state.takesPasteAttachments) host.importer?.startImageImport(uris.map(ComposerImageSource::ItemProvider))
            },
            // Null while answering a request: the paste goes in as text.
            onLongTextPaste = if (takesPaste) { text -> store.send(Action.attachmentAdded(PastedText.attachment(text))) } else null,
            onFileDrop = if (takesPaste) { uris -> host.importer?.startFileImport(uris.map(ComposerFileSource::Url)) } else null,
            onDismissKeyboard = dismissKeyboard,
            onFocusKeyboard = ::focusComposerFromSwipe,
            onCommandReturn = {
                if (!recording) {
                    if (s.isAnsweringRequest) store.send(Action.CommandReturnTapped)
                    else if (!host.isImportingAttachments) sendMessage(Action.CommandReturnTapped)
                }
            },
            onNonSendNewline = { text -> if (!recording) store.send(Action.NonSendNewlineInserted(text)) },
            returnKeySends = returnKeySends,
            autocompleteBridge = if (usesAutocomplete) view.autocompleteBridge else null,
            lineLimit = if (bar == null) null else 4,
            accessibilityLabel = bar?.fieldLabel ?: "Message",
            isHoldingMic = s.dictationHold != null,
            sendTransition = host.sendTransition,
        )
    }
}

/**
 * iOS `$store.draft` and its `onChange`: vertical fields swallow the send key as a newline; a single
 * appended "\n" is the return key (pastes never match this shape).
 */
private fun SessionComposerEnv.onDraftEdited(new: String, recording: Boolean) {
    // Folded while dictating: keys typed into the hidden field don't reach the draft.
    if (recording) return
    val old = store.state.draft
    if (new == old) return
    store.send(Action.Binding(BindingAction.Draft(new)))
    val state = store.state
    if (!returnKeySends || state.nonSendNewlineDraft == new || new != old + "\n") return
    if (state.isAnsweringRequest) store.send(Action.ReturnKeyTapped) else sendMessage(Action.ReturnKeyTapped)
}

// endregion
