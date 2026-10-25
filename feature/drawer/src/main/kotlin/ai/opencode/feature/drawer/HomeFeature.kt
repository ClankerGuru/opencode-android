package ai.opencode.feature.drawer

import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.forEach
import ai.opencode.core.store.ifLet
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import ai.opencode.feature.settings.SettingsFeature

/**
 * Phase 0 placeholder for iOS `HomeFeature` (`OpenCode/Drawer/HomeFeature*.swift`): the session
 * drawer, the session stack, Catch Up and the Settings presentation.
 *
 * Will port `HomeFeature*.swift`, `HomeView*.swift`, `SessionDrawerContainer.swift`,
 * `DrawerList*.swift`, `CatchUp*.swift`, `ServerSwitcher.swift`, `SessionSwitcher*.swift`,
 * `InAppNotices.swift`, `HomeSettingsFeature.swift` and the rest of
 * `/Users/vogel/dev/opencode-ios/OpenCode/Drawer/`. Track every file in `docs/parity/drawer.md`.
 *
 * Shape kept from iOS so `:app` wiring stays put: Home always shows a session (a draft
 * when none is open) under a drawer; opening a session pushes it on [State.stack]; Settings
 * is presented ([State.settings], TCA `@Presents`). [State.serverID]/[State.serverName] are
 * placeholders for iOS's `server: OpenCodeServer` + `snapshot: OpenCodeHomeSnapshot`.
 */
class HomeFeature : Reducer<HomeFeature.State, HomeFeature.Action> {
    data class State(
        val serverID: String,
        val serverName: String,
        /** The connected server (iOS `server`); null only in `AppView`'s fallback before Home exists. */
        val server: OpenCodeServer? = null,
        /** Placeholder for iOS's `snapshot: OpenCodeHomeSnapshot`: root sessions, newest first. */
        val sessions: List<OpenCodeSession> = emptyList(),
        val isDrawerOpen: Boolean = false,
        /** The draft at the root, then opened sessions on top (iOS session stack). */
        val draft: SessionFeature.State = SessionFeature.State.draft(server ?: unconnected, draftProject = null, projects = emptyList()),
        val stack: List<SessionFeature.State> = emptyList(),
        val settings: SettingsFeature.State? = null,
    )

    sealed interface Action {
        data object Task : Action
        data class DrawerOpenChanged(val isOpen: Boolean) : Action
        data class SessionTapped(val sessionID: String, val title: String) : Action
        data object NewSessionTapped : Action
        /** System back / predictive back popped the top session. */
        data object StackPopped : Action
        data class Draft(val action: SessionFeature.Action) : Action
        data class Session(val sessionID: String, val action: SessionFeature.Action) : Action
        data object SettingsTapped : Action
        data class Settings(val action: PresentationAction<SettingsFeature.Action>) : Action
        data class Delegate(val action: DelegateAction) : Action
    }

    /** What Home asks its parent (`AppFeature`) to do (TCA delegate actions). */
    sealed interface DelegateAction {
        /** Leave this server to pick another (iOS `.picking`). */
        data object ServersRequested : DelegateAction
    }

    companion object {
        /** Stands in for the server while Home has none (placeholder Home). */
        internal val unconnected = OpenCodeServer(url = "", username = "", password = "")
    }

    private object SettingsSlot
    private object StackSlot

    private val core = Reducer<State, Action> { state, action ->
        when (action) {
            Action.Task -> Reduced(state)
            is Action.DrawerOpenChanged -> Reduced(state.copy(isDrawerOpen = action.isOpen))
            is Action.SessionTapped -> {
                val session = state.sessions.firstOrNull { it.id == action.sessionID }
                    ?: OpenCodeSession(action.sessionID, action.title, projectID = "", directory = "", updatedAt = Instant.EPOCH)
                val stack = state.stack.filterNot { it.session?.id == action.sessionID } +
                    SessionFeature.State.session(state.server ?: unconnected, session)
                Reduced(state.copy(stack = stack, isDrawerOpen = false))
            }
            Action.NewSessionTapped -> Reduced(state.copy(stack = emptyList(), draft = SessionFeature.State.draft(state.server ?: unconnected, draftProject = null, projects = emptyList()), isDrawerOpen = false))
            Action.StackPopped -> Reduced(state.copy(stack = state.stack.dropLast(1)))
            Action.SettingsTapped -> Reduced(state.copy(settings = SettingsFeature.State(serverName = state.serverName), isDrawerOpen = false))
            is Action.Settings -> when (val presented = action.action) {
                is PresentationAction.Presented -> when (presented.action) {
                    SettingsFeature.Action.ServersTapped -> Reduced(
                        state.copy(settings = null),
                        Effect.send(Action.Delegate(DelegateAction.ServersRequested)),
                    )
                    else -> Reduced(state)
                }
                PresentationAction.Dismiss -> Reduced(state)
            }
            is Action.Draft, is Action.Session, is Action.Delegate -> Reduced(state)
        }
    }

    private val draft = ai.opencode.core.store.scope<State, Action, SessionFeature.State, SessionFeature.Action>(
        state = { it.draft },
        setState = { s, d -> s.copy(draft = d) },
        action = { (it as? Action.Draft)?.action },
        embed = Action::Draft,
        child = SessionFeature(),
    )

    private val body: Reducer<State, Action> = ai.opencode.core.store.combine(
        draft,
        forEach(
            slot = StackSlot,
            parent = ifLet(
                id = SettingsSlot,
                parent = core,
                state = { it.settings },
                setState = { s, c -> s.copy(settings = c) },
                action = { (it as? Action.Settings)?.action },
                embed = Action::Settings,
                child = SettingsFeature(),
            ),
            elements = { it.stack },
            setElements = { s, list -> s.copy(stack = list) },
            elementID = { it.session?.id.orEmpty() },
            action = { (it as? Action.Session)?.let { a -> a.sessionID to a.action } },
            embed = Action::Session,
            child = SessionFeature(),
        ),
    )

    override fun reduce(state: State, action: Action): Reduced<State, Action> = body.reduce(state, action)
}
