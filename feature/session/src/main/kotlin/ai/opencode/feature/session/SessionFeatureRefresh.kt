package ai.opencode.feature.session

import ai.opencode.core.store.Reduced
import ai.opencode.server.OpenCodeServer
import ai.opencode.core.store.Effect
import kotlin.time.Duration

// Ported by group STREAM (iOS `SessionFeature+Refresh.swift`).

/** iOS `SessionFeature.RefreshReason`: why an authoritative read runs. */
sealed interface RefreshReason {
    data object Open : RefreshReason
    data object Resume : RefreshReason
    data object Reconnect : RefreshReason
    data object RunState : RefreshReason
    data object Invalidation : RefreshReason
    data class Settlement(override val messageID: String) : RefreshReason
    data object Watchdog : RefreshReason
    data object Snapshot : RefreshReason

    val label: String
        get() = when (this) {
            Open -> "open"
            Resume -> "resume"
            Reconnect -> "reconnect"
            RunState -> "runState"
            Invalidation -> "invalidation"
            is Settlement -> "settlement"
            Watchdog -> "watchdog"
            Snapshot -> "snapshot"
        }

    val messageID: String? get() = null

    val requiresFollowup: Boolean
        get() = when (this) {
            Invalidation, is Settlement, RunState -> true
            else -> false
        }
}

/**
 * iOS `SessionFeature.WatchdogSchedule`. [deadline] is a reading of the watchdog's monotonic
 * [ai.opencode.core.dependencies.Clock] (`Clock.now()`), as iOS keeps an `AnyClock` instant.
 */
data class WatchdogSchedule(
    val sessionID: String,
    val interval: Duration,
    val deadline: Duration,
)

/**
 * iOS `watchdog(_:)`: (re)arms the reconciliation watchdog for the session's cadence.
 * TODO(group STREAM): port; `func watchdog(_ state: inout State) -> Effect<Action>`.
 */
internal fun SessionFeature.watchdog(
    state: SessionFeature.State,
): Reduced<SessionFeature.State, SessionFeature.Action> = Reduced(state)

/**
 * iOS `fetchQueuedInputs(server:sessionID:)`.
 * TODO(group STREAM): port; `func fetchQueuedInputs(server: OpenCodeServer, sessionID: String) -> Effect<Action>`.
 */
@Suppress("UNUSED_PARAMETER")
internal fun SessionFeature.fetchQueuedInputs(server: OpenCodeServer, sessionID: String): Effect<SessionFeature.Action> =
    Effect.none()
