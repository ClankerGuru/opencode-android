package ai.opencode.feature.voice

import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import ai.opencode.feature.voice.LiveCallCoordinator.PendingAppend
import ai.opencode.feature.voice.LiveCallCoordinator.AnswerSpeech
import java.util.Locale

internal suspend fun LiveCallCoordinator.noteLiveProgress(callID: String, text: String) {
    val delegationID = when {
        callID.isEmpty() -> null
        callID in openDelegations -> callID
        callID in orphanDelegations || callID in clientDelegations -> null
        else -> return
    }
    for (chunk in LiveContentChunker.chunks(text)) {
        if (!running()) return
        sendAppend(PendingAppend(LiveAppendKind.thinking, delegationID, chunk, origin = callID.ifEmpty { null }))
    }
}

internal suspend fun LiveCallCoordinator.announceLive(text: String, sessionID: String?, promptIDs: List<String>) {
    if (!running()) return
    val id = nextEventID("ann")
    val now = clock.now()
    heldAnnouncements += LiveContentChunker.chunks(text).map {
        PendingAppend(LiveAppendKind.commentary, null, it, sessionID, id, promptIDs, now)
    }
    val delay = announcementDelay(now)
    if (delay != null) {
        voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker,
            "Held notice so it doesn't cut off speech (${ReplyWatch.milliseconds(delay)}ms, ${quietGate.holdReason(now)}): ${VoiceWorkClient.excerpt(text, 120)}")
        scheduleAnnouncementFlush()
    } else flushAnnouncements()
}

internal fun LiveCallCoordinator.announcementDelay(now: Duration): Duration? {
    val oldest = heldAnnouncements.firstOrNull()?.enqueuedAt ?: return null
    if (finished || timing.liveAppendQuiet <= Duration.ZERO) return null
    var wait = if (quietAppends.isNotEmpty() || quietAppendsSending > 0 || pendingDelegationTasks.isNotEmpty() || acknowledgements.isNotEmpty()) 200.milliseconds else Duration.ZERO
    quietGate.delay(now)?.let { wait = maxOf(wait, it) }
    return limited(wait, now - oldest, now)
}

private fun LiveCallCoordinator.limited(wait: Duration, heldFor: Duration, now: Duration): Duration? {
    var result = wait
    if (timing.liveResultMaxHold > Duration.ZERO) result = minOf(result, maxOf(Duration.ZERO, timing.liveResultMaxHold - heldFor))
    quietGate.userSpeechHold(now)?.let { result = maxOf(result, it) }
    answerProtection(now)?.let { result = maxOf(result, it) }
    return result.takeIf { it > Duration.ZERO }
}
private fun LiveCallCoordinator.answerProtection(now: Duration): Duration? {
    val left = when (val answer = answerSpeech) {
        is AnswerSpeech.Awaiting -> return answerStartDelay(now)
        is AnswerSpeech.Speaking -> {
            if (!quietGate.assistantSpeaking) return null
            minOf(timing.liveAppendQuiet, timing.liveAnswerSpeakingHold - (now - answer.sentAt))
        }
        is AnswerSpeech.Paused -> minOf(timing.liveAppendQuiet - (now - answer.at), timing.liveAnswerSpeakingHold - (now - answer.sentAt))
        null -> return null
    }
    return left.takeIf { it > Duration.ZERO }
}
private fun LiveCallCoordinator.answerStartDelay(now: Duration): Duration? {
    val answer = answerSpeech as? AnswerSpeech.Awaiting ?: return null
    return (timing.liveAnswerStartWait - (now - answer.since)).takeIf { it > Duration.ZERO }
}
internal fun LiveCallCoordinator.noteAnswerDelivered() {
    answerSpeech = if (timing.liveAnswerStartWait > Duration.ZERO) AnswerSpeech.Awaiting(clock.now()) else null
}
internal fun LiveCallCoordinator.noteAssistantSpeech(speaking: Boolean, frame: Boolean = false) {
    val now = clock.now()
    val changed = speaking != quietGate.assistantSpeaking
    quietGate = quietGate.noteAssistantSpeaking(speaking, now)
    val answer = answerSpeech
    if (speaking && (changed || frame) && answer is AnswerSpeech.Awaiting) {
        answerSpeech = if (answerStartDelay(now) != null) AnswerSpeech.Speaking(answer.since) else null
        return
    }
    if (!changed) return
    when (answer) {
        is AnswerSpeech.Speaking -> if (!speaking) answerSpeech = AnswerSpeech.Paused(now, answer.sentAt)
        is AnswerSpeech.Paused -> if (speaking) answerSpeech = if (now - answer.at < timing.liveAppendQuiet) AnswerSpeech.Speaking(answer.sentAt) else null
        else -> Unit
    }
}
private fun LiveCallCoordinator.scheduleAnnouncementFlush(first: Duration = Duration.ZERO) {
    if (finished || announcementFlushTask != null) return
    announcementFlushTask = scope.launch {
        if (first > Duration.ZERO) clock.sleep(first)
        while (isActive && !finished) {
            val delay = announcementDelay(clock.now())
            if (delay != null) { clock.sleep(delay); continue }
            flushAnnouncements(); return@launch
        }
    }
}
private suspend fun LiveCallCoordinator.flushAnnouncements() {
    announcementFlushTask = null
    if (quietAppends.isNotEmpty()) flushQuietAppends(force = true)
    if (!acquireSendSlot()) return
    try {
        if (quietAppends.isNotEmpty()) { scheduleAnnouncementFlush(200.milliseconds); return }
        if (announcementDelay(clock.now()) != null) { scheduleAnnouncementFlush(); return }
        val held = heldAnnouncements.toList(); heldAnnouncements.clear()
        sendBatch(held)
    } finally { releaseSendSlot() }
}

/** FIFO, cancellation-aware batch slot. End turns every queued caller away. */
private suspend fun LiveCallCoordinator.acquireSendSlot(): Boolean {
    if (!running()) return false
    if (!sendSlotTaken) { sendSlotTaken = true; return true }
    val waiter = CompletableDeferred<Boolean>()
    sendSlotWaiters += waiter
    var granted = false
    try {
        granted = waiter.await()
        if (granted && !running()) { releaseSendSlot(); return false }
        return granted
    } catch (error: CancellationException) {
        // Cancellation can win dispatch after release handed us the slot.
        if (!granted && waiter.isCompleted && !waiter.isCancelled && waiter.getCompleted()) releaseSendSlot()
        throw error
    } finally { sendSlotWaiters.remove(waiter) }
}
private fun LiveCallCoordinator.releaseSendSlot() {
    if (sendSlotWaiters.isEmpty()) sendSlotTaken = false
    else sendSlotWaiters.removeFirst().complete(true)
}
internal fun LiveCallCoordinator.rejectSendSlotWaiters() {
    val waiters = sendSlotWaiters.toList(); sendSlotWaiters.clear()
    waiters.forEach { it.complete(false) }
}
private fun LiveCallCoordinator.hold(appends: List<PendingAppend>) {
    if (finished || appends.isEmpty()) return
    val now = clock.now(); quietGate = quietGate.noteHeld(now)
    quietAppends += appends.map { if (it.enqueuedAt == null) it.copy(enqueuedAt = now) else it }
    scheduleQuietFlush(restart = appends.any { it.answer != null })
}
internal fun LiveCallCoordinator.cancelLiveAnnouncements(promptIDs: List<String>) {
    val answered = promptIDs.toSet()
    val remove: (PendingAppend) -> Boolean = { it.kind == LiveAppendKind.commentary && it.delegationID == null && it.promptIDs.isNotEmpty() && it.promptIDs.all(answered::contains) }
    val before = quietAppends.size + heldAnnouncements.size + undelivered.size
    quietAppends.removeAll(remove); heldAnnouncements.removeAll(remove); undelivered.removeAll(remove)
    if (quietAppends.size + heldAnnouncements.size + undelivered.size < before) {
        voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker, "Suppressed held notice for ${promptIDs.joinToString(", ")} (answered by delegator)")
    }
    if (quietAppends.isEmpty()) clearQuietHold()
}
internal suspend fun LiveCallCoordinator.abandonLiveDelegation(callID: String) {
    pendingDelegationTasks.remove(callID)?.cancel()
    acknowledgements.remove(callID)?.deadline?.cancel()
    delegationOffsets.remove(callID); delegatedAt.remove(callID)
    val wasOpen = openDelegations.remove(callID)
    val wasOrphan = orphanDelegations.remove(callID)
    val wasClient = clientDelegations.remove(callID) != null
    serverDelegationIDs.remove(callID); endServerDelegation(callID, "The request was dropped.")
    if (wasOpen || wasOrphan || wasClient) discardStandbyAndRescheduleRotation()
}
internal suspend fun LiveCallCoordinator.completeLiveFunction(callID: String, output: String) {
    if (!running()) return
    if (serverDelegationIDs.remove(callID)) {
        endServerDelegation(callID, "The request finished."); delegatedAt.remove(callID)
        for (item in orphanCommentaryAppends(LiveContentChunker.commentaryChunks(output), lead = "")) sendAppend(item)
        return
    }
    val isOrphan = orphanDelegations.remove(callID)
    val isKnown = openDelegations.remove(callID)
    val claim = clientDelegations.remove(callID)
    val answer = PendingAppend.Answer(clock.now(), delegatedAt.remove(callID))
    val stale: (PendingAppend) -> Boolean = { it.kind == LiveAppendKind.thinking && it.origin == callID }
    quietAppends.removeAll(stale); undelivered.removeAll(stale)
    if (quietAppends.isEmpty()) clearQuietHold()
    val chunks = LiveContentChunker.commentaryChunks(output)
    if (chunks.isEmpty()) return
    if (claim != null) {
        deliverOrHold(orphanCommentaryAppends(chunks, answer, "${LiveCallCoordinator.claimLeadPrefix}$claim”: "))
        return
    }
    if (isOrphan || !isKnown || active?.ready != true || active?.attached != true) {
        deliverOrHold(orphanCommentaryAppends(chunks, answer)); return
    }
    val socketID = active?.id
    val appends = chunks.map { PendingAppend(LiveAppendKind.commentary, callID, it, answer = answer, socketID = socketID) }
    if (quietAppends.isNotEmpty() || quietDelay(answer) != null) { hold(appends); return }
    if (!acquireSendSlot()) return
    var sessionChanged = false
    try {
        val socket = active
        if (socket?.id != socketID || socket?.ready != true || !socket.attached) sessionChanged = true
        else if (quietAppends.isNotEmpty() || quietDelay(answer) != null) hold(appends)
        else sendBatch(appends) { rest -> orphanCommentaryAppends(rest.map { it.content }, rest.firstOrNull()?.answer) }
    } finally { releaseSendSlot() }
    if (sessionChanged) deliverOrHold(orphanCommentaryAppends(chunks, answer))
}
private fun orphanCommentaryAppends(chunks: List<String>, answer: PendingAppend.Answer? = null, lead: String = LiveCallCoordinator.earlierConnectionLead): List<PendingAppend> =
    chunks.flatMapIndexed { index, chunk -> LiveContentChunker.chunks(if (index == 0) lead + chunk else chunk)
        .map { PendingAppend(LiveAppendKind.commentary, null, it, answer = answer) } }

internal suspend fun LiveCallCoordinator.sendAppend(append: PendingAppend) {
    if (!running()) return
    if (quietAppends.isNotEmpty() || quietDelay(append.answer) != null) { holdLogged(append); return }
    if (!acquireSendSlot()) return
    try {
        if (quietAppends.isNotEmpty() || quietDelay(append.answer) != null) { holdLogged(append); return }
        sendBatch(listOf(append))
    } finally { releaseSendSlot() }
}
private fun LiveCallCoordinator.holdLogged(append: PendingAppend) {
    val now = clock.now(); hold(listOf(append))
    if (append.kind == LiveAppendKind.commentary) quietDelay()?.let {
        voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker,
            "Held notice (${ReplyWatch.milliseconds(it)}ms quiet gate, ${quietGate.holdReason(now)}): ${VoiceWorkClient.excerpt(append.content, 120)}")
    }
}
private fun LiveCallCoordinator.quietDelay(answer: PendingAppend.Answer? = null): Duration? {
    if (timing.liveAppendQuiet <= Duration.ZERO || finished) return null
    val now = clock.now()
    val ownAnswer = quietAppends.firstNotNullOfOrNull { it.answer } ?: answer ?: return quietGate.delay(now)
    val wait = quietGate.answerDelay(now, ownAnswer.readyAt, isPressed(ownAnswer), timing.liveAnswerPressedHold)
    return wait?.let { limited(it, now - ownAnswer.readyAt, now) }
}
private fun LiveCallCoordinator.isPressed(answer: PendingAppend.Answer): Boolean {
    val delegated = answer.delegatedAt ?: return false
    val words = lastUserWordsAt ?: return false
    if (words - delegated <= timing.liveAnswerPressedAfter) return false
    return lastDelegatedAt?.let { it <= words } ?: true
}
private fun LiveCallCoordinator.scheduleQuietFlush(restart: Boolean = false) {
    if (finished || (quietFlushTask != null && !restart)) return
    val generation = ++quietFlushGeneration
    quietFlushTask = scope.launch {
        while (isActive && !finished && generation == quietFlushGeneration) {
            val delay = quietDelay()
            if (delay != null) { clock.sleep(delay); continue }
            flushQuietAppends(); return@launch
        }
    }
}
private fun LiveCallCoordinator.clearQuietHold() {
    quietGate = quietGate.clearHold(); quietFlushTask = null; quietFlushGeneration++
}
private suspend fun LiveCallCoordinator.flushQuietAppends(force: Boolean = false) {
    quietFlushTask = null; quietFlushGeneration++; quietAppendsSending++
    try {
        if (!acquireSendSlot()) return
        try {
            if (quietAppends.isEmpty()) return
            val now = clock.now()
            val blocked = if (force) quietGate.userSpeechHold(now) != null || answerProtection(now) != null else quietDelay() != null
            if (blocked) { scheduleQuietFlush(); return }
            quietGate = quietGate.clearHold()
            val held = quietAppends.toList(); quietAppends.clear()
            sendBatch(held)
        } finally { releaseSendSlot() }
    } finally { quietAppendsSending-- }
}
/** A suspended send never lets a later batch interleave. Failure preserves the
 * failed chunk and everything after it, in order, for the replacement session. */
private suspend fun LiveCallCoordinator.sendBatch(appends: List<PendingAppend>, rewrite: (List<PendingAppend>) -> List<PendingAppend> = { rest -> rest.map { it.copy(delegationID = null) } }) {
    val startedOn = active?.id
    for (index in appends.indices) {
        if (!running()) return
        if (startedOn != null && active?.id != startedOn) { requeue(rewrite(appends.drop(index)), startedOn); return }
        val failedOn = deliverAppend(appends[index])
        if (failedOn != null) { if (!finished) requeue(rewrite(appends.drop(index)), failedOn); return }
    }
}
/** Null means sent or safely queued; a socket ID means the batch must requeue. */
private suspend fun LiveCallCoordinator.deliverAppend(original: PendingAppend): Int? {
    if (!running()) return null
    val append = if (original.socketID != null && original.socketID != active?.id) original.copy(delegationID = null) else original
    val socket = active?.takeIf { it.ready && it.attached }
    if (socket == null) {
        undelivered += if (active?.ready != true) append.copy(delegationID = null) else append
        if (append.kind == LiveAppendKind.commentary) voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker, "Queued notice for voice reconnect: ${VoiceWorkClient.excerpt(append.content, 120)}")
        return null
    }
    val note = deliveryNote(append)
    if (append.answer != null) noteAnswerDelivered()
    try {
        sendAppendThrowing(append, socket.connection)
        note?.let(callLogMarker)
        if (append.kind == LiveAppendKind.commentary && append.enqueuedAt != null) {
            voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.marker, "Sent held notice (after ${ReplyWatch.milliseconds(clock.now() - append.enqueuedAt)}ms): ${VoiceWorkClient.excerpt(append.content, 120)}")
            voiceCallLog.noteCurrent(VoiceCallLog.Note.Kind.injected, append.content)
        }
        return null
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { return socket.id }
}
private fun LiveCallCoordinator.requeue(appends: List<PendingAppend>, failedOn: Int) {
    if (finished || appends.isEmpty()) return
    val current = active
    if (current == null || current.id == failedOn || !current.ready || !current.attached) { undelivered += appends; return }
    scope.launch { deliverOrHold(appends) }
}
private suspend fun LiveCallCoordinator.deliverOrHold(appends: List<PendingAppend>) {
    if (!acquireSendSlot()) return
    try {
        if (quietAppends.isNotEmpty() || quietDelay(appends.firstOrNull()?.answer) != null) { hold(appends); return }
        sendBatch(appends)
    } finally { releaseSendSlot() }
}
internal fun LiveCallCoordinator.deliveryNote(append: PendingAppend): String? {
    if (append.kind == LiveAppendKind.commentary && append.enqueuedAt != null) return null
    val now = clock.now()
    fun seconds(duration: Duration) = String.format(Locale.ROOT, "%.1f s", duration.inWholeNanoseconds / 1e9)
    var text = "Sent ${append.kind.rawValue} to the voice agent · held ${seconds(append.enqueuedAt?.let { now - it } ?: Duration.ZERO)}"
    if (quietGate.assistantSpeaking) text += " · while it was speaking"
    else quietGate.lastAssistantSpeechAt?.let { text += " · it last spoke ${seconds(now - it)} before" }
    if (assistantAudioPlaying) text += " · while its audio played"
    return text
}
internal suspend fun LiveCallCoordinator.sendAppendThrowing(append: PendingAppend, connection: VoiceSocketConnection<LiveServerEvent, LiveCommand>) {
    if (!running()) throw CancellationException()
    val id = nextEventID(append.kind.rawValue)
    // Responses delegation rejects every non-null delegation ID on appends.
    val delegationID = if (delegatesToResponses) null else append.delegationID
    val command = when (append.kind) {
        LiveAppendKind.instructions -> LiveCommand.AppendInstructions(id, delegationID, append.content)
        LiveAppendKind.thinking -> LiveCommand.AppendThinking(id, delegationID, append.content)
        LiveAppendKind.commentary -> LiveCommand.AppendCommentary(id, delegationID, append.content)
    }
    connection.sendControl(command, "live.append")
    if (append.kind == LiveAppendKind.commentary) results = results.record(append.content)
}
internal suspend fun LiveCallCoordinator.sendAppendIfPossible(append: PendingAppend, connection: VoiceSocketConnection<LiveServerEvent, LiveCommand>): Boolean =
    try { sendAppendThrowing(append, connection); true }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { false }
