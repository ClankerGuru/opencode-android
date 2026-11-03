package ai.opencode.app

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.combine
import ai.opencode.core.store.onChange
import ai.opencode.core.store.scope
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.voice.CallAudioFailure
import ai.opencode.feature.voice.VoiceCallAudioFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceOpenLinkFeature
import ai.opencode.feature.voice.VoiceScreenRequestFeature
import ai.opencode.feature.voice.sessionDelegatorElsewhereID
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolsTarget
import kotlin.coroutines.cancellation.CancellationException

/*
 * Port of iOS `AppFeature+ClientTools.swift`: the phone's client tools (Share Screen with
 * Agents, Open Links from Calls, Audio in Calls) around Home.
 */

/** The directory a call's delegator session lives in, read from the server. */
data class ClientToolsLocation(val sessionID: String, val directory: String)

/**
 * The phone's tools' socket: Home's server, in the open session's directory (on a call, the
 * call's), while the app is in the foreground or on a call.
 */
internal val AppFeature.State.clientToolsTarget: ClientToolsTarget?
    get() {
        val tools = clientTools.tools
        if (tools.isEmpty()) return null
        val home = home ?: return null
        val voice = home.sessionDetail?.voice
        if (voice != null && !voice.phase.isTerminal) {
            return ClientToolsTarget(home.server, voice.target.directory, tools)
        }
        if (!isInForeground) return null
        val detail = home.topDetail ?: return null
        val directory = detail.session?.directory ?: detail.draftProject?.directory ?: return null
        return ClientToolsTarget(home.server, directory, tools)
    }

/** The session a live call's delegator steers in another directory; it gets its own socket. */
internal val AppFeature.State.voiceDelegatorSessionID: String?
    get() {
        if (clientTools.tools.isEmpty()) return null
        return home?.sessionDetail?.voice?.sessionDelegatorElsewhereID
    }

/** The second socket, while the delegator's session lives outside the call's directory. */
internal val AppFeature.State.delegatorClientToolsTarget: ClientToolsTarget?
    get() {
        val home = home ?: return null
        val id = voiceDelegatorSessionID ?: return null
        val location = voiceDelegatorLocation ?: return null
        if (location.sessionID != id) return null
        if (location.directory == clientToolsTarget?.directory) return null
        return ClientToolsTarget(home.server, location.directory, clientTools.tools)
    }

/** Why a screen request can't be put to the person now, or null when its card would show. */
internal val AppFeature.State.screenRequestRefusal: String?
    get() = when (clientToolCardBlock) {
        ClientToolCardBlock.NoCall -> VoiceScreenRequestFeature.Refusal.noCall
        ClientToolCardBlock.Background -> VoiceScreenRequestFeature.Refusal.background
        ClientToolCardBlock.Covered -> VoiceScreenRequestFeature.Refusal.covered
        // Its own card refuses a second request itself; the other's here.
        null -> if (home?.sessionDetail?.voice?.openLink?.pending != null) VoiceScreenRequestFeature.Refusal.otherRequest else null
    }

/** Why a link can't be put to the person now, or null when its card would show. */
internal val AppFeature.State.openLinkRefusal: String?
    get() = when (clientToolCardBlock) {
        ClientToolCardBlock.NoCall -> VoiceOpenLinkFeature.Refusal.noCall
        ClientToolCardBlock.Background -> VoiceOpenLinkFeature.Refusal.background
        ClientToolCardBlock.Covered -> VoiceOpenLinkFeature.Refusal.covered
        null -> {
            // Nor while a shared screen is being captured: the card would be in it.
            val screen = home?.sessionDetail?.voice?.screenRequest
            if (screen != null && (screen.pending != null || screen.capturing != null)) VoiceOpenLinkFeature.Refusal.busy else null
        }
    }

private enum class ClientToolCardBlock { NoCall, Background, Covered }

/** What stops a client tool's card showing above the call's composer. */
private val AppFeature.State.clientToolCardBlock: ClientToolCardBlock?
    get() {
        val home = home ?: return ClientToolCardBlock.NoCall
        val detail = home.sessionDetail ?: return ClientToolCardBlock.NoCall
        val voice = detail.voice ?: return ClientToolCardBlock.NoCall
        if (voice.phase != VoiceFeature.State.Phase.Live) return ClientToolCardBlock.NoCall
        if (!isInForeground) return ClientToolCardBlock.Background
        val coversApp = isEditingServer || isScanningPairingCode ||
            pendingPairing != null || pairingError != null ||
            shareError != null || notificationError != null || systemActionError != null
        // Not `presentsOverHome`: a pushed subagent shows the call's card too. What's New and
        // the onboarding playground are Settings pages on Android.
        val coversHome = home.catchUp != null || home.isSettingsShown ||
            home.welcome != null || home.pendingDeletion != null || home.isDrawerOpen
        // Not `presentsOverSession`: the call itself counts there.
        val top = home.topDetail ?: detail
        val coversSession = top.activePicker != null || top.folderBrowser != null || top.shellOutput != null ||
            top.browser.isOpen || top.requestDock.isAnswering
        if (coversApp || coversHome || coversSession) return ClientToolCardBlock.Covered
        return null
    }

private enum class ClientToolsLocateID { Locate }

/**
 * iOS `sharingScreenWithAgents(_:)`: the socket follows [clientToolsTarget], a second follows
 * [delegatorClientToolsTarget], and a tool call on either goes to the call on screen, or is
 * refused when there's none.
 */
internal fun sharingScreenWithAgents(base: Reducer<AppFeature.State, AppFeature.Action>): Reducer<AppFeature.State, AppFeature.Action> {
    val sockets = combine(
        scope(
            state = { it.clientTools },
            setState = { s, tools -> s.copy(clientTools = tools) },
            action = { (it as? AppFeature.Action.ClientTools)?.action },
            embed = AppFeature.Action::ClientTools,
            child = ClientToolsFeature(),
        ),
        scope(
            state = { it.delegatorClientTools },
            setState = { s, tools -> s.copy(delegatorClientTools = tools) },
            action = { (it as? AppFeature.Action.DelegatorClientTools)?.action },
            embed = AppFeature.Action::DelegatorClientTools,
            child = ClientToolsFeature(),
        ),
        base,
        Reducer { state, action -> routeClientTools(state, action) },
    )
    return sockets
        .onChange({ it.clientToolsTarget }) { _, target, state ->
            Reduced(state, Effect.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.TargetChanged(target))))
        }
        .onChange({ it.voiceDelegatorSessionID }) { _, _, state -> locateVoiceDelegator(state) }
        .onChange({ it.delegatorClientToolsTarget }) { _, target, state ->
            Reduced(state, Effect.send(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.TargetChanged(target))))
        }
}

private fun routeClientTools(state: AppFeature.State, action: AppFeature.Action): Reduced<AppFeature.State, AppFeature.Action> {
    if (action is AppFeature.Action.ClientTools) {
        val delegate = action.action as? ClientToolsFeature.Action.Delegate ?: return Reduced(state)
        return Reduced(state, route(delegate.delegate, state) { call, message ->
            AppFeature.Action.ClientTools(ClientToolsFeature.Action.Refuse(call, message))
        })
    }
    if (action is AppFeature.Action.DelegatorClientTools) {
        val delegate = action.action as? ClientToolsFeature.Action.Delegate ?: return Reduced(state)
        return Reduced(state, route(delegate.delegate, state) { call, message ->
            AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Refuse(call, message))
        })
    }
    if (action is AppFeature.Action.VoiceDelegatorLocated) {
        if (action.location.sessionID != state.voiceDelegatorSessionID) return Reduced(state)
        return Reduced(state.copy(voiceDelegatorLocation = action.location))
    }
    return Reduced(state)
}

/** The call's own action, as Home receives it. */
private fun voiceAction(action: VoiceFeature.Action): AppFeature.Action =
    AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.Voice(PresentationAction.Presented(action))))

private fun route(
    delegate: ClientToolsFeature.DelegateAction,
    state: AppFeature.State,
    refuse: (ClientToolCall, String) -> AppFeature.Action,
): Effect<AppFeature.Action> {
    if (delegate is ClientToolsFeature.DelegateAction.Disconnected) {
        if (state.home?.sessionDetail?.voice == null) return Effect.none()
        return Effect.concatenate(
            Effect.send(voiceAction(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.ConnectionClosed(delegate.connection)))),
            Effect.send(voiceAction(VoiceFeature.Action.OpenLink(VoiceOpenLinkFeature.Action.ConnectionClosed(delegate.connection)))),
        )
    }
    val call = (delegate as ClientToolsFeature.DelegateAction.ToolCalled).call
    val isLive = state.home?.sessionDetail?.voice?.phase == VoiceFeature.State.Phase.Live
    when (call.tool) {
        ClientTool.voiceRequestScreen -> {
            val refusal = state.screenRequestRefusal
            if (refusal != null) return Effect.send(refuse(call, refusal))
            return Effect.send(voiceAction(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.Requested(call))))
        }
        ClientTool.voiceOpenURL -> {
            // A bad link is refused for what it is, wherever the person is.
            val link = VoiceOpenLinkFeature.link(call)
            if (link is VoiceOpenLinkFeature.Link.Invalid) return Effect.send(refuse(call, link.failure.message))
            val refusal = state.openLinkRefusal
            if (refusal != null) return Effect.send(refuse(call, refusal))
            return Effect.send(voiceAction(VoiceFeature.Action.OpenLink(VoiceOpenLinkFeature.Action.Requested(call))))
        }
        // Not refused in the background or under a sheet: nothing waits on the person, and
        // music on a call with the phone locked is normal.
        ClientTool.voicePlayAudio -> {
            if (!isLive) return Effect.send(refuse(call, CallAudioFailure.NoCall.message))
            return Effect.send(voiceAction(VoiceFeature.Action.CallAudio(VoiceCallAudioFeature.Action.PlayRequested(call))))
        }
        ClientTool.voiceAudioControl -> {
            if (!isLive) return Effect.send(refuse(call, CallAudioFailure.NoCall.message))
            return Effect.send(voiceAction(VoiceFeature.Action.CallAudio(VoiceCallAudioFeature.Action.ControlRequested(call))))
        }
    }
}

/** Reads where the delegator's session lives (one GET per session per call). */
private fun locateVoiceDelegator(state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val id = state.voiceDelegatorSessionID
    val server = state.home?.server
    if (id == null || server == null) {
        return Reduced(state.copy(voiceDelegatorLocation = null), Effect.cancel(ClientToolsLocateID.Locate))
    }
    if (state.voiceDelegatorLocation?.sessionID == id) return Reduced(state)
    val openCodeClient = dependency(OpenCodeClient)
    val effect = Effect.run<AppFeature.Action> { send ->
        try {
            val session = openCodeClient.getSession(server, id)
            send(AppFeature.Action.VoiceDelegatorLocated(ClientToolsLocation(id, session.directory)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.network.info { "client tools (delegator): couldn't read its session (${error.javaClass.simpleName})" }
        }
    }.cancellable(ClientToolsLocateID.Locate, cancelInFlight = true)
    return Reduced(state, effect)
}
