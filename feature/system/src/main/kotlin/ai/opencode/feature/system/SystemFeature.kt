package ai.opencode.feature.system

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * Phase 0 placeholder for this area's root reducer (iOS TCA feature).
 *
 * Will port `System/Intents/` (→ App Actions, app shortcuts, `opencode://` deep links), `System/LiveActivity/` (→ Live Updates / ongoing notifications), `System/Notifications/` (badges, session notifications), `System/Share/` + `OpenCodeShare/` (→ share target activity), `OpenCodeWidgets/` (→ Glance widgets), `OpenCodeNotifications/` (→ FCM bridge, stubbed behind an interface until credentials exist)
 * from `/Users/vogel/dev/opencode-ios/OpenCode/System/` (all of: OpenCode/System/, OpenCodeShare/, OpenCodeWidgets/, OpenCodeNotifications/).
 * Track every file in `docs/parity/system.md`.
 *
 * `SystemAction` handoffs route through `AppFeature` → `HomeFeature`, as on iOS.
 *
 * Keep the public names (`SystemFeature`, `SystemFeature.State`, `SystemFeature.Action`, `SystemView`) stable: `:app`
 * and parent features already wire them. Replace the placeholder state and actions freely.
 */
class SystemFeature : Reducer<SystemFeature.State, SystemFeature.Action> {
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
