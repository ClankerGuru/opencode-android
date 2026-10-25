package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.ifLet
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.server.ConnectionFailureDetail
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.remember
import ai.opencode.server.friendlyName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Port of iOS `AppFeature` (`OpenCode/App/AppFeature.swift`): routes between the connection
 * screen and Home, owns the manual server form and the connect → health → Home snapshot
 * state machine.
 *
 * Ported so far: launch restore of the saved server, manual entry (`connectButtonTapped`,
 * `addServerTapped`, `cancelEditingTapped`, `serverTapped`), the concurrent candidate probe that
 * prefers the healthiest reachability rank (`connect`), `healthResponse`, `homeResponse`,
 * `FailureContext` and the error report. Still to port (see `docs/parity/app.md`): pairing
 * (scan/paste/open URL/confirm), notifications, system actions, shares, server title sheet,
 * edit/delete saved servers, migrations, windows.
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
        /** Addresses discovered from a pairing code, tried alongside `serverURL`. */
        val alternateURLs: List<String> = emptyList(),
        val connection: Connection = Connection.Idle,
        val connectionGeneration: Int = 0,
        val connectionAttempt: ConnectionAttempt? = null,
        val home: HomeFeature.State? = null,
        /** Not in the background. */
        val isInForeground: Boolean = true,
        /** Manual entry is opt-in; first launch presents the pairing actions. */
        val isEditingServer: Boolean = false,
        /** The saved entry being edited, replaced only once the edited target loads. */
        val editingSavedServer: OpenCodeServer? = null,
        val savedServer: Shared<OpenCodeServer?>,
        val savedServers: Shared<List<OpenCodeServer>>,
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

        /** A blank username field means the server's default user, like the web app. */
        val server: OpenCodeServer
            get() {
                val name = serverName.trim()
                return OpenCodeServer(
                    url = serverURL,
                    username = username.trim().ifEmpty { "opencode" },
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
            /** iOS `State.init()`: restores the last server and its Home so launch shows it at once. */
            fun initial(): State {
                val saved = shared(ServerSharedKeys.savedServer)
                var state = State(savedServer = saved, savedServers = shared(ServerSharedKeys.savedServers))
                val server = saved.value ?: return state
                state = state.load(server)
                return state.copy(home = HomeFeature.State(serverID = server.persistenceID, serverName = server.friendlyName, server = server))
            }
        }
    }

    /** Form edits cannot change the target of work already in flight. */
    data class ConnectionAttempt(val id: Int, val server: OpenCodeServer, val opensDrawer: Boolean = false)

    /** Safe to display or copy: no full URL, credentials, or raw network payload. */
    data class FailureContext(
        val phase: Phase,
        val target: String,
        val attempt: Int,
        val name: String?,
        val details: List<ConnectionFailureDetail>,
        val friendlyName: String,
    ) {
        enum class Phase(val rawValue: String) { Pair("Pair"), Connect("Connect"), LoadSessions("Load sessions") }

        val serverLabel: String get() = name?.let { "$it ($target)" } ?: target

        val detailText: String
            get() = details.withIndex().joinToString("\n\n") { (index, detail) ->
                (if (details.size > 1) "Candidate ${index + 1}: " else "") + detail.text
            }

        companion object {
            private val ALLOWED_HOST = Regex("^[A-Za-z0-9.\\-:\\[\\]]+$")

            /** Host and port only; anything else reads "Unavailable (invalid address)". */
            fun safeTarget(address: String): String {
                val invalid = "Unavailable (invalid address)"
                val uri = runCatching { java.net.URI(address) }.getOrNull() ?: return invalid
                if (uri.scheme?.lowercase() !in setOf("http", "https")) return invalid
                val host = uri.host?.takeIf { it.isNotEmpty() && ALLOWED_HOST.matches(it) } ?: return invalid
                val port = uri.port.takeIf { it != -1 }
                if (port != null && port !in 1..65535) return invalid
                val hostname = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
                return port?.let { "$hostname:$it" } ?: hostname
            }

            fun of(server: OpenCodeServer, phase: Phase, attempt: Int, details: List<ConnectionFailureDetail> = emptyList()): FailureContext {
                val target = safeTarget(server.url)
                val chosen = server.name?.trim().orEmpty()
                val valid = target != "Unavailable (invalid address)"
                val name = if (chosen.isNotEmpty()) chosen else if (valid) server.displayName else "server"
                return FailureContext(
                    phase = phase,
                    target = target,
                    attempt = attempt,
                    name = if (name == OpenCodeServer.machineName(target)) null else name,
                    details = details,
                    friendlyName = server.friendlyName,
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
        data object ConnectButtonTapped : Action
        data object AddServerTapped : Action
        data object CancelEditingTapped : Action
        data class ServerTapped(val server: OpenCodeServer) : Action
        data object ReturnToServerTapped : Action
        data object ScanPairingCodeTapped : Action
        data object PastePairingCodeTapped : Action
        data class CopyTextTapped(val text: String) : Action
        data class HealthResponse(val attemptID: Int, val result: Result<OpenCodeServer>) : Action
        data class HomeResponse(val attemptID: Int, val result: Result<ai.opencode.server.model.OpenCodeHomeSnapshot>) : Action
        data class Home(val action: HomeFeature.Action) : Action
    }

    private enum class CancelID { Health, Home }

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
            // Restored home owns revalidation; never replace its cached list with setup.
            val saved = state.savedServer.value
            if (state.connection != State.Connection.Idle) Reduced(state)
            else if (saved != null) connect(state.load(saved), keepingHome = state.home != null)
            // With no last-used server, wait for a scan or an explicit selection.
            else Reduced(state)
        }
        is Action.ForegroundChanged -> Reduced(state.copy(isInForeground = action.isInForeground))
        is Action.ServerURLChanged -> Reduced(state.copy(serverURL = action.value))
        is Action.UsernameChanged -> Reduced(state.copy(username = action.value))
        is Action.PasswordChanged -> Reduced(state.copy(password = action.value))
        is Action.ServerNameChanged -> Reduced(state.copy(serverName = action.value))
        is Action.HeadersChanged -> Reduced(state.copy(headers = action.headers))
        Action.ConnectButtonTapped -> {
            var next = state
            // Opened over Home from the drawer: a failure offers the way back.
            state.home?.let { home -> next = next.copy(returnServer = state.knownServers.firstOrNull { it.persistenceID == home.serverID }) }
            next = next.copy(serverURL = withDefaultScheme(state.serverURL.trim()))
            val original = state.editingSavedServer
            if (original != null && next.serverURL != original.url) next = next.copy(alternateURLs = emptyList())
            next = next.copy(manualEntryDraft = next.server)
            connect(next)
        }
        Action.AddServerTapped -> {
            val draft = state.manualEntryDraft
            var next = state.copy(editingSavedServer = null, isEditingServer = true)
            next = if (draft != null) next.load(draft) else next.copy(
                serverURL = "", username = "opencode", password = "", serverName = "", headers = emptyList(), alternateURLs = emptyList(),
            )
            cancelConnection(next)
        }
        Action.CancelEditingTapped -> {
            val next = state.copy(isEditingServer = false, editingSavedServer = null)
            if (next.home != null) Reduced(next) else cancelConnection(next)
        }
        is Action.ServerTapped -> connect(state.load(action.server).copy(editingSavedServer = null))
        Action.ReturnToServerTapped -> state.returnServer?.let { connect(state.load(it)) } ?: Reduced(state)
        // Pairing ports with Settings/PairingScanner and OpenCodePairingLink (docs/parity/app.md).
        Action.ScanPairingCodeTapped, Action.PastePairingCodeTapped -> Reduced(state)
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
        is Action.Home -> when (val home = action.action) {
            is HomeFeature.Action.Delegate -> when (home.action) {
                HomeFeature.DelegateAction.ServersRequested -> {
                    val current = state.knownServers.firstOrNull { it.persistenceID == state.home?.serverID }
                    Reduced(
                        state.copy(home = null, connection = State.Connection.Picking, returnServer = current),
                        Effect.merge(Effect.cancel(CancelID.Health), Effect.cancel(CancelID.Home)),
                    )
                }
            }
            else -> Reduced(state)
        }
    }

    private fun healthResponse(state: State, action: Action.HealthResponse): Reduced<State, Action> {
        val attempt = state.connectionAttempt
        if (attempt?.id != action.attemptID || state.connection != State.Connection.Connecting) return Reduced(state)
        val server = action.result.getOrElse { error ->
            val clientError = error as? OpenCodeClientError ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
            // A switch kept Home up while it tried; the error needs the picker.
            return Reduced(
                state.copy(
                    connectionAttempt = null,
                    home = null,
                    connection = State.Connection.Failed(
                        message = clientError.message,
                        context = FailureContext.of(attempt.server, FailureContext.Phase.Connect, action.attemptID, clientError.connectionFailures),
                    ),
                ),
            )
        }
        // Adopt the complete winning target, not a URL combined with form auth.
        val next = state.copy(connection = State.Connection.LoadingHome(server)).load(server)
        if (next.editingSavedServer == null) server.remember()
        val client = dependency(OpenCodeClient)
        return Reduced(
            next,
            Effect.run<Action> { send ->
                val result = try {
                    Result.success(client.homeSnapshot(server))
                } catch (error: OpenCodeClientError) {
                    Result.failure(error)
                }
                send(Action.HomeResponse(action.attemptID, result))
            }.cancellable(CancelID.Home, cancelInFlight = true),
        )
    }

    private fun homeResponse(state: State, action: Action.HomeResponse): Reduced<State, Action> {
        val loading = state.connection as? State.Connection.LoadingHome
        if (state.connectionAttempt?.id != action.attemptID || loading == null) return Reduced(state)
        val server = loading.server
        action.result.exceptionOrNull()?.let { error ->
            val message = (error as? OpenCodeClientError)?.message ?: OpenCodeClientError.UNREACHABLE_MESSAGE
            return Reduced(
                state.copy(
                    connectionAttempt = null,
                    editingSavedServer = null,
                    home = null,
                    connection = State.Connection.Failed(
                        message = message,
                        context = FailureContext.of(server, FailureContext.Phase.LoadSessions, action.attemptID),
                    ),
                ),
            )
        }
        state.editingSavedServer?.let { original -> server.remember(original) }
        val snapshot = action.result.getOrThrow()
        return Reduced(
            state.copy(
                editingSavedServer = null,
                home = HomeFeature.State(
                    serverID = server.persistenceID,
                    serverName = server.friendlyName,
                    server = server,
                    sessions = snapshot.sessions.filter { it.parentID == null }.sortedByDescending { it.updatedAt },
                ),
                isEditingServer = false,
                connection = State.Connection.Connected,
                connectionAttempt = null,
                manualEntryDraft = null,
                returnServer = null,
            ),
        )
    }

    private fun cancelConnection(state: State): Reduced<State, Action> = Reduced(
        state.copy(
            connectionAttempt = null,
            connection = if (state.connection is State.Connection.Failed || state.connection == State.Connection.Connected) state.connection else State.Connection.Idle,
        ),
        Effect.merge(Effect.cancel(CancelID.Health), Effect.cancel(CancelID.Home)),
    )

    /**
     * Probes every address concurrently, but saves the healthiest external scope rather than
     * whichever LAN address happened to answer first (iOS `connect(_:)`).
     */
    private fun connect(state: State, keepingHome: Boolean = false): Reduced<State, Action> {
        val generation = state.connectionGeneration + 1
        val attempt = ConnectionAttempt(id = generation, server = state.server)
        val next = state.copy(
            connection = State.Connection.Connecting,
            home = if (keepingHome) state.home else null,
            connectionGeneration = generation,
            connectionAttempt = attempt,
        )
        val client = dependency(OpenCodeClient)
        val health = Effect.run<Action> { send ->
            val result = probe(client, attempt.server)
            send(Action.HealthResponse(attempt.id, result))
        }
        return Reduced(
            next,
            Effect.merge(Effect.cancel(CancelID.Home), health.cancellable(CancelID.Health, cancelInFlight = true)),
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
        /** An address typed without a scheme is http, as iOS `withDefaultScheme`. */
        fun withDefaultScheme(address: String): String {
            if (address.isEmpty() || address.contains("://")) return address
            return "http://$address"
        }
    }
}
