package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys

/*
 * Port of iOS `AppFeature+Servers.swift` and the server-list cases of `AppFeature.swift`
 * (`serverDeleted`, `forgetIdentity`, `selectServer`). The server-title sheet helpers
 * (`renameServer`, `serverTitleDelegate`, `serverTitleFailed`, `cancelServerTitleConnection`,
 * `openServerTitle`, `keepsHomeBehindSheet`) wait for `ServerTitleFeature` (Experimental
 * Title-Is-the-Switch, off by default) in `:feature:settings`; with no sheet they are no-ops.
 */

internal fun AppFeature.selectServer(
    server: OpenCodeServer,
    state: AppFeature.State,
    preservingPending: Boolean = false,
    keepingHome: Boolean = false,
): Reduced<AppFeature.State, AppFeature.Action> = connect(
    state.copy(isScanningPairingCode = false, isScannerUnavailable = false, isEditingServer = false, editingSavedServer = null).load(server),
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

/** Leaving Home to pair. (Behind the server sheet, Home stays until the new server loads.) */
internal fun leaveHome(state: AppFeature.State): AppFeature.State {
    val current = state.homeServer ?: return state.withoutHome()
    return state.copy(returnServer = current).withoutHome()
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
    // Pending shares and the share inbox's targets are forgotten here once :feature:system
    // ports `ShareInbox` (iOS `discardStagedFiles`, `shareInbox.removeTargets`).
    val forgotten = forgetIdentity(server.persistenceID, next)
    next = forgotten.state
    if (wasEditingServer || next.connectingServerID == server.id) {
        val cancelled = cancelConnection(next)
        return Reduced(cancelled.state, Effect.merge(cancelled.effect, forgotten.effect))
    }
    return forgotten
}

/**
 * Forgets what the app keeps for a server identity that is no longer saved: its cached
 * transcripts and its system integrations. (Composer drafts, session notifications and the
 * badge ledger are forgotten here once their areas port `ComposerPersistence.removeServer`,
 * `SessionNotifications.forget` and `AppIconBadge.forget`.)
 */
internal fun forgetIdentity(persistenceID: String, state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val transcripts = ServerSharedKeys.cachedTranscriptSnapshots()
    if (transcripts.value.entries.any { it.serverID == persistenceID }) transcripts.update { it.removeEntries(persistenceID) }
    dependency(SystemIntegrationClient).removeServer(SessionDirectory.serverKey(persistenceID))
    return Reduced(state)
}
