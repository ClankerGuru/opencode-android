package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTimeClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.retry.NetworkPathClient
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.RetrySchedule
import ai.opencode.server.retry.RetryVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Port of iOS `HomeConnectionFeature` (`OpenCode/Drawer/HomeConnectionFeature.swift`): the
 * event-stream lifecycle, retry timers, and last-known activity deadline. Home reconciles REST
 * snapshots into its session list and coordinates requests on open screens; those operations can
 * settle this child's connection state (`HomeFeatureConnection.kt`).
 *
 * Its cancellation identities are Home's ([HomeFeature.CancelID]): Home also cancels retries after
 * an authoritative snapshot settles, so both sides name the same effects.
 */
class HomeConnectionFeature : Reducer<HomeConnectionFeature.State, HomeConnectionFeature.Action> {
    data class State(
        val server: OpenCodeServer,
        val connectionStatus: ConnectionStatus = ConnectionStatus.connected,
        /** Set only when the event stream is refused with a 401. */
        val connectionErrorMessage: String? = null,
        /** A live stream has connected in this process. */
        val hasConnected: Boolean = false,
        /** The view's task owns the event stream, so foregrounding can restart it. */
        val isStreaming: Boolean = false,
        val isEventStreamLive: Boolean = false,
        /** iOS `@ObservationStateIgnored`: bookkeeping, not shown. */
        val snapshotFailures: Int = 0,
        val lastKnownActivityDeadline: Instant? = null,
    ) {
        @Suppress("EnumEntryName")
        enum class ConnectionStatus { reconnecting, connected, disconnected }

        val credentialsRejected: Boolean get() = connectionErrorMessage != null
        val isConnected: Boolean get() = connectionStatus == ConnectionStatus.connected
    }

    sealed interface Action {
        data object Task : Action
        data object SceneEnteredBackground : Action
        data class ServerConnected(val server: OpenCodeServer) : Action
        data class EventStreamFailed(val error: OpenCodeClientError) : Action
        data object ConnectionLost : Action
        data object SnapshotFailed : Action
        data object SnapshotRetryRequested : Action
        data object LastKnownActivityExpired : Action
        data object NetworkRerouted : Action
        data object RetryConnectionTapped : Action
        data class ServerEvent(val event: OpenCodeServerEvent) : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            val next = state.copy(isStreaming = true)
            val networkPath = dependency(NetworkPathClient)
            Reduced(
                next,
                Effect.merge(
                    eventStream(next.server),
                    Effect.run<Action> { send ->
                        networkPath.reroutes().collect { send(Action.NetworkRerouted) }
                    }.cancellable(HomeFeature.CancelID.NetworkPath, cancelInFlight = true),
                    if (next.isConnected) Effect.none() else lastKnownActivityExpiry(next),
                ),
            )
        }

        Action.SceneEnteredBackground -> {
            if (!state.isEventStreamLive) {
                Reduced(state, Effect.cancel(HomeFeature.CancelID.Events))
            } else {
                // Hold the live stream briefly so a quick return has already caught up.
                val backgroundTime = dependency(BackgroundTimeClient)
                Reduced(
                    state,
                    Effect.run<Action> { backgroundTime.hold("Live updates", HomeFeature.liveUpdatesAfterLeaving) }
                        .cancellable(HomeFeature.CancelID.LiveUpdatesAfterLeaving, cancelInFlight = true),
                )
            }
        }

        is Action.ServerConnected -> {
            // The stream started before any rename since; the name on screen wins. (iOS also
            // marks its PerfTimeline here; Android's diagnostics have no reconnect timeline.)
            val server = action.server.copy(name = state.server.name)
            Reduced(
                state.copy(server = server, hasConnected = true, isEventStreamLive = true, connectionErrorMessage = null),
            )
        }

        is Action.EventStreamFailed -> Reduced(
            state.copy(
                connectionErrorMessage = action.error.message,
                connectionStatus = State.ConnectionStatus.disconnected,
                isEventStreamLive = false,
            ),
        )

        Action.ConnectionLost -> {
            val wasConnected = state.isConnected
            var next = state.copy(
                connectionStatus = if (wasConnected) State.ConnectionStatus.reconnecting else State.ConnectionStatus.disconnected,
                isEventStreamLive = false,
            )
            if (wasConnected) {
                val now = dependency(HomeSnapshotDate).now()
                next = next.copy(lastKnownActivityDeadline = now.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds))
            }
            Reduced(next, if (wasConnected) lastKnownActivityExpiry(next) else Effect.none())
        }

        Action.SnapshotFailed -> {
            if (!state.isEventStreamLive) {
                Reduced(state.copy(connectionStatus = State.ConnectionStatus.disconnected))
            } else {
                val failures = state.snapshotFailures + 1
                val delays = HomeFeature.snapshotRetryDelays
                val delay = delays[minOf(failures, delays.size) - 1]
                val clock = dependency(Clock)
                Reduced(
                    state.copy(snapshotFailures = failures),
                    Effect.run<Action> { send ->
                        clock.sleep(delay.seconds)
                        send(Action.SnapshotRetryRequested)
                    }.cancellable(HomeFeature.CancelID.SnapshotRetry, cancelInFlight = true),
                )
            }
        }

        Action.LastKnownActivityExpired -> Reduced(state.copy(lastKnownActivityDeadline = null))

        Action.NetworkRerouted -> {
            AppLog.stream.info("Network route changed")
            if (!state.isStreaming || state.credentialsRejected) {
                Reduced(state)
            } else {
                val next = state.copy(isEventStreamLive = false)
                Reduced(next, eventStream(next.server))
            }
        }

        Action.RetryConnectionTapped -> {
            if (state.credentialsRejected) {
                Reduced(state)
            } else {
                var next = state.copy(isEventStreamLive = false)
                if (!next.isConnected) next = next.copy(connectionStatus = State.ConnectionStatus.reconnecting)
                Reduced(
                    next,
                    Effect.merge(
                        Effect.cancel(HomeFeature.CancelID.LiveUpdatesAfterLeaving),
                        if (next.isStreaming) eventStream(next.server) else Effect.none(),
                    ),
                )
            }
        }

        // Transport output consumed by Home's snapshot/session coordination.
        Action.SnapshotRetryRequested, is Action.ServerEvent -> Reduced(state)
    }

    /**
     * The event stream: every candidate address in turn, reconnecting on the stream schedule
     * (sooner when the route changes or the server answers a request), or on the overflow schedule
     * at the same address when the app fell behind. A 401 stops it for good.
     */
    fun eventStream(server: OpenCodeServer): Effect<Action> {
        val events = dependency(OpenCodeClient).events
        val networkPath = dependency(NetworkPathClient)
        val clock = dependency(Clock)
        return Effect.run<Action> { send ->
            val schedule = RetrySchedule(RetryPolicy.stream, clock, networkPath.retryEnvironment)
            val overflowSchedule = RetrySchedule(RetryPolicy.streamOverflow, clock, networkPath.retryEnvironment)
            var nextAddressIndex = 0
            val operationID = UUID.randomUUID().toString().uppercase()
            val log = AppLog.stream
            while (currentCoroutineContext().isActive) {
                var overflowed = false
                val candidates = server.candidateURLs
                for (index in nextAddressIndex until candidates.size) {
                    val address = candidates[index]
                    var connected = false
                    val candidate = server.at(address)
                    val opened = clock.now()
                    try {
                        NetworkRetry.streamOperationID.withValue(operationID) {
                            events(candidate).collect { event ->
                                if (event == OpenCodeServerEvent.Connected) {
                                    connected = true
                                    schedule.reset()
                                    log.info { "Connected (address ${index + 1} of ${candidates.size})" }
                                    send(Action.ServerConnected(candidate))
                                } else {
                                    send(Action.ServerEvent(event))
                                }
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        if (!currentCoroutineContext().isActive) return@run
                        overflowed = error is EventStreamOverflow
                        val lasted = (clock.now() - opened).logSummary
                        val outcome = if (connected) "dropped after $lasted" else "failed"
                        if (error is OpenCodeClientError) {
                            log.notice { "Address ${index + 1} of ${candidates.size} $outcome: ${error.statusCode?.let { "HTTP $it" } ?: "stream ended"}" }
                        } else {
                            log.notice { "Address ${index + 1} of ${candidates.size} $outcome: ${logDescription(error)}" }
                        }
                        val failure = error as? OpenCodeClientError ?: OpenCodeClientError("Couldn’t reach the OpenCode server")
                        if (failure.statusCode == 401) {
                            log.error("Stopped: the server rejected the credentials")
                            send(Action.EventStreamFailed(failure))
                            return@run
                        }
                    }
                    if (!currentCoroutineContext().isActive) return@run
                    // A connection that ends without local data loss can clear the overload
                    // history. Merely receiving `.connected` cannot.
                    if (connected && !overflowed) overflowSchedule.reset()
                    send(Action.ConnectionLost)
                    if (overflowed) {
                        nextAddressIndex = index
                        break
                    }
                }
                if (overflowed) {
                    val pause = overflowSchedule.next(RetryVerdict.Transient(retryAfter = null)) ?: return@run
                    log.notice { "Reconnecting after overflow: ${pause.kind.logSummary}" }
                    if (!pause.run()) return@run
                    continue
                }
                nextAddressIndex = 0
                val pause = schedule.next(RetryVerdict.Transient(retryAfter = null)) ?: return@run
                log.notice { "Reconnecting: ${pause.kind.logSummary}, sooner if the route changes or the server answers" }
                if (!pause.run(wake = { nextReconnectCue(networkPath, server.persistenceID) })) return@run
            }
        }.cancellable(HomeFeature.CancelID.Events, cancelInFlight = true)
    }

    /** Ends last-known running state at its deadline. */
    fun lastKnownActivityExpiry(state: State): Effect<Action> {
        val deadline = state.lastKnownActivityDeadline ?: return Effect.cancel(HomeFeature.CancelID.LastKnownActivity)
        val remaining = JavaDuration.between(dependency(HomeSnapshotDate).now(), deadline).toMillis()
        val clock = dependency(Clock)
        return Effect.run<Action> { send ->
            if (remaining > 0) clock.sleep(remaining.milliseconds)
            send(Action.LastKnownActivityExpired)
        }.cancellable(HomeFeature.CancelID.LastKnownActivity, cancelInFlight = true)
    }

    private companion object {
        /** Returns at the next reroute, or once a request reaches the server again, whichever comes first. */
        suspend fun nextReconnectCue(networkPath: NetworkPathClient, server: String) = coroutineScope {
            val reroute = async { nextReroute(networkPath) }
            val answered = async { networkPath.serverAnswered(server) }
            select {
                reroute.onAwait {}
                answered.onAwait {}
            }
            coroutineContext.cancelChildren()
        }

        /** Returns at the next reroute; never, if the path reports none. */
        suspend fun nextReroute(networkPath: NetworkPathClient) {
            if (networkPath.reroutes().firstOrNull() != null) return
            awaitCancellation()
        }

        /**
         * iOS `OpenCodeClient.logDescription`: an exception's type and its cause's, never its
         * message (which can carry the URL). Same rule as `:server`'s internal helper.
         */
        fun logDescription(error: Throwable): String {
            var description = error::class.java.simpleName
            error.cause?.let { description += " (${it::class.java.simpleName})" }
            return description
        }
    }
}
