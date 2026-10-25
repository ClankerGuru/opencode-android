package ai.opencode.feature.drawer.switcher

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.feature.drawer.HomeFeature

/**
 * Port of iOS `SessionSwitcherFeature` (`OpenCode/Drawer/SessionSwitcherFeature.swift`): whether
 * the Session Switcher (experimental) is up. Picking a session closes it; Home opens the pick
 * (`HomeFeature.reduceSessionSwitcher`).
 */
class SessionSwitcherFeature : Reducer<SessionSwitcherFeature.State, SessionSwitcherFeature.Action> {
    data class State(val isPresented: Boolean = false)

    sealed interface Action {
        data class PresentationChanged(val shown: Boolean) : Action
        data class Selected(val sessionID: String) : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.PresentationChanged -> Reduced(state.copy(isPresented = action.shown))
        is Action.Selected -> Reduced(state.copy(isPresented = false))
    }
}

/** Home's side of the switcher: a pick opens that session as a drawer tap does. Off unless the experiment is on. */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.reduceSessionSwitcher(
    state: HomeFeature.State,
    action: SessionSwitcherFeature.Action,
): Reduced<HomeFeature.State, HomeFeature.Action> {
    if (!dependency(ExperimentalFeatures)(ExperimentalFeature.sessionSwitcher)) return Reduced(state)
    return when (action) {
        is SessionSwitcherFeature.Action.PresentationChanged -> Reduced(state)
        is SessionSwitcherFeature.Action.Selected ->
            if (state.sessions.none { it.id == action.sessionID }) Reduced(state)
            else Reduced(state, Effect.send(HomeFeature.Action.SessionTapped(action.sessionID)))
    }
}
