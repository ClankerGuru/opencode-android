package ai.opencode.server

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

/**
 * Mirrors the official clients' small event flush window while keeping high-frequency
 * event handling out of reducers: adjacent deltas for one part coalesce, a flush happens
 * [flushInterval] (75 ms) after the first pending delta, and a slow subscriber receives at
 * most 256 semantic mutations in one batch. Any non-content event first flushes the deltas
 * it must follow (its session's, or all), so ordering is preserved.
 *
 * A plain object, not a reducer. [emit] delivers downstream and returns false once the
 * downstream can't take more (overflow or closed), which terminates the batcher.
 */
class EventBatcher(
    private val scope: CoroutineScope,
    private val emit: (OpenCodeServerEvent) -> Boolean,
    private val hooks: EventBatcherHooks = EventBatcherHooks(),
    private val clock: Clock,
    private val flushInterval: Duration = 75.milliseconds,
) {
    private val lock = Any()
    private val pending = LinkedHashMap<String, MutableList<OpenCodeLiveContentEvent>>()
    private var flushJob: Job? = null
    private var isTerminated = false

    /** This connection's accumulated text per part, as the reducer accumulates it. */
    private val liveText = HashMap<String, String>()

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
        prepareMarkdown(events)
        emitLocked(OpenCodeServerEvent.ContentBatch(sessionID, events))
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
            prepareMarkdown(events)
            emitLocked(OpenCodeServerEvent.ContentBatch(sessionID, events))
        }
    }

    private fun emitLocked(event: OpenCodeServerEvent) {
        if (isTerminated) return
        if (!emit(event)) {
            isTerminated = true
            flushJob?.cancel()
            flushJob = null
            pending.clear()
            liveText.clear()
        }
    }

    private companion object {
        const val BATCH_LIMIT = 256
    }
}
