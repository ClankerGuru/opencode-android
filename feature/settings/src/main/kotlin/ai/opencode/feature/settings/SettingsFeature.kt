package ai.opencode.feature.settings

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * Phase 0 placeholder for this area's root reducer (iOS TCA feature).
 *
 * Will port `SettingsView.swift`, `SettingsServers.swift`, `ServerTitleFeature.swift`, `ServerConnectView.swift`, `ConnectionHealth.swift`, `ConnectionMonitor.swift`, `PairingScanner.swift`, `ExperimentalFeatures.swift`, `Onboarding/`, `WhatsNew/`
 * from `/Users/vogel/dev/opencode-ios/OpenCode/Settings/` (all of: OpenCode/Settings/).
 * Track every file in `docs/parity/settings.md`.
 *
 * The unpaired connection screen and manual server form live in `:app` (`AppView`), as on iOS; `Onboarding/` ports here.
 *
 * Keep the public names (`SettingsFeature`, `SettingsFeature.State`, `SettingsFeature.Action`, `SettingsView`) stable: `:app`
 * and parent features already wire them. Replace the placeholder state and actions freely.
 */
class SettingsFeature : Reducer<SettingsFeature.State, SettingsFeature.Action> {
    data class State(
        /** Placeholder for iOS's connected `OpenCodeServer` and its health. */
        val serverName: String = "",
    )

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action
        /** Servers: leave this server to pick or pair another (Home forwards it to `AppFeature`). */
        data object ServersTapped : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> Reduced(state)
        Action.ServersTapped -> Reduced(state)
    }
}
