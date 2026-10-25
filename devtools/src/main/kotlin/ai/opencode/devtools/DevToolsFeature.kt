package ai.opencode.devtools

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * Phase 0 placeholder for this area's root reducer (iOS TCA feature).
 *
 * Will port nothing 1:1: Android-native developer tools (log viewer over `AppLog.recentLines`, design-system gallery, fixture server shortcuts, perf overlays). iOS `Tuning/` and `Diagnostics/` are references, not port targets, except product-visible Developer Mode behaviour (`DiagnosticsClient` uploads), which ports faithfully
 * from `/Users/vogel/dev/opencode-ios/OpenCode/Tuning/` (all of: OpenCode/Tuning/, OpenCode/Diagnostics/).
 * Track every file in `docs/parity/devtools.md`.
 *
 * Keep the public names (`DevToolsFeature`, `DevToolsFeature.State`, `DevToolsFeature.Action`, `DevToolsView`) stable: `:app`
 * and parent features already wire them. Replace the placeholder state and actions freely.
 */
class DevToolsFeature : Reducer<DevToolsFeature.State, DevToolsFeature.Action> {
    data class State(
        val placeholder: Boolean = true,
    )

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> Reduced(state)
    }
}
