package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.bridge.SessionDelegate
import ai.opencode.feature.drawer.bridge.SessionLinkTarget
import ai.opencode.feature.drawer.bridge.SessionScreenEvent
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException

/*
 * Port of iOS `HomeFeature+SessionLinks.swift`: session IDs linked in transcripts (`SessionLink`).
 * Titles come from sessions already loaded (the list, the open families), then one fetch per
 * unknown ID, a few at a time and the rest queued. A tap opens the session on this server; a
 * subagent pushes over the screen on top.
 */


internal fun HomeFeature.resolveSessionLinks(state: State, ids: List<String>): HomeReduced {
    val answers = LinkedHashMap<String, SessionLinkTarget>()
    var s = state
    for (id in ids) {
        val session = s.linkedSession(id)
        when {
            session != null -> answers[id] = SessionLinkTarget.of(session)
            id in s.missingLinkedSessionIDs -> answers[id] = SessionLinkTarget.Missing
            id !in s.linkedSessionFetches && id !in s.linkedSessionQueue -> s = s.copy(linkedSessionQueue = s.linkedSessionQueue + id)
        }
    }
    s = s.applyingSessionLinks(answers)
    return startLinkedSessionFetches(s)
}

/** Starts queued lookups while fewer than the limit are in flight. */
private fun startLinkedSessionFetches(state: State): HomeReduced {
    var s = state
    val effects = ArrayList<Effect<Action>>()
    val server = s.server
    val client = dependency(OpenCodeClient)
    while (s.linkedSessionFetches.size < HomeFeature.linkedSessionFetchLimit && s.linkedSessionQueue.isNotEmpty()) {
        val id = s.linkedSessionQueue.first()
        s = s.copy(linkedSessionQueue = s.linkedSessionQueue.drop(1), linkedSessionFetches = s.linkedSessionFetches + id)
        effects += Effect.run { send -> send(Action.LinkedSessionFetched(id, getSession(client, server, id))) }
    }
    return Reduced(s, Effect.merge(effects))
}

private suspend fun getSession(client: OpenCodeClient, server: ai.opencode.server.OpenCodeServer, id: String): Result<OpenCodeSession> =
    try {
        Result.success(client.getSession(server, id))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: OpenCodeClientError) {
        Result.failure(error)
    }

internal fun HomeFeature.openSessionLink(state: State, id: String): HomeReduced {
    val session = state.linkedSession(id)
    if (session != null) {
        val opened = openLinkedSession(state.copy(pendingLinkedSessionOpen = null), session)
        return Reduced(opened.state, Effect.merge(Effect.cancel(HomeFeature.CancelID.LinkedSessionOpen), opened.effect))
    }
    val s = state.copy(pendingLinkedSessionOpen = HomeFeature.PendingLinkedSessionOpen(id, state.linkScreen))
    val server = s.server
    val client = dependency(OpenCodeClient)
    return Reduced(
        s,
        Effect.run<Action> { send -> send(Action.LinkedSessionOpened(id, getSession(client, server, id))) }
            .cancellable(HomeFeature.CancelID.LinkedSessionOpen, cancelInFlight = true),
    )
}

internal fun HomeFeature.reduceSessionLinks(state: State, action: Action): HomeReduced = when (action) {
    is Action.LinkedSessionFetched -> {
        val s = state.copy(linkedSessionFetches = state.linkedSessionFetches - action.id)
        startLinkedSessionFetches(learningLinkedSession(s, action.id, action.result))
    }

    is Action.LinkedSessionOpened -> {
        val s = learningLinkedSession(state, action.id, action.result)
        // Only the latest tap, and only while its screen is still on top: a person who moved on
        // isn't taken somewhere they no longer are.
        val pending = s.pendingLinkedSessionOpen
        if (pending == null || pending.id != action.id) {
            Reduced(s)
        } else {
            val cleared = s.copy(pendingLinkedSessionOpen = null)
            val session = action.result.getOrNull()
            when {
                pending.screen != cleared.linkScreen -> Reduced(cleared)
                session == null || session.id != action.id -> {
                    dependency(HapticsClient).play(Haptic.error)
                    Reduced(cleared)
                }
                else -> openLinkedSession(cleared, session)
            }
        }
    }

    else -> Reduced(state)
}

private fun learningLinkedSession(state: State, id: String, result: Result<OpenCodeSession>): State {
    val session = result.getOrNull()
    val error = result.exceptionOrNull() as? OpenCodeClientError
    return when {
        session != null && session.id == id ->
            state.copy(linkedSessions = state.linkedSessions + (id to session)).applyingSessionLinks(mapOf(id to SessionLinkTarget.of(session)))
        error?.statusCode == 404 ->
            state.copy(missingLinkedSessionIDs = state.missingLinkedSessionIDs + id).applyingSessionLinks(mapOf(id to SessionLinkTarget.Missing))
        // Unreachable or odd: screens forget they asked, so the next look asks again.
        else -> state.forgettingSessionLinks(listOf(id))
    }
}

private fun HomeFeature.openLinkedSession(state: State, session: OpenCodeSession): HomeReduced {
    if (state.topDetail?.session?.id == session.id) return Reduced(state)
    if (session.parentID != null) return reduceSessionDetail(state, SessionScreenEvent.Delegate(SessionDelegate.OpenSubagent(session)))
    var s = state
    if (s.sessions.none { it.id == session.id }) s = s.copy(sessions = listOf(session) + s.sessions)
    return Reduced(s.copy(nextMoveReason = "a session link was tapped"), Effect.send(Action.SessionTapped(session.id)))
}

/** The screen on top, as a tapped link remembers it. */
val State.linkScreen: HomeFeature.PendingLinkedSessionOpen.Screen?
    get() = topDetail?.let {
        HomeFeature.PendingLinkedSessionOpen.Screen(it.session?.id, it.navigationID, it.visit, subagentStack.size)
    }

/** A session already at hand: the list, a lookup, or an open screen's family. */
fun State.linkedSession(id: String): OpenCodeSession? {
    (sessions.firstOrNull { it.id == id } ?: linkedSessions[id])?.let { return it }
    for (screen in listOfNotNull(sessionDetail) + subagentStack) {
        val session = if (screen.session?.id == id) screen.session else screen.familySessions.firstOrNull { it.id == id }
        if (session != null) return session
    }
    return null
}

/**
 * Hands each open screen the answers for IDs it asked about (iOS `applySessionLinks`), kept here as
 * [State.linkTargets] for the screens to read. TODO(merge): `SessionFeature.State.applySessionLinks`.
 */
@Suppress("UNUSED_PARAMETER")
fun State.applyingSessionLinks(targets: Map<String, SessionLinkTarget>): State = this

/** A lookup that failed: screens still waiting on it may ask again. TODO(merge): `forgetSessionLinks`. */
@Suppress("UNUSED_PARAMETER")
fun State.forgettingSessionLinks(ids: List<String>): State = this

/** Every linked ID's answer, as the screens see them. */
val State.linkTargets: Map<String, SessionLinkTarget>
    get() = buildMap {
        for ((id, session) in linkedSessions) put(id, SessionLinkTarget.of(session))
        for (id in missingLinkedSessionIDs) put(id, SessionLinkTarget.Missing)
        for (id in linkedSessionFetches + linkedSessionQueue) putIfAbsent(id, SessionLinkTarget.Resolving)
    }
