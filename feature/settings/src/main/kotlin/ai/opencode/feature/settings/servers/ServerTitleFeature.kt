package ai.opencode.feature.settings.servers

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.cancellable
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import kotlin.coroutines.cancellation.CancellationException

/** The store [ServerTitleView] and [ServerConnectView] take. */
typealias ServerTitleStore = StoreOf<ServerTitleFeature.State, ServerTitleFeature.Action>

/** What a failed Paste says when the clipboard has no text (iOS `emptyClipboardMessage`). */
const val emptyClipboardMessage = "Your clipboard is empty"

/** What a failed Paste says when the clipboard's text isn't a pairing link (iOS `notAPairingLinkMessage`). */
const val notAPairingLinkMessage = "That isn’t a pairing link"

/**
 * Saved servers, most recent first, with the one in use even if it was never saved, and its live
 * copy (a rename shows at once). Port of iOS `switchableServers` (`Drawer/ServerSwitcher.swift`),
 * here because the server sheet lists them; the drawer's switcher can use it too.
 */
fun switchableServers(current: OpenCodeServer, saved: List<OpenCodeServer>): List<OpenCodeServer> {
    if (saved.any { it.persistenceID == current.persistenceID }) {
        return saved.map { if (it.persistenceID == current.persistenceID) current else it }
    }
    return listOf(current) + saved
}

/**
 * Port of iOS `Settings/ServerTitleFeature.swift`: the experimental (`serverTitleSwitch`) Servers
 * sheet. It owns its navigation (servers → connect / detail), camera access, light unscoped probes
 * (`/api/info` health, sequential, only while the sheet is active) and clipboard effects.
 * Connecting still goes through `:app`'s pairing/connection pipeline: everything that changes a
 * connection leaves as a [Action.Delegate] for the parent:
 *
 * - [Delegate.Select]: switch to a saved server (`busy` stays on until the parent clears it or reports a failure).
 * - [Delegate.Pair]: a scanned or pasted pairing text that decodes (`OpenCodePairingLink.decode`).
 * - [Delegate.Connect]: a typed address with username and password (the parent normalizes the address).
 * - [Delegate.Reconnect]: Try Again on the server in use (refresh, not a switch).
 * - [Delegate.Rename], [Delegate.Remove], [Delegate.Close].
 *
 * The parent reports a failed attempt by setting `busy = false`, `error = message`,
 * `failureRevision + 1` (which shakes the camera) and playing the error haptic, as iOS
 * `AppFeature.serverTitleFailed` does. Open it with [State.opening].
 */
class ServerTitleFeature : Reducer<ServerTitleFeature.State, ServerTitleFeature.Action> {
    data class State(
        val current: OpenCodeServer? = null,
        val savedServers: Shared<List<OpenCodeServer>> = shared(ServerSharedKeys.savedServers),
        /** Pages pushed over the server list. */
        val path: List<Page> = emptyList(),
        val method: Method = Method.scan,
        val address: String = "",
        val name: String = "",
        val username: String = "opencode",
        val password: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val failureRevision: Int = 0,
        val cameraAllowed: Boolean = false,
        val checks: Map<String, Health> = emptyMap(),
        val removing: OpenCodeServer? = null,
        /** Try Again on the server in use: its check is in flight and clears `busy`. */
        val retrying: String? = null,
        /** The details page's editable name. */
        val nameDraft: String = "",
        /** First run with no servers: the connect page is the whole screen, with no list to go back to. */
        val connectOnly: Boolean = false,
    ) {
        /** The top page. */
        val page: Page get() = path.lastOrNull() ?: Page.Servers

        /** Setting the top page replaces everything pushed. */
        fun withPage(page: Page): State = copy(path = if (page == Page.Servers) emptyList() else listOf(page))

        /** The servers to list, by friendly name, from [saved] (pass the collected shared value in views). */
        fun servers(saved: List<OpenCodeServer> = savedServers.value): List<OpenCodeServer> {
            val all = current?.let { switchableServers(it, saved) } ?: saved
            return all.sortedWith { a, b -> localizedStandardCompare(a.friendlyName, b.friendlyName) }
        }

        val servers: List<OpenCodeServer> get() = servers()

        /** A row's or header's status: a failed check wins, then “In use” for the current server. */
        fun status(server: OpenCodeServer): String {
            val health = checks[server.persistenceID]
            if (health != null && health != Health.connected) return health.label
            if (current?.persistenceID == server.persistenceID) return "In use"
            return health?.label ?: "Checking…"
        }

        companion object {
            /**
             * The sheet as `:app` opens it (iOS `AppFeature.openServerTitle`): with no Home it opens on
             * Connect; on first run, with no servers, Connect is all it shows.
             */
            fun opening(current: OpenCodeServer?, hasHome: Boolean = current != null): State {
                val title = State(current = current)
                if (hasHome) return title
                return if (title.servers.isEmpty()) title.copy(connectOnly = true) else title.withPage(Page.Connect)
            }
        }
    }

    sealed interface Page {
        data object Servers : Page
        data object Connect : Page
        data class Detail(val server: OpenCodeServer) : Page
    }

    enum class Method { scan, address }

    enum class Health {
        connected, asleep, update, signIn;

        val label: String
            get() = when (this) {
                connected -> "Connected"
                asleep -> "Asleep or offline"
                update -> "Needs an update"
                signIn -> "Sign-in failed"
            }

        companion object {
            fun failure(error: OpenCodeClientError): Health {
                if (error.statusCode == 401 || error.statusCode == 403) return signIn
                if (error.isUnsupported || error.statusCode == 404) return update
                return asleep
            }
        }
    }

    sealed interface Action {
        // Bindings (iOS `BindableAction`).
        data class PathChanged(val path: List<Page>) : Action
        data class MethodChanged(val method: Method) : Action
        data class AddressChanged(val address: String) : Action
        data class NameChanged(val name: String) : Action
        data class UsernameChanged(val username: String) : Action
        data class PasswordChanged(val password: String) : Action
        data class NameDraftChanged(val nameDraft: String) : Action
        /** The Remove Server? dialog went away without a choice (`removing = nil`). */
        data object RemovingDismissed : Action

        /** The sheet became active (iOS `scenePhase == .active`): probe every listed server. */
        data object Appeared : Action
        data object Disappeared : Action
        data object Add : Action
        data object Back : Action
        data object Close : Action
        data object Paste : Action
        data object Connect : Action
        data object RequestCamera : Action
        data class CameraResponse(val allowed: Boolean) : Action
        data class Scanned(val text: String) : Action
        data class Select(val server: OpenCodeServer) : Action
        data class Detail(val server: OpenCodeServer) : Action
        data class Retry(val server: OpenCodeServer) : Action
        data class Checked(val id: String, val health: Health) : Action
        data class Copy(val text: String) : Action
        data object SaveName : Action
        data class Remove(val server: OpenCodeServer) : Action
        data object ConfirmRemove : Action
        data class Delegate(val delegate: ServerTitleFeature.Delegate) : Action
    }

    /** What the parent (`:app`'s AppFeature) acts on. */
    sealed interface Delegate {
        data class Select(val server: OpenCodeServer) : Delegate
        /** Raw pairing text that decodes; the parent validates and redeems it. Never log it. */
        data class Pair(val text: String) : Delegate
        data class Connect(val server: OpenCodeServer) : Delegate
        data object Close : Delegate
        /** Try Again on the server in use: Home refreshes rather than switching. */
        data class Reconnect(val server: OpenCodeServer) : Delegate
        data class Rename(val server: OpenCodeServer, val name: String) : Delegate
        data class Remove(val server: OpenCodeServer) : Delegate
    }

    private enum class CancelID { checks, camera, retry }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val haptics = dependency(HapticsClient)
        return when (action) {
            is Action.PathChanged -> Reduced(state.copy(path = action.path, error = null))
            is Action.MethodChanged -> Reduced(state.copy(method = action.method, error = null))
            is Action.AddressChanged -> Reduced(state.copy(address = action.address))
            is Action.NameChanged -> Reduced(state.copy(name = action.name))
            is Action.UsernameChanged -> Reduced(state.copy(username = action.username))
            is Action.PasswordChanged -> Reduced(state.copy(password = action.password))
            is Action.NameDraftChanged -> Reduced(state.copy(nameDraft = action.nameDraft))
            Action.RemovingDismissed -> Reduced(state.copy(removing = null))
            Action.Appeared -> {
                val servers = state.servers
                val client = dependency(OpenCodeClient)
                // Sequential and unscoped: never boot a location's services to list servers.
                Reduced(
                    state,
                    Effect.run<Action> { send ->
                        for (server in servers) send(Action.Checked(server.persistenceID, check(client, server)))
                    }.cancellable(CancelID.checks, cancelInFlight = true),
                )
            }
            Action.Disappeared -> {
                val next = if (state.retrying != null) state.copy(retrying = null, busy = false) else state
                Reduced(next, Effect.merge(Effect.cancel(CancelID.checks), Effect.cancel(CancelID.camera), Effect.cancel(CancelID.retry)))
            }
            Action.Add -> {
                val path = if (state.path.lastOrNull() != Page.Connect) state.path + Page.Connect else state.path
                Reduced(state.copy(path = path, error = null))
            }
            Action.Back -> {
                if (state.busy || state.path.isEmpty()) Reduced(state)
                else Reduced(state.copy(path = state.path.dropLast(1), error = null))
            }
            Action.Close -> Reduced(state, Effect.send(Action.Delegate(Delegate.Close)))
            Action.RequestCamera -> {
                val camera = dependency(ServerCameraClient)
                Reduced(
                    state,
                    Effect.run<Action> { send -> send(Action.CameraResponse(camera.authorize())) }
                        .cancellable(CancelID.camera, cancelInFlight = true),
                )
            }
            is Action.CameraResponse -> Reduced(state.copy(cameraAllowed = action.allowed))
            Action.Paste -> {
                if (state.busy) return Reduced(state)
                val text = dependency(PasteboardClient).string().orEmpty()
                if (text.isBlank()) {
                    haptics.play(Haptic.error)
                    return Reduced(state.copy(error = emptyClipboardMessage, failureRevision = state.failureRevision + 1))
                }
                Reduced(state, Effect.send(Action.Scanned(text)))
            }
            is Action.Scanned -> {
                if (state.busy) return Reduced(state)
                if (OpenCodePairingLink.decode(action.text) == null) {
                    haptics.play(Haptic.error)
                    return Reduced(state.copy(error = "Couldn’t read a pairing link", failureRevision = state.failureRevision + 1))
                }
                Reduced(state.copy(busy = true, error = null), Effect.send(Action.Delegate(Delegate.Pair(action.text))))
            }
            Action.Connect -> {
                val address = state.address.trim()
                if (state.busy || address.isEmpty()) return Reduced(state)
                if (OpenCodePairingLink.decode(address) != null) return Reduced(state, Effect.send(Action.Scanned(address)))
                // AppFeature's connect action normalizes the address before sending it.
                val server = OpenCodeServer(
                    url = address,
                    username = state.username,
                    password = state.password,
                    name = state.name.ifEmpty { null },
                )
                haptics.play(Haptic.tap)
                Reduced(state.copy(busy = true, error = null), Effect.send(Action.Delegate(Delegate.Connect(server))))
            }
            is Action.Select -> {
                val server = action.server
                if (state.busy) return Reduced(state)
                if (server.persistenceID == state.current?.persistenceID) return Reduced(state, Effect.send(Action.Delegate(Delegate.Close)))
                val health = state.checks[server.persistenceID]
                if (health != null && health != Health.connected) {
                    return Reduced(state.withPage(Page.Detail(server)).copy(nameDraft = server.friendlyName))
                }
                haptics.play(Haptic.selection)
                Reduced(state.copy(busy = true, error = null), Effect.send(Action.Delegate(Delegate.Select(server))))
            }
            is Action.Detail -> Reduced(state.withPage(Page.Detail(action.server)).copy(nameDraft = action.server.friendlyName))
            is Action.Retry -> {
                val server = action.server
                if (state.busy) return Reduced(state)
                val busy = state.copy(busy = true, error = null)
                if (server.persistenceID != state.current?.persistenceID) {
                    return Reduced(busy, Effect.send(Action.Delegate(Delegate.Select(server))))
                }
                // The server in use has nothing to switch to: Home refreshes while the sheet checks it again.
                val client = dependency(OpenCodeClient)
                Reduced(
                    busy.copy(retrying = server.persistenceID),
                    Effect.merge(
                        Effect.send(Action.Delegate(Delegate.Reconnect(server))),
                        Effect.run<Action> { send -> send(Action.Checked(server.persistenceID, check(client, server))) }
                            .cancellable(CancelID.retry, cancelInFlight = true),
                    ),
                )
            }
            is Action.Checked -> {
                var next = state.copy(checks = state.checks + (action.id to action.health))
                if (state.retrying == action.id) next = next.copy(retrying = null, busy = false)
                Reduced(next)
            }
            is Action.Copy -> {
                haptics.play(Haptic.tap)
                val pasteboard = dependency(PasteboardClient)
                Reduced(state, Effect.run { pasteboard.setString(action.text) })
            }
            Action.SaveName -> {
                val server = (state.page as? Page.Detail)?.server ?: return Reduced(state)
                val name = state.nameDraft.trim()
                // A cleared name goes back to the server's own name.
                if (name == server.friendlyName) return Reduced(state.copy(nameDraft = server.friendlyName))
                Reduced(state, Effect.send(Action.Delegate(Delegate.Rename(server, name))))
            }
            is Action.Remove -> Reduced(state.copy(removing = action.server))
            Action.ConfirmRemove -> {
                val server = state.removing
                if (server == null || server.persistenceID == state.current?.persistenceID) return Reduced(state)
                Reduced(state.copy(removing = null).withPage(Page.Servers), Effect.send(Action.Delegate(Delegate.Remove(server))))
            }
            is Action.Delegate -> Reduced(state)
        }
    }

    private suspend fun check(client: OpenCodeClient, server: OpenCodeServer): Health = try {
        client.health(server)
        Health.connected
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: OpenCodeClientError) {
        Health.failure(error)
    }
}

/**
 * Foundation's `localizedStandardCompare`: case- and diacritic-insensitive, numbers compared by value
 * (“Mac 2” before “Mac 10”).
 */
internal fun localizedStandardCompare(a: String, b: String): Int {
    val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
    val left = chunks(a)
    val right = chunks(b)
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]
        val y = right[i]
        val result = if (x.first().isDigit() && y.first().isDigit()) {
            x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
        } else {
            collator.compare(x, y)
        }
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}

private fun chunks(text: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    for (c in text) {
        if (current.isNotEmpty() && current.last().isDigit() != c.isDigit()) {
            out += current.toString()
            current.clear()
        }
        current.append(c)
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}
