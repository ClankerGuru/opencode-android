package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeClientError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Full-duplex GPT-Live: continuous audio, client/Responses delegations,
 * quiet-gated appends and session.input-seeded rotation/recovery. */
class LiveCallCoordinator(
    timing: VoiceCallTiming = VoiceCallTiming.live,
    diagnostics: VoiceCallDiagnostics = dependency(VoiceCallDiagnostics),
    callLogMarker: ((String) -> Unit)? = null,
) : VoiceSessionCoordinator<LiveServerEvent, LiveCommand, LiveCallCoordinator.PendingAppend>(timing, diagnostics) {
    data class Utterance(val id: String, val rawText: String, val startMilliseconds: Int?, val endMilliseconds: Int?,
        val delegated: Boolean = false, val userWordsBefore: Duration? = null)
    internal data class Acknowledgement(val offset: Int?, var utteranceID: String? = null, var deadline: Job? = null)
    data class PendingAppend(
        val kind: LiveAppendKind, val delegationID: String?, val content: String,
        val sessionID: String? = null, val announcementID: String? = null, val promptIDs: List<String> = emptyList(),
        val enqueuedAt: Duration? = null, val answer: Answer? = null, val origin: String? = null, val socketID: Int? = null,
    ) { data class Answer(val readyAt: Duration, val delegatedAt: Duration?) }
    internal sealed interface AnswerSpeech {
        data class Awaiting(val since: Duration) : AnswerSpeech
        data class Speaking(val sentAt: Duration) : AnswerSpeech
        data class Paused(val at: Duration, val sentAt: Duration) : AnswerSpeech
    }
    internal class ServerDelegation(val socketID: Int) {
        val calls = mutableListOf<VoiceDelegator.ToolCall>()
        val text = mutableListOf<String>()
        val rounds = ArrayDeque<Result<VoiceDelegator.Response>>()
        var waiter: CompletableDeferred<Result<VoiceDelegator.Response>>? = null
    }
    override val defaultSessionLimit = 120.0 * 60
    override val defaultDiagnosticDomain = "LiveClient"
    override val expiredDiagnosticDomain = "OpenAILive"
    override val canSwapToStandby get() = !assistantAudioPlaying && currentAssistantUtterance == null && !audioInterrupted
    internal var sendSlotTaken = false
    internal val sendSlotWaiters = ArrayDeque<CompletableDeferred<Boolean>>()
    internal var quietFlushGeneration = 0
    internal var quietAppendsSending = 0
    private var apiKey = ""
    private var live: LiveClient? = null
    private var config: LiveSessionConfig? = null
    private var nextEventNumber = 0
    private var isMuted = false
    private var muteKeepAliveTask: Job? = null
    internal var nextUserUtteranceNumber = 1
    internal var nextAssistantUtteranceNumber = 1
    internal var currentUserUtterance: Utterance? = null
    internal var currentAssistantUtterance: Utterance? = null
    internal var assistantSettleTask: Job? = null
    internal val pendingDelegationTasks = mutableMapOf<String, Job>()
    internal val delegationOffsets = mutableMapOf<String, Int?>()
    internal val acknowledgements = linkedMapOf<String, Acknowledgement>()
    internal var movedSinceInstructions = false
    internal var assistantAudioPlaying = false
    internal var speechAudioHangover = 0
    internal var audioInterrupted = false
    internal var quietGate = timing.quietGate
    internal val quietAppends = mutableListOf<PendingAppend>()
    internal var quietFlushTask: Job? = null
    internal val heldAnnouncements = mutableListOf<PendingAppend>()
    internal var announcementFlushTask: Job? = null
    internal var history = VoiceTurnHistory()
    internal var results = VoiceResultRecord()
    internal val seenDelegationIDs = mutableSetOf<String>()
    internal val openDelegations = mutableSetOf<String>()
    internal val delegatedAt = mutableMapOf<String, Duration>()
    internal var lastDelegatedAt: Duration? = null
    internal var lastUserWordsAt: Duration? = null
    internal var answerSpeech: AnswerSpeech? = null
    internal val orphanDelegations = mutableSetOf<String>()
    internal var lastDelegationCreatedAt: Duration? = null
    internal var unbackedClaimTask: Job? = null
    internal var nextClientDelegationNumber = 1
    internal val clientDelegations = mutableMapOf<String, String>()
    internal val undelivered = mutableListOf<PendingAppend>()
    internal val serverDelegationIDs = mutableSetOf<String>()
    internal val serverDelegations = mutableMapOf<String, ServerDelegation>()
    internal val delegatesToResponses get() = config?.delegation is LiveDelegation.Responses
    internal val voiceCallLog = dependency(VoiceCallLogClient)
    internal val callLogMarker: (String) -> Unit = callLogMarker ?: { voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker, it) }

    suspend fun start(apiKey: String, config: LiveSessionConfig, live: LiveClient, audio: VoiceAudioClient): Flow<VoiceCallEvent> = serialized {
        this.apiKey = apiKey; this.config = config; this.live = live
        startAudioThenSocket(config.isAtEar, audio, rtcPossible = true)
    }
    internal fun handleResponseEvent(delegationID: String?, event: LiveResponseEvent) {
        val id = delegationID ?: serverDelegations.keys.singleOrNull() ?: return
        val entry = serverDelegations[id] ?: return
        when (event) {
            is LiveResponseEvent.FunctionCall -> entry.calls += event.call
            is LiveResponseEvent.Message -> entry.text += event.text
            is LiveResponseEvent.Finished -> {
                val response = VoiceDelegator.Response(event.responseID ?: "", entry.calls.toList(), entry.text.joinToString(" "))
                entry.calls.clear(); entry.text.clear()
                val round = if (event.status != "completed") {
                    voiceLog.notice("GPT-Live delegator response ${event.status}")
                    Result.failure(OpenCodeClientError(event.error ?: "The delegator stopped (${event.status})."))
                } else {
                    if (response.calls.isEmpty()) noteAnswerDelivered()
                    Result.success(response)
                }
                val waiter = entry.waiter
                if (waiter != null) { entry.waiter = null; waiter.complete(round) } else entry.rounds += round
            }
            is LiveResponseEvent.Created, LiveResponseEvent.Ignored -> Unit
        }
    }
    suspend fun delegatorRound(delegationID: String): Result<VoiceDelegator.Response> = serialized {
        val entry = serverDelegations[delegationID]
        if (finished || entry == null || entry.waiter != null) return@serialized Result.failure(OpenCodeClientError("That request’s voice connection closed."))
        if (entry.rounds.isNotEmpty()) return@serialized entry.rounds.removeFirst()
        val waiter = CompletableDeferred<Result<VoiceDelegator.Response>>()
        entry.waiter = waiter
        try { waiter.await() }
        finally { if (entry.waiter === waiter) entry.waiter = null }
    }
    suspend fun submitDelegatorInput(delegationID: String, input: List<VoiceDelegator.InputItem>): Boolean = serialized {
        val entry = serverDelegations[delegationID] ?: return@serialized false
        val socket = active?.takeIf { it.id == entry.socketID && it.ready } ?: return@serialized false
        if (finished) return@serialized false
        try {
            for (item in input) {
                if (!running() || active?.id != socket.id) return@serialized false
                socket.connection.sendControl(LiveCommand.ResponseItemCreate(nextEventID("item"), item.liveItem), "live.response_item_create")
            }
            if (!running() || active?.id != socket.id) return@serialized false
            socket.connection.sendControl(LiveCommand.ResponseCreate(nextEventID("continue")), "live.response_create")
            true
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { voiceLog.notice("GPT-Live delegator results not sent"); false }
    }
    suspend fun updateDelegatorInstructions(instructions: String) = serialized {
        if (finished) return@serialized
        val config = config ?: return@serialized
        val backend = (config.delegation as? LiveDelegation.Responses)?.backend ?: return@serialized
        if (backend.instructions == instructions) return@serialized
        this.config = config.copy(delegation = LiveDelegation.Responses(backend.copy(instructions = instructions)))
        for (socket in listOfNotNull(active, standby).filter { it.ready }) {
            if (!running()) return@serialized
            socket.connection.sendControlIfPossible(LiveCommand.UpdateDelegatorInstructions(nextEventID("delegator"), instructions), "live.delegator_instructions")
        }
    }
    internal fun endServerDelegation(id: String, reason: String) {
        serverDelegations.remove(id)?.waiter?.complete(Result.failure(OpenCodeClientError(reason)))
    }
    override suspend fun setMutedImpl(muted: Boolean) {
        isMuted = muted; audio?.setMuted?.invoke(muted)
        muteKeepAliveTask?.cancel(); muteKeepAliveTask = null
        if (finished) return
        if (muted && active?.connection?.startAudio == null) {
            val silence = ByteArray(PCM.chunkFrames * 2)
            muteKeepAliveTask = scope.launch {
                while (isActive && !finished) { clock.sleep(100.milliseconds); if (!finished) forwardCapture(silence) }
            }
        }
        val socket = active?.takeIf { it.ready && it.attached } ?: return
        socket.connection.sendControlIfPossible(if (muted) LiveCommand.Mute(nextEventID("mute")) else LiveCommand.Unmute(nextEventID("unmute")), "live.mute_state")
    }
    override suspend fun updateInstructions(full: String, delta: String?) = serialized {
        if (finished) return@serialized
        config = config?.copy(instructions = full) ?: return@serialized
        if (delta?.startsWith(VoiceInstructions.retargetPrefix) == true) {
            if (!movedSinceInstructions) retargetImpl()
            movedSinceInstructions = false
        }
        for (chunk in LiveContentChunker.chunks(delta ?: full)) {
            if (!running()) return@serialized
            val append = PendingAppend(LiveAppendKind.instructions, null, chunk)
            sendAppend(append)
            val standby = standby
            if (!finished && standby != null) {
                if (standby.ready) sendAppendIfPossible(append, standby.connection) else standby.pendingAppends += append
            }
        }
    }
    override suspend fun open(seeded: Boolean): VoiceCallSocket<LiveServerEvent, LiveCommand, PendingAppend> {
        val client = live ?: throw RealtimeClientError.Closed("The voice connection is closed.")
        var config = config ?: throw RealtimeClientError.Closed("The voice connection is closed.")
        val mark = history.mark
        if (seeded) config = config.copy(input = seedMessages())
        return openSocket(seeded, { client.connect(apiKey, config) }) { history = history.noteSeeded(mark) }
    }
    private fun seedMessages(): List<LiveInputMessage> {
        var header = seedHeader
        val running = openDelegations.size + orphanDelegations.size + clientDelegations.size
        if (running > 0) header += " ${if (running == 1) "One request you delegated is" else "$running requests you delegated are"} still running in the coding session; results will arrive as commentary updates."
        if (results.entries.isNotEmpty()) header += "\n\nResults you were given on this call, oldest first. They are the only work you can report; a recap lists these and nothing else:\n" + results.entries.joinToString("\n") { "- $it" }
        return listOf(LiveInputMessage(LiveInputMessage.Role.developer, header)) + history.turns.takeLast(12).map { turn ->
            val text = turn.text.take(600)
            if (turn.role == VoiceTranscriptTail.Role.assistant && turn.cutOff) LiveInputMessage(LiveInputMessage.Role.developer,
                "Your reply here was cut off; the user heard only part of: “$text”. Don't finish it or say it again.")
            else LiveInputMessage(if (turn.role == VoiceTranscriptTail.Role.user) LiveInputMessage.Role.user else LiveInputMessage.Role.assistant, text)
        }
    }
    override suspend fun activate(socket: VoiceCallSocket<LiveServerEvent, LiveCommand, PendingAppend>) {
        val previous = active
        socket.attached = false; active = socket
        if (standby?.id == socket.id) standby = null
        retireSessionState()
        if (previous != null && previous.id != socket.id) previous.task?.cancel()
        activateAudio(socket)
        if (!finished && socket.ready && active?.id == socket.id) attached(socket)
        if (previous != null && previous.id != socket.id) previous.connection.close()
    }
    override fun retireSessionState() {
        pendingDelegationTasks.values.forEach { it.cancel() }; pendingDelegationTasks.clear()
        settleAssistantUtterance(assistantAudioPlaying || currentAssistantUtterance?.let { !endsSentence(it.rawText) } == true)
        flushAcknowledgements()
        if (assistantAudioPlaying) noteAssistantSpeech(false)
        assistantAudioPlaying = false; speechAudioHangover = 0
        orphanDelegations += openDelegations; openDelegations.clear()
        serverDelegations.keys.toList().forEach { endServerDelegation(it, "The voice connection was replaced before that request finished.") }
    }
    internal suspend fun attached(socket: VoiceCallSocket<LiveServerEvent, LiveCommand, PendingAppend>) {
        noteSocketAttached(resetLinkQuality = true)
        if (isMuted) socket.connection.sendControlIfPossible(LiveCommand.Mute(nextEventID("mute")), "live.mute")
        history.catchUp(socket.seeded)?.let { catchUp ->
            for (chunk in LiveContentChunker.chunks(catchUp.text)) {
                if (finished || active?.id != socket.id) return
                if (!sendAppendIfPossible(PendingAppend(LiveAppendKind.thinking, null, chunk), socket.connection)) return
            }
            history = history.noteSeeded(catchUp.mark)
            callLogMarker("Sent catch-up transcript to the voice agent on the new session (${catchUp.lines} line${if (catchUp.lines == 1) "" else "s"})")
        }
        val standbyAppends = socket.pendingAppends.toList(); socket.pendingAppends.clear()
        for (item in standbyAppends) {
            if (finished || active?.id != socket.id) return
            if (!sendAppendIfPossible(item, socket.connection)) return
        }
        var drained = 0
        try {
            while (undelivered.isNotEmpty()) {
                if (finished || active?.id != socket.id) return
                val item = undelivered.removeAt(0)
                try {
                    val note = deliveryNote(item)
                    if (item.answer != null) noteAnswerDelivered()
                    sendAppendThrowing(item, socket.connection); drained++
                    note?.let(callLogMarker)
                } catch (error: CancellationException) { if (!finished) undelivered.add(0, item); throw error }
                catch (_: Exception) { if (!finished) undelivered.add(0, item); return }
            }
        } finally { if (drained > 0) callLogMarker("Sent $drained undelivered append${if (drained == 1) "" else "s"} to the voice agent on the new session") }
        if (finished || active?.id != socket.id) return
        active?.attached = true
        scheduleRotation(socket)
    }
    override suspend fun forwardCapture(chunk: ByteArray) {
        if (finished || active?.ready != true) return
        val started = clock.now(); uplinkSendStarted()
        try { connection?.send?.invoke(LiveCommand.AppendAudio(chunk)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Per-chunk logging would spam. */ }
        finally { audio?.quality?.sent(clock.now() - started); uplinkSendFinished() }
    }
    override suspend fun handle(event: LiveServerEvent, socket: Int) = handleLiveEvent(event, socket)
    override suspend fun handleAudio(event: VoiceAudioEvent) = handleLiveAudio(event)
    override suspend fun completeFunctionImpl(callID: String, output: String) = completeLiveFunction(callID, output)
    override suspend fun abandonDelegationImpl(callID: String) = abandonLiveDelegation(callID)
    override suspend fun retargetImpl() { movedSinceInstructions = true }
    suspend fun noteProgress(callID: String, text: String) = serialized { if (!finished) noteLiveProgress(callID, text) }
    suspend fun announce(text: String, sessionID: String? = null, promptIDs: List<String> = emptyList()) = serialized { if (!finished) announceLive(text, sessionID, promptIDs) }
    suspend fun cancelPendingAnnouncements(promptIDs: List<String>) = serialized { if (!finished) cancelLiveAnnouncements(promptIDs) }
    internal fun nextEventID(prefix: String) = "${prefix}_${++nextEventNumber}"
    override fun finishing() {
        pendingDelegationTasks.values.forEach { it.cancel() }; pendingDelegationTasks.clear()
        acknowledgements.values.forEach { it.deadline?.cancel() }; acknowledgements.clear()
        assistantSettleTask?.cancel(); unbackedClaimTask?.cancel(); muteKeepAliveTask?.cancel()
        quietFlushTask?.cancel(); quietFlushTask = null; quietGate = quietGate.clearHold(); quietAppends.clear()
        announcementFlushTask?.cancel(); announcementFlushTask = null; heldAnnouncements.clear(); undelivered.clear()
        rejectSendSlotWaiters()
        serverDelegations.keys.toList().forEach { endServerDelegation(it, "The voice call ended.") }
    }
    companion object {
        const val claimLeadPrefix = "Result of the request you acknowledged with “"
        const val earlierConnectionLead = "Result of an earlier request you delegated: "
        const val speechHangoverMilliseconds = 300
        const val seedHeader = "This voice call continues from an earlier connection; keep its context and carry on naturally without mentioning reconnecting. The turns that follow are what was said, for context only: never finish or repeat an earlier reply of yours, and when the user corrected you, the correction stands."
        fun endsSentence(text: String): Boolean = text.trim().lastOrNull()?.let { it in ".!?…\"”)" } ?: true
        fun hasWords(delta: String): Boolean {
            var inTag = false
            for (character in delta) {
                if (character == '[') inTag = true
                else if (character == ']' || character.isWhitespace()) inTag = false
                else if (!inTag && character.isLetter()) return true
            }
            return false
        }
    }
}
