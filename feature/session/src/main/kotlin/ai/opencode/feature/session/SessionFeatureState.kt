package ai.opencode.feature.session

import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.UndoSnapshot
import ai.opencode.feature.composer.DraftState
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.ModelVisibility
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptSubagent
import ai.opencode.feature.voice.VoiceBroker
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceLabDemo
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.SessionActivity
import ai.opencode.server.model.SessionTitle
import ai.opencode.server.model.isUntitled
import ai.opencode.server.model.keepingFirstPrompt
import ai.opencode.server.model.keepingFirstPrompts
import ai.opencode.server.model.learningFirstPrompt
import ai.opencode.server.model.model
import java.time.Instant
import java.util.UUID

// Port of iOS `SessionFeature+State.swift` (and the computed members of `SessionFeature.swift`'s
// `State`): computed properties as extension `val`s, `mutating func`s as `fun State.x(): State`.
// iOS get/set pairs are a getter plus a `withX(value)` setter. Nested types live in `State`
// (`SessionFeature.kt`), since Kotlin keeps nested declarations in the class body.

// region Draft (iOS `draft` `didSet`)

/** Sets [State.draft], keeping iOS's `didSet`: an edit drops [State.nonSendNewlineDraft]. */
fun State.withDraft(text: String): State =
    copy(draft = text, nonSendNewlineDraft = if (nonSendNewlineDraft != text) null else nonSendNewlineDraft)

// endregion

// region Members of `SessionFeature.swift`'s State

/** Null when there's nothing to show: the send failed or was cancelled, or the turn ended without prose. */
val State.browserPeek: State.BrowserPeek?
    get() {
        if (!browser.isOpen) return null
        val id = browser.peekMessageID ?: return null
        if (failedSends.withID(id) != null || id in cancelledInputIDs) return null
        val sent = items.indexOfLast { TranscriptEntry.promptID(it) == id }
        if (sent >= 0) {
            val reply = items.subList(sent + 1, items.size).asReversed().asSequence()
                .mapNotNull { item -> (item as? OpenCodeTranscriptItem.AssistantMarkdown)?.let { State.peekText(it.text) } }
                .firstOrNull { it.isNotEmpty() }
            if (reply != null) return State.BrowserPeek(reply)
        }
        // Working while the message is on its way or its turn runs.
        val waiting = promptStates[id] != null || queuedInputs.any { it.id == id }
        return if (waiting || isRunning || isAwaitingReply) State.BrowserPeek(null) else null
    }

/** iOS `syncShellToolStatuses()`. */
fun State.syncShellToolStatuses(): State {
    val next = State.shellToolStatuses(items)
    val nextSubagents = State.subagentToolSessionIDs(items)
    if (shellToolStatuses == next && subagentToolSessionIDs == nextSubagents) return this
    return copy(shellToolStatuses = next, subagentToolSessionIDs = nextSubagents)
}

/**
 * Shells that outlive their tool call, matching the TUI's shell count minus commands the agent is
 * still waiting on in the foreground.
 */
val State.backgroundShells: List<OpenCodeShell>
    get() {
        val sessionID = session?.id ?: return emptyList()
        if (shells.isEmpty()) return emptyList()
        return shells.filter { shell ->
            if (shell.sessionID != sessionID) return@filter false
            if (shell.isUserCommand) return@filter true
            val status = shellToolStatuses[shell.id] ?: return@filter shell.isListed
            status == OpenCodeToolCall.Status.Completed || status == OpenCodeToolCall.Status.Error
        }
    }

/** Something the person opened over this session. */
val State.presentsOverSession: Boolean
    get() = activePicker != null || voice != null || folderBrowser != null || shellOutput != null ||
        browser.isOpen || requestDock.isAnswering

/** Only the transcript's tail can hold the run's current tool. */
val State.foregroundWork: State.ForegroundWork?
    get() {
        if (!isRunning || session == null) return null
        for (item in items.takeLast(64).asReversed()) {
            if (item !is OpenCodeTranscriptItem.Tool) continue
            val call = item.call
            if (call.messageID == null || call.status != OpenCodeToolCall.Status.Running) continue
            if (!(call.isShell || call.name == "subagent" || call.name == "task")) continue
            return State.ForegroundWork(item.id, call.startedAt)
        }
        return null
    }

/** A quiet line under a send waiting behind one that failed. */
val State.deliveryNotes: Map<String, DeliveryNote>
    get() {
        if (failedSends.isEmpty()) return emptyMap()
        return promptStates.filterValues { it.admission == State.PromptState.Admission.sent }.mapValues { DeliveryNote.waiting }
    }

/** The newest finished compaction in this screen's session. */
val State.completedCompactionMark: VoiceFeature.State.CompactionMark?
    get() {
        val session = session ?: return null
        for (item in items.asReversed()) {
            if (item is OpenCodeTranscriptItem.Compaction &&
                (item.info?.status ?: OpenCodeCompaction.Status.Completed) == OpenCodeCompaction.Status.Completed
            ) {
                return VoiceFeature.State.CompactionMark(session.id, item.id)
            }
        }
        return null
    }

fun State.items(excluding: Set<String>): List<OpenCodeTranscriptItem> {
    if (excluding.isEmpty()) return items
    return items.filter { item -> TranscriptEntry.promptID(item)?.let { it !in excluding } ?: true }
}

val State.browsingStartIndex: Int?
    get() {
        val id = browsingWindowStartID ?: return null
        if (items.firstOrNull()?.id == id) return 0
        items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return it }
        return items.indexOfFirst {
            val messageID = SessionFeature.messageID(it)
            messageID == id || id.startsWith("$messageID-")
        }.takeIf { it >= 0 }
    }

// endregion

// region SwitchSnapshot

/** iOS `SwitchSnapshot.init?(_:)`. */
fun SessionFeature.SwitchSnapshot.Companion.from(state: State): SessionFeature.SwitchSnapshot? {
    val session = state.session ?: return null
    val queued = state.queuedInputs.filter { state.promptStates[it.id]?.admission != State.PromptState.Admission.sent }
    val queuedIDs = queued.map { it.id }.toSet()
    val seenItemIDs: Set<String>
    var hasUnseenItems = false
    val mark = state.newMessages
    val markIndex = mark?.let { m -> state.items.indexOfFirst { it.id == m.itemID } } ?: -1
    val baseline = state.newMessagesBaseline
    if (!state.readsOnView) {
        seenItemIDs = state.catchUpOpeningSeen ?: baseline ?: emptySet()
        hasUnseenItems = mark != null || state.newMessagesUnread
    } else if (markIndex >= 0) {
        seenItemIDs = state.items.subList(0, markIndex).mapTo(HashSet()) { it.id }
        hasUnseenItems = true
    } else if (baseline != null && (baseline.isNotEmpty() || state.newMessagesUnread || state.newMessagesBaselineTimestamp != null ||
            state.isTranscriptCovered || state.isBackgrounded || state.items.isEmpty())
    ) {
        seenItemIDs = baseline + state.newMessagesWatched
        hasUnseenItems = state.newMessagesUnread
    } else {
        seenItemIDs = state.items.mapTo(HashSet()) { it.id }
    }
    return SessionFeature.SwitchSnapshot(
        session = session,
        family = state.familySessions,
        familyReadAt = state.familyReadAt,
        shells = state.shells,
        permissions = state.permissionRequests,
        forms = state.formRequests,
        formAnswers = state.formAnswers,
        questionPageIndices = state.questionPageIndices,
        answerDrafts = state.answerDraftsIncludingBar,
        hasLoadedRequests = state.hasLoadedPendingRequests,
        completedRequests = state.completedRequestIDs,
        selectedRequest = state.selectedRequestID,
        queued = queued,
        positions = state.pendingPromptPositions.filterKeys { it in queuedIDs },
        models = state.models,
        currentModel = state.currentModel,
        pendingModel = state.pendingModel,
        queuedPicks = state.queuedPicks,
        context = state.contextUsage,
        movableWorkKey = state.movableWorkKey,
        // A claim no send has acted on yet isn't a move.
        movedWorkKeys = state.movedWorkKeys - state.autoBackgroundClaims.values.toSet(),
        seenItemIDs = seenItemIDs,
        hasUnseenItems = hasUnseenItems,
    )
}

/** iOS `SwitchSnapshot.restore(into:)`. */
fun SessionFeature.SwitchSnapshot.restore(state: State): State {
    var next = state
    val selected = next.session
    if (selected != null && session.updatedAt >= selected.updatedAt) {
        next = if (session.updatedAt > selected.updatedAt) {
            next.copy(session = session.keepingFirstPrompt(selected))
        } else {
            next.copy(session = selected.copy(revertMessageID = session.revertMessageID))
        }
    }
    next = next.copy(familySessions = family.keepingFirstPrompts(next.familySessions), familyReadAt = familyReadAt, hasLoadedPendingRequests = hasLoadedRequests)
    next = next.updateRequestDock {
        it.copy(
            permissionRequests = permissions,
            formRequests = forms,
            formAnswers = forms.associate { form -> form.id to (formAnswers[form.id] ?: form.defaultAnswers) },
            questionPageIndices = questionPageIndices,
            answerDrafts = if (next.isAnsweringRequest) it.answerDrafts else answerDrafts,
            completedRequestIDs = completedRequests,
            // The pin comes back only while its request is still waiting.
            selectedRequestID = selectedRequest,
        )
    }
    next = next.pruneRequests()
    next = restoreSessionData(next)
    // A waiting request takes the composer from the first frame: the screen, a Catch Up card, or
    // the session Catch Up returns to.
    if (next.wantsRequestDock && !next.isAnsweringRequest) next = next.beginAnswering()
    return next
}

/** iOS `SwitchSnapshot.pruneRequests(removing:)`. */
fun SessionFeature.SwitchSnapshot.pruneRequests(
    removing: (PendingRequest) -> Boolean = { false },
): SessionFeature.SwitchSnapshot {
    val permissions = permissions.filterNot { removing(PendingRequest.Permission(it)) }
    val forms = forms.filterNot { removing(PendingRequest.Form(it)) }
    val live = (permissions.map { it.id } + forms.map { it.id }).toSet()
    return copy(
        permissions = permissions,
        forms = forms,
        formAnswers = SessionFeature.prune(formAnswers, live),
        questionPageIndices = SessionFeature.prune(questionPageIndices, live),
        answerDrafts = SessionFeature.pruneAnswerDrafts(answerDrafts, live),
        selectedRequest = selectedRequest?.takeIf { it in live },
    )
}

/** iOS `SwitchSnapshot.restoreSessionData(into:)`: family navigation keeps the family's discovery and requests. */
fun SessionFeature.SwitchSnapshot.restoreSessionData(state: State): State {
    val promoted = state.promotedPromptIDs.toSet() + SessionFeature.promptIDs(state.items)
    // The outbox is the source of truth for this phone's own sends: laid over the snapshot.
    val local = state.queuedInputs.filter { state.promptStates[it.id] != null }
    val localIDs = local.map { it.id }.toSet()
    val positions = positions.filterKeys { it !in promoted && it !in localIDs } +
        state.pendingPromptPositions.filterKeys { it in localIDs }
    var next = state.copy(
        shells = shells,
        restoredShellIDs = shells.map { it.id }.toSet(),
        queuedInputs = queued.filter { it.id !in promoted && it.id !in localIDs } + local,
        pendingPromptPositions = positions,
        models = models,
        currentModel = currentModel,
        pendingModel = pendingModel,
        queuedPicks = queuedPicks + state.queuedPicks,
        contextUsage = context,
        movableWorkKey = movableWorkKey,
        movedWorkKeys = movedWorkKeys,
    )
    next = next.syncVoiceModel()
    return next.copy(sessionSheet = next.sessionSheet?.copy(pendingModel = pendingModel))
}

// endregion

// region Loading and errors

val State.isResyncing: Boolean get() = resync?.isShown == true

val State.errorMessage: String?
    get() {
        val loadError = loadError ?: return null
        if (isDraft || initialTranscriptIsFresh || isRunning || queuedInputs.isNotEmpty() || isTranscriptLoading || items.isNotEmpty()) {
            return null
        }
        return loadError
    }

/** iOS `errorMessage`'s setter: writes [State.loadError]. */
fun State.withErrorMessage(value: String?): State = copy(loadError = value)

val State.effectiveDirectory: String? get() = session?.directory ?: draftProject?.directory

// endregion

// region Dictation (forwarding to `dictation`)

fun State.updateDictation(transform: (DictationFeature.State) -> DictationFeature.State): State = copy(dictation = transform(dictation))

val State.dictationPhase: DictationFeature.Phase get() = dictation.phase
fun State.withDictationPhase(value: DictationFeature.Phase): State = updateDictation { it.copy(phase = value) }
val State.dictationError: String? get() = dictation.error
fun State.withDictationError(value: String?): State = updateDictation { it.copy(error = value) }
val State.dictationBase: String get() = dictation.base
fun State.withDictationBase(value: String): State = updateDictation { it.copy(base = value) }
val State.dictationRecording: Int get() = dictation.recording
fun State.withDictationRecording(value: Int): State = updateDictation { it.copy(recording = value) }
val State.dictationPress: Int get() = dictation.press
fun State.withDictationPress(value: Int): State = updateDictation { it.copy(press = value) }
val State.sendingDictation: DictationFeature.Sending? get() = dictation.sending
fun State.withSendingDictation(value: DictationFeature.Sending?): State = updateDictation { it.copy(sending = value) }
val State.dictationEndedAt: Instant? get() = dictation.endedAt
fun State.withDictationEndedAt(value: Instant?): State = updateDictation { it.copy(endedAt = value) }
val State.dictationHold: DictationFeature.Hold? get() = dictation.hold
fun State.withDictationHold(value: DictationFeature.Hold?): State = updateDictation { it.copy(hold = value) }
/** A dictated message on its way to Send: a request arriving meanwhile waits for it. */
val State.dictationSendPending: Boolean get() = dictation.sendPending
fun State.withDictationSendPending(value: Boolean): State = updateDictation { it.copy(sendPending = value) }
/** An answer's recording carried here from the screen it answered on, to stop as this screen's task starts. */
val State.discardedDictation: DictationFeature.Discarded? get() = dictation.discarded
fun State.withDiscardedDictation(value: DictationFeature.Discarded?): State = updateDictation { it.copy(discarded = value) }

// endregion

// region Attachments (forwarding to `composerAttachments`)

fun State.updateComposerAttachments(transform: (ComposerAttachmentsFeature.State) -> ComposerAttachmentsFeature.State): State =
    copy(composerAttachments = transform(composerAttachments))

val State.pendingAttachments: List<OpenCodeOutgoingAttachment> get() = composerAttachments.pendingAttachments
fun State.withPendingAttachments(value: List<OpenCodeOutgoingAttachment>): State =
    updateComposerAttachments { it.copy(pendingAttachments = value) }
/** Non-image files: uploading, uploaded (sent as path references), or failed. */
val State.pendingFiles: List<ComposerFileAttachment> get() = composerAttachments.pendingFiles
fun State.withPendingFiles(value: List<ComposerFileAttachment>): State = updateComposerAttachments { it.copy(pendingFiles = value) }
/** A location from the + menu, waiting to go with the next message. */
val State.pendingLocation: SharedLocation? get() = composerAttachments.pendingLocation
fun State.withPendingLocation(value: SharedLocation?): State = updateComposerAttachments { it.copy(pendingLocation = value) }
/** The + menu's Location is finding the phone. */
val State.isLocating: Boolean get() = composerAttachments.isLocating
fun State.withIsLocating(value: Boolean): State = updateComposerAttachments { it.copy(isLocating = value) }
/** Why the last Location couldn't be shared, until dismissed. */
val State.locationError: String? get() = composerAttachments.locationError
fun State.withLocationError(value: String?): State = updateComposerAttachments { it.copy(locationError = value) }
/** Send was tapped while files were still uploading; the message goes with this delivery once they land. */
val State.sendAfterUploads: OpenCodeDelivery? get() = composerAttachments.sendAfterUploads
fun State.withSendAfterUploads(value: OpenCodeDelivery?): State = updateComposerAttachments { it.copy(sendAfterUploads = value) }
/** Shared files absorbed into this composer whose uploads haven't started. */
val State.unstartedUploadIDs: List<UUID> get() = composerAttachments.unstartedUploadIDs
fun State.withUnstartedUploadIDs(value: List<UUID>): State = updateComposerAttachments { it.copy(unstartedUploadIDs = value) }

// endregion

// region Requests (forwarding to `requestDock`)

fun State.updateRequestDock(transform: (RequestDockFeature.State<DraftState>) -> RequestDockFeature.State<DraftState>): State =
    copy(requestDock = transform(requestDock))

val State.permissionRequests: List<OpenCodePermissionRequest> get() = requestDock.permissionRequests
fun State.withPermissionRequests(value: List<OpenCodePermissionRequest>): State = updateRequestDock { it.copy(permissionRequests = value) }
val State.formRequests: List<OpenCodeFormRequest> get() = requestDock.formRequests
fun State.withFormRequests(value: List<OpenCodeFormRequest>): State = updateRequestDock { it.copy(formRequests = value) }
val State.formAnswers: Map<String, Map<String, OpenCodeFormValue>> get() = requestDock.formAnswers
fun State.withFormAnswers(value: Map<String, Map<String, OpenCodeFormValue>>): State = updateRequestDock { it.copy(formAnswers = value) }
val State.respondingRequestIDs: Set<String> get() = requestDock.respondingRequestIDs
fun State.withRespondingRequestIDs(value: Set<String>): State = updateRequestDock { it.copy(respondingRequestIDs = value) }
val State.questionPageIndices: Map<String, Int> get() = requestDock.questionPageIndices
fun State.withQuestionPageIndices(value: Map<String, Int>): State = updateRequestDock { it.copy(questionPageIndices = value) }
val State.requestLoadError: String? get() = requestDock.requestLoadError
fun State.withRequestLoadError(value: String?): State = updateRequestDock { it.copy(requestLoadError = value) }
val State.requestLoadAttempt: RequestDockFeature.State.LoadAttempt? get() = requestDock.requestLoadAttempt
fun State.withRequestLoadAttempt(value: RequestDockFeature.State.LoadAttempt?): State = updateRequestDock { it.copy(requestLoadAttempt = value) }
val State.requestFailureAwaitingReconnect: Boolean get() = requestDock.requestFailureAwaitingReconnect
fun State.withRequestFailureAwaitingReconnect(value: Boolean): State =
    updateRequestDock { it.copy(requestFailureAwaitingReconnect = value) }
val State.requestsConnectionIsAvailable: Boolean get() = requestDock.requestsConnectionIsAvailable
fun State.withRequestsConnectionIsAvailable(value: Boolean): State = updateRequestDock { it.copy(requestsConnectionIsAvailable = value) }
val State.requestErrors: Map<String, String> get() = requestDock.requestErrors
fun State.withRequestErrors(value: Map<String, String>): State = updateRequestDock { it.copy(requestErrors = value) }
/** While a pending request docks, the composer is its answer, and what was typed waits here. */
val State.parkedComposer: DraftState? get() = requestDock.parkedComposer
fun State.withParkedComposer(value: DraftState?): State = updateRequestDock { it.copy(parkedComposer = value) }
/** The bar's text for each question page or request it has answered. */
val State.answerDrafts: Map<String, String> get() = requestDock.answerDrafts
/**
 * iOS `answerDraftsIncludingBar` (`Composer/SessionStatePersistence.swift`): [answerDrafts] with the
 * words on the bar now, which are only filed there when the bar moves to another page or request.
 */
val State.answerDraftsIncludingBar: Map<String, String>
    get() {
        val shown = answerShownTarget
        return if (parkedComposer != null && shown != null) answerDrafts + (shown to draft) else answerDrafts
    }
fun State.withAnswerDrafts(value: Map<String, String>): State = updateRequestDock { it.copy(answerDrafts = value) }
/** Whose words the bar holds now (an `answerTarget`). */
val State.answerShownTarget: String? get() = requestDock.answerShownTarget
fun State.withAnswerShownTarget(value: String?): State = updateRequestDock { it.copy(answerShownTarget = value) }
/** The bar's text as last loaded or applied. */
val State.answerBarBaseline: String get() = requestDock.answerBarBaseline
fun State.withAnswerBarBaseline(value: String): State = updateRequestDock { it.copy(answerBarBaseline = value) }
/** The pending request the dock shows; null shows the first. */
val State.selectedRequestID: String? get() = requestDock.selectedRequestID
fun State.withSelectedRequestID(value: String?): State = updateRequestDock { it.copy(selectedRequestID = value) }
/** Requests whose last reply didn't reach the server: their dock reads “Couldn’t send …”. */
val State.failedReplyIDs: Set<String> get() = requestDock.failedReplyIDs
fun State.withFailedReplyIDs(value: Set<String>): State = updateRequestDock { it.copy(failedReplyIDs = value) }
/** A successful reply must not become actionable again through an older poll. */
val State.completedRequestIDs: Set<String> get() = requestDock.completedRequestIDs
fun State.withCompletedRequestIDs(value: Set<String>): State = updateRequestDock { it.copy(completedRequestIDs = value) }

/** The composer as an undo/revert left it (the parked composer's while a request holds the bar). */
val State.undoSnapshot: UndoSnapshot?
    get() = if (parkedComposer != null) parkedComposer?.undoSnapshot else composerAttachments.undoSnapshot

fun State.withUndoSnapshot(value: UndoSnapshot?): State {
    val parked = parkedComposer
    return if (parked != null) withParkedComposer(parked.copy(undoSnapshot = value))
    else updateComposerAttachments { it.copy(undoSnapshot = value) }
}

/** The request the dock shows: the selected one while it's pending, else the first. */
val State.dockRequestID: String? get() = requestDock.dockRequestID

/** Drops requests matching [removing] and any answers, pages, errors, or selection for requests no longer pending. */
fun State.pruneRequests(
    removing: (id: String, sessionID: String) -> Boolean = { _, _ -> false },
    keepingSelection: Set<String> = emptySet(),
): State {
    val (dock, clearedSelection) = requestDock.prune(removing, keepingSelection)
    var next = copy(requestDock = dock)
    if (clearedSelection && next.activePicker == State.ActivePicker.requests) next = next.copy(activePicker = null)
    return next
}

/** Some request dock is (or should be) replacing the composer. */
val State.hasPendingRequests: Boolean get() = requestDock.hasPendingRequests

val State.reviewRequestIDs: List<String> get() = requestDock.reviewRequestIDs

/** Sessions a pending request waits on: their activity glyphs show Waiting for Input. */
val State.waitingSessionIDs: Set<String> get() = requestDock.waitingSessionIDs

/** Transient request failures stay quiet while connectivity is already explaining the outage. */
val State.settledRequestLoadError: String?
    get() = if (!requestsConnectionIsAvailable || requestLoadAttempt != null) null else requestLoadError

// endregion

// region Working run

fun State.updateWorkingRun(old: WorkingRun.Signal): State =
    copy(workingRun = workingRun.update(old, workingRunSignal, items, failedSends.map { it.id }.toSet()))

/** The Working line's cached orb: per detail, and per draft run. */
val State.workingOrbKey: String get() = "$navigationID|" + (workingRun.key ?: session?.id ?: "draft")

val State.workingRunSignal: WorkingRun.Signal
    get() = WorkingRun.Signal(session?.id, isRunning, isInterrupting, items)

/**
 * A reply's text for the browser peek: its prose, one block per line. Tables and images wait for
 * the transcript. (iOS keeps inline styling in an `AttributedString`; this takes the plain text of
 * the reply's Markdown, which Android items carry raw.)
 */
fun State.Companion.peekText(markdown: String): String {
    val result = StringBuilder()
    for (block in OpenCodeMarkdownBlock.blocks(markdown)) {
        val piece = when (val kind = block.kind) {
            is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.Blockquote -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.Heading -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.NumberedItem -> "${kind.marker} ${kind.text.text}"
            is OpenCodeMarkdownBlock.Kind.ListItem -> "${kind.marker} ${kind.text.text}"
            is OpenCodeMarkdownBlock.Kind.Code -> kind.code.source
            else -> null
        }
        if (piece.isNullOrEmpty()) continue
        if (result.isNotEmpty()) result.append('\n')
        result.append(piece)
    }
    return result.toString()
}

// endregion

// region Voice

/**
 * Voice is an opt-in Advanced feature and requires the user's own key for the chosen engine unless
 * Server Voice is active on a supporting server.
 */
val State.voiceRoute: VoiceRoute
    get() {
        val broker = VoiceBroker(
            serverVoice = ExperimentalFeature.serverVoice.isActive(serverVoiceExperiment.value),
            choice = voiceServer.value,
            connected = server,
            saved = { VoiceFeature.savedServers },
        )
        return VoiceRoute.resolve(
            selectedEngine = voiceEngine.value,
            serverVoiceEnabled = broker.isEnabled,
            serverVoiceProvider = serverVoiceProvider.value,
            serverVoiceSupport = broker.support(serverVoiceSupportByServer.value),
            hasOpenAIKey = !openAIAPIKey.value.isNullOrBlank(),
            hasXAIKey = !xaiAPIKey.value.isNullOrBlank(),
        )
    }

val State.isVoiceAvailable: Boolean get() = voiceLabOverride || (voiceModeEnabled.value && voiceRoute.isAvailable)

fun State.Companion.isVoiceAvailable(enabled: Boolean, apiKey: String?): Boolean =
    voiceLabOverride || (enabled && !apiKey.isNullOrBlank())

/**
 * iOS's `#if DEBUG` voice lab switches: a scripted call, a stand-in server, or the lab's key make
 * the call button available. [VoiceLabDemo] answers nothing in release builds.
 */
private val voiceLabOverride: Boolean
    get() = VoiceLabDemo.isEnabled || VoiceLabDemo.hasStandInServer || VoiceLabDemo.labAPIKey != null

/** The model the next send applies: a draft's model for the session it creates, or a pick still waiting in a session. */
val State.sendModel: OpenCodeModelRef? get() = if (session == null) currentModel else pendingModel

/** Hands the call the model its next delegation applies, while it's on this screen's session or draft. */
fun State.syncVoiceModel(): State {
    val model = sendModel
    val voice = voice ?: return this
    if (voice.target.sessionID != session?.id || voice.pendingModel == model) return this
    return copy(voice = voice.copy(pendingModel = model))
}

/** The server has [model] for [sessionID]: a pick it matches no longer waits. */
fun State.modelApplied(model: OpenCodeModelRef, sessionID: String): State {
    var next = this
    val index = familySessions.indexOfFirst { it.id == sessionID }
    if (index >= 0) next = next.copy(familySessions = familySessions.toMutableList().also { it[index] = it[index].copy(model = model) })
    if (next.session?.id == sessionID) {
        var pending = next.pendingModel
        if (pending == model) pending = null
        // An earlier send's model landed after a newer pick went back to this one: the composer's model waits again.
        val current = next.currentModel
        if (current != null && current != model) pending = current
        next = next.copy(
            pendingModel = pending,
            session = next.session?.copy(model = model),
            refresh = next.refresh?.copy(modelChanged = true),
        )
    } else if (next.familyPendingModels[sessionID] == model) {
        next = next.copy(familyPendingModels = next.familyPendingModels - sessionID)
    }
    // A call on a family member off screen holds its own copy.
    val voice = next.voice
    if (voice != null && voice.target.sessionID == sessionID && voice.pendingModel == model) {
        next = next.copy(voice = voice.copy(pendingModel = null))
    }
    return next
}

/** The newest finished compaction in the session a call is on, while the screen shows it. */
val State.voiceCompaction: VoiceFeature.State.CompactionMark?
    get() {
        val session = session ?: return null
        if (voice?.target?.sessionID != session.id) return null
        return completedCompactionMark
    }

/** Projects this screen's (or [targetScreen]'s) model pick, request state, and compaction onto the active call. */
fun State.syncVoiceTarget(targetScreen: State? = null): State {
    val target = targetScreen ?: this
    var voice = voice ?: return this
    if (voice.target.sessionID != target.session?.id) return this
    voice = voice.copy(pendingModel = target.sendModel, targetNeedsInput = target.hasPendingRequests)
    target.completedCompactionMark?.let { mark -> voice = voice.copy(compactions = voice.compactions + (mark.sessionID to mark.compactionID)) }
    return copy(voice = voice)
}

/** Enforces cross-field invariants after a parent constructs, restores, or retargets a screen. */
fun State.reconcileInvariants(targetScreen: State? = null): State {
    var next = this
    if (!next.wantsRequestDock && next.isAnsweringRequest) next = next.endAnswering()
    return next.syncVoiceTarget(targetScreen)
}

/** The call's bar and its notification name its session. */
fun State.renameVoiceTarget(sessionID: String, title: String): State {
    val resolved = VoiceFeature.State.usableTitle(title) ?: return this
    val voice = voice ?: return this
    if (voice.target.sessionID != sessionID || voice.sessionTitle == resolved) return this
    return copy(voice = voice.copy(sessionTitle = resolved))
}

/** A call is connecting, live, or leaving: its bar holds the composer's place and its audio holds the session. */
val State.callOwnsAudio: Boolean get() = voice?.let { !it.phase.showsCard } ?: false

/** Whether tapping the composer's call button starts a call rather than opening the project picker or Settings. */
val State.canStartCallFromTap: Boolean
    get() {
        if (voice != null) return false
        if (callsFromRoot) return session != null && isVoiceAvailable
        return session != null || draftProject != null
    }

/** A voice call vouches for its pinned session from admission until the last delegated prompt answers. */
private val State.isRunningVoiceWork: Boolean
    get() {
        val voice = voice ?: return false
        if (voice.target.sessionID != session?.id) return false
        return voice.hasAdmittedWork
    }

// endregion

// region Session, context and family

val State.contextLimit: Int?
    get() {
        val usage = contextUsage ?: return null
        return models.model(usage.model)?.contextLimit
    }

/** The share of the window used, as the TUI's percent; null hides the ring. */
val State.contextFraction: Double?
    get() {
        val usage = contextUsage ?: return null
        val limit = contextLimit ?: return null
        return usage.tokens.toDouble() / limit.toDouble()
    }

/** A root's spend includes its subagents', as in the TUI. */
val State.sessionCost: Double?
    get() {
        val session = session ?: return null
        if (session.parentID != null) return session.cost
        val total = (session.cost ?: 0.0) + familySessions.filter { it.id != session.id }.sumOf { it.cost ?: 0.0 }
        return if (total > 0) total else null
    }

val State.isDraft: Boolean get() = session == null

/** The screen's task identity: each server, visit, and session runs `task` once, except a draft becoming its own session. */
val State.taskIdentity: State.TaskIdentity
    get() {
        val continues = session != null && session?.id == draftBecameSessionID
        return State.TaskIdentity(server.persistenceID, visit, if (continues) null else session?.id)
    }

/** The Project sheet, from the top: search cleared, no folder pushed. */
fun State.openProjectPicker(): State =
    copy(activePicker = State.ActivePicker.project, projectFilter = "", folderBrowser = FolderBrowserFeature.State(server = server))

/** Typed admission and queued prompts bridge gaps in server execution; a voice call also owns work until its replies arrive. */
val State.isRunning: Boolean get() = isAwaitingReply || isExternallyActive || isRunningVoiceWork

/** Busy descendants are visible even after this session's own run ends. */
val State.activeSubagents: List<OpenCodeSession>
    get() {
        val selectedID = session?.id ?: return emptyList()
        val parents = HashMap<String, String>()
        for (member in familySessions) member.parentID?.let { parents[member.id] = it }
        return familySessions.filter {
            it.id != selectedID && it.id in activeFamilySessionIDs &&
                selectedID in SessionActivity.ancestors(setOf(it.id), parents)
        }.sortedBy { it.id }
    }

val State.isBusy: Boolean get() = isRunning || activeSubagents.isNotEmpty()
val State.hasBackgroundWork: Boolean get() = activeSubagents.isNotEmpty() || backgroundShells.isNotEmpty()

val State.transcriptSubagents: Map<String, TranscriptSubagent>
    get() {
        val selectedID = session?.id
        val waiting = waitingSessionIDs
        val result = LinkedHashMap<String, TranscriptSubagent>()
        for (member in familySessions) {
            if (member.id == selectedID) continue
            result[member.id] = TranscriptSubagent(
                title = member.title,
                agent = member.agent,
                isKnown = true,
                isRunning = member.id in activeFamilySessionIDs,
                needsInput = member.id in waiting,
            )
        }
        val hasUnknownActive = activeFamilySessionIDs.any { it != selectedID && result[it] == null }
        val hasUnknownWaiting = waiting.any { it != selectedID && result[it] == null }
        if (!hasUnknownActive && !hasUnknownWaiting) return result
        val referencedIDs = subagentToolSessionIDs
        for (id in activeFamilySessionIDs + waiting) {
            if (id == selectedID || result[id] != null || id !in referencedIDs) continue
            result[id] = TranscriptSubagent(
                title = null,
                agent = null,
                isKnown = false,
                isRunning = id in activeFamilySessionIDs,
                needsInput = id in waiting,
            )
        }
        return result
    }

/** Returns the state with a Session sheet for the current session, and whether there is one. */
fun State.ensureSessionSheet(): Pair<State, Boolean> {
    val session = session ?: return this to false
    var sheet = sessionSheet
    if (sheet?.sessionID != session.id) sheet = SessionSheetFeature.State(server = server, sessionID = session.id)
    return copy(sessionSheet = sheet.copy(pendingModel = pendingModel)) to true
}

/** Records a session in this family as read up to its idle watermark. */
fun State.markViewed(session: OpenCodeSession): State {
    val idle = session.idleMilliseconds ?: return this
    var next = this
    val shown = next.session
    if (shown != null && shown.id == session.id) {
        next = next.copy(session = shown.copy(viewedMilliseconds = maxOf(shown.viewedMilliseconds ?: 0, idle)))
    }
    val index = next.familySessions.indexOfFirst { it.id == session.id }
    if (index >= 0) {
        val member = next.familySessions[index]
        next = next.copy(
            familySessions = next.familySessions.toMutableList().also {
                it[index] = member.copy(viewedMilliseconds = maxOf(member.viewedMilliseconds ?: 0, idle))
            },
        )
    }
    return next
}

/** The composer follows the model the server reports for the session, unless a pick of its own is still waiting. */
fun State.adoptServerModel(model: OpenCodeModelRef?): State {
    if (model == null || pendingModel != null || currentModel == model) return this
    return copy(currentModel = model)
}

fun State.setRevertMessageID(messageID: String?): State {
    var next = copy(session = session?.copy(revertMessageID = messageID))
    val id = next.session?.id
    val index = if (id == null) -1 else next.familySessions.indexOfFirst { it.id == id }
    if (index >= 0) {
        next = next.copy(familySessions = next.familySessions.toMutableList().also { it[index] = it[index].copy(revertMessageID = messageID) })
    }
    return next.copy(refresh = next.refresh?.copy(revertChanged = true))
}

fun State.updateObservedSession(fresh: OpenCodeSession): State {
    val observed = fresh.keepingFirstPrompt(session)
    var next = this
    if (observed.revertMessageID == null) next = next.clearUntouchedUndoDraft()
    next = next.copy(session = observed)
    val index = next.familySessions.indexOfFirst { it.id == observed.id }
    if (index >= 0) next = next.copy(familySessions = next.familySessions.toMutableList().also { it[index] = observed })
    return next.renameVoiceTarget(observed.id, observed.title)
}

/**
 * The session on screen (and its family copy) learn their first prompt once the loaded transcript
 * reaches the start, so an unnamed session reads as it in the header and, through Home, everywhere.
 */
fun State.learningFirstPrompt(): State {
    val shown = session ?: return this
    if (shown.firstPrompt != null || !shown.isUntitled) return this
    return learningFirstPrompt(SessionTitle.firstPrompt(items, reachesStart = earlierHistory == State.EarlierHistory.Exhausted))
}

/** The session on screen (and its family copy) learn [preview] as their first prompt, if unnamed and unknown. */
fun State.learningFirstPrompt(preview: String?): State {
    val shown = session ?: return this
    if (preview == null || shown.firstPrompt != null || !shown.isUntitled) return this
    val learned = shown.learningFirstPrompt(preview)
    val index = familySessions.indexOfFirst { it.id == learned.id }
    return copy(
        session = learned,
        familySessions = if (index < 0) familySessions else familySessions.toMutableList().also { it[index] = it[index].learningFirstPrompt(preview) },
    )
}

/** Whether [id] (an attachment's `UUID` string) was among the attachments the undo restored. */
private fun UndoSnapshot.hasAttachment(id: String): Boolean =
    runCatching { UUID.fromString(id) }.getOrNull()?.let { it in attachmentIDs } == true

fun State.clearUntouchedUndoDraft(): State {
    val currentDraft = parkedComposer?.text ?: draft
    val snapshot = undoSnapshot
    var next = this
    if (snapshot != null && currentDraft == snapshot.restoredDraft) {
        next = next.applySessionComposer { composer ->
            composer.copy(
                text = snapshot.priorDraft,
                location = if (composer.location == snapshot.restoredLocation) snapshot.priorLocation else composer.location,
                attachments = composer.attachments.filterNot { snapshot.hasAttachment(it.id) },
                files = composer.files.filterNot { it.id in snapshot.fileIDs },
            )
        }
        if (next.parkedComposer != null) {
            next = next.withPendingAttachments(next.pendingAttachments.filterNot { snapshot.hasAttachment(it.id) })
                .withPendingFiles(next.pendingFiles.filterNot { it.id in snapshot.fileIDs })
        }
    }
    return next.withUndoSnapshot(null)
}

/** The session moved on: Stop and every run-state read in flight are stale. */
fun State.supersedeRunWork(): State = copy(runWorkGeneration = runWorkGeneration + 1, isInterrupting = false, stopAttempt = null)

fun State.resetLiveProjection(): State = copy(liveText = emptyMap(), gappedLiveText = emptySet(), projectedMessageIDs = emptySet(), lastProjectedMessageID = null)

val State.showsMoveToBackground: Boolean
    get() {
        val key = movableWorkKey ?: return false
        if (!offersManualMoveToBackground || key in movedWorkKeys) return false
        return foregroundWork?.key == key
    }

// endregion

// region Sending

/** A send the server hasn't taken yet. It never blocks the next one: the outbox delivers them in order. */
val State.isSubmittingPrompt: Boolean get() = promptStates.values.any { it.admission == State.PromptState.Admission.sent }

val State.hasPendingLocalPrompt: Boolean
    get() = isSubmittingPrompt || queuedInputs.any { promptStates[it.id]?.ownsRun == true }

/** A send from here that will still reach the agent without the reader acting. */
val State.hasMovingLocalPrompt: Boolean
    get() = queuedInputs.any {
        promptStates[it.id]?.ownsRun == true && promptStates[it.id]?.admission == State.PromptState.Admission.admitted
    } || (isSubmittingPrompt && failedSends.isEmpty())

/** A send from here the agent takes without waiting for a run to end well. */
val State.hasSteerOnItsWay: Boolean
    get() = queuedInputs.any {
        it.delivery == OpenCodeDelivery.Steer && promptStates[it.id]?.ownsRun == true &&
            promptStates[it.id]?.admission == State.PromptState.Admission.admitted
    } || (failedSends.isEmpty() && promptStates.any { (id, prompt) ->
        prompt.admission == State.PromptState.Admission.sent && (deliveryOverrides[id] ?: sentDeliveries[id]) != OpenCodeDelivery.Queue
    })

val State.supportsFileUploads: Boolean get() = server.persistenceID !in uploadUnsupportedServerIDs.value
val State.isUploadingFiles: Boolean get() = composerAttachments.isUploadingFiles
val State.isWaitingForUploads: Boolean get() = composerAttachments.isWaitingForUploads

/** Something to send: text, inline media, or files. */
val State.hasPromptContent: Boolean get() = draft.isNotBlank() || composerAttachments.hasContent

/** The composer's text, inline media, and files, saved while elsewhere. */
val State.draftState: DraftState
    get() = DraftState(
        text = draft,
        attachments = pendingAttachments,
        files = pendingFiles,
        location = pendingLocation,
        chips = autocomplete.chips,
        undoSnapshot = composerAttachments.undoSnapshot,
    )

/** iOS `draftState`'s setter. */
fun State.withDraftState(value: DraftState): State =
    withDraft(value.text).copy(
        autocomplete = autocomplete.copy(chips = value.chips),
        composerAttachments = composerAttachments.copy(
            pendingAttachments = value.attachments,
            pendingFiles = value.files,
            pendingLocation = value.location,
            undoSnapshot = value.undoSnapshot,
        ),
    )

/** Applies [change] to the session's composer (the parked one while a request holds the bar). */
fun State.applySessionComposer(change: (DraftState) -> DraftState): State {
    val composer = change(sessionComposer)
    if (parkedComposer != null) return withParkedComposer(composer)
    return setTyped(composer.text).copy(
        autocomplete = autocomplete.copy(chips = composer.chips),
        composerAttachments = composerAttachments.copy(
            pendingAttachments = composer.attachments,
            pendingFiles = composer.files,
            pendingLocation = composer.location,
            undoSnapshot = composer.undoSnapshot,
        ),
    )
}

/** Restores a saved composer. An upload still in flight when it was saved comes back as failed and retryable. */
fun State.restoreDraft(saved: DraftState): State {
    var next = applySessionComposer {
        saved.copy(
            files = saved.files.map { file ->
                if (!file.isUploading) file else file.copy(status = ComposerFileAttachment.Status.Failed("Couldn’t upload"))
            },
        )
    }
    if (next.parkedComposer == null && next.hasLiveDictation) next = next.clearEditedUndoDraft()
    return next
}

/** A failed upload blocks sending until it is retried or removed. */
val State.canSendPrompt: Boolean
    get() = !isWaitingForUploads && hasPromptContent && pendingFiles.none { it.isFailed }

/** Whether the selected model reads [input] ("pdf", "image") natively. */
fun State.currentModelReads(input: String): Boolean {
    val current = currentModel ?: return false
    return models.model(current)?.inputs?.contains(input) == true
}

/** Text typed into the composer by the app: a running recording's words follow it. */
fun State.setTyped(text: String): State = if (hasLiveDictation) rebaseDictation(text) else withDraft(text)

/** Text the app adds below what's already typed, never over it, and once. */
fun State.typeBelow(text: String): State {
    val added = text.trim()
    return when {
        draft.isEmpty() -> setTyped(text)
        !hasLiveDictation && added.isNotEmpty() && !draft.contains(added) -> withDraft(SharedComposerDraft.joined(draft, text))
        else -> this
    }
}

/** Words that won't be sent after all land in this composer, after what's there, with their attachments. */
fun State.keepAsDraft(kept: DraftState): State {
    val next = applySessionComposer { it.appending(kept) }
    return if (next.parkedComposer == null) next.clearEditedUndoDraft() else next
}

// endregion

// region Dictation

val State.isDictating: Boolean get() = dictation.isDictating
val State.isPreparingDictation: Boolean get() = dictation.isPreparing
val State.isFinishingDictation: Boolean get() = dictation.isFinishing

/** Recording, or finalizing its last words. */
val State.hasLiveDictation: Boolean get() = dictation.hasLive

/** Raise to Talk never starts a call while the mic records or finalizes a message, nor within the grace after one ends. */
fun State.dictationBlocksRaise(now: () -> Instant = { dependency(DateClient).now() }): Boolean = dictation.blocksRaise(now)

/** Shows the words after what was typed: in the hold while the mic is held or locked, otherwise in the draft. */
fun State.showDictation(transcript: String): State {
    val (dictation, text) = dictation.show(transcript)
    val next = copy(dictation = dictation)
    return if (text != null) next.withDraft(text) else next
}

/** The composer as typed, without the running recording's words. */
val State.composerWithoutDictation: DraftState
    get() {
        val composer = draftState
        if (hasLiveDictation && dictationHold == null) {
            return composer.copy(text = if (dictationBase.endsWith(" ")) dictationBase.dropLast(1) else dictationBase)
        }
        return composer
    }

/** The typed text changed under a running recording: its words follow the new text. */
fun State.rebaseDictation(typed: String): State {
    val (dictation, text) = dictation.rebase(typed, draft)
    val next = copy(dictation = dictation)
    return if (text != null) next.withDraft(text) else next
}

val State.carriedDictation: DictationFeature.Carried?
    get() {
        if (!hasLiveDictation && sendingDictation == null) return null
        return DictationFeature.Carried(
            phase = dictationPhase,
            recording = dictationRecording,
            press = dictationPress,
            hold = dictationHold,
            transcript = dictation.transcript(draft),
            sending = sendingDictation,
            isAnswer = isAnsweringRequest && hasLiveDictation,
        )
    }

/** Takes over a recording from the screen this one replaces. */
fun State.adoptDictation(carried: DictationFeature.Carried): State {
    var next = withDictationRecording(carried.recording).withDictationPress(carried.press).withSendingDictation(carried.sending)
    if (carried.phase == DictationFeature.Phase.Idle) return next
    if (carried.isAnswer) {
        // An answer's words never land in another session: the mic stops when this screen's task starts.
        return next.withDiscardedDictation(DictationFeature.Discarded(carried.recording, carried.press))
            .withDictationRecording(next.dictationRecording + 1)
    }
    // A message's recording is never an answer: the request waits for it.
    if (next.isAnsweringRequest) next = next.endAnswering()
    val phase = carried.phase
    next = if (phase is DictationFeature.Phase.Finishing && phase.send != null) {
        next.withDictationPhase(DictationFeature.Phase.Finishing(send = null))
    } else {
        next.withDictationPhase(phase)
    }
    next = next.withDictationBase(DictationFeature.State.base(next.draft))
    carried.hold?.let { carriedHold ->
        var hold = carriedHold
        if (!hold.isLocked) hold = hold.copy(isLocked = true, armed = null)
        next = next.withDictationHold(hold.copy(text = next.draft))
    }
    return next.showDictation(carried.transcript)
}

// endregion

// region Models and projects

/** The current model by its display name, never its slug once the catalog has it. */
val State.currentModelName: String
    get() {
        val current = currentModel ?: return "Model"
        return models.model(current)?.name ?: current.id
    }

/** The current model's catalog entry. */
val State.currentModelInfo: OpenCodeModel? get() = currentModel?.let { models.model(it) }

/** "Claude Haiku 5.5 · High": the model and its reasoning variant, if any. */
val State.currentModelLabel: String
    get() {
        val variant = currentModel?.variant
        if (variant == null || variant == "default") return currentModelName
        return "$currentModelName · ${ModelVariant.label(variant)}"
    }

val State.filteredModels: List<OpenCodeModel> get() = ModelSearch.rank(pickerModels, modelFilter, favoriteModelIDs.value)

/** This server's model visibility while Hide Unused Models is on; null lists the whole catalog. */
val State.modelVisibility: ModelVisibility?
    get() {
        if (!ExperimentalFeature.hideModels.isActive(hideModelsExperiment.value)) return null
        return modelVisibilityByServer.value[server.persistenceID] ?: ModelVisibility()
    }

/** The models the pickers list: the visible ones and the current model, or all of them after Show All. */
val State.pickerModels: List<OpenCodeModel>
    get() {
        val visibility = modelVisibility
        if (visibility == null || showsAllModels) return models
        return visibility.visible(models, currentModelInfo?.id)
    }

/** How many models Show All adds; zero hides the button. */
val State.hiddenModelCount: Int
    get() {
        val visibility = modelVisibility ?: return 0
        return models.size - visibility.visible(models, currentModelInfo?.id).size
    }

val State.isFilteringModels: Boolean get() = modelFilter.trim().isNotEmpty()

val State.favoriteModels: List<OpenCodeModel> get() = filteredModels.filter { it.id in favoriteModelIDs.value }

val State.unfavoriteModels: List<OpenCodeModel> get() = filteredModels.filter { it.id !in favoriteModelIDs.value }

val State.filteredProjects: List<OpenCodeProject> get() = uniqueProjects.filtered(projectFilter) { listOf(it.name, it.directory) }

/** Each folder once, most recent first: the server can list one folder under more than one project. */
private val State.uniqueProjects: List<OpenCodeProject>
    get() {
        val directories = HashSet<String>()
        return projects.filter { directories.add(it.directory) }
    }

/**
 * Where a session whose folder is gone can move: its project's folder, when that's somewhere
 * else and isn't the home folder (iOS `missingFolderMoveTarget`).
 */
val State.missingFolderMoveTarget: OpenCodeProject?
    get() {
        val session = session ?: return null
        val project = SessionListContext.project(session, projects) ?: return null
        if (project.directory == session.directory || project.isHomeFolder) return null
        return project
    }

/** The projects a draft lists above its composer: the five most recent, with the chosen one first when it isn't among them. */
val State.recentProjects: List<OpenCodeProject>
    get() {
        val recent = uniqueProjects.take(State.recentProjectLimit)
        val chosen = draftProject ?: return recent
        if (recent.any { it.id == chosen.id || it.directory == chosen.directory }) return recent
        return listOf(chosen) + recent.take(State.recentProjectLimit - 1)
    }

/** A draft's project can change until its first send leaves; a call started here creates the session in it straight away. */
val State.canChooseProject: Boolean get() = session == null && outboxDraftID == null && callSessionID == null && items.isEmpty()

val State.queuedInputRows: List<OpenCodeQueuedInput>
    get() = queuedInputs.map { input -> OpenCodeQueuedInput(input, queuedInputImages[input.id]?.takeIf { it.isNotEmpty() } ?: input.images) }

// endregion

// region Transcript window

val State.isLoadingEarlier: Boolean get() = earlierHistory is State.EarlierHistory.Loading

/** The transcript as the server confirmed it, for caches: no message still in flight or failed. */
val State.cacheableItems: List<OpenCodeTranscriptItem> get() = items(optimisticPromptIDs + failedSends.ids)

/** Without failed sends' bubbles, which `restoreFailedSends` brings back. */
val State.itemsWithoutFailedSends: List<OpenCodeTranscriptItem> get() = items(failedSends.ids.toSet())

/** The first loaded row the transcript shows. */
val State.windowStart: Int
    get() {
        val latestStart = maxOf(0, items.size - visibleLimit)
        val start = minOf(latestStart, browsingStartIndex ?: latestStart)
        return TranscriptEntry.entryStart(start, items)
    }

val State.visibleItems: List<OpenCodeTranscriptItem>
    get() = hidingUndone(items.subList(minOf(windowStart, items.size), items.size))

val State.firstVisibleItemID: String?
    get() {
        if (session?.revertMessageID != null) return visibleItems.firstOrNull()?.id
        val start = windowStart
        return items.getOrNull(start)?.id
    }

/** Once drawn, keeps the window starting at the row it started at before an update (OC-375/386). */
fun State.retainRenderedWindowStart(previousFirstID: String?): State {
    if (!hasRenderedFirstContentFrame || previousFirstID == null) return this
    val index = if (items.firstOrNull()?.id == previousFirstID) {
        0
    } else {
        items.indexOfFirst { it.id == previousFirstID }.takeIf { it >= 0 }
            ?: items.indexOfFirst {
                val messageID = SessionFeature.messageID(it)
                messageID == previousFirstID || previousFirstID.startsWith("$messageID-")
            }.takeIf { it >= 0 }
            ?: return this
    }
    // A row removed below it must not pull an earlier one into view: then exactly the rows from it on.
    val nextLimit = if (windowStart < index) items.size - index else maxOf(visibleLimit, items.size - index)
    return if (visibleLimit != nextLimit) copy(visibleLimit = nextLimit) else this
}

fun State.pinBrowsingWindowIfNeeded(): State {
    if (browsingWindowStartID == null) return this
    val start = windowStart
    val nextID = items.getOrNull(start)?.id ?: firstVisibleItemID
    return if (browsingWindowStartID != nextID) copy(browsingWindowStartID = nextID) else this
}

val State.hasEarlierItems: Boolean
    get() = items.size > visibleLimit ||
        // A restored cache can be browsed immediately, but do not start the cursor chain from its stale edge.
        (earlierHistory != State.EarlierHistory.Exhausted && !isLoading &&
            ((!hasRenderedFirstContentFrame &&
                (earlierHistory != State.EarlierHistory.UnknownEdge || items.isEmpty() ||
                    (refresh == null && items.size >= SessionFeature.initialFetchLimit))) ||
                initialTranscriptIsFresh) &&
            !(items.isEmpty() && initialTranscriptIsFresh && earlierHistory == State.EarlierHistory.UnknownEdge))

/** Whether a transcript request is in flight or the first load has not yet been attempted. */
val State.isTranscriptLoading: Boolean
    get() = isLoading || isLoadingEarlier ||
        (!initialTranscriptIsFresh &&
            (refresh != null || initialTranscriptRequestIsExternal || (!isDraft && nextRefreshID == 0 && loadError == null)) &&
            items.isEmpty())

/** Whether the empty-session placeholder may be shown. */
val State.showsEmptyState: Boolean
    get() = !isDraft && initialTranscriptIsFresh && items.isEmpty() && queuedInputs.isEmpty() &&
        !isRunning && !isTranscriptLoading && !hasEarlierItems && earlierHistoryError == null

// endregion
