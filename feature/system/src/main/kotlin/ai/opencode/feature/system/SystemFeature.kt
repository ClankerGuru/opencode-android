package ai.opencode.feature.system

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * The system area's public reducer name, kept for parents that wire it. iOS has no
 * `SystemFeature`: its `OpenCode/System/` is clients and system entry points that
 * `AppFeature` and `HomeFeature` drive. The same holds here; the entry points are:
 *
 * - `intents/`: [ai.opencode.feature.system.intents.SystemAction],
 *   [ai.opencode.feature.system.intents.SystemIntegrationClient] (actions in, directory out),
 *   `SystemActionActivity` (shortcuts, links, tiles, widgets, notification taps),
 *   `AskOpenCodeActivity`, `BackgroundCallActivity`, `SystemPromptSender`.
 * - `liveactivity/`: `VoiceLiveActivityClient`, `DictationLiveActivityClient` (Live Updates).
 * - `notifications/`: `SessionNotificationsClient`, `NotificationPermissionClient`,
 *   `AppIconBadgeClient`, `SessionPushNotifications`.
 * - `share/`: `ShareInboxClient` and the share target (`ShareActivity`).
 * - `widgets/`: Quick Settings tiles and Glance widgets.
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
