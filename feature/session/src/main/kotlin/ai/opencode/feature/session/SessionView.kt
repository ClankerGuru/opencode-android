package ai.opencode.feature.session

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.voice.DotScopeRenderer
import ai.opencode.feature.voice.LocalDotScopeCovered
import ai.opencode.feature.voice.VoiceStoreOf
import ai.opencode.feature.voice.floatingCallComposer
import ai.opencode.feature.session.header.HeaderBlurTunables
import ai.opencode.feature.session.header.SessionHeaderBackdrop
import ai.opencode.feature.session.header.SessionHeaderStyle
import ai.opencode.feature.composer.AttachmentMenuDismissArea
import ai.opencode.feature.composer.AttachmentMenuItem
import ai.opencode.feature.composer.AttachmentMenuOverlay
import ai.opencode.feature.composer.AttachmentMenuPhase
import ai.opencode.feature.composer.AttachmentMenuPress
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.composer.DictationPermissionClient
import ai.opencode.feature.composer.LocationPermissionPrompt
import ai.opencode.feature.composer.PastedTextViewer
import ai.opencode.feature.composer.modelpicker.ModelPickerAnchors
import androidx.compose.runtime.rememberCoroutineScope
import ai.opencode.feature.composer.RequestDockContext
import ai.opencode.feature.composer.RequestReviewSheet
import ai.opencode.feature.media.LocalMediaPresenter
import ai.opencode.feature.media.MediaFeature
import ai.opencode.feature.media.MediaView
import ai.opencode.core.store.Store
import ai.opencode.core.motion.rememberReduceMotion
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CompletableDeferred
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.feature.composer.LocationClientError
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeSession
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import kotlinx.coroutines.flow.drop
import kotlin.math.min
import kotlin.math.roundToInt

// Port of iOS `Session/SessionView.swift`: the session screen scaffold. The transcript runs
// full-bleed under the header and the composer; the header's backdrop and controls sit over its
// top, the composer (`SessionComposer`, SessionView+Composer's port) over its bottom, and the
// background-work capsule and panel over both. Sheets follow `activePicker`; lifecycle changes
// reach the reducer as actions (no side effects here).
//
// iOS's per-view gesture state (the model list, the plus menu's photo sheet, dictation holds, the
// request panel's room) belongs to the composer and picker views on Android, each in its own file.

/**
 * One session screen (or a new session's draft): header, transcript, composer, sheets.
 *
 * @param navigationIcon the drawer button Home supplies (iOS `drawerControl`); unused while
 *   [onBack] or [onCatchUpOpen] is set.
 * @param listContext what Home knows about this session (project, pin, unread, mute, notices).
 * @param onBack set on a subagent pushed over its parent: the header trades the drawer button for Back.
 * @param onCatchUpOpen set while the session is a catch-up card: the title leaves Catch Up for the session.
 * @param hostedCall the call its root owns, on a pushed subagent (iOS `hostedCall`): its cards and
 *   bar show here, as the screen's own call would.
 */
@Composable
fun SessionView(
    store: StoreOf<State, Action>,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    listContext: SessionListContext = SessionListContext(),
    onBack: (() -> Unit)? = null,
    onCatchUpOpen: (() -> Unit)? = null,
    hostedCall: VoiceStoreOf? = null,
) {
    val scrollEdgeBridge = remember { SessionScrollEdgeBridge() }
    val backgroundWorkAnchor = rememberBackgroundWorkAnchor()
    // What the composer shares with the screen around it (the capsule's place, jump to latest, the + menu).
    val composerHost = remember { SessionComposerHost() }
    val importer = rememberSessionAttachmentImporter(store)
    val anchorScope = rememberCoroutineScope()
    val modelPickerAnchors = remember { ModelPickerAnchors(anchorScope) }
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current

    // The header controls' laid-out height (dp); the backdrop follows it.
    var headerHeight by remember { mutableFloatStateOf(SessionHeaderStyle.DEFAULT_HEADER_HEIGHT) }
    var showsBackgroundWork by remember { mutableStateOf(false) }

    val headerEnvironment = SessionHeaderStyle.Environment(
        increaseContrast = colors.highContrast,
        isDark = colors.isDark,
        sizeCategory = density.fontScale.toString(),
        headerHeight = headerHeight,
    )
    val resolvedHeader = remember(headerEnvironment) { HeaderBlurTunables.resolved(headerEnvironment) }

    SessionLifecycle(store)
    val callStore = store.rememberCallStore(hostedCall)
    val ownsCall by store.observe { it.voice != null }
    SessionCallTasks(store, callStore, hasCall = ownsCall || hostedCall != null)
    val isCovered by store.observe { it.isCovered }
    val floatsCall = rememberFloatsCall()

    val work by store.observe(BackgroundWorkModel::from)
    val agentScope by store.observe { "${it.server.persistenceID}|${it.session?.id ?: "draft"}" }
    val errorMessage by store.observe { it.errorMessage }
    val identity by store.observe { it.transcriptIdentity }

    SideEffect {
        composerHost.backgroundWorkAnchor = backgroundWorkAnchor
        composerHost.isCatchUpCard = onCatchUpOpen != null
        composerHost.onJumpToLatest = { scrollEdgeBridge.jumpRequest += 1 }
        composerHost.isImportingAttachments = importer.isLoadingImages || importer.isLoadingFiles
        composerHost.importer = importer
        composerHost.modelPickerAnchors = modelPickerAnchors
        composerHost.headerHeight = headerHeight
        if (composerHost.pendingAttachmentsRow == null) composerHost.pendingAttachmentsRow = { SessionPendingAttachments(store) }
    }
    SessionPermissionPrompts()

    SessionMediaHost {
    CompositionLocalProvider(
        LocalAgentActivityScope provides agentScope,
        LocalSessionComposerHost provides composerHost,
        // Covered (a pushed subagent, a browser page), every scope underneath stops drawing.
        LocalDotScopeCovered provides isCovered,
    ) {
        // The + menu draws above the background-work capsule (iOS: the menu's glass covers it).
        Box(modifier) {
        BackgroundWorkPresentation(
            anchor = backgroundWorkAnchor,
            sessionID = work.sessionID,
            agents = work.agents,
            shells = work.shells,
            waitingSessionIDs = work.waitingSessionIDs,
            probe = work.probe,
            isPresented = showsBackgroundWork,
            onPresentedChange = { showsBackgroundWork = it },
            onOpenSubagent = { store.send(Action.SubagentTapped(it)) },
            onOpenShell = { store.send(Action.ShellOutputTapped(it)) },
            isCovered = composerHost.composerPanelCovers,
            inCall = work.inCall,
            showsCallTranscript = work.showsCallTranscript,
            // The capsule draws above the + menu's dismiss area: tapping it closes the menu.
            onToggle = composerHost::dismissComposerKeyboard,
        ) {
            Box(Modifier.fillMaxSize().background(colors.canvas)) {
                if (errorMessage != null) {
                    LoadFailure(errorMessage.orEmpty(), onRetry = { store.send(Action.Task) })
                } else {
                    // A draft's transcript carries on into the session it became, so the first
                    // send's bubble and Working never remount mid-flight.
                    key(identity) {
                        SessionTranscriptView(store, scrollEdgeBridge, resolvedHeader.transcriptTop)
                    }
                }
                SessionHeaderBackdrop(
                    backdrop = resolvedHeader.backdrop,
                    appBlur = resolvedHeader.appBlur,
                    safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    scrolledUnderHeader = { scrollEdgeBridge.scrolledUnderHeader },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                // Behind the composer, so its × stays reachable: a tap anywhere else or a downward
                // drag dismisses the menu, or Composer Autocomplete's suggestions.
                SessionComposerDismissArea(store, composerHost)
                SessionHeaderControls(
                    store = store,
                    listContext = listContext,
                    navigationIcon = navigationIcon,
                    onBack = onBack,
                    onCatchUpOpen = onCatchUpOpen,
                    onHeaderHeight = { headerHeight = it.roundToInt().toFloat() },
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = if (onCatchUpOpen == null) 0.dp else CATCH_UP_COMPOSER_CLEARANCE.dp)
                        // The readable column (iOS `readableColumn()`): 720 wide at most.
                        .widthIn(max = TranscriptMetrics.readableColumnWidth)
                        .onSizeChanged { scrollEdgeBridge.composerHeight = with(density) { it.height.toDp().value } }
                        // A floating call rests above the composer.
                        .then(if (floatsCall) Modifier.floatingCallComposer() else Modifier),
                ) {
                    SessionCallCards(callStore)
                    SessionComposer(store, callStore = callStore)
                }
                RequestPanelRoomProbe(composerHost, Modifier.matchParentSize())
                SessionModelPickerOverlays(store, modelPickerAnchors, headerHeight, composerHost.attachmentAnchors.composer)
            }
        }
        SessionAttachmentMenu(store, composerHost, importer)
        }
        SessionSheets(store, listContext)
        LocationErrorAlert(store)
        SessionAttachmentAlerts(importer)
    }
    }
}

/** iOS `CatchUpMetrics.composerClearance.bottom`. */
private const val CATCH_UP_COMPOSER_CLEARANCE = 12f


/** What the background-work panel reads from the session. */
@Immutable
private data class BackgroundWorkModel(
    val sessionID: String?,
    val agents: List<OpenCodeSession>,
    val shells: List<OpenCodeShell>,
    val waitingSessionIDs: Set<String>,
    val probe: ShellGlyphProbe?,
    val inCall: Boolean,
    val showsCallTranscript: Boolean,
) {
    companion object {
        fun from(state: State) = BackgroundWorkModel(
            sessionID = state.session?.id,
            agents = state.activeSubagents,
            shells = state.backgroundShells,
            waitingSessionIDs = state.waitingSessionIDs,
            probe = state.shellGlyphProbe,
            // A call takes the composer's place; its transcript covers the pills.
            inCall = state.voice != null,
            showsCallTranscript = state.voice?.showsCaptions == true,
        )
    }
}

/**
 * iOS `.task(id: taskIdentity)`, `onAppear`/`onDisappear` and `onChange(of: scenePhase)`: the
 * screen's visit starts its task, visibility and the scene's phase reach the reducer, and the
 * New Messages Divider and Since You Left settings tell an open session when they change.
 *
 * Android's lifecycle maps to scene phases: RESUMED is active, STARTED inactive, CREATED
 * (stopped) background.
 */
@Composable
private fun SessionLifecycle(store: StoreOf<State, Action>) {
    val taskIdentity by store.observe { it.taskIdentity }
    // Keyed by visit and session: the drawer swaps the screen in place, a draft opened from another
    // draft starts a fresh task, and a draft becoming its own session continues the one it started.
    LaunchedEffect(taskIdentity) { store.send(Action.Task) }
    // A held mic's bar shows its scope at once.
    LaunchedEffect(Unit) { DotScopeRenderer.prepare() }

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentStore by rememberUpdatedState(store)
    DisposableEffect(lifecycleOwner, store) {
        val lifecycle = lifecycleOwner.lifecycle
        currentStore.send(Action.VisibilityChanged(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)))
        var catchingUp = true
        val observer = LifecycleEventObserver { _, event ->
            // `addObserver` replays the events up to the current state: the appearance above covers them.
            if (catchingUp) return@LifecycleEventObserver
            val active = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_START -> false
                else -> return@LifecycleEventObserver
            }
            currentStore.send(Action.SceneActiveChanged(active))
            currentStore.send(Action.VisibilityChanged(active))
            if (event == Lifecycle.Event.ON_STOP) currentStore.send(Action.SceneEnteredBackground)
        }
        lifecycle.addObserver(observer)
        catchingUp = false
        onDispose {
            lifecycle.removeObserver(observer)
            if (currentStore.state.isVisible) currentStore.send(Action.VisibilityChanged(false))
        }
    }

    // iOS `markVisibleSession`: notifications learn which session is on screen, so its own don't
    // show. Told again as the scene, the cover or the session changes; told it left on disappear.
    val notifications = remember { dependency(SessionNotificationsClient) }
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val sceneActive = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val sessionID by store.observe { it.session?.id }
    val isVisible by store.observe { it.isVisible }
    val isCovered by store.observe { it.isCovered }
    val latestSessionID by rememberUpdatedState(sessionID)
    LaunchedEffect(notifications, sceneActive, isVisible, isCovered, sessionID) {
        notifications.setVisibleSession(sceneActive && isVisible && !isCovered, sessionID)
    }
    DisposableEffect(notifications) {
        onDispose { notifications.setVisibleSession(false, latestSessionID) }
    }

    // Switched off in Settings, an open session's divider goes at once.
    LaunchedEffect(store) {
        shared(ExperimentalFeature.newMessagesDivider.key).flow.drop(1).collect {
            store.send(Action.NewMessagesDividerChanged(it))
        }
    }
    LaunchedEffect(store) {
        shared(ExperimentalFeature.sinceYouLeft.key).flow.drop(1).collect {
            store.send(Action.SinceYouLeftChanged(it))
        }
    }
}

/**
 * The + menu (iOS `attachmentMenuOverlay`, `toggleAttachmentMenu` and `chooseAttachment`) and what
 * it opens: Photos (the system Photo Picker, or with the New Plus Menu experiment the composer's
 * photo sheet), Files (the system document picker) and Location (one at a time). The menu is
 * :feature:composer's [AttachmentMenuOverlay], growing out of the + ([AttachmentPickerButton] in
 * the composer's row, which also chooses a row by sliding to it); it keeps the field's focus, and
 * choosing a picker gives the keyboard up, which returns once something is attached if the field
 * had it. System Back closes it (iOS's escape action).
 *
 * Android has no in-menu camera or Paste row yet (iOS `AttachmentCamera`, the system PasteButton):
 * Android pastes images straight into the field.
 */
@Composable
private fun SessionAttachmentMenu(store: StoreOf<State, Action>, host: SessionComposerHost, importer: SessionAttachmentImporter) {
    var phase by remember { mutableStateOf(AttachmentMenuPhase.Closed) }
    var restoresFocus by remember { mutableStateOf(false) }
    var keepingLocationRow by remember { mutableStateOf(false) }
    var showsPhotoPicker by remember { mutableStateOf(false) }
    var showsPlusSheet by remember { mutableStateOf(false) }
    var tapped by remember { mutableIntStateOf(0) }
    val press = remember { AttachmentMenuPress() }
    val taskIdentity by store.observe { it.taskIdentity }
    val supportsFileUploads by store.observe { it.supportsFileUploads }
    val wantsLocation by store.observe { it.pendingLocation != null || it.isLocating }
    val plusSheetSetting by remember { shared(ExperimentalFeature.plusMenuSheet.key).flow }.collectAsState(false)
    val usesPlusMenuSheet = ExperimentalFeature.plusMenuSheet.isActive(plusSheetSetting)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importer.importPickedFiles(uris, onFocus = host::focusComposer)
    }

    // Another session in place: the menu closes at once.
    LaunchedEffect(taskIdentity) {
        phase = AttachmentMenuPhase.Closed
        restoresFocus = false
        showsPlusSheet = false
    }
    HapticEffect(Haptic.tap, tapped)

    fun close() {
        phase = AttachmentMenuPhase.Closed
        showsPlusSheet = false
        if (restoresFocus) host.focusComposer()
    }

    fun toggle() {
        if (phase == AttachmentMenuPhase.Closed) {
            keepingLocationRow = false
            restoresFocus = host.messageFieldFocused
            phase = AttachmentMenuPhase.Menu
            press.menuOpened()
        } else {
            close()
        }
    }

    fun choose(item: AttachmentMenuItem) {
        if (phase != AttachmentMenuPhase.Menu) return
        when (item) {
            AttachmentMenuItem.Photos -> {
                // Choosing the photo picker gives it the room the keyboard had.
                host.dismissComposerKeyboard()
                if (usesPlusMenuSheet) {
                    showsPlusSheet = true
                } else {
                    phase = AttachmentMenuPhase.Closed
                    showsPhotoPicker = true
                }
            }
            AttachmentMenuItem.Files -> {
                restoresFocus = false
                phase = AttachmentMenuPhase.Closed
                files.launch(arrayOf("*/*"))
            }
            AttachmentMenuItem.Location -> {
                restoresFocus = false
                keepingLocationRow = true
                store.send(Action.locationTapped)
                phase = AttachmentMenuPhase.Closed
            }
            AttachmentMenuItem.Camera, AttachmentMenuItem.Paste -> phase = AttachmentMenuPhase.Closed
        }
    }

    SideEffect {
        host.attachmentPress = press
        host.isAttachmentMenuOpen = phase != AttachmentMenuPhase.Closed
        host.onPlusTapped = ::toggle
        host.attachmentMenuRows = if (phase == AttachmentMenuPhase.Menu && !showsPlusSheet) host.attachmentAnchors.rows else emptyMap()
        // A slide from + chose it; the press already answered with its haptic.
        host.onAttachmentChoose = ::choose
        host.closeAttachmentMenu = { if (phase != AttachmentMenuPhase.Closed) close() }
    }
    BackHandler(enabled = phase != AttachmentMenuPhase.Closed && !showsPlusSheet) { close() }

    val items = sessionAttachmentMenuItems(
        hasCamera = false,
        supportsFileUploads = supportsFileUploads,
        wantsLocationAttachment = wantsLocation,
        keepingLocationRowWhileMenuCloses = keepingLocationRow,
        clipboardHasImages = false,
    )
    AttachmentMenuOverlay(
        phase = phase,
        onPhaseChange = { next -> if (next == AttachmentMenuPhase.Closed) close() else phase = next },
        anchors = host.attachmentAnchors,
        press = press,
        items = items,
        includesVideos = supportsFileUploads,
        onChoose = { item ->
            tapped += 1
            choose(item)
        },
        onPaste = { phase = AttachmentMenuPhase.Closed },
        modifier = Modifier.fillMaxSize(),
        // The New Plus Menu's morphing glass (`ComposerMorph`) isn't hosted: its menu stays this overlay's.
        overComposer = false,
        handedOff = showsPlusSheet,
        identity = taskIdentity,
    )
    if (showsPhotoPicker) {
        SessionPhotoSheet(
            store = store,
            importer = importer,
            onDismiss = { showsPhotoPicker = false },
            onAdded = if (restoresFocus) host::focusComposer else null,
        )
    }
    if (showsPlusSheet) {
        SessionPlusPhotoSheet(
            importer = importer,
            onBack = { showsPlusSheet = false },
            onDismiss = { close() },
            onAdded = if (restoresFocus) host::focusComposer else null,
            onClosed = {
                showsPlusSheet = false
                phase = AttachmentMenuPhase.Closed
            },
        )
    }
}

/**
 * Behind the composer (iOS the capsule's `.background`): while the + menu is open, or Composer
 * Autocomplete's suggestions are, a tap anywhere else or a downward drag dismisses them.
 */
@Composable
private fun SessionComposerDismissArea(store: StoreOf<State, Action>, host: SessionComposerHost) {
    val autocompleteOpen by store.observe { it.autocomplete.isOpen }
    val autocompleteSetting by remember { shared(ExperimentalFeature.composerAutocomplete.key).flow }.collectAsState(false)
    when {
        host.isAttachmentMenuOpen -> AttachmentMenuDismissArea(onDismiss = host.closeAttachmentMenu)
        ExperimentalFeature.composerAutocomplete.isActive(autocompleteSetting) && autocompleteOpen ->
            AttachmentMenuDismissArea(onDismiss = { store.send(Action.Autocomplete(ComposerAutocompleteAction.Dismissed)) })
    }
}

/**
 * The room the request panel's cap is made from (iOS `requestPanelInputs.room`): the screen's
 * height between the top safe area and the keyboard (or the bottom safe area). Its own
 * composable, so the keyboard's frames recompose only it.
 */
@Composable
private fun RequestPanelRoomProbe(host: SessionComposerHost, modifier: Modifier) {
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(0) }
    val top = WindowInsets.statusBars.getTop(density)
    val bottom = maxOf(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density))
    Box(modifier.onSizeChanged { height = it.height })
    SideEffect {
        val room = with(density) { (height - top - bottom).coerceAtLeast(0).toDp().value }
        if (host.availableHeight != room) host.availableHeight = room
    }
}

/**
 * Registers the screen's permission prompts while it's shown: the microphone for dictation
 * ([DictationPermissionClient.register]) and location for the + menu's Location
 * ([LocationPermissionPrompt.install]), each through an activity-result launcher the screen owns.
 */
@Composable
private fun SessionPermissionPrompts() {
    val mic = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val location = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        mic[0]?.complete(granted)
        mic[0] = null
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        location[0]?.complete(grants.values.any { it })
        location[0] = null
    }
    DisposableEffect(micLauncher) {
        val remove = dependency(DictationPermissionClient).register {
            val answer = CompletableDeferred<Boolean>()
            mic[0]?.complete(false)
            mic[0] = answer
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            answer.await()
        }
        onDispose {
            remove()
            mic[0]?.complete(false)
            mic[0] = null
        }
    }
    DisposableEffect(locationLauncher) {
        val remove = LocationPermissionPrompt.install {
            val answer = CompletableDeferred<Boolean>()
            location[0]?.complete(false)
            location[0] = answer
            locationLauncher.launch(LocationPermissionPrompt.permissions)
            answer.await()
        }
        onDispose {
            remove()
            location[0]?.complete(false)
            location[0] = null
        }
    }
}

/**
 * Full-screen media over the session (iOS `MediaPresenter`): the app's [MediaView] when one hosts
 * this screen ([LocalMediaPresenter]), else the session's own, so transcript images, files and
 * pasted text always open in the viewer.
 */
@Composable
private fun SessionMediaHost(content: @Composable () -> Unit) {
    if (LocalMediaPresenter.current != null) {
        content()
        return
    }
    val media = remember { Store(MediaFeature.State(), MediaFeature()) }
    MediaView(media, content = content)
}

/** What the composer holds, above its field (iOS `pendingAttachmentsRow`). */
@Composable
private fun SessionPendingAttachments(store: StoreOf<State, Action>) {
    val attachments by store.observe { it.pendingAttachments }
    val files by store.observe { it.pendingFiles }
    val canRetry by store.observe { it.supportsFileUploads }
    val location by store.observe { it.pendingLocation }
    val isLocating by store.observe { it.isLocating }
    val presenter = LocalMediaPresenter.current
    val reducedMotion = rememberReduceMotion()
    val context = LocalContext.current
    PendingAttachmentsRow(
        attachments = attachments,
        files = files,
        canRetryFiles = canRetry,
        showsLocation = location != null || isLocating,
        location = location,
        isLocating = isLocating,
        onRemoveAttachment = { store.send(Action.attachmentRemoved(it)) },
        onRemoveFile = { store.send(Action.fileRemoved(it)) },
        onRetryFile = { store.send(Action.fileRetryTapped(it)) },
        onRemoveLocation = { store.send(Action.locationRemoved) },
        onOpenPastedText = { attachment ->
            val text = attachment.pastedText
            val summary = attachment.pasted
            if (text != null && summary != null) {
                presenter?.present(sourceID = null, isMedia = false, reducesMotion = reducedMotion, screenName = "Pasted Text") { viewer ->
                    PastedTextViewer(
                        text = text,
                        summary = summary,
                        onCopy = { store.send(Action.CopyTextTapped(it)) },
                        onShare = { shareText(context, it) },
                        onDone = { viewer.dismiss() },
                    )
                }
            }
        },
        onOpenLocation = { store.send(Action.LinkTapped(it)) },
    )
}

/** The system share sheet with [text] (iOS `ShareLink`). */
internal fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
    }
}

/** iOS `ContentUnavailableView` for a session that couldn't load. */
@Composable
private fun LoadFailure(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OpenCodeIcon(SFSymbol.ExclamationmarkTriangle, contentDescription = null, size = OpenCodeIconSize.hero, tint = OpenCodeTheme.colors.secondaryText)
        Text("Couldn’t Load Session", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
        OpenCodeSecondaryButton("Try Again", onClick = onRetry)
    }
}

/**
 * A child store for an optional child state, while it's present (iOS `store.scope(\.x, action: \.x)`
 * returning nil). It keeps the last value while the child leaves, so a closing sheet draws what
 * it had.
 */
@Composable
internal fun <C : Any, CA> StoreOf<State, Action>.rememberOptionalScope(
    state: (State) -> C?,
    action: (CA) -> Action,
): StoreOf<C, CA>? {
    val present by observe { state(it) != null }
    if (!present) return null
    return remember(this) {
        var last: C? = null
        scope({ parent -> (state(parent) ?: last ?: error("rememberOptionalScope read before its state appeared")).also { last = it } }, action)
    }
}

/**
 * The sheets over the session (iOS `.sheet(item: $store.activePicker)`, the shell output sheet,
 * and the menu's prompts while the Session sheet isn't showing its own).
 */
@Composable
private fun SessionSheets(store: StoreOf<State, Action>, listContext: SessionListContext) {
    val activePicker by store.observe { it.activePicker }
    val dismiss = { store.send(Action.Binding(BindingAction.ActivePicker(null))) }

    // iOS `onDismiss`: whichever sheet leaves, the Project sheet's folders reset.
    var shownPicker by remember { mutableStateOf(activePicker) }
    LaunchedEffect(activePicker) {
        if (shownPicker != null && shownPicker != activePicker) store.send(Action.ProjectPickerDismissed)
        shownPicker = activePicker
    }

    val sheet = store.rememberOptionalScope({ it.sessionSheet }, Action::SessionSheet)
    when (activePicker) {
        State.ActivePicker.session -> if (sheet != null) {
            val details by store.observe(::sessionSheetDetails)
            SessionSheetView(
                sheet = sheet,
                context = listContext,
                details = details,
                onModelTapped = { store.send(Action.ModelButtonTapped) },
                onDismissRequest = dismiss,
                providerIcon = { providerID, canonical, size -> ProviderIcon(providerID, Modifier.size(size), canonical) },
                statusOrb = { status, size ->
                    // iOS: the session's own activity, drawn like a subagent's elsewhere.
                    val activity = SubagentActivity(
                        sessionID = details.session?.id,
                        phase = if (status == SessionSheetStatus.Idle) SubagentActivity.Phase.idle else SubagentActivity.Phase.running,
                        needsInput = status == SessionSheetStatus.Waiting,
                    )
                    CompositionLocalProvider(
                        LocalAgentActivityScope provides "${sheet.state.server.persistenceID}|${details.session?.id ?: "draft"}|sheet",
                    ) {
                        AgentActivityGlyph(
                            AgentActivitySource.Subagent(activity),
                            Modifier.semantics { contentDescription = "Status"; stateDescription = status.text },
                            size = size,
                        )
                    }
                },
            )
        }
        State.ActivePicker.model -> SessionModelPicker(store)
        State.ActivePicker.project -> {
            val project by store.observe { ProjectSheetModel(it.projects, it.draftProject, it.projectFilter) }
            val folderBrowser = store.rememberOptionalScope<FolderBrowserFeature.State, FolderBrowserFeature.Action>(
                { it.folderBrowser },
                { Action.FolderBrowser(PresentationAction.Presented(it)) },
            )
            ProjectPickerSheet(
                projects = project.projects,
                draftProject = project.draftProject,
                projectFilter = project.filter,
                onProjectFilterChange = { store.send(Action.Binding(BindingAction.ProjectFilter(it))) },
                onProjectPicked = { store.send(Action.ProjectPicked(it)) },
                onCancel = dismiss,
                folderBrowser = folderBrowser,
            )
        }
        State.ActivePicker.requests -> {
            val dock by store.observe { it.requestDock }
            val context by store.observe { RequestDockContext(currentSessionID = it.session?.id, familySessions = it.familySessions, isVisible = it.isVisible) }
            // What the composer's row holds goes with Dismiss, as with the panel's ×.
            val dismissMessage by store.observe { if (it.isAnsweringRequest) it.draft else null }
            RequestReviewSheet(
                dock = dock,
                context = context,
                dismissMessage = dismissMessage,
                send = { store.send(Action.RequestDock(it)) },
                onClose = dismiss,
                onOpenURL = { store.send(Action.LinkTapped(it)) },
            )
        }
        null -> Unit
    }

    // The menu's rename, delete, and export prompts, while the sheet isn't showing its own.
    if (activePicker != State.ActivePicker.session && sheet != null) {
        val title by store.observe { it.session?.title }
        SessionActionPresentations(sheet, title)
    }

    store.rememberOptionalScope<ShellOutputFeature.State, ShellOutputFeature.Action>(
        { it.shellOutput },
        { Action.ShellOutput(PresentationAction.Presented(it)) },
    )?.let { shell ->
        ShellOutputView(shell, onClose = { store.send(Action.ShellOutput(PresentationAction.Dismiss)) })
    }
}

@Immutable
private data class ProjectSheetModel(
    val projects: List<ai.opencode.server.model.OpenCodeProject>,
    val draftProject: ai.opencode.server.model.OpenCodeProject?,
    val filter: String,
)

private fun sessionSheetDetails(state: State) = SessionSheetDetails(
    session = state.session,
    hasPendingRequests = state.permissionRequests.isNotEmpty() || state.formRequests.isNotEmpty(),
    isBusy = state.isBusy,
    currentModel = state.currentModel,
    currentModelInfo = state.currentModelInfo,
    currentModelName = state.currentModelName,
    currentModelLabel = state.currentModelLabel,
    contextUsage = state.contextUsage,
    contextFraction = state.contextFraction,
    contextLimit = state.contextLimit,
    sessionCost = state.sessionCost,
)

/** iOS `.alert("Couldn’t Share Location", …)`: Open Settings when access is off. */
@Composable
private fun LocationErrorAlert(store: StoreOf<State, Action>) {
    val error by store.observe { it.locationError }
    val message = error ?: return
    AlertDialog(
        onDismissRequest = { store.send(Action.locationErrorDismissed) },
        title = { Text("Couldn’t Share Location") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { store.send(Action.locationErrorDismissed) }) { Text("OK") }
        },
        dismissButton = if (message == LocationClientError.Denied.message) {
            { TextButton(onClick = { store.send(Action.locationSettingsTapped) }) { Text("Open Settings") } }
        } else {
            null
        },
    )
}

/**
 * The drawer button's glyph: a long stroke over a short one, drawn at whole pixels so it reads at
 * full strength beside the Material Symbols around it. Scales with the body text up to 22 dp.
 */
@Composable
fun DrawerGlyph(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val width = min(18f * density.fontScale, 22f)
    val color = LocalContentColor.current
    with(density) {
        fun pixels(points: Float): Float = (points * this.density).roundToInt().toFloat()
        val stroke = pixels(2.5f)
        val gap = pixels(width * 0.3f)
        val long = pixels(width)
        val short = pixels(width * 0.6f)
        Canvas(modifier.size(long.toDp(), (stroke * 2 + gap).toDp()).clearAndSetSemantics {}) {
            val radius = CornerRadius(stroke / 2, stroke / 2)
            drawRoundRect(color, Offset.Zero, Size(long, stroke), radius)
            drawRoundRect(color, Offset(0f, stroke + gap), Size(short, stroke), radius)
        }
    }
}

/**
 * A provider's logo from OpenCode's provider icon set, tinted like text (iOS template images; the
 * iOS SVGs ship in `assets/provider-icons`). Decorative: the row it sits in names the provider.
 *
 * @param canonical the provider this one stands for, when its own ID has no icon.
 */
@Composable
fun ProviderIcon(providerID: String, modifier: Modifier = Modifier, canonical: String? = null) {
    val context = LocalContext.current
    val name = remember(providerID, canonical) { ProviderIcon.assetName(providerID, canonical) }
    val request = remember(name) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/provider-icons/$name.svg")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(LocalContentColor.current),
    )
}

object ProviderIcon {
    /**
     * Like desktop: the provider's own icon, else its canonical provider's, else a Console
     * connection's upstream, else the neutral placeholder.
     */
    fun assetName(providerID: String, canonical: String?): String {
        val candidates = listOfNotNull(providerID, canonical, if (providerID.startsWith("console-")) providerID.drop(8) else null)
        return candidates.firstOrNull { it in available } ?: "synthetic"
    }

    /** The icons in `assets/provider-icons` (iOS `ProviderIcons.xcassets`, `provider-<id>`). */
    val available: Set<String> = setOf(
        "302ai", "abacus", "aihubmix", "alibaba-cn", "alibaba", "amazon-bedrock", "anthropic", "azure-cognitive-services", "azure",
        "bailing", "baseten", "berget", "cerebras", "chutes", "cloudferro-sherlock", "cloudflare-ai-gateway", "cloudflare-workers-ai",
        "cohere", "cortecs", "deepinfra", "deepseek", "digitalocean", "evroc", "fastrouter", "fireworks-ai", "firmware", "friendli",
        "github-copilot", "github-models", "gitlab", "google-vertex-anthropic", "google-vertex", "google", "groq", "helicone",
        "huggingface", "iflowcn", "inception", "inference", "io-net", "jiekou", "kilo", "kimi-for-coding", "kuae-cloud-coding-plan",
        "llama", "lmstudio", "lucidquery", "meganova", "minimax-cn-coding-plan", "minimax-cn", "minimax-coding-plan", "minimax",
        "mistral", "moark", "modelscope", "moonshotai-cn", "moonshotai", "morph", "nano-gpt", "nebius", "nova", "novita-ai", "nvidia",
        "ollama-cloud", "openai", "opencode-go", "opencode", "openrouter", "ovhcloud", "perplexity", "poe", "privatemode-ai",
        "qihang-ai", "qiniu-ai", "requesty", "sap-ai-core", "scaleway", "siliconflow-cn", "siliconflow", "stackit", "stepfun",
        "submodel", "synthetic", "togetherai", "upstage", "v0", "venice", "vercel", "vivgrid", "vultr", "wandb", "xai", "xiaomi",
        "zai-coding-plan", "zai", "zenmux", "zhipuai-coding-plan", "zhipuai",
    )
}

/**
 * What the composer's row swaps as a request takes it or gives it back (the placeholder, the mark
 * and +, the trailing controls): the outgoing leaves a beat before the incoming arrives, so two
 * never read at once. iOS blur-replaces; Android fades with a slight scale. Reduce Motion fades.
 */
object RequestBarSwap {
    fun enter(reducedMotion: Boolean): EnterTransition {
        val spec = tween<Float>(durationMillis = if (reducedMotion) 160 else 240, delayMillis = 100, easing = Motion.EaseOut)
        return if (reducedMotion) fadeIn(spec) else fadeIn(spec) + scaleIn(spec, initialScale = 0.96f)
    }

    fun exit(reducedMotion: Boolean): ExitTransition {
        val spec = tween<Float>(durationMillis = 120, easing = EaseIn)
        return if (reducedMotion) fadeOut(spec) else fadeOut(spec) + scaleOut(spec, targetScale = 0.96f)
    }

    /** SwiftUI `.easeIn`. */
    private val EaseIn = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 1f, 1f)
}

/** The composer and the call trade places on one surface: iOS blur-replaces; a cross-fade under Reduce Motion. */
object ComposerSwap {
    fun enter(reducedMotion: Boolean): EnterTransition =
        if (reducedMotion) fadeIn(Motion.reducedFadeSpec()) else fadeIn(Motion.settle.spec()) + scaleIn(Motion.settle.spec(), initialScale = 0.96f)

    fun exit(reducedMotion: Boolean): ExitTransition =
        if (reducedMotion) fadeOut(Motion.reducedFadeSpec()) else fadeOut(Motion.settle.spec()) + scaleOut(Motion.settle.spec(), targetScale = 0.96f)
}
