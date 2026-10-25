package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.settings.servers.ServerTitleFeature
import ai.opencode.server.friendlyName
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.feature.system.notifications.AppIconBadgeClient
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.rememberName

/*
 * Port of iOS `AppFeature+Servers.swift` and the server-list cases of `AppFeature.swift`
 * (`serverDeleted`, `forgetIdentity`, `selectServer`), including the Experimental Title Is the
 * Switch sheet's helpers (`serverTitleDelegate`, `serverTitleFailed`,
 * `cancelServerTitleConnection`, `openServerTitle`, `keepsHomeBehindSheet`).
 */

/** Renames a saved server in place, without reconnecting; Home takes the name too. */
internal fun renameServer(server: OpenCodeServer, name: String, state: AppFeature.State): AppFeature.State {
    val trimmed = name.trim()
    val renamed = (state.knownServers.firstOrNull { it.persistenceID == server.persistenceID } ?: server)
        .copy(name = trimmed.ifEmpty { null })
    var next = state
    val home = next.home
    if (home != null && home.server.persistenceID == renamed.persistenceID) {
        next = next.copy(home = home.copy(connection = home.connection.copy(server = home.server.copy(name = renamed.name))))
    }
    renamed.rememberName()
    val title = next.serverTitle ?: return next
    var renamedTitle = title
    if (title.current?.persistenceID == renamed.persistenceID) renamedTitle = renamedTitle.copy(current = renamed)
    val detail = title.page as? ServerTitleFeature.Page.Detail
    if (detail != null && detail.server.persistenceID == renamed.persistenceID) {
        renamedTitle = renamedTitle.copy(
            path = renamedTitle.path.dropLast(1) + ServerTitleFeature.Page.Detail(renamed),
            nameDraft = renamed.friendlyName,
        )
    }
    return next.copy(serverTitle = renamedTitle)
}

internal fun AppFeature.selectServer(
    server: OpenCodeServer,
    state: AppFeature.State,
    preservingPending: Boolean = false,
    keepingHome: Boolean = false,
): Reduced<AppFeature.State, AppFeature.Action> = connect(
    state.copy(isScanningPairingCode = false, isEditingServer = false, editingSavedServer = null).load(server),
    preservingPending = preservingPending,
    keepingHome = keepingHome,
)

/** Opens the form for a new server: the last failed entry, or a blank one. */
internal fun beginNewServer(state: AppFeature.State): AppFeature.State {
    val next = state.copy(isEditingServer = true, editingSavedServer = null)
    val draft = next.manualEntryDraft
    return if (draft != null) next.load(draft) else next.copy(
        serverURL = "",
        username = "opencode",
        password = "",
        alternateURLs = emptyList(),
        serverName = "",
        headers = emptyList(),
    )
}

/** Leaving Home to pair. Behind the server sheet Home stays up until the new server's sessions load. */
internal fun leaveHome(state: AppFeature.State): AppFeature.State {
    state.keepingHomeBehindSheet(switchingTo = null)?.let { return it }
    val current = state.homeServer ?: return state.withoutHome()
    return state.copy(returnServer = current).withoutHome()
}

/**
 * The server sheet over Home (iOS `keepsHomeBehindSheet(switchingTo:)`): an attempt from it keeps
 * Home until it succeeds, returning the state with Home showing the switch, or null when Home
 * doesn't stay. A call or dictation still ends Home first, as a switch from the drawer does.
 */
internal fun AppFeature.State.keepingHomeBehindSheet(switchingTo: OpenCodeServer?): AppFeature.State? {
    val home = home
    if (serverTitle == null || home == null || home.hasLiveCallOrRecording) return null
    if (switchingTo != null && switchingTo.persistenceID != home.server.persistenceID) return showingSwitch(switchingTo)
    return this
}

/** With no Home the sheet opens on Connect; on first run, with no servers, Connect is all it shows. */
internal fun openServerTitle(state: AppFeature.State): AppFeature.State =
    state.copy(serverTitle = ServerTitleFeature.State.opening(state.homeServer, hasHome = state.home != null))

internal fun AppFeature.serverTitleDelegate(
    delegate: ServerTitleFeature.Delegate,
    state: AppFeature.State,
): Reduced<AppFeature.State, AppFeature.Action> = when (delegate) {
    ServerTitleFeature.Delegate.Close -> cancelServerTitleConnection(state.copy(serverTitle = null))
    is ServerTitleFeature.Delegate.Select -> {
        val home = state.home
        when {
            home == null -> Reduced(state, Effect.send(AppFeature.Action.ServerTapped(delegate.server)))
            // Already in use: there's nothing to switch to, so the sheet mustn't wait on it.
            home.server.persistenceID == delegate.server.persistenceID ->
                Reduced(state.copy(serverTitle = state.serverTitle?.copy(busy = false)))
            else -> Reduced(
                state,
                Effect.send(AppFeature.Action.Home(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerSwitchTapped(delegate.server)))),
            )
        }
    }
    is ServerTitleFeature.Delegate.Reconnect ->
        if (state.home == null) Reduced(state) else Reduced(state, Effect.send(AppFeature.Action.Home(HomeFeature.Action.RetryConnectionTapped)))
    is ServerTitleFeature.Delegate.Pair -> Reduced(state, Effect.send(AppFeature.Action.PairingCodeScanned(delegate.text)))
    is ServerTitleFeature.Delegate.Connect ->
        Reduced(state.copy(editingSavedServer = null).load(delegate.server), Effect.send(AppFeature.Action.ConnectButtonTapped))
    is ServerTitleFeature.Delegate.Rename -> Reduced(
        state,
        Effect.send(AppFeature.Action.Home(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerRenamed(delegate.server, delegate.name)))),
    )
    is ServerTitleFeature.Delegate.Remove -> Reduced(state, Effect.send(AppFeature.Action.ServerDeleted(delegate.server)))
}

/**
 * Shows a failed attempt in the server sheet (iOS `serverTitleFailed`). `handled` is true when
 * Home was behind the sheet: then Home and the saved server stay exactly as they were, and only a
 * successful switch replaces them.
 */
internal data class ServerTitleFailure(val state: AppFeature.State, val handled: Boolean)

internal fun serverTitleFailed(message: String, state: AppFeature.State): ServerTitleFailure {
    val title = state.serverTitle ?: return ServerTitleFailure(state, false)
    var next = state.copy(serverTitle = title.copy(busy = false, error = message, failureRevision = title.failureRevision + 1))
    dependency(HapticsClient).play(Haptic.error)
    val home = next.home ?: return ServerTitleFailure(next, false)
    val current = home.server
    next = next.copy(
        home = home.copy(switchingTo = null),
        connection = AppFeature.State.Connection.Connected,
        connectionAttempt = null,
        editingSavedServer = null,
        returnServer = null,
    ).load(current)
    // Health may have saved the new server before its sessions failed to load.
    if (next.savedServer.value != current) next.savedServer.set(current)
    return ServerTitleFailure(next, true)
}

internal fun cancelServerTitleConnection(state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    var next = state
    val home = next.home
    if (home != null) {
        val current = home.server
        next = next.copy(home = home.copy(switchingTo = null), returnServer = null).load(current)
        // Health may have succeeded before the Home read was cancelled. Relaunch must still
        // restore the server the person is looking at.
        if (next.savedServer.value != current) next.savedServer.set(current)
    }
    return cancelConnection(next, if (next.home == null) AppFeature.State.Connection.Picking else AppFeature.State.Connection.Connected)
}

/** An empty or wrong clipboard is an ordinary mistake: say so beside Paste, not in an error report. */
internal fun pastePairingCode(state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val haptics = dependency(HapticsClient)
    val text = dependency(PasteboardClient).string()
    if (text == null || text.isBlank()) {
        haptics.play(Haptic.error)
        return cancelConnection(state.copy(pasteError = AppFeature.EMPTY_CLIPBOARD_MESSAGE))
    }
    validatePairing(text).exceptionOrNull()?.let { error ->
        haptics.play(Haptic.error)
        val message = if (error.message == AppFeature.invalidPairingCodeMessage) AppFeature.NOT_A_PAIRING_LINK_MESSAGE else error.message.orEmpty()
        return cancelConnection(state.copy(pasteError = message))
    }
    return Reduced(state, Effect.send(AppFeature.Action.PairingCodeScanned(text)))
}

/** Exchange a one-time pairing link for a token, then connect normally. */
internal fun validatePairing(text: String): Result<OpenCodePairingLink> {
    val link = OpenCodePairingLink.decode(text)
        ?: return Result.failure(OpenCodeClientError(AppFeature.invalidPairingCodeMessage))
    // `opencode pair` reports whatever the service is bound to. If that's only loopback, no
    // amount of retrying will help — say so precisely.
    if (link.isLoopback) return Result.failure(OpenCodeClientError(AppFeature.loopbackPairingMessage))
    return Result.success(link.normalizedForRedemption)
}

internal fun cancelConnection(
    state: AppFeature.State,
    connection: AppFeature.State.Connection = AppFeature.State.Connection.Picking,
): Reduced<AppFeature.State, AppFeature.Action> = Reduced(
    state.copy(connection = connection, connectionAttempt = null, pendingNotification = null, pendingSystemAction = null),
    Effect.cancel(
        listOf(AppFeature.CancelID.Health, AppFeature.CancelID.Home, AppFeature.CancelID.Notification, AppFeature.CancelID.SystemSession),
    ),
)

internal fun serverDeleted(state: AppFeature.State, server: OpenCodeServer): Reduced<AppFeature.State, AppFeature.Action> {
    var next = state
    if (next.returnServer?.persistenceID == server.persistenceID) next = next.copy(returnServer = null)
    val wasEditingServer = next.editingSavedServer?.persistenceID == server.persistenceID
    if (wasEditingServer) next = next.copy(editingSavedServer = null, isEditingServer = false)
    next.cachedHomeSnapshots.update { it - server.persistenceID }
    next.savedServers.update { servers -> servers.filterNot { it.persistenceID == server.persistenceID } }
    if (next.savedServer.value?.persistenceID == server.persistenceID) next.savedServer.set(null)
    discardStagedFiles(next.pendingShares.filter { it.handoff.serverID == server.persistenceID })
    next = next.copy(pendingShares = next.pendingShares.filterNot { it.handoff.serverID == server.persistenceID })
    dependency(ShareInboxClient).removeTargets(server.persistenceID)
    val forgotten = forgetIdentity(server.persistenceID, next)
    next = forgotten.state
    if (wasEditingServer || next.connectingServerID == server.id) {
        val cancelled = cancelConnection(next)
        return Reduced(cancelled.state, Effect.merge(cancelled.effect, forgotten.effect))
    }
    return forgotten
}

/**
 * Forgets what the system, notifications, the icon's badge and the composer keep for a server
 * identity that is no longer saved.
 */
internal fun forgetIdentity(persistenceID: String, state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    dependency(ComposerPersistence).removeServer(persistenceID)
    val transcripts = ServerSharedKeys.cachedTranscriptSnapshots()
    if (transcripts.value.entries.any { it.serverID == persistenceID }) transcripts.update { it.removeEntries(persistenceID) }
    dependency(SystemIntegrationClient).removeServer(SessionDirectory.serverKey(persistenceID))
    val sessionNotifications = dependency(SessionNotificationsClient)
    val appIconBadge = dependency(AppIconBadgeClient)
    return Reduced(
        state,
        Effect.run {
            sessionNotifications.forget(persistenceID)
            appIconBadge.forget(persistenceID)
        },
    )
}
