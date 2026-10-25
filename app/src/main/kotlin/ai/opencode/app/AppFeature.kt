package ai.opencode.app

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.ifLet
import ai.opencode.core.ui.ThisDevice
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.ConnectionFailureDetail
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.remember
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Port of iOS `AppFeature` (`OpenCode/App/AppFeature.swift`, `AppFeature+Servers.swift`): routes
 * between the connection screen and Home, owns the manual server form, pairing (scan, paste,
 * opened links with confirmation), saved-server edit and removal, the connect → health → Home
 * snapshot state machine, and routes notification taps and system actions to Home.
 *
 * Until `:feature:drawer` ports `HomeFeature`, Home is the phase 0 stub: [State.homeServer]
 * stands in for iOS `home.server`, and Home's iOS actions (`sessionTapped`,
 * `systemActionOpened`, `pairedComputerOpened`) map onto the stub's (see [openInHome]). See
 * `docs/parity/app.md` for what is still missing.
 */
class AppFeature : Reducer<AppFeature.State, AppFeature.Action> {
    data class State(
        val serverURL: String = "",
        val username: String = "opencode",
        val password: String = "",
        /** Blank shows the server's default name. */
        val serverName: String = "",
        /** The form's header rows, blank ones included while being typed. */
        val headers: List<OpenCodeServer.Header> = emptyList(),
        /** The last manual entry that failed, restored when the form reopens. */
        val manualEntryDraft: OpenCodeServer? = null,
        /** The server that was open when the user left it to pick another. */
        val returnServer: OpenCodeServer? = null,
        /** Why the welcome's Paste didn't pair, shown beside it. */
        val pasteError: String? = null,
        val connection: Connection = Connection.Idle,
        val connectionGeneration: Int = 0,
        val connectionAttempt: ConnectionAttempt? = null,
        val home: HomeFeature.State? = null,
        /** iOS `home.server` until the drawer's `HomeFeature.State` carries it. */
        val homeServer: OpenCodeServer? = null,
        val serverSwitchConfirmation: String? = null,
        /** A tapped notification's session, opened once its server's home loads. */
        val pendingNotification: NotificationDestination? = null,
        val notificationError: String? = null,
        /** An intent, shortcut or link, waiting for its server's home to load. */
        val pendingSystemAction: SystemAction? = null,
        val systemActionError: String? = null,
        /** Not in the background. */
        val isInForeground: Boolean = true,
        /** Manual entry is opt-in; first launch presents the pairing actions. */
        val isEditingServer: Boolean = false,
        /** The saved entry being edited, replaced only once the edited target loads. */
        val editingSavedServer: OpenCodeServer? = null,
        /** Addresses discovered from a pairing code, tried alongside `serverURL`. */
        val alternateURLs: List<String> = emptyList(),
        val isScanningPairingCode: Boolean = false,
        val pendingPairing: PendingPairing? = null,
        val ignoredPendingPairingCount: Int = 0,
        val pairingError: String? = null,
        val savedServer: Shared<OpenCodeServer?>,
        val savedServers: Shared<List<OpenCodeServer>>,
        val cachedHomeSnapshots: Shared<Map<String, CachedHomeSnapshot>>,
        val lastViewedSessionIDs: Shared<Map<String, String>>,
    ) {
        sealed interface Connection {
            data object Idle : Connection
            /** Redeeming a one-time `opencode pair` link for a session token. */
            data object Pairing : Connection
            data object Connecting : Connection
            data class LoadingHome(val server: OpenCodeServer) : Connection
            data object Connected : Connection
            data class Failed(
                val message: String,
                val phase: FailureContext.Phase = FailureContext.Phase.Connect,
                val context: FailureContext? = null,
            ) : Connection
            /** Left the session list on purpose to pick a server; must not auto-reconnect. */
            data object Picking : Connection
        }

        val knownServers: List<OpenCodeServer> get() = savedServers.value + listOfNotNull(savedServer.value)

        /**
         * Cold launch keeps the splash until there's something to show: no Home, or Home with
         * its list (iOS `isColdLaunchContentReady`; the stub Home has no open-session frame).
         */
        val isColdLaunchContentReady: Boolean
            get() = home == null || connection == Connection.Connected || connection is Connection.Failed || home.sessions.isNotEmpty()

        /** iOS `home.snapshotLoaded`: Home has heard from its server once. */
        val isHomeSnapshotLoaded: Boolean get() = home != null && connection == Connection.Connected

        /** A blank username field means the server's default user, like the web app. */
        val server: OpenCodeServer
            get() {
                val name = serverName.trim()
                return OpenCodeServer(
                    url = serverURL,
                    username = if (username.isBlank()) "opencode" else username,
                    password = password,
                    alternateURLs = alternateURLs,
                    name = name.ifEmpty { null },
                    headers = headers.mapNotNull { header ->
                        val headerName = header.name.trim()
                        if (headerName.isEmpty()) null else OpenCodeServer.Header(headerName, header.value.trim())
                    },
                )
            }

        /** Why the form's headers can't be sent, if they can't. */
        val headerProblem: String?
            get() {
                server.headers.firstOrNull { !it.hasValidName }?.let { return "“${it.name}” isn’t a valid header name" }
                server.headers.firstOrNull { !it.hasValidValue }?.let { return "The value of ${it.name} can’t contain a line break" }
                return null
            }

        /** Placeholder for the Name field: the address's friendly name once typed, or "Optional". */
        val credentialNamePrompt: String
            get() {
                val address = withDefaultScheme(serverURL.trim())
                if (address.isEmpty()) return "Optional"
                var target = server.copy(name = null, url = address)
                val original = editingSavedServer
                if (original != null && address != original.url) target = target.copy(alternateURLs = emptyList())
                return target.friendlyName
            }

        val connectingServerID: String?
            get() = when (val connection = connection) {
                Connection.Pairing, Connection.Connecting -> connectionAttempt?.server?.id
                is Connection.LoadingHome -> connection.server.id
                else -> null
            }

        val connectionProgressLabel: String?
            get() = when (connection) {
                Connection.Pairing -> "Pairing"
                Connection.Connecting -> "Connecting"
                is Connection.LoadingHome -> "Loading sessions"
                else -> null
            }

        /**
         * Anything shown over Home from the app: the server setup and scanner sheets, the pairing
         * confirmation and the error alerts. (Home's own presentations join when the drawer ports.)
         */
        val isPresentingAnything: Boolean
            get() = isEditingServer || isScanningPairingCode || pendingPairing != null || pairingError != null ||
                notificationError != null || systemActionError != null

        val errorMessage: String? get() = (connection as? Connection.Failed)?.message
        val errorContext: FailureContext? get() = (connection as? Connection.Failed)?.context
        val errorPhase: FailureContext.Phase? get() = (connection as? Connection.Failed)?.let { it.context?.phase ?: it.phase }

        /** Fills the connection form with a complete target. */
        fun load(server: OpenCodeServer): State = copy(
            serverURL = server.url,
            username = server.username,
            password = server.password,
            alternateURLs = server.alternateURLs,
            serverName = server.name ?: "",
            headers = server.headers,
        )

        /** Generated only when copying: captured safe context, never form credentials. */
        fun errorReport(appVersion: String, build: String, generatedAt: java.time.Instant): String? {
            val message = errorMessage ?: return null
            val lines = mutableListOf(
                "OpenCode connection error",
                "App: OpenCode $appVersion ($build)",
                "Report generated at: ${java.time.format.DateTimeFormatter.ISO_INSTANT.format(generatedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))}",
            )
            errorContext?.let { context ->
                lines += listOf("Phase: ${context.phase.rawValue}", "Server: ${context.target}", "Attempt: ${context.attempt}")
                if (context.details.isNotEmpty()) {
                    if (context.phase == FailureContext.Phase.Connect) lines += listOf("", "Couldn’t connect to ${context.friendlyName}")
                    lines += listOf("", context.detailText)
                }
            }
            return (lines + listOf("", "Message:", message)).joinToString("\n")
        }

        companion object {
            /** iOS `State.init()`: restores the last server and its cached Home so launch shows it at once. */
            fun initial(): State {
                val saved = shared(ServerSharedKeys.savedServer)
                val cached = shared(ServerSharedKeys.cachedHomeSnapshots)
                var state = State(
                    savedServer = saved,
                    savedServers = shared(ServerSharedKeys.savedServers),
                    cachedHomeSnapshots = cached,
                    lastViewedSessionIDs = shared(SharedKeys.lastViewedSessionIDs),
                )
                val server = saved.value ?: return state
                state = state.load(server)
                val snapshot = cached.value[server.persistenceID]?.snapshot
                return state.withHome(server, snapshot)
            }
        }

        /** iOS `HomeFeature.State(server:snapshot:)` on the stub Home: root sessions, newest first. */
        internal fun withHome(server: OpenCodeServer, snapshot: OpenCodeHomeSnapshot?): State = copy(
            homeServer = server,
            home = HomeFeature.State(
                serverID = server.persistenceID,
                serverName = server.friendlyName,
                server = server,
                sessions = snapshot?.sessions.orEmpty().filter { it.parentID == null }.sortedByDescending { it.updatedAt },
            ),
        )

        internal fun withoutHome(): State = copy(home = null, homeServer = null)

        /**
         * Whether Home can stay up while another server connects (iOS `keepsHome(switchingTo:)`):
         * not for the server in use, nor an edit of it. (Calls and recordings, which also end
         * Home first on iOS, arrive with the drawer's Home.)
         */
        fun keepsHome(switchingTo: OpenCodeServer): Boolean {
            val server = switchingTo
            val current = homeServer ?: return false
            if (home == null || current.persistenceID == server.persistenceID) return false
            return editingSavedServer?.persistenceID != current.persistenceID
        }
    }

    /**
     * An unsolicited pairing link (an opened link, a transcript or browser tap) waiting for
     * explicit confirmation before redeeming, navigating, or replacing saved credentials.
     */
    data class PendingPairing(
        val id: String,
        val link: OpenCodePairingLink,
        val replacedServerNames: List<String> = emptyList(),
    ) {
        val replacedServerName: String? get() = replacedServerNames.firstOrNull()

        val host: String get() = link.server.host

        /** Distinct display hosts across `link.redeemOrigins`, preserving order. */
        val hosts: List<String>
            get() {
                val seen = HashSet<String>()
                return link.redeemOrigins
                    .map { OpenCodeServer(url = it, username = "opencode", password = "").host }
                    .filter { seen.add(it.lowercase()) }
            }

        val alternateHosts: List<String> get() = hosts.drop(1)

        val title: String get() = "Pair with $host?"

        val message: String
            get() {
                val allHosts = hosts
                val action = when (allHosts.size) {
                    0, 1 -> if (link.redeemOrigins.size > 1) {
                        "OpenCode will try $host and switch to the first address that answers."
                    } else {
                        "OpenCode will pair with $host and switch to it."
                    }
                    2 -> "OpenCode will try ${allHosts[0]} and ${allHosts[1]} and switch to the first that answers."
                    else -> {
                        val leading = allHosts.dropLast(1).joinToString(", ")
                        "OpenCode will try $leading, and ${allHosts.last()} and switch to the first that answers."
                    }
                }
                if (replacedServerNames.isEmpty()) return action
                return "$action Saved credentials for ${replacedServerNames.joinToString(", ")} may be replaced."
            }
    }

    /** Form edits cannot change the target of work already in flight. */
    data class ConnectionAttempt(
        val id: Int,
        val server: OpenCodeServer,
        /** A computer paired for the first time: its Home opens the drawer on its sessions. */
        val opensDrawer: Boolean = false,
    )

    /** Safe to display or copy: no full URL, credentials, or raw network payload. */
    data class FailureContext(
        val phase: Phase,
        val target: String,
        val attempt: Int,
        /** The server's name, when it says more than the address does. */
        val name: String?,
        val details: List<ConnectionFailureDetail>,
        val friendlyName: String,
    ) {
        enum class Phase(val rawValue: String) { Pair("Pair"), Connect("Connect"), LoadSessions("Load sessions") }

        /** For the error card: the name with the address, or just the address. */
        val serverLabel: String get() = name?.let { "$it ($target)" } ?: target

        val detailText: String
            get() = details.withIndex().joinToString("\n\n") { (index, detail) ->
                (if (details.size > 1) "Candidate ${index + 1}: " else "") + detail.text
            }

        companion object {
            private val ALLOWED_HOST = Regex("^[A-Za-z0-9.\\-:\\[\\]]+$")
            private const val INVALID = "Unavailable (invalid address)"

            /** Host and port only; anything else reads "Unavailable (invalid address)". */
            fun safeTarget(address: String): String {
                val uri = runCatching { java.net.URI(address) }.getOrNull() ?: return INVALID
                if (uri.scheme?.lowercase() !in setOf("http", "https")) return INVALID
                val host = uri.host?.takeIf { it.isNotEmpty() && ALLOWED_HOST.matches(it) } ?: return INVALID
                val port = uri.port.takeIf { it != -1 }
                if (port != null && port !in 1..65535) return INVALID
                val hostname = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
                return port?.let { "$hostname:$it" } ?: hostname
            }

            fun of(server: OpenCodeServer, phase: Phase, attempt: Int, details: List<ConnectionFailureDetail> = emptyList()): FailureContext {
                val target = safeTarget(server.url)
                val chosen = server.name?.trim().orEmpty()
                val valid = target != INVALID
                val name = if (chosen.isNotEmpty()) chosen else if (valid) server.displayName else "server"
                return FailureContext(
                    phase = phase,
                    target = target,
                    attempt = attempt,
                    name = if (name == OpenCodeServer.machineName(target)) null else name,
                    details = details.sortedBy { it.endpoint },
                    friendlyName = if (chosen.isNotEmpty()) chosen else if (valid) server.friendlyName else "server",
                )
            }
        }
    }

    sealed interface Action {
        data object Task : Action
        data class ForegroundChanged(val isInForeground: Boolean) : Action
        data class ServerURLChanged(val value: String) : Action
        data class UsernameChanged(val value: String) : Action
        data class PasswordChanged(val value: String) : Action
        data class ServerNameChanged(val value: String) : Action
        data class HeadersChanged(val headers: List<OpenCodeServer.Header>) : Action
        data object ServerSwitchConfirmationDismissed : Action
        data class NotificationOpened(val destination: NotificationDestination) : Action
        data class NotificationSessionLoaded(val destination: NotificationDestination, val result: Result<OpenCodeSession>) : Action
        data object NotificationErrorDismissed : Action
        /** Listens for shortcuts, widgets and intents for as long as the app runs. */
        data object SystemActionsTask : Action
        /** From an app shortcut, a widget, an intent or an `opencode://` link. */
        data class SystemActionReceived(val action: SystemAction) : Action
        /** A session the action names that the list doesn't have. */
        data class SystemSessionLoaded(val action: SystemAction, val sessionID: String, val result: Result<OpenCodeSession>) : Action
        data object SystemActionErrorDismissed : Action
        data object ConnectButtonTapped : Action
        data object AddServerTapped : Action
        data class EditServerTapped(val server: OpenCodeServer) : Action
        data object CancelEditingTapped : Action
        data class ServerTapped(val server: OpenCodeServer) : Action
        data object ReturnToServerTapped : Action
        data class ServerDeleted(val server: OpenCodeServer) : Action
        data object ScanPairingCodeTapped : Action
        data object ScannerDismissed : Action
        data object PastePairingCodeTapped : Action
        data class PairingCodeScanned(val text: String) : Action
        data class PairingURLOpened(val url: String) : Action
        data class PairingConfirmed(val id: String) : Action
        data class PairingCancelled(val id: String) : Action
        data object PairingErrorDismissed : Action
        data class PairingRedeemed(val attemptID: Int, val result: Result<OpenCodeServer>) : Action
        data class HealthResponse(val attemptID: Int, val result: Result<OpenCodeServer>) : Action
        data class CopyTextTapped(val text: String) : Action
        data class Home(val action: HomeFeature.Action) : Action
        data class HomeResponse(val attemptID: Int, val result: Result<OpenCodeHomeSnapshot>) : Action
    }

    internal enum class CancelID { Health, Home, Notification, SystemActions, SystemSession }

    private val core = Reducer<State, Action> { state, action -> reduceCore(state, action) }

    /** `.ifLet(\.home, action: \.home)` (plain optional: Home runs first on its own actions). */
    private val body: Reducer<State, Action> = ifLet(
        parent = core,
        state = { it.home },
        setState = { s, home -> s.copy(home = home) },
        action = { (it as? Action.Home)?.action },
        embed = Action::Home,
        child = HomeFeature(),
    )

    override fun reduce(state: State, action: Action): Reduced<State, Action> = body.reduce(state, action)

    private fun reduceCore(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            // Restored home owns revalidation on iOS; the stub Home can't, so the app reconnects
            // its server behind it and keeps the cached list up meanwhile.
            val saved = state.savedServer.value
            if (state.connection != State.Connection.Idle) Reduced(state)
            // A notification or system action that arrived first waits for that Home's list, as
            // iOS's restored Home does, so the reconnect keeps it.
            else if (saved != null) connect(state.load(saved), preservingPending = state.home != null, keepingHome = state.home != null)
            // With no last-used server, wait for a scan or an explicit selection.
            else Reduced(state)
        }
        is Action.ForegroundChanged -> {
            if (state.isInForeground != action.isInForeground) AppLog.app.info { if (action.isInForeground) "Foreground" else "Background" }
            Reduced(state.copy(isInForeground = action.isInForeground))
        }
        is Action.ServerURLChanged -> serverEdited(state, state.copy(serverURL = action.value))
        is Action.UsernameChanged -> serverEdited(state, state.copy(username = action.value))
        is Action.PasswordChanged -> serverEdited(state, state.copy(password = action.value))
        is Action.ServerNameChanged -> serverEdited(state, state.copy(serverName = action.value))
        is Action.HeadersChanged -> serverEdited(state, state.copy(headers = action.headers))
        Action.ServerSwitchConfirmationDismissed -> Reduced(state.copy(serverSwitchConfirmation = null))
        is Action.NotificationOpened -> notificationOpened(state, action.destination)
        is Action.NotificationSessionLoaded -> notificationSessionLoaded(state, action)
        Action.NotificationErrorDismissed -> Reduced(state.copy(notificationError = null))
        Action.SystemActionsTask -> {
            val actions = dependency(SystemIntegrationClient).actions
            Reduced(
                state,
                Effect.run<Action> { send -> actions().collect { send(Action.SystemActionReceived(it)) } }
                    .cancellable(CancelID.SystemActions, cancelInFlight = true),
            )
        }
        is Action.SystemActionReceived -> systemActionReceived(state, action.action)
        is Action.SystemSessionLoaded -> systemSessionLoaded(state, action)
        Action.SystemActionErrorDismissed -> Reduced(state.copy(systemActionError = null))
        Action.ConnectButtonTapped -> connectButtonTapped(state)
        Action.AddServerTapped -> cancelConnection(beginNewServer(state))
        is Action.EditServerTapped -> {
            val cancelled = cancelConnection(state)
            Reduced(
                cancelled.state.copy(editingSavedServer = action.server, manualEntryDraft = null, isEditingServer = true).load(action.server),
                cancelled.effect,
            )
        }
        Action.CancelEditingTapped -> {
            val next = state.copy(isEditingServer = false, editingSavedServer = null)
            if (next.home != null) Reduced(next) else cancelConnection(next)
        }
        is Action.ServerTapped -> selectServer(action.server, state)
        Action.ReturnToServerTapped -> state.returnServer?.let { selectServer(it, state) } ?: Reduced(state)
        is Action.ServerDeleted -> serverDeleted(state, action.server)
        Action.ScanPairingCodeTapped -> cancelConnection(
            state.copy(pasteError = null, isScanningPairingCode = true, editingSavedServer = null),
        )
        Action.ScannerDismissed -> {
            val next = state.copy(isScanningPairingCode = false)
            // Opened over Home, there's no attempt to cancel and Home carries on.
            if (next.home != null) Reduced(next) else cancelConnection(next)
        }
        Action.PastePairingCodeTapped -> pastePairingCode(state)
        is Action.PairingCodeScanned -> pairingCodeScanned(state, action.text)
        is Action.PairingURLOpened -> {
            LaunchBloomController.completeColdLaunch()
            requestPairing(action.url, state)
        }
        is Action.PairingConfirmed -> {
            val pending = state.pendingPairing
            if (pending == null || pending.id != action.id) Reduced(state)
            else beginPairing(pending.link, state.copy(pendingPairing = null, ignoredPendingPairingCount = 0))
        }
        is Action.PairingCancelled -> {
            if (state.pendingPairing?.id != action.id) Reduced(state)
            else Reduced(state.copy(pendingPairing = null, ignoredPendingPairingCount = 0))
        }
        Action.PairingErrorDismissed -> Reduced(state.copy(pairingError = null))
        is Action.PairingRedeemed -> pairingRedeemed(state, action)
        is Action.CopyTextTapped -> {
            if (action.text.isEmpty()) Reduced(state)
            else {
                dependency(HapticsClient).play(Haptic.tap)
                val pasteboard = dependency(PasteboardClient)
                Reduced(state, Effect.run { pasteboard.setString(action.text) })
            }
        }
        is Action.HealthResponse -> healthResponse(state, action)
        is Action.HomeResponse -> homeResponse(state, action)
        is Action.Home -> home(state, action.action)
    }

    /**
     * iOS `BindingReducer().onChange(of: \.server)`: only an actual edit invalidates the attempt
     * in flight. While connecting a manual entry, a field flushing its final value after Connect
     * retries with the completed entry instead of silently abandoning the attempt.
     */
    private fun serverEdited(old: State, new: State): Reduced<State, Action> {
        if (old.server == new.server || new.connectionAttempt == null) return Reduced(new)
        if (new.manualEntryDraft != null) return connect(new.copy(manualEntryDraft = new.server))
        return cancelConnection(new)
    }

    private fun connectButtonTapped(state: State): Reduced<State, Action> {
        // The link `opencode pair` prints (or its QR payload), pasted as a new server's address,
        // pairs rather than being taken for an address with no password.
        val typed = state.serverURL.trim()
        if (state.editingSavedServer == null &&
            (OpenCodePairingLink.decode(typed) != null || OpenCodePairingLink.isPairingEndpoint(typed))
        ) {
            return Reduced(state.copy(serverURL = ""), Effect.send(Action.PairingCodeScanned(typed)))
        }
        var next = state
        // Opened over Home from the drawer: a failure offers the way back.
        state.homeServer?.let { next = next.copy(returnServer = it) }
        next = next.copy(serverURL = withDefaultScheme(state.serverURL.trim()))
        val original = next.editingSavedServer
        // A changed address replaces the old one; stale pairing alternates must not silently
        // reconnect to the address the user just edited.
        if (original != null && next.serverURL != original.url) next = next.copy(alternateURLs = emptyList())
        next = next.copy(manualEntryDraft = next.server)
        // A server added from the drawer is switched to like a saved one; an edit of the server
        // in use reconnects it, which ends its Home first.
        return connect(next, keepingHome = next.keepsHome(switchingTo = next.server))
    }

    private fun home(state: State, action: HomeFeature.Action): Reduced<State, Action> = when (action) {
        is HomeFeature.Action.Delegate -> when (action.action) {
            // Back to the picker (iOS `.home(.serverBadgeTapped)`): Home is rebuilt from scratch
            // on reconnect since sessions, projects and the event stream are all server-scoped.
            HomeFeature.DelegateAction.ServersRequested -> cancelConnection(state.copy(returnServer = state.homeServer).withoutHome())
        }
        else -> Reduced(state)
    }

    // MARK: Notifications and system actions

    private fun notificationOpened(state: State, destination: NotificationDestination): Reduced<State, Action> {
        LaunchBloomController.completeColdLaunch()
        if (!destination.isValid) return Reduced(state)
        val server = state.knownServers.firstOrNull { it.persistenceID == destination.serverKey }
            ?: return Reduced(
                state.copy(
                    notificationError = "This server is no longer saved on this ${ThisDevice.name}. Connect to it again to open the session.",
                ),
            )
        val next = state.copy(notificationError = null, pendingNotification = destination)
        // A home for this server opens the session itself, even while it is still reconnecting
        // after a cold launch: rebuilding it would flash "Connecting…" over the restored list.
        val home = next.home
        val homeServer = next.homeServer
        if (home != null && homeServer?.persistenceID == destination.serverKey) {
            if (home.sessions.any { it.id == destination.sessionID }) {
                val opened = next.copy(pendingNotification = null)
                return Reduced(opened, openSession(opened, destination.sessionID))
            }
            return Reduced(next, loadNotificationSession(destination, homeServer))
        }
        return selectServer(server, next, preservingPending = true)
    }

    private fun notificationSessionLoaded(state: State, action: Action.NotificationSessionLoaded): Reduced<State, Action> {
        val destination = action.destination
        if (state.pendingNotification != destination || state.homeServer?.persistenceID != destination.serverKey) return Reduced(state)
        val next = state.copy(pendingNotification = null)
        val session = action.result.getOrNull()
        if (session == null || session.id != destination.sessionID) {
            return Reduced(next.copy(notificationError = "It may have been deleted, or the server may be unavailable."))
        }
        val inserted = next.insertingSession(session)
        return Reduced(inserted, openSession(inserted, session.id))
    }

    private fun systemActionReceived(state: State, action: SystemAction): Reduced<State, Action> {
        LaunchBloomController.completeColdLaunch()
        val key = action.serverKey
        val server = if (key != null) {
            state.knownServers.firstOrNull { SessionDirectory.serverKey(it.persistenceID) == key }
        } else {
            state.homeServer ?: state.savedServer.value
        }
        if (server == null) {
            // Nothing to open before a server is paired; the app is open now.
            if (key == null) return Reduced(state)
            return Reduced(
                state.copy(
                    systemActionError = "This server is no longer saved on this ${ThisDevice.name}. Connect to it again to open the session.",
                ),
            )
        }
        val next = state.copy(systemActionError = null)
        // As with notifications, a home for this server handles it even while still reconnecting.
        if (next.homeServer?.persistenceID == server.persistenceID) return routeSystemAction(action, next)
        val pending = action.copy(serverKey = SessionDirectory.serverKey(server.persistenceID))
        val waiting = next.copy(pendingSystemAction = pending)
        if (waiting.connectingServerID == server.id) return Reduced(waiting)
        return selectServer(server, waiting, preservingPending = true)
    }

    private fun systemSessionLoaded(state: State, action: Action.SystemSessionLoaded): Reduced<State, Action> {
        if (state.home == null) return Reduced(state)
        val session = action.result.getOrNull()
        if (session != null && session.id == action.sessionID) {
            val inserted = state.insertingSession(session)
            return Reduced(inserted, openInHome(inserted, SystemAction.Destination.Session(session.id)))
        }
        // A remembered session that's gone falls back to the newest one.
        if (action.action.destination == SystemAction.Destination.LastViewed) {
            return Reduced(state, openInHome(state, SystemAction.Destination.LastViewed, skipping = action.sessionID))
        }
        return Reduced(state.copy(systemActionError = "It may have been deleted, or the server may be unavailable."))
    }

    /**
     * Hands an action to the open server's home once its list has loaded (until then it waits
     * in `pendingSystemAction`), first fetching a session the list doesn't have.
     */
    internal fun routeSystemAction(action: SystemAction, state: State): Reduced<State, Action> {
        val home = state.home ?: return Reduced(state)
        val server = state.homeServer ?: return Reduced(state)
        if (!state.isHomeSnapshotLoaded) {
            return Reduced(state.copy(pendingSystemAction = action.copy(serverKey = SessionDirectory.serverKey(server.persistenceID))))
        }
        val next = state.copy(pendingSystemAction = null)
        val sessionID = when (val destination = action.destination) {
            is SystemAction.Destination.Session -> destination.id
            SystemAction.Destination.LastViewed -> next.lastViewedSessionIDs.value[server.persistenceID]
            is SystemAction.Destination.Draft -> null
        }
        if (sessionID != null && home.sessions.none { it.id == sessionID }) {
            val client = dependency(OpenCodeClient)
            return Reduced(
                next,
                Effect.run<Action> { send ->
                    val result = try {
                        Result.success(client.getSession(server, sessionID))
                    } catch (error: OpenCodeClientError) {
                        Result.failure(error)
                    }
                    send(Action.SystemSessionLoaded(action, sessionID, result))
                }.cancellable(CancelID.SystemSession, cancelInFlight = true),
            )
        }
        return Reduced(next, openInHome(next, action.destination))
    }

    private fun loadNotificationSession(destination: NotificationDestination, server: OpenCodeServer): Effect<Action> {
        val client = dependency(OpenCodeClient)
        return Effect.run<Action> { send ->
            val result = try {
                Result.success(client.getSession(server, destination.sessionID))
            } catch (error: OpenCodeClientError) {
                Result.failure(error)
            }
            send(Action.NotificationSessionLoaded(destination, result))
        }.cancellable(CancelID.Notification, cancelInFlight = true)
    }

    private fun State.insertingSession(session: OpenCodeSession): State {
        val home = home ?: return this
        if (home.sessions.any { it.id == session.id }) return this
        return copy(home = home.copy(sessions = listOf(session) + home.sessions))
    }

    /** iOS `.home(.sessionTapped(id))` on the stub Home. */
    private fun openSession(state: State, sessionID: String): Effect<Action> {
        val title = state.home?.sessions?.firstOrNull { it.id == sessionID }?.title.orEmpty()
        return Effect.send(Action.Home(HomeFeature.Action.SessionTapped(sessionID, title)))
    }

    /**
     * iOS `.home(.systemActionOpened(destination, startsCall:, inBackground:))` on the stub Home:
     * a session opens it, the last viewed falls back to the newest, and a draft opens New Session.
     * (Projects for drafts and starting calls arrive with the drawer's and voice's ports.)
     */
    private fun openInHome(state: State, destination: SystemAction.Destination, skipping: String? = null): Effect<Action> {
        val home = state.home ?: return Effect.none()
        return when (destination) {
            is SystemAction.Destination.Session -> openSession(state, destination.id)
            SystemAction.Destination.LastViewed -> {
                val remembered = state.homeServer?.let { state.lastViewedSessionIDs.value[it.persistenceID] }
                    ?.takeIf { id -> id != skipping && home.sessions.any { it.id == id } }
                val target = remembered ?: home.sessions.firstOrNull { it.id != skipping }?.id
                if (target != null) openSession(state, target) else Effect.send(Action.Home(HomeFeature.Action.NewSessionTapped))
            }
            is SystemAction.Destination.Draft -> Effect.send(Action.Home(HomeFeature.Action.NewSessionTapped))
        }
    }

    // MARK: Pairing

    private fun pairingCodeScanned(state: State, text: String): Reduced<State, Action> {
        LaunchBloomController.completeColdLaunch()
        val next = leaveHome(
            state.copy(
                pasteError = null,
                pendingPairing = null,
                ignoredPendingPairingCount = 0,
                pairingError = null,
                isScanningPairingCode = false,
                isEditingServer = false,
                editingSavedServer = null,
                manualEntryDraft = null,
            ),
        )
        val haptics = dependency(HapticsClient)
        return validatePairing(text).fold(
            onSuccess = { link ->
                haptics.play(Haptic.success)
                redeem(link, next)
            },
            onFailure = { error ->
                haptics.play(Haptic.error)
                cancelConnection(next, State.Connection.Failed(message = error.message.orEmpty(), phase = FailureContext.Phase.Pair))
            },
        )
    }

    private fun requestPairing(text: String, state: State): Reduced<State, Action> {
        LaunchBloomController.completeColdLaunch()
        val pending = state.pendingPairing
        if (pending != null) {
            val count = state.ignoredPendingPairingCount + 1
            AppLog.app.info { "pairing link ignored while pending id=${pending.id} ignoredCount=$count" }
            return Reduced(state.copy(ignoredPendingPairingCount = count))
        }
        return validatePairing(text).fold(
            onSuccess = { link ->
                Reduced(
                    state.copy(
                        pairingError = null,
                        ignoredPendingPairingCount = 0,
                        pendingPairing = PendingPairing(
                            id = dependency(UuidClient).next().toString(),
                            link = link,
                            replacedServerNames = link.replacedServers(state.knownServers).map { it.displayName },
                        ),
                    ),
                )
            },
            onFailure = { error ->
                dependency(HapticsClient).play(Haptic.error)
                Reduced(state.copy(pairingError = error.message))
            },
        )
    }

    private fun beginPairing(link: OpenCodePairingLink, state: State): Reduced<State, Action> {
        val next = leaveHome(
            state.copy(
                pendingPairing = null,
                ignoredPendingPairingCount = 0,
                pairingError = null,
                isScanningPairingCode = false,
                isEditingServer = false,
                editingSavedServer = null,
                manualEntryDraft = null,
            ),
        )
        dependency(HapticsClient).play(Haptic.success)
        return redeem(link, next)
    }

    /** Exchange a one-time pairing link for a token, then connect normally. */
    private fun redeem(link: OpenCodePairingLink, state: State): Reduced<State, Action> {
        val normalized = link.normalizedForRedemption
        val generation = state.connectionGeneration + 1
        val attempt = ConnectionAttempt(id = generation, server = normalized.server)
        val next = state.copy(
            pendingNotification = null,
            connection = State.Connection.Pairing,
            connectionGeneration = generation,
            connectionAttempt = attempt,
        )
        val client = dependency(OpenCodeClient)
        return Reduced(
            next,
            Effect.run<Action> { send ->
                val result = try {
                    Result.success(client.redeemPairing(normalized))
                } catch (error: OpenCodeClientError) {
                    Result.failure(error)
                }
                send(Action.PairingRedeemed(attempt.id, result))
            }.cancellable(CancelID.Health, cancelInFlight = true),
        )
    }

    private fun pairingRedeemed(state: State, action: Action.PairingRedeemed): Reduced<State, Action> {
        val attempt = state.connectionAttempt
        if (attempt?.id != action.attemptID || state.connection != State.Connection.Pairing) return Reduced(state)
        val server = action.result.getOrElse { error ->
            val clientError = error as? OpenCodeClientError ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
            return Reduced(
                state.copy(
                    connectionAttempt = null,
                    connection = State.Connection.Failed(
                        message = clientError.message,
                        context = FailureContext.of(attempt.server, FailureContext.Phase.Pair, action.attemptID, clientError.connectionFailures),
                    ),
                ),
            )
        }
        val isNewComputer = server.isNewComputer(state.knownServers)
        return connect(state.load(server.keepingLocalSettings(state.knownServers)), opensDrawer = isNewComputer)
    }

    // MARK: Connecting

    private fun healthResponse(state: State, action: Action.HealthResponse): Reduced<State, Action> {
        val attempt = state.connectionAttempt
        if (attempt?.id != action.attemptID || state.connection != State.Connection.Connecting) return Reduced(state)
        val server = action.result.getOrElse { error ->
            val clientError = error as? OpenCodeClientError ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
            // A switch kept Home up while it tried; the error needs the picker.
            return Reduced(
                state.copy(
                    connectionAttempt = null,
                    connection = State.Connection.Failed(
                        message = clientError.message,
                        context = FailureContext.of(attempt.server, FailureContext.Phase.Connect, action.attemptID, clientError.connectionFailures),
                    ),
                ).withoutHome(),
            )
        }
        // Adopt the complete winning target, not a URL combined with form auth.
        var next = state.copy(connection = State.Connection.LoadingHome(server)).load(server)
        // An edited entry is replaced only after its sessions load.
        val forgotten = ArrayList<Effect<Action>>()
        if (next.editingSavedServer == null) {
            // Re-pairing can give a saved server a new identity; forget the old one's now, since
            // remembering this one drops it whatever the sessions' read does.
            val superseded = next.knownServers
                .filter { server.supersedes(it) && it.persistenceID != server.persistenceID }
                .map { it.persistenceID }.toSet().sorted()
            superseded.forEach { id ->
                val reduced = forgetIdentity(id, next)
                next = reduced.state
                forgotten += reduced.effect
            }
            server.remember()
        }
        val client = dependency(OpenCodeClient)
        return Reduced(
            next,
            Effect.merge(
                forgotten + Effect.run<Action> { send ->
                    val result = try {
                        Result.success(client.homeSnapshot(server))
                    } catch (error: OpenCodeClientError) {
                        Result.failure(error)
                    }
                    send(Action.HomeResponse(action.attemptID, result))
                }.cancellable(CancelID.Home, cancelInFlight = true),
            ),
        )
    }

    private fun homeResponse(state: State, action: Action.HomeResponse): Reduced<State, Action> {
        val loading = state.connection as? State.Connection.LoadingHome
        if (state.connectionAttempt?.id != action.attemptID || loading == null) return Reduced(state)
        val server = loading.server
        val snapshot = action.result.getOrElse { error ->
            val message = (error as? OpenCodeClientError)?.message ?: OpenCodeClientError.UNREACHABLE_MESSAGE
            return Reduced(
                state.copy(
                    connectionAttempt = null,
                    editingSavedServer = null,
                    connection = State.Connection.Failed(
                        message = message,
                        context = FailureContext.of(server, FailureContext.Phase.LoadSessions, action.attemptID),
                    ),
                ).withoutHome(),
            )
        }
        // An edit is saved only now; until then the original stays saved under its own identity,
        // so a failed read keeps what it had.
        var next = state
        var forgotten: Effect<Action> = Effect.none()
        next.editingSavedServer?.let { original ->
            if (original.persistenceID != server.persistenceID) {
                val reduced = forgetIdentity(original.persistenceID, next)
                next = reduced.state
                forgotten = reduced.effect
            }
            server.remember(original)
            next = next.copy(editingSavedServer = null)
        }
        val opensDrawer = next.connectionAttempt?.opensDrawer == true
        next = next.withHome(server, snapshot)
        persistSnapshot(next, server, snapshot)
        next = next.copy(
            isEditingServer = false,
            connection = State.Connection.Connected,
            connectionAttempt = null,
            manualEntryDraft = null,
            returnServer = null,
        )
        // Anything the person asked for on the way in wins over the drawer.
        val asked = next.pendingSystemAction != null || next.pendingNotification != null
        var system: Effect<Action> = Effect.none()
        val pendingAction = next.pendingSystemAction
        if (pendingAction != null && pendingAction.serverKey == SessionDirectory.serverKey(server.persistenceID)) {
            val reduced = routeSystemAction(pendingAction, next)
            next = reduced.state
            system = reduced.effect
        }
        val destination = next.pendingNotification
        if (destination != null && destination.serverKey == server.persistenceID) {
            return Reduced(next, Effect.merge(forgotten, system, loadNotificationSession(destination, server)))
        }
        if (opensDrawer && !asked) {
            if (next.isPresentingAnything) {
                AppLog.app.info { "paired computer drawer open dropped under an app presentation" }
                return Reduced(next, Effect.merge(forgotten, system))
            }
            return Reduced(next, Effect.merge(forgotten, system, Effect.send(Action.Home(HomeFeature.Action.DrawerOpenChanged(true)))))
        }
        return Reduced(next, Effect.merge(forgotten, system))
    }

    /** iOS `home.persistSnapshot(at:)`, until the drawer's Home persists its own snapshots. */
    private fun persistSnapshot(state: State, server: OpenCodeServer, snapshot: OpenCodeHomeSnapshot) {
        val entry = CachedHomeSnapshot(serverID = server.persistenceID, snapshot = snapshot, capturedAt = dependency(DateClient).now())
        state.cachedHomeSnapshots.update { it + (server.persistenceID to entry) }
    }

    /**
     * Probes every address concurrently, but saves the healthiest external scope rather than
     * whichever LAN address happened to answer first (iOS `connect(_:)`).
     */
    internal fun connect(
        state: State,
        preservingPending: Boolean = false,
        keepingHome: Boolean = false,
        opensDrawer: Boolean = false,
    ): Reduced<State, Action> {
        var next = state
        if (!preservingPending) next = next.copy(pendingNotification = null, pendingSystemAction = null)
        val generation = next.connectionGeneration + 1
        val attempt = ConnectionAttempt(id = generation, server = next.server, opensDrawer = opensDrawer)
        next = next.copy(connection = State.Connection.Connecting, connectionGeneration = generation, connectionAttempt = attempt)
        if (!keepingHome) next = next.withoutHome()
        val client = dependency(OpenCodeClient)
        val health = Effect.run<Action> { send ->
            val result = probe(client, attempt.server)
            send(Action.HealthResponse(attempt.id, result))
        }
        return Reduced(
            next,
            Effect.merge(
                Effect.cancel(CancelID.Notification),
                Effect.cancel(CancelID.Home),
                health.cancellable(CancelID.Health, cancelInFlight = true),
            ),
        )
    }

    private suspend fun probe(client: OpenCodeClient, server: OpenCodeServer): Result<OpenCodeServer> = coroutineScope {
        var lastError = OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
        val pending = HashMap<Int, Int>()
        val healthy = HashMap<Int, OpenCodeServer>()
        val results = Channel<Pair<Int, Result<OpenCodeServer>>>(Channel.UNLIMITED)
        val jobs = server.candidateURLs.map { url ->
            val candidate = server.at(url)
            val rank = OpenCodeServer.reachabilityRank(url)
            pending[rank] = (pending[rank] ?: 0) + 1
            launch {
                val result = try {
                    client.health(candidate)
                    Result.success(candidate)
                } catch (error: OpenCodeClientError) {
                    Result.failure(error)
                }
                results.send(rank to result)
            }
        }
        repeat(jobs.size) {
            val (rank, result) = results.receive()
            pending[rank] = (pending[rank] ?: 1) - 1
            result.fold(
                onSuccess = { if (healthy[rank] == null) healthy[rank] = it },
                onFailure = { error ->
                    val clientError = error as OpenCodeClientError
                    lastError = clientError.copy(connectionFailures = lastError.connectionFailures + clientError.connectionFailures)
                },
            )
            val preferredRank = healthy.keys.minOrNull()
            if (preferredRank != null && pending.none { it.key < preferredRank && it.value > 0 }) {
                jobs.forEach { it.cancel() }
                return@coroutineScope Result.success(healthy.getValue(preferredRank))
            }
        }
        Result.failure(lastError)
    }

    companion object {
        /** A typed address without a scheme means plain HTTP on a LAN or tailnet (iOS `withDefaultScheme`). */
        fun withDefaultScheme(address: String): String {
            if (address.isEmpty() || address.contains("://")) return address
            return "http://$address"
        }

        val invalidPairingCodeMessage =
            "That isn’t an OpenCode pairing code. Run opencode pair --remote on your computer, then scan its QR code or paste the link it prints."
        val loopbackPairingMessage: String
            get() = "That server only listens on localhost, so this ${ThisDevice.casualName} can’t reach it. Run opencode pair --remote on your computer instead, which works from any network."
        const val EMPTY_CLIPBOARD_MESSAGE = "Your clipboard is empty"
        const val NOT_A_PAIRING_LINK_MESSAGE = "That isn’t a pairing link"
    }
}
