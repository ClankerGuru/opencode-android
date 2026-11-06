package ai.opencode.feature.drawer

import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.session.ServerConnectionNotice
import ai.opencode.feature.session.ServerFlowTunables
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

/*
 * Server Flow (OC-582, iOS `OpenCode/Drawer/ServerFlow.swift`, d8a311bd, graduated in p217):
 * what the drawer and a session show of the servers' connections. Picking a server swaps the
 * drawer to it at once with Title Leads' motion: its name rolls in, ghost rows follow it like a
 * chain, and an underline shimmers under the name until the server answers. One that fails, or
 * doesn't answer in `ServerFlowTunables.timeout`, is switched back from on its own with a note
 * (the switch and switch-back are AppFeature's). A session whose server drops shows a pill above
 * the composer that retries.
 *
 * `ServerFlowTunables` and `ServerConnectionNotice` live in `:feature:session`
 * (`ServerConnectionPill.kt`), which reads the pill's palette and carries the notice in
 * `SessionListContext`.
 */

/** Why a server was last given up on, shown in the server menu. */
enum class ServerReachability { unreachable, signInFailed }

/** Left in the drawer after a switch went back on its own. */
data class ServerSwitchNote(
    /** The server that didn't answer. */
    val server: OpenCodeServer,
    /** The name of the server it went back to. */
    val returnedTo: String,
    val reason: Reason,
) {
    sealed interface Reason {
        /** It didn't answer within this many seconds. */
        data class TimedOut(val seconds: Double) : Reason
        data object Unreachable : Reason
        data object SignInFailed : Reason
    }

    val title: String get() = "Switched back to $returnedTo"

    val detail: String
        get() {
            val name = server.friendlyName
            return when (reason) {
                is Reason.TimedOut -> {
                    val seconds = reason.seconds
                    val shown = if (seconds == Math.rint(seconds)) seconds.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", seconds)
                    "$name didn’t answer in $shown s"
                }
                Reason.Unreachable -> "Couldn’t connect to $name"
                Reason.SignInFailed -> "Couldn’t sign in to $name"
            }
        }

    val systemImage: SFSymbol
        get() = when (reason) {
            is Reason.TimedOut -> SFSymbol.Clock
            Reason.Unreachable -> SFSymbol.ExclamationmarkCircleFill
            Reason.SignInFailed -> SFSymbol.KeyFill
        }

    /** The note's button: pair again for a sign-in the server refused, otherwise try again. */
    val action: String get() = if (reason == Reason.SignInFailed) "Pair Again" else "Try Again"

    /** What the server menu shows for the server afterwards. */
    val reachability: ServerReachability
        get() = if (reason == Reason.SignInFailed) ServerReachability.signInFailed else ServerReachability.unreachable
}

/** A server's status in the server menu: a glyph and a word. */
enum class ServerMenuStatus(val title: String, val systemImage: SFSymbol) {
    connected("Connected", SFSymbol.CircleFill),
    connecting("Connecting…", SFSymbol.ArrowTriangle2Circlepath),
    unreachable("Unreachable", SFSymbol.ExclamationmarkCircle),
    signInFailed("Sign-in failed", SFSymbol.Key),
    ;

    companion object {
        fun of(
            server: OpenCodeServer,
            current: OpenCodeServer,
            status: HomeConnectionFeature.State.ConnectionStatus,
            credentialsRejected: Boolean,
            switchingTo: OpenCodeServer?,
            reachability: Map<String, ServerReachability>,
        ): ServerMenuStatus? {
            val id = server.persistenceID
            if (id == switchingTo?.persistenceID) return connecting
            if (id == current.persistenceID) {
                return when (status) {
                    HomeConnectionFeature.State.ConnectionStatus.connected -> connected
                    HomeConnectionFeature.State.ConnectionStatus.reconnecting -> connecting
                    HomeConnectionFeature.State.ConnectionStatus.disconnected -> if (credentialsRejected) signInFailed else unreachable
                }
            }
            return when (reachability[id]) {
                ServerReachability.unreachable -> unreachable
                ServerReachability.signInFailed -> signInFailed
                null -> null
            }
        }
    }
}

/**
 * iOS `ServerConnectionNotice.init?(status:credentialsRejected:errorMessage:server:isSwitching:reconnectingIsDue:)`:
 * null while connected, while the drawer switches servers (the switch is the drawer's to show),
 * and while a reconnect is still in its grace ([ServerConnectionGrace]).
 */
operator fun ServerConnectionNotice.Companion.invoke(
    status: HomeConnectionFeature.State.ConnectionStatus,
    credentialsRejected: Boolean,
    errorMessage: String?,
    server: OpenCodeServer,
    isSwitching: Boolean,
    reconnectingIsDue: Boolean = true,
): ServerConnectionNotice? = of(
    isConnected = status == HomeConnectionFeature.State.ConnectionStatus.connected,
    isReconnecting = status == HomeConnectionFeature.State.ConnectionStatus.reconnecting,
    credentialsRejected = credentialsRejected,
    errorMessage = errorMessage,
    serverName = server.friendlyName,
    isSwitching = isSwitching,
    reconnectingIsDue = reconnectingIsDue,
)

/**
 * iOS `ServerConnectionGrace`: holds “Reconnecting…” back for [ServerFlowTunables.grace]. Launch
 * restores Home as reconnecting until its first snapshot, so without it the pill flashed on every
 * launch and the transcript stepped up and back by its height. A retry from a pill already on
 * screen shows at once. The grace runs only while the app is in front (iOS `scenePhase ==
 * .active`, RESUMED here) and starts over after the background, where it could have run out.
 */
@Composable
internal fun ServerConnectionGrace(status: HomeConnectionFeature.State.ConnectionStatus, view: HomeViewState) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isActive = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val isInBackground = !lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val isReconnecting = status == HomeConnectionFeature.State.ConnectionStatus.reconnecting
    val currentStatus by rememberUpdatedState(status)
    LaunchedEffect(status) {
        if (status != HomeConnectionFeature.State.ConnectionStatus.reconnecting) {
            view.reconnectingPillIsDue = status == HomeConnectionFeature.State.ConnectionStatus.disconnected
        }
    }
    LaunchedEffect(isInBackground) {
        if (isInBackground && currentStatus == HomeConnectionFeature.State.ConnectionStatus.reconnecting) {
            view.reconnectingPillIsDue = false
        }
    }
    LaunchedEffect(isReconnecting && isActive) {
        if (!isReconnecting || !isActive || view.reconnectingPillIsDue) return@LaunchedEffect
        delay((ServerFlowTunables.grace * 1000).toLong())
        view.reconnectingPillIsDue = true
    }
}

/** The server the drawer shows, as the swap's motion needs it (iOS `ServerSwapTarget`). */
data class ServerSwapTarget(
    /** Its persistence ID. */
    val id: String,
    val name: String,
    /** Its place in the server menu: the name rolls the way the menu goes. */
    val order: Int,
    /** Not answered yet: the underline shimmers under the name. */
    val isConnecting: Boolean,
) {
    companion object {
        /** The server picked, from the moment it's picked. */
        operator fun invoke(current: OpenCodeServer, switchingTo: OpenCodeServer?, servers: List<OpenCodeServer>): ServerSwapTarget {
            val shown = switchingTo ?: current
            return ServerSwapTarget(
                id = shown.persistenceID,
                name = shown.friendlyName,
                order = servers.indexOfFirst { it.persistenceID == shown.persistenceID }.coerceAtLeast(0),
                isConnecting = switchingTo != null,
            )
        }
    }
}
