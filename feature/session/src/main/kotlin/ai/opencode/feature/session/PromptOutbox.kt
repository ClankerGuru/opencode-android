package ai.opencode.feature.session

import ai.opencode.core.dependencies.BackgroundTaskClient
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerID
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.retry.NetworkRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

// Port of iOS `Composer/PromptOutbox.swift`. It carries `SessionFeature.PendingPrompt` and
// `DeliveryOutcome`, so it lands in this module (the composer module sits below the session's).

/**
 * The persistence the outbox and [UndeliveredPrompts] write through (iOS `ComposerPersistence`'s
 * outbox members). The composer persistence port implements it and binds it at the screen's start
 * (`restorePersistedComposer`); unbound, sends live for the process only.
 */
interface OutboxPersistence {
    val identity: String
    fun recordOutbox(
        serverID: String,
        sessionID: String?,
        draftID: String?,
        directory: String?,
        newSessionID: String,
        createdSession: OpenCodeSession?,
        pending: PendingPrompt,
        isHeld: Boolean,
        backgroundKey: String?,
    )
    fun updateOutboxDelivery(messageID: String, delivery: OpenCodeDelivery, backgroundKey: String?)
    fun noteDraftSessionCreated(serverID: String, draftID: String?, session: OpenCodeSession)
    fun loadOutbox(serverID: String, sessionID: String?): List<RestoredOutboxEntry>
    fun loadUndelivered(serverID: String, sessionID: String?): List<UndeliveredPrompts.Entry>
    fun markUndelivered(pending: PendingPrompt, reason: String, serverID: String, sessionID: String?)
    fun settlePrompts(messageIDs: Set<String>)
}

/** iOS `RestoredOutboxEntry`: one outbox send kept across termination. */
data class RestoredOutboxEntry(
    val serverID: String,
    val sessionID: String?,
    val draftID: String?,
    val directory: String?,
    val newSessionID: String,
    val createdSession: OpenCodeSession?,
    val pending: PendingPrompt,
    val isHeld: Boolean,
    val backgroundKey: String?,
)

/**
 * Sends from this phone waiting for the server, in the order they were made. Each session (or
 * draft, until its session exists) is one lane that delivers one message at a time, so the server
 * admits them in order, while the screen shows every send at once and never waits for it.
 *
 * It lives for the app's lifetime, outside any screen: leaving a session, switching sessions, or
 * backgrounding the app doesn't stop a lane, and a screen that opens on the session shows what's
 * still on its way. What the reader asks of a send (Delete, Move to Queue, Send Now) is kept on the
 * send itself, so it holds wherever the reader goes: a send still waiting changes before it's
 * posted, and one already being posted changes on the server once it's admitted. A send that fails
 * for good, or that Stop holds back, is held: sends behind a held one wait until it's retried (in
 * its place) or deleted. Every send ends delivered or held as a failed send the screen shows with
 * Retry and Delete; none is ever left without a way on.
 */
class PromptOutbox(
    /** Keeps the app running briefly when it leaves the foreground with sends still on their way; returns the call that ends it. */
    private val backgroundWork: suspend () -> (() -> Unit) = { {} },
    private val now: () -> Instant = { Instant.now() },
) {
    sealed interface Lane {
        data class Session(val id: String) : Lane
        /** A new session's draft, named by its first message. Once a send creates the session, later sends in the lane go to it. */
        data class Draft(val id: String) : Lane
    }

    sealed interface Status {
        /** Delivered or failed, with the send as it last stood. */
        data class Settled(val outcome: DeliveryOutcome, val pending: PendingPrompt) : Status
        /** Deleted: never posted, or taken back from the server once admitted. */
        data object Withdrawn : Status
        /** Stop held it back before it was posted; the screen that stopped it shows it as a failed send. */
        data object Stopped : Status
        /** A change asked for while it was being posted didn't reach the server. */
        data class UpdateFailed(val sessionID: String, val restoring: OpenCodeDelivery?, val error: OpenCodeClientError) : Status
        /** Blocking foreground work was moved (or failed to move) ahead of a steer. */
        data class BackgroundMoved(val sessionID: String, val key: String, val error: OpenCodeClientError?) : Status
        /** The send ended (or was held) without moving the work it claimed. */
        data class BackgroundUnused(val key: String) : Status
    }

    /** What a delivery job does next, from what the reader has asked. */
    sealed interface Attempt {
        data class Post(val pending: PendingPrompt) : Attempt
        data object Cancelled : Attempt
        data object Stopped : Attempt
        /** A retry replaced it; the new job takes over and this one just ends. */
        data object Superseded : Attempt
    }

    /** What the reader asked of a send while it was being posted. */
    data class Intent(
        val cancelled: Boolean = false,
        /** Stop came while it was being posted: it finishes this attempt and, if that fails, isn't tried again. */
        val stopped: Boolean = false,
        /** Set when the delivery wanted now isn't the one it was posted with. */
        val changedDelivery: OpenCodeDelivery? = null,
        val postedDelivery: OpenCodeDelivery? = null,
    )

    /** A delivery job's view of its send. */
    class Handle(
        /** The session an earlier send in a draft's lane created, if any. */
        val createdSessionID: String?,
        /** The ID a draft's lane creates its session with, the same for every attempt. */
        val newSessionID: String,
        /** The send as it stands now. */
        val current: () -> PendingPrompt,
        /** Called before each attempt: posts it as it stands now, or stops. */
        val begin: () -> Attempt,
        /**
         * Waits out `sleep` before trying again: true to try again, false when `sleep` gave up (the
         * retry budget ran out offline) or Delete or Stop ended it early.
         */
        val wait: suspend (sleep: suspend () -> Boolean) -> Boolean,
        val intent: () -> Intent,
        /** A failed deferred change: the screen hears it, and the send is kept as the server has it. */
        val report: (Status) -> Unit,
        /** The draft's session exists: Stop from here on is a session's Stop. */
        val created: (OpenCodeSession) -> Unit,
        /** Claims the blocking foreground work key to background ahead of a steer, once. */
        val takeBackgroundKey: () -> String? = { null },
        val networkOperation: NetworkRetry.ManualOperation = NetworkRetry.ManualOperation(),
    )

    /** Delivers one message and reports how it ended: sent, failed (a stopped send included), or withdrawn when it was deleted. */
    fun interface Job {
        suspend fun deliver(handle: Handle): DeliveryOutcome
    }

    private enum class Phase { waiting, posting, retrying, held }

    private data class Item(
        val pending: PendingPrompt,
        val job: Job,
        val phase: Phase,
        val cancelled: Boolean = false,
        val stopped: Boolean = false,
        val postedDelivery: OpenCodeDelivery? = null,
        val backgroundKey: String? = null,
        val sleeper: kotlinx.coroutines.Deferred<Boolean>? = null,
        /** A retry replaces the item; a job still running for the one it replaced stops at its next step. */
        val generation: Int,
        val networkOperation: NetworkRetry.ManualOperation = NetworkRetry.ManualOperation(),
    )

    private data class Admitted(val pending: PendingPrompt, val sessionID: String, val at: Instant)

    private data class LaneState(
        val serverKey: String,
        val items: MutableList<Item> = mutableListOf(),
        var admitted: List<Admitted> = emptyList(),
        var isDraft: Boolean = false,
        var draftID: String? = null,
        var directory: String? = null,
        val newSessionID: String,
        var createdSession: OpenCodeSession? = null,
        var createdSessionID: String? = null,
        /** The created session has been reported to a screen. */
        var announced: Boolean = false,
        var isWorking: Boolean = false,
    ) {
        fun appendAdmitted(pending: PendingPrompt, sessionID: String, at: Instant) {
            admitted = (admitted + Admitted(pending, sessionID, at)).takeLast(8)
        }
    }

    private class Storage {
        val lanes = HashMap<String, LaneState>()
        /** A session created by a draft's lane keeps using that lane. */
        val aliases = HashMap<String, String>()
        val observers = HashMap<String, LinkedHashMap<UUID, Channel<Status>>>()
        var generation = 0
        /** Messages the server announced as delivered, newest last. */
        val delivered = ArrayList<String>()
        var persistence: OutboxPersistence? = null
    }

    private class PersistedSnapshot(
        val persistence: OutboxPersistence,
        val serverID: String,
        val sessionID: String?,
        val draftID: String?,
        val directory: String?,
        val newSessionID: String,
        val createdSession: OpenCodeSession?,
        val pending: PendingPrompt,
        val isHeld: Boolean,
        val backgroundKey: String?,
    ) {
        fun record() = persistence.recordOutbox(
            serverID, sessionID, draftID, directory, newSessionID, createdSession, pending, isHeld, backgroundKey,
        )
    }

    private val lock = Any()
    private val storage = Storage()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private inline fun <R> locked(block: (Storage) -> R): R = synchronized(lock) { block(storage) }

    fun bind(persistence: OutboxPersistence) {
        locked { it.persistence = persistence }
    }

    fun contains(messageID: String): Boolean = locked { locate(messageID, it) != null }

    fun directory(messageID: String): String? = locked { storage ->
        val (key, _) = locate(messageID, storage) ?: return@locked null
        storage.lanes[key]?.directory
    }

    fun seedPersisted(entry: RestoredOutboxEntry, job: Job) {
        locked { storage ->
            val id = entry.pending.messageID
            if (locate(id, storage) != null) return@locked
            storage.generation += 1
            val resolvedSessionID = entry.sessionID ?: entry.createdSession?.id
            val lane: Lane = when {
                resolvedSessionID != null -> Lane.Session(resolvedSessionID)
                entry.draftID != null -> Lane.Draft(entry.draftID)
                else -> Lane.Draft(id)
            }
            val key = resolve(lane, entry.serverID, storage)
            val state = storage.lanes[key] ?: LaneState(serverKey = entry.serverID, newSessionID = entry.newSessionID)
            if (lane is Lane.Draft && resolvedSessionID == null) {
                state.isDraft = true
                state.draftID = lane.id
            } else if (entry.draftID != null) {
                storage.aliases[key(Lane.Draft(entry.draftID), entry.serverID)] = key
            }
            entry.directory?.let { state.directory = it }
            entry.createdSession?.let { created ->
                state.createdSession = created
                state.createdSessionID = created.id
                state.draftID = null
                storage.aliases[key(Lane.Session(created.id), entry.serverID)] = key
            }
            if (resolvedSessionID != null) {
                if (state.createdSessionID == null) state.createdSessionID = resolvedSessionID
                state.draftID = null
                storage.aliases[key(Lane.Session(resolvedSessionID), entry.serverID)] = key
            }
            state.items += Item(
                pending = entry.pending,
                job = job,
                phase = if (entry.isHeld) Phase.held else Phase.waiting,
                backgroundKey = entry.backgroundKey,
                generation = storage.generation,
            )
            storage.lanes[key] = state
        }
    }

    fun startIfNeeded(lane: Lane, serverKey: String) {
        val start = locked { storage -> claimWorker(resolve(lane, serverKey, storage), storage) }
        if (start != null) launchRun(start)
    }

    /**
     * Adds a send to the end of its lane, or, for a held send being retried, puts it back in its own
     * place. Call it in the order the sends were made. The channel reports the send until it settles.
     */
    fun enqueue(
        pending: PendingPrompt,
        lane: Lane,
        serverKey: String,
        backgroundKey: String? = null,
        newSessionID: String? = null,
        directory: String? = null,
        job: Job,
    ): ReceiveChannel<Status> {
        val channel = Channel<Status>(Channel.UNLIMITED)
        val (start, persisted) = locked { storage ->
            val id = pending.messageID
            observe(id, channel, storage)
            storage.generation += 1
            val explicitSessionID = (lane as? Lane.Session)?.id
            val explicitDraftID = (lane as? Lane.Draft)?.id
            val key: String
            val located = locate(id, storage)
            if (located != null) {
                key = located.first
                val index = located.second
                val laneState = storage.lanes.getValue(key)
                if (laneState.items[index].phase == Phase.held) {
                    val operation = laneState.items[index].networkOperation
                    operation.retried()
                    laneState.items[index] = Item(
                        pending = pending, job = job, phase = Phase.waiting,
                        backgroundKey = backgroundKey, generation = storage.generation, networkOperation = operation,
                    )
                }
            } else {
                key = resolve(lane, serverKey, storage)
                if (storage.lanes[key] == null) {
                    // A draft lane creates its session under this ID; one the share sheet may already
                    // have made (`newSessionID`) is found, not made twice.
                    val state = LaneState(
                        serverKey = serverKey,
                        draftID = if (explicitSessionID == null) explicitDraftID else null,
                        directory = directory,
                        newSessionID = newSessionID ?: newSessionID(now(), id),
                        createdSessionID = explicitSessionID,
                    )
                    if (lane is Lane.Draft) state.isDraft = true
                    storage.lanes[key] = state
                }
                storage.lanes.getValue(key).items += Item(
                    pending = pending, job = job, phase = Phase.waiting,
                    backgroundKey = backgroundKey, generation = storage.generation,
                )
            }
            val laneState = storage.lanes.getValue(key)
            if (explicitSessionID != null) {
                laneState.createdSessionID = explicitSessionID
                laneState.draftID = null
            } else if (laneState.createdSessionID != null || laneState.announced) {
                laneState.draftID = null
            } else if (explicitDraftID != null && laneState.draftID == null) {
                laneState.draftID = explicitDraftID
            }
            if (directory != null && laneState.directory == null) laneState.directory = directory
            val snapshot = persistedSnapshot(
                laneState, storage, sessionID = explicitSessionID, draftID = explicitDraftID, directory = directory,
                pending = pending, isHeld = false, backgroundKey = backgroundKey,
            )
            claimWorker(key, storage) to snapshot
        }
        persisted?.record()
        if (start != null) launchRun(start)
        return channel
    }

    /** Reports a send already on its way, for a screen opened after it was made. Null once it has settled or been deleted. */
    fun statuses(messageID: String): ReceiveChannel<Status>? {
        val channel = Channel<Status>(Channel.UNLIMITED)
        val found = locked { storage ->
            val (key, index) = locate(messageID, storage) ?: return@locked false
            val item = storage.lanes[key]?.items?.get(index) ?: return@locked false
            if (item.phase == Phase.held || item.cancelled) return@locked false
            observe(messageID, channel, storage)
            true
        }
        return if (found) channel else null
    }

    /** Waiting to try a send again. Nothing on screen shows it (a retry reads as sent). */
    fun isRetrying(messageID: String): Boolean = locked { storage ->
        val (key, index) = locate(messageID, storage) ?: return@locked false
        storage.lanes[key]?.items?.get(index)?.phase == Phase.retrying
    }

    data class Entry(
        val pending: PendingPrompt,
        /** Waits behind a send that failed or was stopped. */
        val isBlocked: Boolean,
    )

    data class Contents(
        /** Still on their way, in order, without any held ([UndeliveredPrompts] keeps those) or deleted. */
        val unsent: List<Entry> = emptyList(),
        /** Admitted in the last couple of minutes, oldest first. */
        val admitted: List<PendingPrompt> = emptyList(),
    )

    fun contents(serverKey: String, sessionID: String): Contents = contents(serverKey, Lane.Session(sessionID))

    fun contents(serverKey: String, lane: Lane): Contents = locked { storage ->
        val laneState = storage.lanes[resolve(lane, serverKey, storage)] ?: return@locked Contents()
        val unsent = ArrayList<Entry>()
        var blocked = false
        for (item in laneState.items) {
            if (item.phase == Phase.held) {
                blocked = true
                continue
            }
            if (item.cancelled) continue
            unsent += Entry(item.pending, blocked)
        }
        var admitted = emptyList<PendingPrompt>()
        if (lane is Lane.Session) {
            val cutoff = now().minusMillis((ADMITTED_MEMORY_SECONDS * 1000).toLong())
            admitted = laneState.admitted.filter { it.at.isAfter(cutoff) && it.sessionID == lane.id }.map { it.pending }
        }
        Contents(unsent, admitted)
    }

    /**
     * A send this phone made outside the outbox (the voice agent's), once the server admitted it, so
     * a screen that opens on its session shows it like any admitted send. It reads as pending
     * (`wasRunning`) until the server announces its delivery.
     */
    fun recordAdmitted(pending: PendingPrompt, sessionID: String, serverKey: String) {
        locked { storage ->
            val key = resolve(Lane.Session(sessionID), serverKey, storage)
            val lane = storage.lanes[key] ?: LaneState(serverKey = serverKey, newSessionID = newSessionID(now(), pending.messageID))
            val admitted = pending.copy(ownsRun = false, wasRunning = pending.messageID !in storage.delivered)
            lane.admitted = lane.admitted.filter { it.pending.messageID != admitted.messageID }
            lane.appendAdmitted(admitted, sessionID, now())
            storage.lanes[key] = lane
        }
    }

    /** The server delivered a message into its session's history: a screen opening now shows it as sent. */
    fun markDelivered(messageID: String) {
        locked { storage ->
            storage.delivered.remove(messageID)
            storage.delivered += messageID
            if (storage.delivered.size > 64) repeat(storage.delivered.size - 64) { storage.delivered.removeAt(0) }
            for (lane in storage.lanes.values) {
                lane.admitted = lane.admitted.map {
                    if (it.pending.messageID == messageID) it.copy(pending = it.pending.copy(wasRunning = false)) else it
                }
            }
        }
    }

    /** The server dropped an admitted message from its inbox before delivering it. */
    fun forgetAdmitted(messageID: String) {
        locked { storage ->
            for (lane in storage.lanes.values) lane.admitted = lane.admitted.filter { it.pending.messageID != messageID }
        }
    }

    /** A send failed or was stopped in this lane, so later sends wait. */
    fun isHeld(lane: Lane, serverKey: String): Boolean = locked { storage ->
        storage.lanes[resolve(lane, serverKey, storage)]?.items?.any { it.phase == Phase.held } == true
    }

    /**
     * Deletes a send: true if the outbox has it. Waiting or held, it goes now; being retried, its wait
     * ends and it goes; being posted, the server is told to drop it once it's admitted. Sends behind it go on.
     */
    fun withdraw(messageID: String): Boolean {
        var found = false
        var observers: List<Channel<Status>> = emptyList()
        var start: String? = null
        var unused: String? = null
        var persistence: OutboxPersistence? = null
        locked { storage ->
            val (key, index) = locate(messageID, storage) ?: return@locked
            val lane = storage.lanes[key] ?: return@locked
            val item = lane.items[index]
            found = true
            persistence = storage.persistence
            when (item.phase) {
                Phase.waiting, Phase.held -> {
                    lane.items.removeAt(index)
                    observers = storage.observers.remove(messageID)?.values?.toList() ?: emptyList()
                    start = claimWorker(key, storage)
                    if (start == null) prune(key, storage)
                    unused = item.backgroundKey
                }
                Phase.retrying, Phase.posting -> {
                    item.sleeper?.cancel()
                    lane.items[index] = item.copy(cancelled = true)
                }
            }
        }
        if (found) persistence?.settlePrompts(setOf(messageID))
        for (observer in observers) {
            unused?.let { observer.trySend(Status.BackgroundUnused(it)) }
            observer.trySend(Status.Withdrawn)
            observer.close()
        }
        start?.let(::launchRun)
        return found
    }

    /** Changes how a send is delivered: true if the outbox has it. Not yet posted, it goes that way; being posted, the server hears once it's admitted. */
    fun setDelivery(messageID: String, delivery: OpenCodeDelivery, backgroundKey: String? = null): Boolean {
        var updated = false
        var effective: String? = null
        var persistence: OutboxPersistence? = null
        locked { storage ->
            val (key, index) = locate(messageID, storage) ?: return@locked
            val lane = storage.lanes[key] ?: return@locked
            val item = lane.items[index]
            if (item.cancelled) return@locked
            val next = if (delivery == OpenCodeDelivery.Steer) item.backgroundKey ?: backgroundKey else null
            lane.items[index] = item.copy(pending = item.pending.copy(delivery = delivery), backgroundKey = next)
            updated = true
            effective = next
            persistence = storage.persistence
        }
        if (updated) persistence?.updateOutboxDelivery(messageID, delivery, effective)
        return updated
    }

    /**
     * Stop: nothing in the lane is posted until the reader retries it. Sends waiting or being retried
     * are held at once and returned, as they now stand, for the screen to show as failed sends. One
     * already being posted finishes this attempt: admitted, it's delivered; failed, it's held as a
     * failed send without another try.
     */
    fun stop(lane: Lane, serverKey: String): List<PendingPrompt> {
        val stopped = ArrayList<PendingPrompt>()
        val observers = ArrayList<Pair<Channel<Status>, String?>>()
        locked { storage ->
            val state = storage.lanes[resolve(lane, serverKey, storage)] ?: return@locked
            for (index in state.items.indices) {
                var item = state.items[index]
                if (item.cancelled) continue
                item = item.copy(stopped = true)
                when (item.phase) {
                    Phase.waiting, Phase.retrying -> {
                        // A job waiting to retry wakes, sees it held, and stops.
                        item.sleeper?.cancel()
                        val unused = item.backgroundKey
                        item = item.copy(sleeper = null, phase = Phase.held, backgroundKey = null)
                        stopped += item.pending
                        storage.observers.remove(item.pending.messageID)?.values?.forEach { observers += it to unused }
                    }
                    Phase.posting, Phase.held -> Unit
                }
                state.items[index] = item
            }
        }
        for ((observer, unused) in observers) {
            unused?.let { observer.trySend(Status.BackgroundUnused(it)) }
            observer.trySend(Status.Stopped)
            observer.close()
        }
        return stopped
    }

    sealed interface DraftStop {
        /** Its session exists (it may not have reached the screen yet): Stop is that session's Stop. */
        data class Created(val session: OpenCodeSession) : DraftStop
        /** Nothing was created: every send not yet admitted is deleted, and returned for the composer. */
        data class Cancelled(val pendings: List<PendingPrompt>) : DraftStop
    }

    /** A draft's Stop, decided at once against the session's creation. */
    fun stopDraft(lane: Lane, serverKey: String): DraftStop {
        val decision = locked { storage ->
            val key = resolve(lane, serverKey, storage)
            val state = storage.lanes[key] ?: return@locked DraftStop.Cancelled(emptyList())
            val session = state.createdSession
            if (session != null) {
                state.announced = true
                state.draftID = null
                return@locked DraftStop.Created(session)
            }
            DraftStop.Cancelled(state.items.filter { it.phase != Phase.held && !it.cancelled }.map { it.pending })
        }
        if (decision is DraftStop.Cancelled) decision.pendings.forEach { withdraw(it.messageID) }
        return decision
    }

    /** Failed sends the server turns out to have after all no longer hold their lanes. */
    fun settle(messageIDs: Set<String>) {
        if (messageIDs.isEmpty()) return
        val (held, persistence) = locked { storage ->
            storage.lanes.values.flatMap { lane ->
                lane.items.filter { it.phase == Phase.held && it.pending.messageID in messageIDs }.map { it.pending.messageID }
            } to storage.persistence
        }
        persistence?.settlePrompts(messageIDs)
        held.forEach { withdraw(it) }
    }

    // region Lanes

    private fun launchRun(key: String) {
        scope.launch(DependencyValues.current.asContextElement()) { run(key) }
    }

    private suspend fun run(key: String) {
        val endBackgroundWork = backgroundWork()
        try {
            while (true) {
                val (item, lane) = next(key) ?: break
                val id = item.pending.messageID
                val generation = item.generation
                val handle = Handle(
                    createdSessionID = lane.createdSessionID,
                    newSessionID = lane.newSessionID,
                    current = { pending(id) ?: item.pending },
                    begin = { begin(id, generation) },
                    wait = { sleep -> wait(id, generation, sleep) },
                    intent = { intent(id) },
                    report = { status -> report(status, id) },
                    created = { session -> created(session, key) },
                    takeBackgroundKey = { takeBackgroundKey(id, generation) },
                    networkOperation = item.networkOperation,
                )
                val outcome = item.job.deliver(handle)
                finish(id, generation, key, outcome)
            }
        } finally {
            endBackgroundWork()
        }
    }

    private fun finish(id: String, generation: Int, key: String, outcome: DeliveryOutcome) {
        var observers: List<Channel<Status>> = emptyList()
        var status: Status? = null
        var unused: String? = null
        var settledPersistence: OutboxPersistence? = null
        var heldSnapshot: PersistedSnapshot? = null
        locked { storage ->
            val lane = storage.lanes[key] ?: return@locked
            val index = lane.items.indexOfFirst { it.pending.messageID == id }
            if (index < 0 || lane.items[index].generation != generation) return@locked
            val item = lane.items[index]
            // A claim the job never took: a held send claims afresh on Retry.
            lane.items[index] = item.copy(backgroundKey = null)
            val created = outcome.created
            if (lane.isDraft && created != null && lane.createdSession == null) {
                lane.createdSession = created
                lane.createdSessionID = created.id
                storage.aliases[key(Lane.Session(created.id), lane.serverKey)] = key
            }
            // The first screen to hear of a draft's session is told it was created, once.
            var announce: OpenCodeSession? = null
            val createdSession = lane.createdSession
            if (lane.isDraft && !lane.announced && createdSession != null) {
                lane.announced = true
                announce = createdSession
            }
            if (lane.createdSessionID != null || lane.announced) lane.draftID = null
            when (outcome) {
                is DeliveryOutcome.Sent -> {
                    lane.items.removeAt(index)
                    var pending = item.pending
                    // Its delivery can arrive over the event stream before the post's answer.
                    if (pending.messageID in storage.delivered) pending = pending.copy(wasRunning = false)
                    lane.appendAdmitted(pending, outcome.sessionID, now())
                    status = Status.Settled(
                        DeliveryOutcome.Sent(outcome.sessionID, announce, outcome.appliedModel, outcome.appliedAgent),
                        item.pending,
                    )
                    settledPersistence = storage.persistence
                }
                is DeliveryOutcome.Failed -> {
                    if (item.cancelled) {
                        lane.items.removeAt(index)
                        status = Status.Withdrawn
                        settledPersistence = storage.persistence
                    } else {
                        lane.items[index] = lane.items[index].copy(phase = Phase.held, sleeper = null)
                        status = Status.Settled(DeliveryOutcome.Failed(outcome.sessionID, announce, outcome.error), item.pending)
                        heldSnapshot = persistedSnapshot(
                            lane, storage, sessionID = outcome.sessionID, pending = item.pending, isHeld = true, backgroundKey = null,
                        )
                    }
                }
                is DeliveryOutcome.Withdrawn -> {
                    lane.items.removeAt(index)
                    status = Status.Withdrawn
                    settledPersistence = storage.persistence
                }
            }
            observers = storage.observers.remove(id)?.values?.toList() ?: emptyList()
            unused = item.backgroundKey
        }
        settledPersistence?.settlePrompts(setOf(id))
        heldSnapshot?.record()
        val settled = status ?: return
        for (observer in observers) {
            unused?.let { observer.trySend(Status.BackgroundUnused(it)) }
            observer.trySend(settled)
            observer.close()
        }
    }

    /** A draft's session exists as soon as it's created, before its first send is admitted. */
    private fun created(session: OpenCodeSession, key: String) {
        var note: Triple<OutboxPersistence, String, String?>? = null
        locked { storage ->
            val lane = storage.lanes[key] ?: return@locked
            if (!lane.isDraft || lane.createdSession != null) return@locked
            val draftID = lane.draftID
            lane.createdSession = session
            lane.createdSessionID = session.id
            lane.draftID = null
            storage.aliases[key(Lane.Session(session.id), lane.serverKey)] = key
            val persistence = storage.persistence ?: return@locked
            note = Triple(persistence, lane.serverKey, draftID)
        }
        note?.let { (persistence, serverKey, draftID) -> persistence.noteDraftSessionCreated(serverKey, draftID, session) }
    }

    /** The lane's first send, if it's waiting; otherwise the worker stops. */
    private fun next(key: String): Pair<Item, LaneState>? = locked { storage ->
        val lane = storage.lanes[key]
        val head = lane?.items?.firstOrNull()
        if (lane == null || head == null || head.phase != Phase.waiting) {
            lane?.isWorking = false
            prune(key, storage)
            return@locked null
        }
        lane.items[0] = head.copy(phase = Phase.posting)
        head to lane.copy(items = lane.items.toMutableList())
    }

    private fun pending(messageID: String): PendingPrompt? = locked { storage ->
        val (key, index) = locate(messageID, storage) ?: return@locked null
        storage.lanes[key]?.items?.get(index)?.pending
    }

    private fun begin(messageID: String, generation: Int): Attempt = locked { storage ->
        val (key, index) = locate(messageID, storage) ?: return@locked Attempt.Cancelled
        val lane = storage.lanes[key] ?: return@locked Attempt.Cancelled
        val item = lane.items[index]
        if (item.generation != generation) return@locked Attempt.Superseded
        if (item.cancelled) return@locked Attempt.Cancelled
        if (item.stopped || item.phase == Phase.held) return@locked Attempt.Stopped
        lane.items[index] = item.copy(phase = Phase.posting, postedDelivery = item.pending.delivery, sleeper = null)
        Attempt.Post(item.pending)
    }

    private fun intent(messageID: String): Intent = locked { storage ->
        val (key, index) = locate(messageID, storage) ?: return@locked Intent(cancelled = true)
        val item = storage.lanes[key]?.items?.get(index) ?: return@locked Intent(cancelled = true)
        val changed = item.postedDelivery?.let { if (it == item.pending.delivery) null else item.pending.delivery }
        Intent(cancelled = item.cancelled, stopped = item.stopped, changedDelivery = changed, postedDelivery = item.postedDelivery)
    }

    private fun takeBackgroundKey(messageID: String, generation: Int): String? = locked { storage ->
        val (key, index) = locate(messageID, storage) ?: return@locked null
        val lane = storage.lanes[key] ?: return@locked null
        val item = lane.items[index]
        if (item.generation != generation || item.cancelled || item.stopped || item.pending.delivery != OpenCodeDelivery.Steer) {
            return@locked null
        }
        val backgroundKey = item.backgroundKey ?: return@locked null
        lane.items[index] = item.copy(backgroundKey = null)
        backgroundKey
    }

    /** Marks the send as being retried and waits, unless Delete or Stop came first or come meanwhile. */
    private suspend fun wait(messageID: String, generation: Int, sleep: suspend () -> Boolean): Boolean {
        val context = DependencyValues.current.asContextElement()
        val sleeper = locked { storage ->
            val (key, index) = locate(messageID, storage) ?: return@locked null
            val lane = storage.lanes[key] ?: return@locked null
            val item = lane.items[index]
            if (item.generation != generation || item.cancelled || item.stopped || item.phase == Phase.held) return@locked null
            val sleeper = scope.async(context) { sleep() }
            lane.items[index] = item.copy(phase = Phase.retrying, sleeper = sleeper)
            sleeper
        } ?: return false
        return try {
            sleeper.await() && !sleeper.isCancelled
        } catch (_: CancellationException) {
            // Delete or Stop cut the wait short; this job itself being cancelled propagates.
            currentCoroutineContext().ensureActive()
            false
        }
    }

    private fun report(status: Status, messageID: String) {
        val observers = locked { storage ->
            // A failed PATCH: the send is kept (and remembered once admitted) as the server has it,
            // with the delivery it was posted with.
            if (status is Status.UpdateFailed && status.restoring != null) {
                locate(messageID, storage)?.let { (key, index) ->
                    val lane = storage.lanes.getValue(key)
                    lane.items[index] = lane.items[index].let { it.copy(pending = it.pending.copy(delivery = status.restoring)) }
                }
            }
            storage.observers[messageID]?.values?.toList() ?: emptyList()
        }
        for (observer in observers) observer.trySend(status)
    }

    private fun observe(messageID: String, channel: Channel<Status>, storage: Storage) {
        val token = UUID.randomUUID()
        storage.observers.getOrPut(messageID) { LinkedHashMap() }[token] = channel
        // Runs when the follower stops listening (or the outbox finishes it).
        channel.invokeOnClose {
            locked { storage ->
                val observers = storage.observers[messageID] ?: return@locked
                observers.remove(token)
                if (observers.isEmpty()) storage.observers.remove(messageID)
            }
        }
    }

    // endregion

    companion object Key : DependencyKey<PromptOutbox> {
        /** How long a delivered send is remembered for a screen opened just after, in seconds. */
        const val ADMITTED_MEMORY_SECONDS: Double = 120.0

        override val liveValue: PromptOutbox by lazy {
            PromptOutbox(backgroundWork = { dependency(BackgroundTaskClient).begin("Send messages") })
        }
        override val testValue: PromptOutbox get() = PromptOutbox()

        /** Marks the lane as worked on if its first send is ready and no worker runs; the caller then starts one. */
        private fun claimWorker(key: String, storage: Storage): String? {
            val lane = storage.lanes[key] ?: return null
            if (lane.isWorking || lane.items.firstOrNull()?.phase != Phase.waiting) return null
            lane.isWorking = true
            return key
        }

        /**
         * An empty session lane goes once it has nothing recent to remember. A draft's lane stays, so
         * a send the screen makes before it hears of its session still goes there.
         */
        private fun prune(key: String, storage: Storage) {
            val lane = storage.lanes[key] ?: return
            if (lane.items.isEmpty() && !lane.isWorking && !lane.isDraft && lane.admitted.isEmpty()) storage.lanes.remove(key)
        }

        private fun locate(messageID: String, storage: Storage): Pair<String, Int>? {
            for ((key, lane) in storage.lanes) {
                val index = lane.items.indexOfFirst { it.pending.messageID == messageID }
                if (index >= 0) return key to index
            }
            return null
        }

        private fun resolve(lane: Lane, serverKey: String, storage: Storage): String {
            val key = key(lane, serverKey)
            return storage.aliases[key] ?: key
        }

        private fun key(lane: Lane, serverKey: String): String = when (lane) {
            is Lane.Session -> "$serverKey|session|${lane.id}"
            is Lane.Draft -> "$serverKey|draft|${lane.id}"
        }

        private fun persistedSnapshot(
            state: LaneState?,
            storage: Storage,
            sessionID: String?,
            draftID: String? = null,
            directory: String? = null,
            pending: PendingPrompt,
            isHeld: Boolean,
            backgroundKey: String?,
        ): PersistedSnapshot? {
            val persistence = storage.persistence ?: return null
            if (state == null) return null
            val resolvedSessionID = sessionID ?: state.createdSessionID
            return PersistedSnapshot(
                persistence = persistence,
                serverID = state.serverKey,
                sessionID = resolvedSessionID,
                draftID = if (resolvedSessionID == null && !state.announced) draftID ?: state.draftID else null,
                directory = directory ?: state.directory,
                newSessionID = state.newSessionID,
                createdSession = state.createdSession,
                pending = pending,
                isHeld = isHeld,
                backgroundKey = backgroundKey,
            )
        }

        /**
         * A session ID in the server's own shape (`ses_` + a descending time + randomness), so sessions
         * made here sort among the rest (iOS `ShareSendRequest.newSessionID`). The randomness comes
         * from the random tail of the lane's first message ID, so a create retried after a lost
         * response finds the session it already made instead of making a second.
         */
        fun newSessionID(at: Instant = Instant.now(), entropy: String = UUID.randomUUID().toString().uppercase()): String {
            val time = ServerID.timePart(ServerID.milliseconds(at.toEpochMilli()), counter = 1, descending = true)
            val random = entropy.split('_').last()
            val tail = random.filter { it.isLetterOrDigit() }.takeLast(ServerID.RANDOM_LENGTH)
            return "ses_" + time + tail.padEnd(ServerID.RANDOM_LENGTH, '0')
        }
    }
}

/** The session a delivery created, whichever way it ended. */
private val DeliveryOutcome.created: OpenCodeSession?
    get() = when (this) {
        is DeliveryOutcome.Sent -> created
        is DeliveryOutcome.Failed -> created
        is DeliveryOutcome.Withdrawn -> created
    }
