package ai.opencode.server

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What [EventBatcher] calls as events pass through, installed by the features that own them
 * (`DependencyValues.installRoot { this[EventBatcherHooks] = … }`):
 *
 * - [prepareMarkdown] projects a text part's Markdown off the reducer's path (iOS
 *   `MarkdownStreamClient.prepare`). For a streaming part, [text] is the accumulated text so
 *   far and the transcript applies `holdingPendingMarker` itself; for an ended part it's the
 *   final text.
 * - [record] sees every event once per upstream connection (iOS `AgentActivityLedger.record`).
 */
class EventBatcherHooks(
    val prepareMarkdown: (partID: String, text: String, isStreaming: Boolean) -> Unit = { _, _, _ -> },
    val record: (OpenCodeServerEvent) -> Unit = {},
) {
    companion object Key : DependencyKey<EventBatcherHooks> {
        override val liveValue: EventBatcherHooks = EventBatcherHooks()
        override val testValue: EventBatcherHooks = EventBatcherHooks()
    }
}

/** What happened to one event [EventBatcher] handed downstream (iOS `AsyncStream.YieldResult`). */
sealed interface EventEmission {
    /** Queued, with [remaining] free slots left in the buffer. */
    data class Enqueued(val remaining: Int) : EventEmission

    /** The buffer was full. The downstream has already ended with [EventStreamOverflow]. */
    data object Dropped : EventEmission

    /** The downstream is closed. */
    data object Terminated : EventEmission
}

/**
 * Mirrors the official clients' small event flush window while keeping high-frequency
 * event handling out of reducers: adjacent deltas for one part coalesce, a flush happens
 * [flushInterval] (75 ms) after the first pending delta, and a slow subscriber receives at
 * most 256 semantic mutations in one batch. Any non-content event first flushes the deltas
 * it must follow (its session's, or all), so ordering is preserved.
 *
 * Under pressure (half of the [capacity]-slot buffer waiting on a slow main thread) a session off
 * screen ([isFocused] false) loses its streaming deltas rather than overflowing the stream for
 * everyone (390f157d).
 *
 * A plain object, not a reducer. [emit] delivers downstream and reports what happened; anything
 * but [EventEmission.Enqueued] terminates the batcher.
 */
class EventBatcher(
    private val scope: CoroutineScope,
    private val emit: (OpenCodeServerEvent) -> EventEmission,
    private val hooks: EventBatcherHooks = EventBatcherHooks(),
    private val clock: Clock,
    private val flushInterval: Duration = 75.milliseconds,
    private val capacity: Int = BUFFER_CAPACITY,
    private val isFocused: (String) -> Boolean = { true },
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, MutableList<OpenCodeLiveContentEvent>>()
    private var flushJob: Job? = null
    private var isTerminated = false

    /** This connection's accumulated text per part, as the reducer accumulates it. */
    private val liveText = HashMap<String, String>()

    /** The buffer's free slots after the last emission, and when that was measured. */
    private var remaining = capacity
    private var remainingMeasuredAt = clock.now()
    private var shedDeltas = 0
    private var shedBatches = 0

    /**
     * Half the buffer is waiting on a slow main thread. A reading older than a second is stale:
     * nothing was emitted since, so the reducer has caught up.
     */
    private val isUnderPressure: Boolean
        get() = remaining <= capacity / 2 && clock.now() - remainingMeasuredAt < 1.seconds

    /**
     * Under pressure, a session off screen loses its streaming deltas: its rows aren't drawn, and
     * their ends (`TextEnded`, `ToolInputEnded`) and every other event still arrive, so nothing
     * durable goes. Emitting them would fill the buffer and drop the connection for everyone (a
     * reconnect gap, and a backoff of up to 15 s).
     */
    private fun shedding(events: List<OpenCodeLiveContentEvent>, sessionID: String): List<OpenCodeLiveContentEvent> {
        if (!isUnderPressure || isFocused(sessionID)) return events
        val kept = ArrayList<OpenCodeLiveContentEvent>(events.size)
        for (event in events) {
            when (event) {
                is OpenCodeLiveContentEvent.TextDelta -> liveText.remove("${event.messageID}-text-${event.ordinal}")
                is OpenCodeLiveContentEvent.ReasoningDelta, is OpenCodeLiveContentEvent.ToolInputDelta -> Unit
                else -> kept.add(event)
            }
        }
        shedDeltas += events.size - kept.size
        if (kept.isEmpty()) shedBatches += 1
        return kept
    }

    /** Mirrors the session reducer's text handling. */
    private fun prepareMarkdown(events: List<OpenCodeLiveContentEvent>) {
        for (event in events) {
            when (event) {
                is OpenCodeLiveContentEvent.TextStarted -> {
                    val id = "${event.messageID}-text-${event.ordinal}"
                    liveText[id] = liveText[id] ?: ""
                }
                is OpenCodeLiveContentEvent.TextDelta -> {
                    val id = "${event.messageID}-text-${event.ordinal}"
                    val text = (liveText[id] ?: "") + event.delta
                    liveText[id] = text
                    hooks.prepareMarkdown(id, text, true)
                }
                is OpenCodeLiveContentEvent.TextEnded -> {
                    val id = "${event.messageID}-text-${event.ordinal}"
                    liveText.remove(id)
                    hooks.prepareMarkdown(id, event.text, false)
                }
                else -> Unit
            }
        }
        // A part that never ends (a dropped run) mustn't grow without bound.
        if (liveText.size > 32) liveText.keys.minOrNull()?.let(liveText::remove)
    }

    fun receive(event: OpenCodeServerEvent) {
        synchronized(lock) {
            if (isTerminated) return
            hooks.record(event)
            if (event !is OpenCodeServerEvent.ContentBatch) {
                val target = event.targetSessionID
                if (target != null) flush(target) else flushLocked()
                emitLocked(event)
                return
            }
            pending.getOrPut(event.sessionID) { mutableListOf() }.appendCoalescingDeltas(event.events)
            if ((pending[event.sessionID]?.size ?: 0) >= BATCH_LIMIT) {
                flush(event.sessionID)
                return
            }
            if (pending.values.sumOf { it.size } >= BATCH_LIMIT) {
                flushLocked()
                return
            }
            if (flushJob != null) return
            flushJob = scope.launch {
                clock.sleep(flushInterval)
                if (!isActive) return@launch
                flush()
            }
        }
    }

    private fun flush(sessionID: String) {
        val events = pending.remove(sessionID)
        if (events.isNullOrEmpty()) return
        if (pending.isEmpty()) {
            flushJob?.cancel()
            flushJob = null
        }
        emitBatch(sessionID, events)
    }

    /** Delivers every pending batch now. */
    fun flush() = synchronized(lock) { flushLocked() }

    private fun flushLocked() {
        flushJob?.cancel()
        flushJob = null
        if (pending.isEmpty()) return
        val batches = pending.toList()
        pending.clear()
        for ((sessionID, events) in batches) {
            if (events.isEmpty()) continue
            if (isTerminated) return
            emitBatch(sessionID, events)
        }
    }

    private fun emitBatch(sessionID: String, events: List<OpenCodeLiveContentEvent>) {
        val kept = shedding(events, sessionID)
        if (kept.isEmpty()) return
        prepareMarkdown(kept)
        emitLocked(OpenCodeServerEvent.ContentBatch(sessionID, kept))
    }

    private fun emitLocked(event: OpenCodeServerEvent) {
        if (isTerminated) return
        when (val result = emit(event)) {
            is EventEmission.Enqueued -> {
                remaining = result.remaining
                remainingMeasuredAt = clock.now()
                if (shedDeltas > 0 && !isUnderPressure) {
                    AppLog.stream.notice { "Event buffer pressure eased: shedDeltas=$shedDeltas shedBatches=$shedBatches" }
                    shedDeltas = 0
                    shedBatches = 0
                }
            }
            EventEmission.Dropped -> {
                AppLog.stream.notice { "Event buffer overflow: dropped=1 shedDeltas=$shedDeltas shedBatches=$shedBatches" }
                terminate()
            }
            EventEmission.Terminated -> terminate()
        }
    }

    private fun terminate() {
        isTerminated = true
        flushJob?.cancel()
        flushJob = null
        pending.clear()
        liveText.clear()
    }

    companion object {
        /** The event stream's buffer size (iOS `EventBatcher.bufferCapacity`). */
        const val BUFFER_CAPACITY = 128
        private const val BATCH_LIMIT = 256
    }
}
