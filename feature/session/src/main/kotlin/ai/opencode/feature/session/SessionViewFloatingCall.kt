package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.composer.AttachmentMenuPhase
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.voice.FloatingCallCenter
import ai.opencode.feature.voice.FloatingCallStage
import ai.opencode.feature.voice.FloatingCallStageFeature
import ai.opencode.feature.voice.FloatingCallStageTunables
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceStoreOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// Port of iOS `SessionView+FloatingCall.swift`: the Living Orb's card or sheet over the composer,
// what the composer hides and gives up while it's covered, and the request that shrinks it back.

/**
 * iOS `floatingCallCovers`: the Living Orb's card or sheet covers the composer
 * (`call.stage.covers`). The composer and its pills hide under it rather than show through it,
 * until the call shrinks back to the orb or ends. [stage] is the live call's floating stage
 * (null with no call, or a call that's over).
 */
internal fun floatingCallCovers(
    floatsCall: Boolean,
    stage: FloatingCallStageFeature.State?,
    composerColumn: Rect?,
    card: Rect?,
): Boolean {
    if (!floatsCall || stage == null || !FloatingCallStageTunables.covers.value) return false
    val grown = maxOf(stage.stage, stage.dragTarget ?: FloatingCallStage.orb)
    if (grown <= FloatingCallStage.orb) return false
    return grown.covers(composer = composerColumn, card = card)
}

/**
 * iOS `floatingCallCoverage(_:)`, on the screen's watchers: sets [SessionComposerHost.floatingCallCovers],
 * and covered, the composer gives up what it had open (the keyboard, the + menu and its photos,
 * the model panels, autocomplete); none would show, and each would come back stranded when the
 * call shrinks. A docked request shrinks the call back to its orb.
 */
@Composable
internal fun SessionFloatingCallCoverage(
    store: StoreOf<State, Action>,
    callStore: VoiceStoreOf?,
    floatsCall: Boolean,
    host: SessionComposerHost,
    photoSheet: SessionPhotoSheetState,
) {
    val center = remember { dependency(FloatingCallCenter) }
    val liveStage by remember(callStore) {
        callStore?.states?.map { if (it.phase.isTerminal) null else it.floatingStage } ?: flowOf(null)
    }.collectAsState(callStore?.state?.let { if (it.phase.isTerminal) null else it.floatingStage })
    val callShowsCard by remember(callStore) { callStore?.states?.map { it.phase.showsCard } ?: flowOf(false) }
        .collectAsState(callStore?.state?.phase?.showsCard ?: false)
    val covers = floatingCallCovers(floatsCall, liveStage, center.composerColumn, center.card)
    SideEffect { if (host.floatingCallCovers != covers) host.floatingCallCovers = covers }

    var lastCovers by remember { mutableStateOf(covers) }
    LaunchedEffect(covers) {
        if (lastCovers == covers) return@LaunchedEffect
        lastCovers = covers
        if (!covers) return@LaunchedEffect
        host.dismissComposerKeyboard()
        photoSheet.attachmentMenuRestoresFocus = false
        // The photo sheet goes at once: folding, it would land on the hidden composer and hand it
        // the keyboard. (iOS `showsAllPhotos` is the sheet's expanded detent here.)
        if (photoSheet.morph.isShown) photoSheet.resetPhotoSheet()
        if (photoSheet.attachmentMenu != AttachmentMenuPhase.Closed) photoSheet.closeAttachmentMenu()
        photoSheet.recentPeek.hide()
        // Cancels the model list's focus restore first (iOS `modelListFocusRestore.cancel()`).
        host.modelPanels?.closeModelPanels()
        if (store.state.autocomplete.isOpen) store.send(Action.Autocomplete(ComposerAutocompleteAction.Dismissed))
    }

    // The card and the sheet would cover a docked request: the call shrinks back to its orb, which
    // sits above the composer, whichever request docks and on whichever screen, once it's
    // uncovered. iOS `docksRequest`: `voiceStore == nil && isAnsweringRequest`.
    val holdsBar = callStore != null && !floatsCall && !callShowsCard
    val dock by store.observe {
        RequestDockCall(it.taskIdentity, it.dockRequestID, it.isCovered, docksRequest = !holdsBar && it.isAnsweringRequest)
    }
    val call by rememberUpdatedState(callStore)
    val floats by rememberUpdatedState(floatsCall)
    // iOS `onChange(of:initial: true)`: the first value counts too.
    LaunchedEffect(dock) {
        if (!dock.shrinksCall) return@LaunchedEffect
        val current = call ?: return@LaunchedEffect
        if (floats) current.send(VoiceFeature.Action.FloatingStage(FloatingCallStageFeature.Action.RequestDocked))
    }
}

/**
 * Which request docks in the composer, on which screen (iOS `RequestDockCall`): a new one
 * (another waiting session, a subagent popped back to its parent, a queued request replacing the
 * answered one) or the screen uncovering makes way for it.
 */
data class RequestDockCall(
    val identity: SessionFeature.State.TaskIdentity,
    val requestID: String?,
    val isCovered: Boolean,
    val docksRequest: Boolean,
) {
    /** A screen under another (a pushed subagent, the drawer's next) leaves the call alone until it's the one showing. */
    val shrinksCall: Boolean get() = docksRequest && !isCovered
}
