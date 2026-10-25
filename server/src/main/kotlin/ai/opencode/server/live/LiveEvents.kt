package ai.opencode.server.live

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.DiagnosticsErrorCategory
import ai.opencode.core.diagnostics.DiagnosticsFailurePhase
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.DiagnosticsStreamName
import ai.opencode.core.diagnostics.DiagnosticsStreamProgress
import ai.opencode.core.log.AppLog
import ai.opencode.server.EventBatcher
import ai.opencode.server.EventBatcherHooks
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.internal.ElementSerializer
import ai.opencode.server.internal.WireJson
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.opt
import ai.opencode.server.internal.str
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeProjectIcon
import ai.opencode.server.model.utf8Length
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.ServerContact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.Call
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
private const val EVENT_BUFFER = 128

/**
 * Long-lived SSE subscription to `/api/event`. The flow throws on disconnect (an
 * [OpenCodeClientError], or [EventStreamOverflow] when the collector fell 128 events behind);
 * the caller owns the reconnect-and-rehydrate loop.
 */
internal fun subscribeEvents(server: OpenCodeServer): Flow<OpenCodeServerEvent> = callbackFlow {
    val batches = EventBatcher(
        scope = this,
        emit = { event ->
            val result = trySend(event)
            when {
                result.isSuccess -> true
                result.isClosed -> false
                else -> {
                    // A missing delta makes the rest of this connection incomplete: finish so the
                    // subscriber reconnects and hydrates from REST rather than hiding a gap.
                    AppLog.stream.notice { "Event buffer overflow: dropped=1" }
                    close(EventStreamOverflow())
                    false
                }
            }
        },
        hooks = dependency(EventBatcherHooks),
        clock = dependency(Clock),
    )
    val activeCall = AtomicReference<Call?>(null)
    val operationID = NetworkRetry.streamOperationID.current() ?: UUID.randomUUID().toString().uppercase()
    val stream = StreamDiagnostics(operationID)
    launch(Dispatchers.IO) {
        try {
            val url = server.endpoint("/api/event") ?: throw OpenCodeClientError("Enter a valid HTTP or HTTPS server URL.")
            val builder = Request.Builder().url(url).header("Accept", "text/event-stream")
            server.authorize(builder)
            builder.header("X-OpenCode-Op", operationID).header("X-OpenCode-Req", UUID.randomUUID().toString().uppercase())
            val call = dependency(OpenCodeTransport).eventStream.newCall(builder.build())
            activeCall.set(call)
            stream.record(DiagnosticsStreamName.ConnectStart)
            call.execute().use { response ->
                if (response.code == 401) {
                    stream.record(DiagnosticsStreamName.Disconnected, 401, DiagnosticsErrorCategory.Unauthorized, DiagnosticsFailurePhase.HttpStatus)
                    throw OpenCodeClientError("The server rejected these credentials.", statusCode = 401)
                }
                if (response.code !in 200..299) {
                    stream.record(DiagnosticsStreamName.Disconnected, response.code, DiagnosticsErrorCategory.of(response.code), DiagnosticsFailurePhase.HttpStatus)
                    throw OpenCodeClientError("The event stream returned HTTP ${response.code}.", statusCode = response.code)
                }
                stream.connected(response.code)
                ServerContact.shared.heard(server.persistenceID)
                // The server frames one `data: {json}` line per event (no event:/id: fields, no
                // multi-line data) plus `: heartbeat` comment lines.
                val source = response.body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    stream.line(line.length)
                    if (!line.startsWith("data:")) continue
                    if (line.length - 5 > MAX_FRAME_BYTES ||
                        (line.length * 3 - 5 > MAX_FRAME_BYTES && utf8Length(line) - 5 > MAX_FRAME_BYTES)
                    ) {
                        throw OpenCodeClientError("The event stream sent an oversized frame.")
                    }
                    // JSON tolerates the whitespace after `data:`.
                    val event = decodeStreamFrame(line.substring(5)) ?: continue
                    batches.receive(event)
                }
            }
            batches.flush()
            stream.record(DiagnosticsStreamName.Disconnected)
            close(OpenCodeClientError("The event stream ended."))
        } catch (cancelled: CancellationException) {
            stream.record(DiagnosticsStreamName.Disconnected, errorCategory = DiagnosticsErrorCategory.Cancelled, failurePhase = DiagnosticsFailurePhase.Cancelled)
            throw cancelled
        } catch (error: OpenCodeClientError) {
            batches.flush()
            if (error.statusCode == null) stream.record(DiagnosticsStreamName.Disconnected, errorCategory = DiagnosticsErrorCategory.Transport)
            close(error)
        } catch (error: Exception) {
            batches.flush()
            stream.record(DiagnosticsStreamName.Disconnected, errorCategory = DiagnosticsErrorCategory.of(error), failurePhase = DiagnosticsFailurePhase.BodyRead)
            close(streamTransportError(error))
        }
    }
    awaitClose { activeCall.get()?.cancel() }
}.buffer(EVENT_BUFFER)

/** The event stream's diagnostics marks (iOS `DiagnosticsRecorder.stream`): line counts and timings only. */
private class StreamDiagnostics(private val operationID: String) {
    private val recorder = DiagnosticsRecorder.shared
    private val requestID = UUID.randomUUID().toString().uppercase()
    private val progress = DiagnosticsStreamProgress(startedAt = recorder.now())
    private var disconnected = false

    fun record(
        name: DiagnosticsStreamName,
        statusCode: Int? = null,
        errorCategory: DiagnosticsErrorCategory? = null,
        failurePhase: DiagnosticsFailurePhase? = null,
    ) {
        if (name == DiagnosticsStreamName.Disconnected) {
            if (disconnected) return
            disconnected = true
        }
        recorder.stream(name, requestID, operationID, progress, statusCode, errorCategory, failurePhase)
    }

    fun connected(statusCode: Int) {
        progress.connectedAt = recorder.now()
        record(DiagnosticsStreamName.Connected, statusCode)
    }

    fun line(byteCount: Int) {
        if (!recorder.isEnabled) return
        if (progress.receiveLine(byteCount, recorder.now())) record(DiagnosticsStreamName.Resumed)
    }
}

private fun streamTransportError(error: Exception): OpenCodeClientError =
    transportError(error, ai.opencode.server.retry.TransportFailure.of(error))

/** The fields events carry; any type mismatch drops `data` for the frame, as iOS's `try?` does. */
@Serializable
internal data class EventPayload(
    val sessionID: String? = null,
    val parentID: String? = null,
    /** `session.viewed`'s idle watermark, in epoch milliseconds. */
    val idle: Double? = null,
    val inboxID: String? = null,
    val title: String? = null,
    val form: Form? = null,
    val assistantMessageID: String? = null,
    val ordinal: Int? = null,
    val delta: String? = null,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val canonical: String? = null,
    val repository: String? = null,
    val icon: OpenCodeProjectIcon? = null,
    val input: OpenCodeJSONValue? = null,
    val metadata: Map<String, OpenCodeJSONValue>? = null,
    val content: List<Content>? = null,
    val error: ErrorPayload? = null,
    val info: ShellInfo? = null,
    /** `session.revert.staged`'s boundary. */
    val revert: RevertPayload? = null,
    /** `session.inbox.enqueued`'s admitted item; one the app can't read leaves the rest intact. */
    @Serializable(with = LenientInboxItemSerializer::class) val item: InboxItem? = null,
) {
    @Serializable data class Form(val sessionID: String)
    @Serializable data class Content(val type: String, val text: String? = null)
    @Serializable data class InboxItem(val type: String, val payload: PendingInputsPayload.Payload? = null, val delivery: String? = null)

    val textOutput: List<String> get() = content?.filter { it.type == "text" }?.mapNotNull { it.text } ?: emptyList()
}

internal object LenientInboxItemSerializer : ElementSerializer<EventPayload.InboxItem?>(
    decode = { element -> runCatching { WireJson.decodeFromJsonElement(EventPayload.InboxItem.serializer(), element) }.getOrNull() },
    encode = { item -> item?.let { WireJson.encodeToJsonElement(EventPayload.InboxItem.serializer(), it) } ?: JsonNull },
)

private val HANDLED_TYPES = setOf(
    "server.connected",
    "session.execution.started",
    "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted",
    "session.created", "session.forked", "session.viewed",
    "session.revert.staged", "session.revert.cleared", "session.revert.committed",
    "session.renamed", "session.deleted",
    "session.inbox.cancelled", "session.inbox.enqueued", "session.inbox.delivery.changed", "session.inbox.delivered",
    "session.step.started", "session.step.streamed", "session.step.ended", "session.step.failed",
    "session.text.started", "session.text.delta", "session.text.ended",
    "session.reasoning.started", "session.reasoning.delta", "session.reasoning.ended",
    "session.tool.input.started", "session.tool.input.delta", "session.tool.input.ended",
    "session.tool.called", "session.tool.progress", "session.tool.success", "session.tool.failed",
    "session.message.content.updated", "session.synthetic", "session.instructions.updated",
    "session.agent.selected", "session.model.selected", "session.moved", "session.permissions",
    "session.retry.scheduled", "session.compaction.started",
    "session.compaction.ended", "session.compaction.failed", "session.shell.started", "session.shell.ended",
    "permission.asked", "form.created", "permission.replied", "form.replied", "form.cancelled",
    "shell.created", "shell.exited", "shell.deleted",
    "project.updated",
)

/**
 * One SSE `data:` payload: throws for a server failure frame (`_tag`), null for a frame the
 * app ignores. Unhandled event types skip decoding `data`.
 */
internal fun decodeStreamFrame(text: String): OpenCodeServerEvent? {
    val envelope = try { WireJson.parseToJsonElement(text).obj() } catch (_: Exception) { return null }
    val type: String?
    val tag: String?
    try {
        type = envelope.opt("type") { it.str() }
        tag = envelope.opt("_tag") { it.str() }
    } catch (_: RuntimeException) {
        return null
    }
    if (tag != null) throw OpenCodeClientError("The event stream reported a server failure.")
    type ?: return null
    if (type !in HANDLED_TYPES) return null
    val data: EventPayload? = envelope["data"]?.takeUnless { it is JsonNull }?.let(::decodePayload)
    return serverEvent(type, data)
}

private fun decodePayload(element: JsonElement): EventPayload? =
    try { WireJson.decodeFromJsonElement(EventPayload.serializer(), element) } catch (_: Exception) { null }

private fun serverEvent(type: String, data: EventPayload?): OpenCodeServerEvent? {
    fun content(make: (String) -> OpenCodeLiveContentEvent): OpenCodeServerEvent? {
        val sessionID = data?.sessionID ?: return null
        val messageID = data.assistantMessageID ?: return null
        return OpenCodeServerEvent.ContentBatch(sessionID, listOf(make(messageID)))
    }
    fun ordinalContent(make: (String, Int) -> OpenCodeLiveContentEvent): OpenCodeServerEvent? {
        val ordinal = data?.ordinal ?: return null
        return content { make(it, ordinal) }
    }
    fun toolContent(make: (String, String, EventPayload) -> OpenCodeLiveContentEvent): OpenCodeServerEvent? {
        val payload = data ?: return null
        val toolID = payload.id ?: return null
        return content { make(it, toolID, payload) }
    }
    return when (type) {
        "server.connected" -> OpenCodeServerEvent.Connected
        "session.execution.started" -> data?.sessionID?.let(OpenCodeServerEvent::ExecutionStarted)
        "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" ->
            data?.sessionID?.let(OpenCodeServerEvent::ExecutionEnded)
        "session.created" -> data?.sessionID?.let { OpenCodeServerEvent.SessionCreated(it, data.parentID) }
        // A fork is a new root: its `parentID` names the session it was copied from, not a parent.
        "session.forked" -> data?.sessionID?.let { OpenCodeServerEvent.SessionCreated(it, null) }
        "session.viewed" -> {
            val id = data?.sessionID ?: return null
            val idle = data.idle ?: return null
            OpenCodeServerEvent.SessionViewChanged(id, idle.toLong())
        }
        "session.revert.staged" -> {
            val id = data?.sessionID ?: return null
            val boundary = data.revert?.messageID ?: return null
            OpenCodeServerEvent.RevertChanged(id, boundary, committed = false)
        }
        "session.revert.cleared", "session.revert.committed" ->
            data?.sessionID?.let { OpenCodeServerEvent.RevertChanged(it, null, committed = type == "session.revert.committed") }
        "session.renamed" -> {
            val id = data?.sessionID ?: return null
            val title = data.title ?: return null
            OpenCodeServerEvent.SessionRenamed(id, title)
        }
        "session.deleted" -> data?.sessionID?.let(OpenCodeServerEvent::SessionDeleted)
        "project.updated" -> {
            val id = data?.id ?: return null
            val canonical = data.canonical ?: return null
            OpenCodeServerEvent.ProjectUpdated(ProjectPayload(id, canonical, data.name, data.repository, data.icon).project())
        }
        "session.inbox.cancelled" -> {
            val id = data?.sessionID ?: return null
            val inputID = data.inboxID ?: return null
            OpenCodeServerEvent.InputCancelled(id, inputID)
        }
        "session.inbox.enqueued", "session.inbox.delivery.changed" -> {
            val id = data?.sessionID ?: return null
            // The admitted message rides on the event, so it can show before an inbox read.
            val input = if (type == "session.inbox.enqueued") {
                val inboxID = data.inboxID
                val item = data.item
                if (inboxID != null && item != null) {
                    pendingInputRows(
                        PendingInputsPayload(listOf(PendingInputsPayload.Item(inboxID, item.type, item.payload, item.delivery))),
                    ).firstOrNull()
                } else null
            } else null
            OpenCodeServerEvent.InputEnqueued(id, input)
        }
        "session.inbox.delivered" -> data?.sessionID?.let { OpenCodeServerEvent.InputQueueChanged(it, data.inboxID) }
        "session.step.started" -> content(OpenCodeLiveContentEvent::StepStarted)
        "session.step.streamed" -> content(OpenCodeLiveContentEvent::StepStreamed)
        "session.step.ended", "session.step.failed" -> content(OpenCodeLiveContentEvent::StepEnded)
        "session.text.started" -> ordinalContent(OpenCodeLiveContentEvent::TextStarted)
        "session.text.delta" -> {
            val delta = data?.delta ?: return null
            ordinalContent { id, ordinal -> OpenCodeLiveContentEvent.TextDelta(id, ordinal, delta) }
        }
        "session.text.ended" -> {
            val text = data?.text ?: return null
            ordinalContent { id, ordinal -> OpenCodeLiveContentEvent.TextEnded(id, ordinal, text) }
        }
        "session.reasoning.started" -> ordinalContent(OpenCodeLiveContentEvent::ReasoningStarted)
        "session.reasoning.delta" -> {
            val delta = data?.delta ?: return null
            ordinalContent { id, ordinal -> OpenCodeLiveContentEvent.ReasoningDelta(id, ordinal, delta) }
        }
        "session.reasoning.ended" -> {
            val text = data?.text ?: return null
            ordinalContent { id, ordinal -> OpenCodeLiveContentEvent.ReasoningEnded(id, ordinal, text) }
        }
        "session.tool.input.started" -> {
            val name = data?.name ?: return null
            toolContent { messageID, toolID, _ -> OpenCodeLiveContentEvent.ToolInputStarted(messageID, toolID, name) }
        }
        "session.tool.input.delta" ->
            toolContent { messageID, toolID, payload -> OpenCodeLiveContentEvent.ToolInputDelta(messageID, toolID, payload.delta ?: "") }
        "session.tool.input.ended" ->
            toolContent { messageID, toolID, payload -> OpenCodeLiveContentEvent.ToolInputEnded(messageID, toolID, payload.text ?: "") }
        "session.tool.called" -> toolContent { messageID, toolID, payload ->
            OpenCodeLiveContentEvent.ToolCalled(messageID, toolID, payload.input ?: OpenCodeJSONValue.Object(emptyMap()))
        }
        "session.tool.progress" -> toolContent { messageID, toolID, payload ->
            OpenCodeLiveContentEvent.ToolProgress(messageID, toolID, payload.metadata ?: emptyMap())
        }
        "session.tool.success" -> toolContent { messageID, toolID, payload ->
            OpenCodeLiveContentEvent.ToolSucceeded(messageID, toolID, payload.textOutput, payload.metadata ?: emptyMap())
        }
        "session.tool.failed" -> toolContent { messageID, toolID, payload ->
            OpenCodeLiveContentEvent.ToolFailed(
                messageID, toolID, payload.error?.message ?: "Tool failed", payload.error?.type,
                payload.textOutput, payload.metadata ?: emptyMap(),
            )
        }
        "session.message.content.updated", "session.synthetic", "session.instructions.updated",
        "session.agent.selected", "session.model.selected", "session.moved", "session.permissions",
        "session.retry.scheduled", "session.compaction.started",
        "session.compaction.ended", "session.compaction.failed", "session.shell.started", "session.shell.ended",
        -> data?.sessionID?.let(OpenCodeServerEvent::MessageInvalidated)
        "permission.asked", "form.created" -> {
            val id = data?.sessionID ?: data?.form?.sessionID ?: return null
            if (id == "global") null else OpenCodeServerEvent.AttentionChanged(id)
        }
        "permission.replied", "form.replied", "form.cancelled" ->
            if (data?.sessionID != null) OpenCodeServerEvent.AttentionChanged(null) else null
        "shell.created" -> {
            val info = data?.info ?: return null
            if (info.status != "running") null else OpenCodeServerEvent.ShellStarted(info.shell(isListed = false))
        }
        "shell.exited", "shell.deleted" -> data?.id?.let(OpenCodeServerEvent::ShellEnded)
        else -> null
    }
}
