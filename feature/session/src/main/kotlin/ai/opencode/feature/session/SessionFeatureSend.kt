package ai.opencode.feature.session

import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.composer.AutocompleteItem
import ai.opencode.feature.composer.ComposerCommandResult
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.core.store.map
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.DeliveryOutcome
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.UndoSnapshot
import ai.opencode.feature.composer.DraftState
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerAutocompleteChip
import ai.opencode.feature.composer.ComposerAutocompleteDraftResolution
import ai.opencode.feature.composer.ComposerCommandFailure
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DictationHoldRelease
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.server.model.PageShareRequest
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PastedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File
import java.util.UUID

// Port of iOS `SessionFeature+Send.swift`: sends, failed sends, run errors, and undo.

/** Sends, failed sends, run errors, and undo. Null: the action passes on. */
internal fun SessionFeature.reduceSending(state: State, action: Action): Reduced<State, Action>? {
    when (action) {
        Action.SharedSendRequested -> {
            val send = state.sharedSend ?: return Reduced(state)
            var next = state.copy(sharedSend = null)
            // A pending request holds the composer: the share stays a draft.
            if (next.hasPendingRequests) return Reduced(next.copy(composerFocusRequest = next.composerFocusRequest + 1))
            next = next.copy(sharedMessageID = send.messageID, sharedSessionID = send.sessionID)
            // No finger asked for it: no tap.
            return sendComposer(OpenCodeDelivery.Steer, next, isDeferred = true)
        }

        Action.SendTapped, Action.QueueTapped -> {
            val delivery = if (action == Action.QueueTapped) OpenCodeDelivery.Queue else OpenCodeDelivery.Steer
            // Dictated into the draft, then sent: the audio held for another press isn't needed.
            val sent = sendComposer(delivery, state)
            return Reduced(sent.state, Effect.merge(releaseDictationHold(DictationHoldRelease.Sent), sent.effect))
        }

        is Action.FailedSendRetryTapped -> {
            // A move on its way sends it again once the session has moved.
            if (state.movingFailedSendID != null) return Reduced(state)
            val failed = state.failedSends.withID(action.id) ?: return Reduced(state)
            if (state.session == null && state.draftProject == null) return Reduced(state)
            haptics.play(Haptic.tap)
            return retry(failed, state)
        }

        is Action.FailedSendMoveTapped -> {
            val id = action.id
            if (state.movingFailedSendID != null) return Reduced(state)
            val failed = state.failedSends.withID(id)?.takeIf { it.isFolderGone } ?: return Reduced(state)
            val session = state.session ?: return Reduced(state)
            val target = state.missingFolderMoveTarget ?: return Reduced(state)
            haptics.play(Haptic.tap)
            val server = state.server
            AppLog.send.notice { "$id: ${session.id}'s folder is gone, moving it to its project's folder" }
            return Reduced(
                state.copy(movingFailedSendID = id),
                Effect.run<Action> { send ->
                    try {
                        openCodeClient.moveSession(server, session.id, target.directory)
                        send(Action.MissingFolderMoved(sessionID = session.id, messageID = failed.id, directory = target.directory))
                    } catch (error: OpenCodeClientError) {
                        send(Action.MissingFolderMoveFailed(sessionID = session.id, messageID = failed.id, error = error))
                    }
                }.cancellable(SessionFeature.CancelID.MissingFolderMove, cancelInFlight = true),
            )
        }

        is Action.MissingFolderMoved -> {
            var next = state
            if (next.movingFailedSendID == action.messageID) next = next.copy(movingFailedSendID = null)
            AppLog.send.notice { "${action.sessionID} moved to its project's folder" }
            // The server's move event says the same; the send can't wait for it.
            val moved = sessionMoved(action.sessionID, action.directory, next)
            val failed = moved.state.failedSends.withID(action.messageID)
            if (moved.state.session?.id != action.sessionID || failed == null) return moved
            val retried = retry(failed, moved.state)
            return Reduced(retried.state, Effect.merge(moved.effect, retried.effect))
        }

        is Action.MissingFolderMoveFailed -> {
            var next = state
            if (next.movingFailedSendID == action.messageID) next = next.copy(movingFailedSendID = null)
            if (next.session?.id != action.sessionID || next.failedSends.withID(action.messageID) == null) return Reduced(next)
            AppLog.send.error { "${action.sessionID} couldn’t move to its project's folder: ${action.error.logSummary}" }
            next = next.copy(
                failedSends = next.failedSends.map { if (it.id == action.messageID) it.copy(reason = FailedSend.moveFailedReason) else it },
            )
            haptics.play(Haptic.error)
            return Reduced(next)
        }

        is Action.SessionMoved -> return sessionMoved(action.sessionID, action.directory, state)

        is Action.FailedSendEditTapped -> {
            val failed = state.failedSends.withID(action.id) ?: return Reduced(state)
            haptics.play(Haptic.tap)
            var next = removeFailedSend(action.id, state)
            val cache = publishCache(next)
            next = returnToComposer(failed.pending, next)
            // The sends that waited behind it go on.
            if (next.hasMovingLocalPrompt) next = next.copy(isAwaitingReply = true)
            return Reduced(next, cache)
        }

        is Action.FailedSendDeleteTapped -> {
            if (state.failedSends.withID(action.id) == null) return Reduced(state)
            haptics.play(Haptic.tap)
            var next = removeFailedSend(action.id, state)
            if (next.hasMovingLocalPrompt) next = next.copy(isAwaitingReply = true)
            return Reduced(next, publishCache(next))
        }

        is Action.RunErrorActionTapped -> {
            val session = state.session
            if (action.action !in state.actionableRunErrorActions || session == null) return Reduced(state)
            return when (action.action) {
                OpenCodeRunError.Action.TryAgain -> {
                    // V2 has no retry: the agent picks up from where the run stopped, as a person
                    // would ask it in the TUI.
                    haptics.play(Haptic.tap)
                    submit(
                        messageID = serverID.messageID(), text = SessionFeature.tryAgainPrompt, attachments = emptyList(),
                        files = emptyList(), delivery = OpenCodeDelivery.Steer, isRetry = false, state = state,
                    )
                }
                OpenCodeRunError.Action.Edit -> {
                    val message = state.editableLastMessage
                    if (message == null || state.isUndoingLastMessage) return Reduced(state)
                    haptics.play(Haptic.tap)
                    val server = state.server
                    val client = openCodeClient
                    // Like the TUI's /undo: hide the message and what followed, roll back its file
                    // changes, and give its words back to edit.
                    Reduced(
                        state.copy(isUndoingLastMessage = true),
                        Effect.run<Action> { send ->
                            val error = try {
                                client.stageRevert(server, session.id, message.id)
                                null
                            } catch (error: OpenCodeClientError) {
                                error
                            }
                            send(Action.LastMessageUndone(session.id, message.id, message.text, error))
                        }.cancellable(SessionFeature.CancelID.Revert, cancelInFlight = true),
                    )
                }
                OpenCodeRunError.Action.Compact -> Reduced(state, Effect.send(Action.SessionMenu(SessionSheetFeature.Action.CompactTapped)))
                OpenCodeRunError.Action.SwitchModel -> Reduced(state, Effect.send(Action.ModelButtonTapped))
            }
        }

        is Action.LastMessageUndone -> {
            if (state.session?.id != action.sessionID) return Reduced(state)
            if (state.isRunning || !state.isUndoingLastMessage) return Reduced(state.copy(isUndoingLastMessage = false))
            var next = state.copy(isUndoingLastMessage = false)
            val error = action.error
            if (error != null) {
                haptics.play(Haptic.error)
                return Reduced(appendInfo(next, "Couldn’t undo the message: ${error.message}"))
            }
            next = next.setRevertMessageID(action.messageID)
            next = fillComposerDraft(
                PendingPrompt(messageID = action.messageID, text = action.text, attachments = emptyList(), requestEpoch = 0),
                next,
                focus = false,
            )
            return Reduced(next, publishCache(next))
        }

        is Action.RevertToHereTapped -> {
            val session = state.session
            if (state.isRunning || state.isUndoingLastMessage || session == null) return Reduced(state)
            val restored = state.restoredPrompt(action.messageID, uuid) ?: return Reduced(state)
            haptics.play(Haptic.confirm)
            return stageRevert(action.messageID, session.id, restored, state)
        }

        is Action.MessageReverted -> {
            if (state.session?.id != action.sessionID) return Reduced(state)
            if (state.isRunning || !state.isUndoingLastMessage) return Reduced(state.copy(isUndoingLastMessage = false))
            var next = state.copy(isUndoingLastMessage = false)
            action.error?.let { return reportRevertError(it, next) }
            next = next.setRevertMessageID(action.messageID)
            next = fillComposerDraft(action.restored, next)
            return Reduced(next, publishCache(next))
        }

        Action.RedoTapped -> {
            val session = state.session
            if (session?.revertMessageID == null) return Reduced(state)
            haptics.play(Haptic.tap)
            val server = state.server
            val client = openCodeClient
            return Reduced(
                state.copy(isUndoingLastMessage = false),
                Effect.run<Action> { send ->
                    val error = try {
                        client.clearRevert(server, session.id)
                        null
                    } catch (error: OpenCodeClientError) {
                        error
                    }
                    send(Action.Redone(session.id, error))
                }.cancellable(SessionFeature.CancelID.Revert, cancelInFlight = true),
            )
        }

        is Action.Redone -> {
            if (state.session?.id != action.sessionID) return Reduced(state)
            action.error?.let { return reportRevertError(it, state) }
            // The turn is back, so its words leave the composer, unless the reader has started
            // editing them (then, like the TUI and desktop, Redo leaves the composer alone).
            val next = state.setRevertMessageID(null).clearUntouchedUndoDraft()
            return Reduced(next, publishCache(next))
        }

        is Action.RevertChanged -> {
            if (state.session?.id != action.sessionID) return Reduced(state)
            var next = state
            if (action.committed) {
                next = next.clearUntouchedUndoDraft().dropUndone().setRevertMessageID(null)
            } else {
                if (action.messageID == null) next = next.clearUntouchedUndoDraft()
                next = next.setRevertMessageID(action.messageID)
            }
            return Reduced(next, Effect.send(Action.RefreshRequested(RefreshReason.Invalidation)))
        }

        is Action.NonSendNewlineInserted -> {
            if (state.hasLiveDictation || state.dictationHold != null) return Reduced(state)
            return Reduced(state.copy(nonSendNewlineDraft = action.text))
        }

        Action.ReturnKeyTapped -> {
            // A multi-line text field inserts "\n" instead of submitting; the view detects that single
            // appended newline and routes it here.
            if (!state.returnKeySends.value || state.nonSendNewlineDraft == state.draft || !state.draft.endsWith("\n")) {
                return Reduced(state)
            }
            val next = state.withDraft(state.draft.dropLast(1))
            // The answer stands as it is: a choice, or the bar's words.
            if (next.isAnsweringRequest) return sendRequestBar(next)
            if (!next.hasPromptContent) return Reduced(next)
            return Reduced(next, Effect.send(Action.SendTapped))
        }

        Action.CommandReturnTapped -> {
            if (state.hasLiveDictation || state.dictationHold != null) return Reduced(state)
            if (state.isAnsweringRequest) return sendRequestBar(state)
            if (!state.canSendPrompt) return Reduced(state)
            return Reduced(state, Effect.send(Action.SendTapped))
        }

        else -> return null
    }
}

/** Sends the composer's draft, or answers the docked request when the bar holds it. */
internal fun SessionFeature.sendComposer(
    delivery: OpenCodeDelivery,
    state: State,
    isDeferred: Boolean = false,
    keepsDictation: Boolean = false,
): Reduced<State, Action> {
    var next = state.withDictationSendPending(false)
    // The bar is a pending request's answer: Send answers it.
    if (next.isAnsweringRequest) return sendRequestBar(next)
    // Earlier sends still on their way never hold this one up: the outbox delivers them in order
    // while each shows at once. A lingering keyboard submit must not turn an answer into chat input.
    // A recorded message finalizing on its own (`sendingDictation`) was sent before the request
    // arrived: it goes as the message it was.
    if (next.hasPendingRequests && !keepsDictation) return Reduced(next)
    LabMarks.mark("send_tap", sessionID = next.session?.id)
    // Both delivery modes are admitted to the inbox. Steering promotes at the next step; an explicit
    // queue waits until the response completes.
    val typed = next.draft.trim()
    val rawDraft = next.draft
    // A shared location rides at the top of the text, where the agent reads it and the transcript
    // turns it into a card.
    val text = next.pendingLocation?.prompt(typed) ?: typed
    val attachments = next.pendingAttachments
    val files = next.pendingFiles
    if (!next.hasPromptContent) return Reduced(forgetSharedSend(next))
    if (next.session == null && next.draftProject == null) return Reduced(forgetSharedSend(next).openProjectPicker())
    if (files.any { it.isFailed }) {
        next = forgetSharedSend(next).withSendAfterUploads(null)
        haptics.play(Haptic.error)
        return Reduced(next)
    }
    if (files.any { it.isUploading }) {
        // The message goes as soon as the last upload lands.
        if (!isDeferred) haptics.play(Haptic.tap)
        return Reduced(next.withSendAfterUploads(delivery))
    }
    next = next.withSendAfterUploads(null)
    if (!isDeferred) haptics.play(Haptic.tap)
    var stopDictating: Effect<Action> = Effect.none()
    if (!keepsDictation) {
        val stopped = stopDictation(next)
        next = stopped.state
        stopDictating = stopped.effect
    }
    next = next.withDictationError(null)
    val savedDraft = next.draftState
    val sentChips = next.autocomplete.chips
    val autocompleteResolution = if (experimentalFeatures(ExperimentalFeature.composerAutocomplete) && sentChips.isNotEmpty()) {
        ComposerAutocompleteDraftResolution.resolve(draft = rawDraft, sentPromptText = text, chips = sentChips)
    } else {
        null
    }
    next = next.copy(autocomplete = next.autocomplete.clearForSend()).withDraftState(DraftState())
    if (autocompleteResolution is ComposerAutocompleteDraftResolution.Command) {
        val command = autocompleteResolution.execution
        next = next.copy(sharedMessageID = null)
        val existingSessionID = next.session?.id
        val draftDirectory = next.draftProject?.directory
        val clientSessionID: String? = if (existingSessionID == null) next.sharedSessionID ?: next.callSessionID ?: serverID.sessionID() else null
        next = next.copy(sharedSessionID = null)
        val (settled, cancelRevert) = next.settleRevertForNewPrompt()
        next = settled.noteOwnSend().supersedeRunWork().copy(browsingWindowStartID = null, isAwaitingReply = true)
        val server = next.server
        val client = openCodeClient
        AppLog.send.info { "command send: session=${existingSessionID ?: clientSessionID ?: "none"} length=${command.argumentsText.length}" }
        return Reduced(
            next,
            Effect.merge(
                stopDictating,
                Effect.cancel(SessionFeature.CancelID.AutocompleteAutoRun),
                cancelRevert,
                Effect.run<Action> { send ->
                    try {
                        val sessionID = if (existingSessionID != null) {
                            existingSessionID
                        } else if (draftDirectory != null) {
                            val created = client.createSession(server, draftDirectory, clientSessionID)
                            send(Action.SessionCreated(created))
                            created.id
                        } else {
                            return@run
                        }
                        client.sendCommand(server, sessionID, command.name, command.argumentsText, command.mentions, delivery)
                        send(Action.Autocomplete(ComposerAutocompleteAction.CommandFinished(ComposerCommandResult.Success(sessionID))))
                    } catch (error: OpenCodeClientError) {
                        send(Action.Autocomplete(ComposerAutocompleteAction.CommandFinished(ComposerCommandResult.Failure(ComposerCommandFailure(error, savedDraft)))))
                    }
                }.cancellable(SessionFeature.CancelID.AutocompleteCommand, cancelInFlight = true),
            ),
        )
    }
    val messageID = next.sharedMessageID ?: serverID.messageID()
    next = next.copy(sharedMessageID = null)
    var mentions: OpenCodeAutocompleteMentions? = null
    var sentText = text
    if (autocompleteResolution is ComposerAutocompleteDraftResolution.Prompt) {
        val augmentation = autocompleteResolution.augmentation
        if (!augmentation.mentions.isEmpty) mentions = augmentation.mentions
        // Session chips go as `@session:<id>`, as desktop sends them.
        augmentation.text?.let { sentText = it }
    }
    // Their titles are known: the sent bubble's chips show them at once.
    for (chip in sentChips) {
        val session = (chip.item.payload as? AutocompleteItem.Payload.Session)?.session ?: continue
        if (next.sessionLinks[session.id] == null) {
            next = next.copy(sessionLinks = next.sessionLinks + (session.id to SessionLinkTarget.Session(session.title, null)))
        }
    }
    val submitted = submit(
        messageID = messageID,
        text = sentText,
        attachments = attachments,
        files = files,
        delivery = delivery,
        mentions = mentions,
        chips = if (experimentalFeatures(ExperimentalFeature.composerAutocomplete)) sentChips else emptyList(),
        isRetry = false,
        state = next,
    )
    return Reduced(
        submitted.state,
        Effect.merge(stopDictating, Effect.cancel(SessionFeature.CancelID.AutocompleteAutoRun), submitted.effect),
    )
}

/**
 * A finished recording that won't be sent: its words go to the draft of the session it was meant
 * for, here, in a subagent of this family, or through Home for another session.
 */
internal fun SessionFeature.keepUnsent(sending: DictationFeature.Sending, state: State): Reduced<State, Action> {
    if (sending.sessionID == state.session?.id) return Reduced(state.keepAsDraft(sending.draft))
    val target = sending.sessionID
    if (target != null && state.familySessions.any { it.id == target }) {
        val merged = (state.familyDrafts[target] ?: DraftState()).appending(sending.draft)
        return Reduced(state.settingFamilyDraft(target, merged))
    }
    val draftTarget = if (target == null) SharedDraftTarget.NewSession else SharedDraftTarget.Session(target)
    return Reduced(state, Effect.send(Action.Delegate(SessionFeature.Delegate.KeepDraft(draftTarget, SharedComposerDraft(composer = sending.draft)))))
}

/** Streams one composer file to the server. Progress and the result route back by ID, so a family switch that parks the draft keeps it moving. */
internal fun SessionFeature.upload(id: UUID, state: State): Reduced<State, Action> {
    var target: ComposerFileAttachment? = null
    val next = updateFile(id, state) { file ->
        file.copy(status = ComposerFileAttachment.Status.Uploading(0)).also { target = it }
    }
    val file = target ?: return Reduced(next)
    return Reduced(
        next,
        ComposerAttachmentsFeature.upload(file, next.server, next.effectiveDirectory, next.supportsFileUploads, openCodeClient)
            .map(Action::ComposerAttachments),
    )
}

/** Applies [change] to the file wherever its draft currently lives. */
internal fun updateFile(id: UUID, state: State, change: (ComposerFileAttachment) -> ComposerFileAttachment): State {
    val pendingIndex = state.pendingFiles.indexOfFirst { it.id == id }
    if (pendingIndex >= 0) {
        return state.withPendingFiles(state.pendingFiles.toMutableList().also { it[pendingIndex] = change(it[pendingIndex]) })
    }
    val parked = state.parkedComposer
    val parkedIndex = parked?.files?.indexOfFirst { it.id == id } ?: -1
    if (parked != null && parkedIndex >= 0) {
        return state.withParkedComposer(parked.copy(files = parked.files.toMutableList().also { it[parkedIndex] = change(it[parkedIndex]) }))
    }
    val drafts = state.familyDrafts
    for (key in drafts.keys) {
        val draft = drafts[key] ?: continue
        val index = draft.files.indexOfFirst { it.id == id }
        if (index < 0) continue
        return state.settingFamilyDraft(key, draft.copy(files = draft.files.toMutableList().also { it[index] = change(it[index]) }))
    }
    return state
}

/** Completes a send that was waiting on uploads once none remain in flight. */
internal fun SessionFeature.sendIfUploadsFinished(state: State): Reduced<State, Action> {
    // A request took the composer meanwhile: the message keeps waiting, with its uploads, and goes
    // once the composer is the session's again.
    if (state.isAnsweringRequest) return Reduced(state)
    val delivery = state.sendAfterUploads
    if (delivery == null || state.isUploadingFiles) return Reduced(state)
    val next = state.withSendAfterUploads(null)
    if (!next.hasPromptContent) return Reduced(forgetSharedSend(next))
    return Reduced(next, Effect.send(Action.DeferredSendReady(delivery)))
}

/** The share sheet's IDs belong to its one send; a send that didn't go never hands them to the next one the person makes. */
internal fun forgetSharedSend(state: State): State = state.copy(sharedMessageID = null, sharedSessionID = null)

/**
 * Shows a message as sent and delivers it: a bubble in an idle session, a pending row (steering or
 * queued) mid-run. A retry reuses the failed message's ID and doesn't fly from the composer, which
 * it never left.
 */
internal fun SessionFeature.submit(
    messageID: String,
    text: String,
    attachments: List<OpenCodeOutgoingAttachment>,
    files: List<ComposerFileAttachment>,
    delivery: OpenCodeDelivery,
    page: PageShareRequest? = null,
    mentions: OpenCodeAutocompleteMentions? = null,
    chips: List<ComposerAutocompleteChip> = emptyList(),
    isRetry: Boolean,
    state: State,
): Reduced<State, Action> {
    var next = state
    val target: SessionFeature.SendTarget
    val session = next.session
    if (session != null) {
        target = SessionFeature.SendTarget.Existing(session.id)
    } else {
        val directory = persistedDraftDirectory(messageID, isRetry, next) ?: next.draftProject?.directory ?: return Reduced(next)
        next = next.copy(draftProject = OpenCodeProject.folder(directory, next.projects))
        target = SessionFeature.SendTarget.NewSession(directory)
    }
    // A new prompt makes a staged undo final on the server, which drops what it hid: they go here
    // too, rather than coming back with the next read.
    val (settled, cancelRevert) = next.settleRevertForNewPrompt()
    // Like a chat app, sending reads everything above it.
    next = settled.noteOwnSend()
    // The composer's model as it is now, even for a retry: a pick made since the send failed goes with it.
    val model = next.sendModel
    val agent = if (experimentalFeatures(ExperimentalFeature.agentSelection)) next.sendAgent else null
    // Every send before the session exists goes to the one its first send creates.
    val lane: PromptOutbox.Lane
    val current = next.session
    if (current != null) {
        lane = PromptOutbox.Lane.Session(current.id)
    } else {
        if (next.outboxDraftID == null) next = next.copy(outboxDraftID = messageID)
        lane = PromptOutbox.Lane.Draft(next.outboxDraftID ?: messageID)
    }
    // A send moves the session on: a Stop still trying, or a read about the run before, must not act
    // on the run this send starts.
    next = next.supersedeRunWork()
    val wasRunning = next.isRunning
    val wasBrowsing = next.browsingWindowStartID != null
    // Sending returns a browsing reader to the end: the window lets go of the rows they were reading
    // in this same update, not one frame into the list's glide back.
    if (!isRetry) next = next.copy(browsingWindowStartID = null)
    // Behind a failed or stopped send nothing goes until the reader acts, so nothing is working; a
    // retry is what lets the lane go again.
    if (isRetry || !promptOutbox.isHeld(lane, next.server.persistenceID)) next = next.copy(isAwaitingReply = true)
    val requestEpoch = next.nextPromptRequestEpoch + 1
    next = next.copy(
        nextPromptRequestEpoch = requestEpoch,
        promptStates = next.promptStates + (messageID to State.PromptState(requestEpoch)),
        optimisticPromptIDs = next.optimisticPromptIDs + messageID,
    )
    val pending = PendingPrompt(
        messageID = messageID, text = text, attachments = attachments, files = files,
        wasRunning = wasRunning, delivery = delivery, requestEpoch = requestEpoch, model = model, agent = agent, page = page,
        mentions = mentions, chips = chips,
    )
    if (wasRunning) {
        next = enqueueRunningPrompt(pending, next, isRetry = isRetry)
        next = next.copy(sentDeliveries = next.sentDeliveries + (messageID to delivery))
        if (delivery == OpenCodeDelivery.Queue && model != null) next = next.copy(queuedPicks = next.queuedPicks + (messageID to model))
        if (delivery == OpenCodeDelivery.Queue && agent != null) next = next.withQueuedAgentPicks(next.queuedAgentPicks + (messageID to agent))
        // A steering send flies off the composer into its dim bubble, like an idle send into its
        // bubble; a queued one flies into the pill instead.
        if (!isRetry) next = next.copy(lastSentItemID = messageID, sendGeneration = next.sendGeneration + 1)
    } else if (isRetry && SessionFeature.containsMessage(messageID, next.items)) {
        // A retried bubble keeps its place, ahead of the sends that waited behind it.
    } else {
        val previousFirstID = if (wasBrowsing) null else next.visibleItems.firstOrNull()?.id
        val rows = SessionFeature.promptRows(messageID, text, localImages(attachments, messageID), attachments, files)
        next = next.copy(items = next.items + rows).retainRenderedWindowStart(previousFirstID)
        val last = rows.lastOrNull()
        if (!isRetry && last != null) next = next.copy(lastSentItemID = last.id, sendGeneration = next.sendGeneration + 1)
    }
    // Not waiting behind a run (a retry once it ended), it applies its own model.
    if (!pending.waitsBehindRun) {
        next = next.copy(queuedPicks = next.queuedPicks - messageID).withQueuedAgentPicks(next.queuedAgentPicks - messageID)
    }
    var backgroundKey: String? = null
    if (delivery == OpenCodeDelivery.Steer) {
        val (claimed, key) = claimAutoBackground(messageID, next)
        next = claimed
        backgroundKey = key
    }
    // A New Session the share sheet may already have made is the one this creates.
    val newSessionID = next.sharedSessionID
    next = next.copy(sharedSessionID = null)
    return Reduced(
        next,
        Effect.merge(
            cancelRevert,
            deliver(
                pending = pending, target = target, lane = lane, server = next.server,
                reconcileFirst = isRetry, backgroundKey = backgroundKey,
                // A call's or share sheet's session being made here: the send creates that one.
                newSessionID = newSessionID ?: if (next.session == null) next.callSessionID else null,
            ),
        ),
    )
}

/**
 * A steer moves the run's running shells and subagents to the background first, so the agent reads
 * it next. Claimed once: work already moved, or being moved, isn't again.
 */
internal fun claimAutoBackground(messageID: String, state: State): Pair<State, String?> {
    val key = state.foregroundWork?.key ?: return state to null
    if (key in state.movedWorkKeys) return state to null
    return state.copy(
        movedWorkKeys = state.movedWorkKeys + key,
        autoBackgroundClaims = state.autoBackgroundClaims + (messageID to key),
    ) to key
}

internal fun releaseAutoBackgroundClaim(messageID: String, state: State): State {
    val key = state.autoBackgroundClaims[messageID] ?: return state
    return state.copy(autoBackgroundClaims = state.autoBackgroundClaims - messageID, movedWorkKeys = state.movedWorkKeys - key)
}

/** Stop held these back before they were posted: each shows as a failed send with Retry and Delete, kept for screens that open later. */
internal fun SessionFeature.holdStoppedSends(stopped: List<PendingPrompt>, sessionID: String, state: State): State {
    var next = state
    for (pending in stopped) {
        if (next.promptStates[pending.messageID] == null) continue
        val id = pending.messageID
        next = releaseAutoBackgroundClaim(id, next)
        next = next.copy(sentDeliveries = next.sentDeliveries - id, deliveryOverrides = next.deliveryOverrides - id)
        undeliveredPrompts.keep(pending, SessionFeature.stoppedReason, next.server.persistenceID, sessionID)
        next = markFailed(pending, SessionFeature.stoppedReason, next)
    }
    if (!next.hasSteerOnItsWay && !next.isExternallyActive) next = next.copy(isAwaitingReply = false)
    return next
}

/**
 * A draft's Stop. Once its session exists (even before this screen has heard), it's that session's
 * Stop: the screen moves to the session, then stops it as any session's Stop does. Before then,
 * the session's creation and every send not yet admitted are called off, their words, attachments,
 * and files go back to the composer ahead of anything typed since, in the order they were sent,
 * and the next send starts afresh.
 */
internal fun SessionFeature.stopDraft(state: State): Reduced<State, Action> {
    val draftID = state.outboxDraftID ?: return Reduced(state)
    return when (val decision = promptOutbox.stopDraft(PromptOutbox.Lane.Draft(draftID), state.server.persistenceID)) {
        is PromptOutbox.DraftStop.Created -> Reduced(
            state,
            Effect.concatenate(Effect.send(Action.SessionCreated(decision.session)), Effect.send(Action.InterruptTapped)),
        )
        is PromptOutbox.DraftStop.Cancelled -> {
            var next = state.copy(outboxDraftID = null)
            if (decision.pendings.isEmpty()) return Reduced(next)
            haptics.play(Haptic.cancel)
            for (pending in decision.pendings.asReversed()) {
                val id = pending.messageID
                next = next.copy(
                    items = next.items.filterNot { SessionFeature.item(it, id) },
                    queuedInputs = next.queuedInputs.filterNot { it.id == id },
                    pendingPromptPositions = next.pendingPromptPositions - id,
                    queuedInputImages = next.queuedInputImages - id,
                    promptStates = next.promptStates - id,
                    sentDeliveries = next.sentDeliveries - id,
                    deliveryOverrides = next.deliveryOverrides - id,
                    optimisticPromptIDs = next.optimisticPromptIDs - id,
                    cancelledInputIDs = next.cancelledInputIDs + id,
                )
                next = returnToComposer(pending, next)
            }
            next = next.copy(lastSentItemID = null)
            if (!next.hasMovingLocalPrompt) next = next.copy(isAwaitingReply = false)
            Reduced(next)
        }
    }
}

/**
 * A send's words lead the draft (or follow it when reverting a turn), its attachments and files
 * join the composer's; a shared location comes back as its card.
 */
private fun returnToComposer(
    pending: PendingPrompt,
    state: State,
    appendText: Boolean = false,
    preferReturnedLocation: Boolean = false,
): State {
    fun join(incoming: String, existing: String): String =
        if (appendText) SharedComposerDraft.joined(existing, incoming) else SharedComposerDraft.joined(incoming, existing)
    val next = state.applySessionComposer { composer ->
        // Session chips went as `@session:<id>`: their titles come back.
        val text = ComposerAutocompleteDraftResolution.draftText(sent = pending.text, chips = pending.chips)
        val shared = SharedLocation.parse(text)
        var updated = if (shared != null) {
            val (location, remainder) = shared
            composer.copy(
                location = if (preferReturnedLocation) location else composer.location ?: location,
                text = join(remainder, composer.text),
            )
        } else {
            composer.copy(text = join(text, composer.text))
        }
        updated = if (appendText) {
            updated.copy(attachments = updated.attachments + pending.attachments, files = updated.files + pending.files)
        } else {
            updated.copy(attachments = pending.attachments + updated.attachments, files = pending.files + updated.files)
        }
        val newChips = pending.chips.filter { chip -> updated.chips.none { it.id == chip.id } }
        updated.copy(chips = newChips + updated.chips)
    }
    return if (!appendText) next.clearEditedUndoDraft() else next
}

/** Stages a revert on the server before touching the transcript or composer, so a failure never leaves the transcript half-removed. */
internal fun SessionFeature.stageRevert(
    messageID: String,
    sessionID: String,
    restoring: PendingPrompt,
    state: State,
): Reduced<State, Action> {
    val server = state.server
    val client = openCodeClient
    return Reduced(
        state.copy(isUndoingLastMessage = true),
        Effect.run<Action> { send ->
            val error = try {
                client.stageRevert(server, sessionID, messageID)
                null
            } catch (error: OpenCodeClientError) {
                error
            }
            send(Action.MessageReverted(sessionID, messageID, restoring, error))
        }.cancellable(SessionFeature.CancelID.Revert, cancelInFlight = true),
    )
}

private fun SessionFeature.reportRevertError(error: OpenCodeClientError, state: State): Reduced<State, Action> {
    haptics.play(Haptic.error)
    val details = error.message.trim()
    return Reduced(appendInfo(state, if (details.isEmpty()) "Couldn’t revert" else "Couldn’t revert: $details"))
}

/**
 * Puts a reverted message's text, location, images, pasted text cards, and file references back
 * in the composer (appended after any existing draft) and focuses the field.
 */
internal fun fillComposerDraft(pending: PendingPrompt, state: State, focus: Boolean = true): State {
    var next = state.clearUntouchedUndoDraft()
    val prior = next.sessionComposer
    next = returnToComposer(pending, next, appendText = true, preferReturnedLocation = true)
    next = next.withUndoSnapshot(
        UndoSnapshot(
            restoredDraft = next.parkedComposer?.text ?: next.draft,
            priorDraft = prior.text,
            restoredLocation = next.sessionComposer.location,
            priorLocation = prior.location,
            attachmentIDs = pending.attachments.mapNotNull { runCatching { UUID.fromString(it.id) }.getOrNull() }.toSet(),
            fileIDs = pending.files.map { it.id }.toSet(),
        ),
    )
    if (focus && next.parkedComposer == null) next = next.copy(composerFocusRequest = next.composerFocusRequest + 1)
    return next
}

/**
 * Lays the outbox over what the screen restored (its cache or Home's snapshot), which never holds
 * unadmitted sends: the session's sends still on their way, shown as the screen that sent them did
 * and followed until they settle, and those admitted in the last couple of minutes, until the
 * transcript has them. Running it twice adds nothing.
 */
internal fun SessionFeature.restoreOutbox(state: State): Reduced<State, Action> {
    val session = state.session
    val draftID = state.outboxDraftID
    val lane: PromptOutbox.Lane = when {
        session != null -> PromptOutbox.Lane.Session(session.id)
        draftID != null -> PromptOutbox.Lane.Draft(draftID)
        else -> return Reduced(state)
    }
    var next = state
    val contents = promptOutbox.contents(next.server.persistenceID, lane)
    val promoted = next.promotedPromptIDs.toSet() + SessionFeature.promptIDs(next.items)
    for (pending in contents.admitted) {
        val id = pending.messageID
        if (next.promptStates[id] != null || id in promoted || id in next.cancelledInputIDs || next.queuedInputs.any { it.id == id }) continue
        next = showRestored(pending, State.PromptState.Admission.admitted, next).first
    }
    val effects = ArrayList<Effect<Action>>()
    for (entry in contents.unsent) {
        val pending = entry.pending
        val id = pending.messageID
        if (next.promptStates[id] != null || next.failedSends.withID(id) != null || next.queuedInputs.any { it.id == id }) continue
        val statuses = promptOutbox.statuses(id) ?: continue
        val (shown, restored) = showRestored(pending, State.PromptState.Admission.sent, next)
        next = shown
        // Waiting behind a failed or stopped send, nothing is working.
        if (!entry.isBlocked) next = next.copy(isAwaitingReply = true)
        effects += follow(statuses, restored, next.server)
    }
    promptOutbox.startIfNeeded(lane, next.server.persistenceID)
    return Reduced(next, Effect.merge(effects))
}

/** Keeps a mid-run send in `queuedInputs` (steering in the transcript, queued in the pill) with its position, predecessor ordering, and images. */
private fun enqueueRunningPrompt(pending: PendingPrompt, state: State, isRetry: Boolean = false): State {
    val id = pending.messageID
    val input = OpenCodePendingInput(
        id = id, text = pending.text, delivery = pending.delivery, files = pending.files.mapNotNull { it.reference },
        inlineFiles = SessionFeature.inlineFiles(id, pending.attachments),
    )
    var next = state
    // A retry goes ahead of the sends that waited behind it, as it's delivered.
    val behind = if (isRetry) {
        next.queuedInputs.indexOfFirst { it.id != id && next.promptStates[it.id]?.admission == State.PromptState.Admission.sent }
    } else {
        -1
    }
    val queued = next.queuedInputs.toMutableList()
    if (behind >= 0) queued.add(behind, input) else queued += input
    next = next.copy(queuedInputs = queued)
    next = next.copy(
        pendingPromptPositions = next.pendingPromptPositions + (
            id to State.PendingPromptPosition(
                precedingPromptIDs = pendingPredecessors(next),
                precedingMessageIDs = SessionFeature.windowMessageIDs(next.items),
                requestEpoch = pending.requestEpoch,
            )
            ),
    )
    if (pending.delivery == OpenCodeDelivery.Steer) next = recordSteeringPredecessor(id, next)
    val images = localImages(pending.attachments, id)
    if (images.isNotEmpty()) next = next.copy(queuedInputImages = next.queuedInputImages + (id to images))
    return next
}

/** A send from an earlier screen, shown as that screen showed it. */
private fun showRestored(pending: PendingPrompt, admission: State.PromptState.Admission, state: State): Pair<State, PendingPrompt> {
    val epoch = state.nextPromptRequestEpoch + 1
    val restored = pending.copy(requestEpoch = epoch)
    val id = restored.messageID
    var next = state.copy(
        nextPromptRequestEpoch = epoch,
        promptStates = state.promptStates + (id to State.PromptState(restored.requestEpoch, admission, restored.ownsRun)),
        optimisticPromptIDs = state.optimisticPromptIDs + id,
    )
    if (restored.wasRunning) {
        next = enqueueRunningPrompt(restored, next)
        if (admission == State.PromptState.Admission.sent) next = next.copy(sentDeliveries = next.sentDeliveries + (id to restored.delivery))
        val model = restored.model
        if (restored.waitsBehindRun && model != null && next.queuedPicks[id] == null) next = next.copy(queuedPicks = next.queuedPicks + (id to model))
        val agent = restored.agent
        if (restored.waitsBehindRun && agent != null && next.queuedAgentPicks[id] == null) {
            next = next.withQueuedAgentPicks(next.queuedAgentPicks + (id to agent))
        }
    } else if (!SessionFeature.containsMessage(id, next.items)) {
        val previousFirstID = next.visibleItems.firstOrNull()?.id
        next = next.copy(
            items = next.items + SessionFeature.promptRows(id, restored.text, localImages(restored.attachments, id), restored.attachments, restored.files),
        ).retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
    }
    return next to restored
}

/**
 * A sent message's images under the IDs the server's decoder gives them (`<message>-img-<n>`, `n`
 * its place among all the message's files, Pasted Text cards included), from the steering row
 * through delivery and the read after it, so its thumbnails keep their images and never redraw as
 * the message is delivered.
 */
private fun localImages(attachments: List<OpenCodeOutgoingAttachment>, messageID: String): List<OpenCodeImageAttachment> =
    attachments.mapIndexedNotNull { index, attachment ->
        if (attachment.isImage) OpenCodeImageAttachment(id = "$messageID-img-$index", data = attachment.data) else null
    }

/**
 * A delivery's outcome is this screen's when it's for the session on screen or, on a draft, for
 * one of its own sends to the session the draft is creating (which this screen may not have heard of yet).
 */
internal fun SessionFeature.Companion.ownsDelivery(sessionID: String?, messageID: String, state: State): Boolean {
    sessionID ?: return true
    state.session?.id?.let { return it == sessionID }
    return state.promptStates[messageID] != null
}

/** A prompt's user rows as the server's decoder names them: images, then files, then text. */
internal fun SessionFeature.Companion.promptRows(
    messageID: String,
    text: String,
    source: OpenCodePromptSource? = null,
    images: List<OpenCodeImageAttachment>,
    fileReferences: List<OpenCodeFileReference>,
    inlineFiles: List<OpenCodeTranscriptFile>,
): List<OpenCodeTranscriptItem> {
    val transcriptFiles = fileReferences.mapIndexed { index, file ->
        OpenCodeTranscriptFile(id = "$messageID-file-$index", name = file.name, mime = file.mime, path = file.path)
    } + inlineFiles
    val rows = ArrayList<OpenCodeTranscriptItem>()
    if (images.isNotEmpty()) rows += OpenCodeTranscriptItem.UserImages("$messageID-images", images)
    if (transcriptFiles.isNotEmpty()) rows += OpenCodeTranscriptItem.UserFiles("$messageID-files", transcriptFiles)
    if (text.isNotEmpty()) rows += OpenCodeTranscriptItem.UserText(messageID, text, source)
    return rows
}

private fun SessionFeature.Companion.promptRows(
    messageID: String,
    text: String,
    images: List<OpenCodeImageAttachment>,
    attachments: List<OpenCodeOutgoingAttachment>,
    files: List<ComposerFileAttachment>,
): List<OpenCodeTranscriptItem> = promptRows(
    messageID = messageID, text = text, images = images,
    fileReferences = files.mapNotNull { it.reference },
    inlineFiles = inlineFiles(messageID, attachments),
)

/** Inline attachments that aren't images, as the server's decoder names them (indices count images too): file chips and Pasted Text cards. */
internal fun SessionFeature.Companion.inlineFiles(messageID: String, attachments: List<OpenCodeOutgoingAttachment>): List<OpenCodeTranscriptFile> =
    attachments.mapIndexedNotNull { index, attachment ->
        if (attachment.isImage) return@mapIndexedNotNull null
        val text = attachment.pastedText
        OpenCodeTranscriptFile(
            id = "$messageID-file-inline-$index",
            name = attachment.name ?: if (text == null) "File" else PastedText.NAME,
            mime = attachment.mime,
            text = text,
            pasted = attachment.pasted,
        )
    }

/**
 * The message stays where the reader saw it go, now as a failed bubble: a pending row (steering or
 * queued) becomes one at the end. It stays optimistic, so refreshes carry it until Retry, Edit, or Delete.
 */
internal fun markFailed(pending: PendingPrompt, reason: String, state: State): State {
    val id = pending.messageID
    val images = state.queuedInputImages[id]
    var next = state.copy(
        promptStates = state.promptStates - id,
        pendingPromptPositions = state.pendingPromptPositions - id,
        queuedInputs = state.queuedInputs.filterNot { it.id == id },
        queuedInputImages = state.queuedInputImages - id,
    )
    if (!SessionFeature.containsMessage(id, next.items)) {
        val previousFirstID = next.visibleItems.firstOrNull()?.id
        next = next.copy(
            items = next.items + SessionFeature.promptRows(
                id, pending.text, images ?: localImages(pending.attachments, id), pending.attachments, pending.files,
            ),
        ).retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
    }
    val failed = FailedSend(pending, reason)
    val index = next.failedSends.indexOfFirst { it.id == id }
    val failedSends = if (index >= 0) next.failedSends.toMutableList().also { it[index] = failed } else next.failedSends + failed
    return next.copy(optimisticPromptIDs = next.optimisticPromptIDs + id, failedSends = failedSends)
}

/** A pick waits for the next send. A draft's model goes with every send until its session exists (`sendModel`). */
internal fun pick(model: OpenCodeModelRef, state: State): State {
    val next = state.copy(currentModel = model)
    return if (next.session != null) next.copy(pendingModel = model) else next
}

/** Retry, Edit, and Delete all take the failed bubble away first. Edit and Delete also take it out of the outbox, so the sends behind it go on. */
/**
 * Sends a failed message again from its own place in the outbox, ahead of the sends that waited
 * behind it. In an idle session its bubble stays where it is; mid-run it becomes a pending row again.
 */
internal fun SessionFeature.retry(failed: FailedSend, state: State): Reduced<State, Action> {
    val next = removeFailedSend(failed.id, state, leavesOutbox = false, keepsBubble = !state.isRunning)
    // The same ID and delivery: the server admits an ID once, so a send that landed after all can't arrive twice.
    val pending = failed.pending
    return submit(
        messageID = failed.id, text = pending.text, attachments = pending.attachments, files = pending.files,
        delivery = pending.delivery, page = pending.page, mentions = pending.mentions, chips = pending.chips,
        isRetry = true, state = next,
    )
}

/**
 * [sessionID] now lives in [directory]. On its own screen, what was read in the old folder
 * (requests, models, shells) is read again in the new one, and a read already on its way keeps
 * the new folder.
 */
internal fun SessionFeature.sessionMoved(sessionID: String, directory: String, state: State): Reduced<State, Action> {
    var next = state
    val index = next.familySessions.indexOfFirst { it.id == sessionID }
    if (index >= 0 && next.familySessions[index].directory != directory) {
        next = next.copy(familySessions = next.familySessions.toMutableList().also { it[index] = it[index].copy(directory = directory) })
    }
    // iOS also tells the voice state (`voice?.sessionMoved`); VoiceFeature.State has no move yet.
    val session = next.session
    if (session?.id != sessionID || session.directory == directory) return Reduced(next)
    next = next.copy(session = session.copy(directory = directory), refresh = next.refresh?.copy(directoryChanged = true))
    val pending = startPendingWork(next, RequestDockFeature.State.LoadAttempt.Initial)
    val models = if (pending.state.models.isEmpty()) reloadModels(pending.state) else Reduced(pending.state)
    val shells = refreshShells(models.state)
    return Reduced(shells.state, Effect.merge(pending.effect, models.effect, shells.effect))
}

private fun SessionFeature.removeFailedSend(id: String, state: State, leavesOutbox: Boolean = true, keepsBubble: Boolean = false): State {
    var next = state.copy(failedSends = state.failedSends.filterNot { it.id == id })
    if (next.movingFailedSendID == id) next = next.copy(movingFailedSendID = null)
    if (!keepsBubble) {
        next = next.copy(
            queuedPicks = next.queuedPicks - id,
            items = next.items.filterNot { SessionFeature.item(it, id) },
            optimisticPromptIDs = next.optimisticPromptIDs - id,
        ).withQueuedAgentPicks(next.queuedAgentPicks - id)
    }
    undeliveredPrompts.resolve(setOf(id))
    if (leavesOutbox) promptOutbox.withdraw(id)
    // A draft whose only sends are gone is unsent again: its project can change, and the next send starts a fresh lane.
    if (leavesOutbox && next.session == null && next.failedSends.isEmpty() && next.items.isEmpty() &&
        next.promptStates.keys.none { it != id }
    ) {
        next = next.copy(outboxDraftID = null)
    }
    return next
}

/**
 * Failed sends kept for this session (or this draft's failed create) that the screen doesn't show
 * yet: leaving and coming back keeps them. One the transcript already holds from the server landed after all.
 */
internal fun SessionFeature.restoreFailedSends(state: State): State {
    var next = state
    for (entry in undeliveredPrompts.entries(next.server.persistenceID, next.session?.id)) {
        val id = entry.pending.messageID
        if (next.failedSends.withID(id) != null) continue
        if (SessionFeature.containsMessage(id, next.items) && id !in next.optimisticPromptIDs) {
            undeliveredPrompts.resolve(setOf(id))
            promptOutbox.settle(setOf(id))
        } else {
            next = markFailed(entry.pending, entry.reason, next)
        }
    }
    return next
}

/** Hands Home the confirmed transcript again, so its cache drops what left. */
internal fun publishCache(state: State): Effect<Action> {
    val session = state.session ?: return Effect.none()
    return Effect.send(Action.AcceptedTranscriptUpdated(session.id, state.cacheableItems))
}

/** Hands one prompt to the outbox, which delivers its lane's sends in order, and follows it: the run that follows, then the reply. */
internal fun SessionFeature.deliver(
    pending: PendingPrompt,
    target: SessionFeature.SendTarget,
    lane: PromptOutbox.Lane,
    server: OpenCodeServer,
    reconcileFirst: Boolean = false,
    backgroundKey: String? = null,
    newSessionID: String? = null,
): Effect<Action> {
    val job = SessionFeature.deliveryJob(
        target = target, server = server, reconcileFirst = reconcileFirst,
        openCodeClient = openCodeClient, clock = clock, networkPath = networkPath, undeliveredPrompts = undeliveredPrompts,
        sessionNotifications = sessionNotifications, pageReader = pageReader,
    )
    val destination = when (lane) {
        is PromptOutbox.Lane.Session -> lane.id
        is PromptOutbox.Lane.Draft -> "a new session"
    }
    AppLog.send.info {
        "${pending.messageID} ${if (reconcileFirst) "retried" else "queued"} for $destination: ${pending.text.length} characters, " +
            "${pending.attachments.size} attachments, ${pending.files.size} files, ${pending.delivery.rawValue}"
    }
    // Enqueued now, in the order of the taps; the effect only listens.
    val directory = (target as? SessionFeature.SendTarget.NewSession)?.directory
    val statuses = promptOutbox.enqueue(
        pending, lane, server.persistenceID,
        backgroundKey = backgroundKey, newSessionID = newSessionID, directory = directory, job = job,
    )
    return follow(statuses, pending, server)
}

/** Reports a send's progress to this screen. Leaving the screen stops only the reporting: the outbox delivers it regardless. */
internal fun SessionFeature.follow(statuses: Flow<PromptOutbox.Status>, pending: PendingPrompt, server: OpenCodeServer): Effect<Action> {
    val client = openCodeClient
    val messageID = pending.messageID
    val requestEpoch = pending.requestEpoch
    return Effect.run { send ->
        var settled: PromptOutbox.Status.Settled? = null
        var ended = false
        // Collecting ends when the send settles; leaving the screen cancels only this collection.
        statuses.takeWhile { status ->
            when (status) {
                is PromptOutbox.Status.BackgroundMoved ->
                    send(Action.MoveToBackgroundFinished(status.sessionID, status.key, status.error, isAutomatic = true))
                is PromptOutbox.Status.UpdateFailed ->
                    send(Action.PendingInputUpdateFailed(status.sessionID, messageID, status.restoring, status.error))
                is PromptOutbox.Status.Settled -> settled = status
                is PromptOutbox.Status.BackgroundUnused -> send(Action.AutoBackgroundClaimLapsed(messageID, requestEpoch))
                PromptOutbox.Status.Withdrawn, PromptOutbox.Status.Stopped -> ended = true
            }
            !ended
        }.collect()
        if (ended) return@run
        val outcome = settled ?: return@run
        val latest = outcome.pending.copy(requestEpoch = requestEpoch)
        val sessionID: String
        when (val result = outcome.outcome) {
            is DeliveryOutcome.Failed -> {
                result.created?.let { send(Action.SessionCreated(it)) }
                send(Action.PromptFailed(result.sessionID, result.error, latest))
                return@run
            }
            is DeliveryOutcome.Withdrawn -> return@run
            is DeliveryOutcome.Sent -> {
                result.created?.let { send(Action.SessionCreated(it)) }
                result.appliedModel?.let { send(Action.ModelApplied(result.sessionID, it)) }
                result.appliedAgent?.let { send(Action.AgentApplied(result.sessionID, it)) }
                sessionID = result.sessionID
            }
        }
        send(Action.PromptAdmitted(sessionID, messageID, requestEpoch))
        if (latest.wasRunning) send(Action.PendingInputsRequested(sessionID))
        // The optimistic local bubble covers the interim; wait for the run to finish, then load
        // the reply. Token streaming arrives with the event stream.
        try {
            client.waitForIdle(server, sessionID)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@run
        }
        if (!currentCoroutineContext().isActive) return@run
        send(Action.ReplyCompleted(sessionID))
    }
}
